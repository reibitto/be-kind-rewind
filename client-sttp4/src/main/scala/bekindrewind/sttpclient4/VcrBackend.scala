package bekindrewind.sttpclient4

import bekindrewind._
import bekindrewind.storage.VcrStorage
import sttp.capabilities.Effect
import sttp.client4._
import sttp.client4.internal.{ BodyFromResponseAs, SttpFile }
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.ws.{ GotAWebSocketException, NotAWebSocketException }
import sttp.model.{ Header, HeaderNames, HttpVersion, ResponseMetadata, StatusCode }
import sttp.monad.{ IdentityMonad, MonadError }
import sttp.monad.syntax._
import sttp.shared.Identity

import java.nio.file.Files
import java.time.OffsetDateTime
import scala.collection.immutable
import scala.util.Try

/**
 * Wraps an sttp4 backend. Create one with the `VcrBackend.apply` overload for your backend type, for example
 * `VcrBackend(DefaultSyncBackend(), VcrSettings(storage))`. Web socket requests are passed through without recording.
 */
abstract class VcrBackend[F[_], P] private (underlyingClient: GenericBackend[F, P], settings: VcrSettings)
    extends DelegateBackend(underlyingClient)
    with VcrClient {
  import VcrBackend._

  type R = P with Effect[F]

  override val storage: VcrStorage          = settings.storage
  override val recordOptions: RecordOptions = settings.recordOptions
  override val matcher: VcrMatcher          = settings.matcher

  override def send[T](request: GenericRequest[T, R]): F[Response[T]] =
    monad.suspend {
      // Only requests with a regular (not streaming or web socket) response can be recorded.
      request match {
        case r: Request[T @unchecked] =>
          bufferBody(r) match {
            case Some((bufferedRequest, bytes)) => sendBuffered(bufferedRequest, bytes)
            case None                           => sendUnrecordable(request)
          }
        case _                        => sendUnrecordable(request)
      }
    }

  private def sendUnrecordable[T](request: GenericRequest[T, R]): F[Response[T]] =
    resolveUnrecordable(
      toVcrRequest(request, Array.emptyByteArray),
      "it's a web socket, stream or multipart request"
    ) match {
      case VcrAction.Fail(error) => monad.error[Response[T]](error)
      case _                     => underlyingClient.send(request)
    }

  private def sendBuffered[T](request: Request[T], body: Array[Byte]): F[Response[T]] = {
    val vcrRequest = toVcrRequest(request, body)

    resolve(vcrRequest) match {
      case VcrAction.Replay(entry) =>
        val meta = ResponseMetadata(
          StatusCode(entry.response.statusCode),
          entry.response.statusText,
          toSttpHeaders(entry.response.headers + (VcrClient.vcrCacheHeaderName -> Seq("true")))
        )

        monad.eval {
          val body = bodyFromResponseAs.apply[T](request.response, meta, Left(entry.response.bodyBytes))
          Response(body, meta.code, meta.statusText, meta.headers, Nil, request.onlyMetadata)
        }

      // The body is read as bytes and then converted the same way as when replaying, so both behave the same.
      case VcrAction.Record =>
        underlyingClient.send(request.response(asByteArrayAlways)).map { response =>
          addNewEntry(
            VcrEntry(
              vcrRequest,
              VcrResponse.fromBytes(
                response.code.code,
                response.statusText,
                toPlainHeaders(
                  recordedHeaders(response.headers, decompressed = request.options.decompressResponseBody)
                ),
                response.body,
                response.contentType,
                None // sttp doesn't expose the protocol version of responses
              ),
              OffsetDateTime.now
            )
          )

          response.copy(body = bodyFromResponseAs.apply[T](request.response, response, Left(response.body)))
        }

      case VcrAction.PassThrough => underlyingClient.send(request)

      case VcrAction.Fail(error) => monad.error(error)
    }
  }

  /** Closes the underlying backend, then fails if saving failed (e.g. because of unused entries). */
  override def close(): F[Unit] = {
    val saved = Try(save())
    underlyingClient.close().flatMap(_ => saved.fold(monad.error[Unit], _ => monad.unit(())))
  }
}

object VcrBackend {
  def apply(underlyingClient: SyncBackend, settings: VcrSettings): SyncBackend with VcrClient =
    new VcrBackend(underlyingClient, settings) with SyncBackend {}

  def apply[F[_]](underlyingClient: Backend[F], settings: VcrSettings): Backend[F] with VcrClient =
    new VcrBackend(underlyingClient, settings) with Backend[F] {}

  def apply[F[_], S](underlyingClient: StreamBackend[F, S], settings: VcrSettings): StreamBackend[F, S] with VcrClient =
    new VcrBackend(underlyingClient, settings) with StreamBackend[F, S] {}

