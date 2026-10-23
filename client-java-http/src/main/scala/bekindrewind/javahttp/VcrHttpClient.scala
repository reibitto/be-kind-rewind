package bekindrewind.javahttp

import bekindrewind._
import bekindrewind.storage.VcrStorage

import java.io.{ ByteArrayOutputStream, Closeable }
import java.lang.reflect.InvocationTargetException
import java.net.http.HttpClient.{ Redirect, Version }
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.{ BodyHandler, BodyHandlers, PushPromiseHandler, ResponseInfo }
import java.net.http._
import java.net.{ Authenticator, CookieHandler, ProxySelector, URI }
import java.nio.ByteBuffer
import java.time.{ Duration, OffsetDateTime }
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ CompletableFuture, CompletionStage, ExecutionException, Executor, Flow }
import java.util.{ List => JList, Optional }
import javax.net.ssl.{ SSLContext, SSLParameters, SSLSession }
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * A `java.net.http.HttpClient` that records and replays requests. Responses are buffered in memory before being passed
 * to the request's `BodyHandler`.
 */
class VcrHttpClient(
  val underlyingClient: HttpClient,
  val storage: VcrStorage,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
) extends HttpClient
    with VcrClient
    with Closeable {
  import VcrHttpClient._

  override def send[T](request: HttpRequest, handler: BodyHandler[T]): HttpResponse[T] =
    try sendAsync(request, handler).get()
    catch {
      case e: ExecutionException => throw Option(e.getCause).getOrElse(e)
    }

  override def sendAsync[T](request: HttpRequest, handler: BodyHandler[T]): CompletableFuture[HttpResponse[T]] =
    readBody(request).thenCompose { body =>
      val sendable   = withBufferedBody(request, body)
      val vcrRequest = VcrRequest.fromBytes(
        request.method(),
        request.uri(),
        body,
        toScalaHeaders(request.headers()),
        request.version().map[String](showVersion(_)).toScala
      )

      resolve(vcrRequest) match {
        case VcrAction.Replay(entry) =>
          val response = entry.response
          val headers  = toJavaHeaders(response.headers + (VcrClient.vcrCacheHeaderName -> Seq("true")))
          val version  = response.httpVersion.flatMap(parseVersion).getOrElse(Version.HTTP_1_1)
          toResponse(request, handler, response.statusCode, headers, version, response.bodyBytes, Optional.empty())

        case VcrAction.Record =>
          underlyingClient.sendAsync(sendable, BodyHandlers.ofByteArray()).thenCompose { raw =>
            val headers = toScalaHeaders(raw.headers())
            addNewEntry(
              VcrEntry(
                vcrRequest,
                VcrResponse.fromBytes(
                  raw.statusCode(),
                  "",
                  headers,
                  raw.body(),
                  raw.headers().firstValue("Content-Type").toScala,
                  Some(showVersion(raw.version()))
                ),
                OffsetDateTime.now
              )
            )
            toResponse(request, handler, raw.statusCode(), raw.headers(), raw.version(), raw.body(), raw.sslSession())
          }

        case VcrAction.PassThrough => underlyingClient.sendAsync(sendable, handler)

        case VcrAction.Fail(error) => CompletableFuture.failedFuture[HttpResponse[T]](error)
      }
    }

  /** Push promises aren't recorded, so they are ignored. */
  override def sendAsync[T](
    request: HttpRequest,
    handler: BodyHandler[T],
    pushPromiseHandler: PushPromiseHandler[T]
  ): CompletableFuture[HttpResponse[T]] = sendAsync(request, handler)

  /**
   * Closes the underlyingClient client (`HttpClient` is closeable since JDK 21), then rethrows any error from saving.
   */
  override def close(): Unit =
    try save()
    finally
      (underlyingClient: AnyRef) match {
        case closeable: AutoCloseable => closeable.close()
        case _                        => ()
      }

  // These override JDK 21+ `HttpClient` methods whose defaults do nothing. Reflection, since this targets JDK 11.

  /** Shuts down the underlying client (JDK 21+). Doesn't save the recording, unlike [[close]]. */
  def shutdown(): Unit = { callUnderlying("shutdown"); () }

  /** Shuts down the underlying client immediately (JDK 21+). Doesn't save the recording, unlike [[close]]. */
  def shutdownNow(): Unit = { callUnderlying("shutdownNow"); () }

  def awaitTermination(duration: Duration): Boolean =
    callUnderlying("awaitTermination", classOf[Duration] -> duration).asInstanceOf[Boolean]

  def isTerminated(): Boolean = callUnderlying("isTerminated").asInstanceOf[Boolean]

  private def callUnderlying(name: String, args: (Class[_], AnyRef)*): AnyRef =
    try classOf[HttpClient].getMethod(name, args.map(_._1): _*).invoke(underlyingClient, args.map(_._2): _*)
    catch {
      case _: NoSuchMethodException     => throw new UnsupportedOperationException(s"HttpClient.$name requires JDK 21+")
      case e: InvocationTargetException => throw e.getCause
    }

  override def cookieHandler(): Optional[CookieHandler] = underlyingClient.cookieHandler()

  override def connectTimeout(): Optional[Duration] = underlyingClient.connectTimeout()

  override def followRedirects(): Redirect = underlyingClient.followRedirects()

  override def proxy(): Optional[ProxySelector] = underlyingClient.proxy()

  override def sslContext(): SSLContext = underlyingClient.sslContext()

  override def sslParameters(): SSLParameters = underlyingClient.sslParameters()

  override def authenticator(): Optional[Authenticator] = underlyingClient.authenticator()

  override def version(): Version = underlyingClient.version()

  override def executor(): Optional[Executor] = underlyingClient.executor()

  override def newWebSocketBuilder(): WebSocket.Builder = underlyingClient.newWebSocketBuilder()
}