  def apply(underlyingClient: WebSocketSyncBackend, settings: VcrSettings): WebSocketSyncBackend with VcrClient =
    new VcrBackend(underlyingClient, settings) with WebSocketSyncBackend {}

  def apply[F[_]](underlyingClient: WebSocketBackend[F], settings: VcrSettings): WebSocketBackend[F] with VcrClient =
    new VcrBackend(underlyingClient, settings) with WebSocketBackend[F] {}

  def apply[F[_], S](
    underlyingClient: WebSocketStreamBackend[F, S],
    settings: VcrSettings
  ): WebSocketStreamBackend[F, S] with VcrClient =
    new VcrBackend(underlyingClient, settings) with WebSocketStreamBackend[F, S] {}

  private def toVcrRequest(request: GenericRequest[_, _], body: Array[Byte]): VcrRequest =
    VcrRequest.fromBytes(
      request.method.method,
      request.uri.toJavaUri,
      body,
      toPlainHeaders(request.headers),
      request.httpVersion.map(showHttpVersion)
    )

  /**
   * Returns the request body along with a request that can still be sent (one-shot bodies like input streams are
   * replaced with the bytes that were read). Returns `None` for bodies that can't be buffered.
   */
  private def bufferBody[T](request: Request[T]): Option[(Request[T], Array[Byte])] =
    request.body match {
      case NoBody                    => Some((request, Array.emptyByteArray))
      case StringBody(s, charset, _) => Some((request, s.getBytes(charset)))
      case ByteArrayBody(b, _)       => Some((request, b))
      case ByteBufferBody(b, _)      =>
        val bytes = new Array[Byte](b.remaining())
        b.duplicate().get(bytes)
        Some((request, bytes))
      case InputStreamBody(b, ct)    =>
        val bytes = b.readAllBytes()
        Some((request.body(ByteArrayBody(bytes, ct)), bytes))
      case FileBody(f, _)            => Some((request, f.readAsByteArray()))
      case _                         => None
    }

  /**
   * The response headers worth recording. HTTP/2 pseudo-headers (such as `:status`) aren't real headers, and when sttp
   * decompressed the body, `Content-Encoding` and `Content-Length` describe the compressed body rather than the
   * recorded one.
   */
  private def recordedHeaders(headers: Seq[Header], decompressed: Boolean): Seq[Header] =
    headers.filterNot { header =>
      header.name.startsWith(":") ||
      (decompressed && (header.is(HeaderNames.ContentEncoding) || header.is(HeaderNames.ContentLength)))
    }

  private def showHttpVersion(version: HttpVersion): String =
    version match {
      case HttpVersion.HTTP_1   => "HTTP/1.0"
      case HttpVersion.HTTP_1_1 => "HTTP/1.1"
      case HttpVersion.HTTP_2   => "HTTP/2"
      case HttpVersion.HTTP_3   => "HTTP/3"
    }

  private def toPlainHeaders(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name).map { case (k, vs) => (k, vs.map(_.value)) }

  private def toSttpHeaders(headers: Map[String, Seq[String]]): immutable.Seq[Header] =
    headers.flatMap { case (k, vs) => vs.map(Header(k, _)) }.toVector

  private val bodyFromResponseAs: BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing] = {
    implicit val idMonad: MonadError[Identity] = IdentityMonad

    new BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing] {
      override protected def withReplayableBody(
        response: Array[Byte],
        replayableBody: Either[Array[Byte], SttpFile]
      ): Identity[Array[Byte]] = response

      override protected def regularIgnore(response: Array[Byte]): Identity[Unit] = ()

      override protected def regularAsByteArray(response: Array[Byte]): Identity[Array[Byte]] = response

      override protected def regularAsFile(response: Array[Byte], file: SttpFile): Identity[SttpFile] = {
        Files.write(file.toPath, response)
        file
      }

      override protected def regularAsStream(response: Array[Byte]): Identity[(Nothing, () => Identity[Unit])] =
        throw new IllegalStateException("VcrBackend does not support replaying streaming responses")

      override protected def regularAsInputStream(response: Array[Byte]): Identity[java.io.InputStream] =
        new java.io.ByteArrayInputStream(response)

      override protected def handleWS[T](
        responseAs: GenericWebSocketResponseAs[T, _],
        meta: ResponseMetadata,
        ws: Nothing
      ): Identity[T] = ws

      override protected def cleanupWhenNotAWebSocket(
        response: Array[Byte],
        e: NotAWebSocketException
      ): Identity[Unit] = ()

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): Identity[Unit] =
        ()
    }
  }
}