object VcrHttpClient {
  def apply(
    underlyingClient: HttpClient,
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  ): VcrHttpClient =
    new VcrHttpClient(underlyingClient, storage, recordOptions, matcher)

  def apply(underlyingClient: HttpClient, settings: VcrSettings): VcrHttpClient =
    apply(underlyingClient, settings.storage, settings.recordOptions, settings.matcher)

  private def readBody(request: HttpRequest): CompletableFuture[Array[Byte]] =
    request.bodyPublisher().toScala match {
      case None            => CompletableFuture.completedFuture(Array.emptyByteArray)
      case Some(publisher) =>
        val result = new CompletableFuture[Array[Byte]]()
        publisher.subscribe(new Flow.Subscriber[ByteBuffer] {
          private val out = new ByteArrayOutputStream()

          override def onSubscribe(subscription: Flow.Subscription): Unit = subscription.request(Long.MaxValue)

          override def onNext(item: ByteBuffer): Unit = {
            val bytes = new Array[Byte](item.remaining())
            item.get(bytes)
            out.write(bytes)
          }

          override def onError(throwable: Throwable): Unit = { result.completeExceptionally(throwable); () }

          override def onComplete(): Unit = { result.complete(out.toByteArray); () }
        })
        result
    }

  /** Rebuilds the request with the bytes that were read, since body publishers (like input streams) can be one-shot. */
  private def withBufferedBody(request: HttpRequest, body: Array[Byte]): HttpRequest =
    if (body.isEmpty) request
    else {
      val builder = HttpRequest
        .newBuilder(request.uri())
        .method(request.method(), BodyPublishers.ofByteArray(body))
        .expectContinue(request.expectContinue())
      request.timeout().ifPresent { t => builder.timeout(t); () }
      request.version().ifPresent { v => builder.version(v); () }
      for {
        (name, values) <- request.headers().map().asScala
        value          <- values.asScala
      } builder.header(name, value)
      builder.build()
    }

  /** Feeds the recorded bytes to the caller's body handler, as if they had come from the network. */
  private def toResponse[T](
    request: HttpRequest,
    handler: BodyHandler[T],
    statusCode: Int,
    headers: HttpHeaders,
    version: Version,
    body: Array[Byte],
    sslSession: Optional[SSLSession]
  ): CompletionStage[HttpResponse[T]] = {
    val subscriber = handler.apply(ResponseInfoImpl(statusCode, headers, version))

    subscriber.onSubscribe(new Flow.Subscription {
      private val done = new AtomicBoolean(false)

      override def request(n: Long): Unit =
        if (n > 0 && done.compareAndSet(false, true)) {
          if (body.nonEmpty) subscriber.onNext(JList.of(ByteBuffer.wrap(body)))
          subscriber.onComplete()
        }

      override def cancel(): Unit = done.set(true)
    })

    subscriber.getBody.thenApply[HttpResponse[T]] { value =>
      VcrHttpResponse(statusCode, request, headers, value, request.uri(), version, sslSession)
    }
  }

  // Covers versions added in newer JDKs (such as HTTP_3) too.
  private[javahttp] def showVersion(version: Version): String =
    if (version == Version.HTTP_1_1) "HTTP/1.1" else version.name.replace("HTTP_", "HTTP/")

  private[javahttp] def parseVersion(version: String): Option[Version] =
    version match {
      case "HTTP/1.1"            => Some(Version.HTTP_1_1)
      case "HTTP/2" | "HTTP/2.0" => Some(Version.HTTP_2)
      case other                 => Try(Version.valueOf(other.replace("HTTP/", "HTTP_"))).toOption
    }

  // HTTP/2 pseudo-headers (such as `:status`) aren't real headers.
  private def toScalaHeaders(headers: HttpHeaders): Map[String, Seq[String]] =
    headers.map().asScala.collect { case (name, values) if !name.startsWith(":") => name -> values.asScala.toSeq }.toMap

  private def toJavaHeaders(headers: Map[String, Seq[String]]): HttpHeaders =
    HttpHeaders.of(headers.map { case (name, values) => name -> values.asJava }.asJava, (_, _) => true)

  private final case class ResponseInfoImpl(statusCode: Int, headers: HttpHeaders, version: Version)
      extends ResponseInfo

  private final case class VcrHttpResponse[T](
    statusCode: Int,
    request: HttpRequest,
    headers: HttpHeaders,
    body: T,
    uri: URI,
    version: Version,
    sslSession: Optional[SSLSession]
  ) extends HttpResponse[T] {
    override def previousResponse(): Optional[HttpResponse[T]] = Optional.empty()
  }
}
