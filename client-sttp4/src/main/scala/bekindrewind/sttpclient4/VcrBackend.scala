package bekindrewind.sttpclient4

import bekindrewind._
import bekindrewind.storage.VcrStorage
import sttp.capabilities.Effect
import sttp.client4._
import sttp.client4.internal.{ BodyFromResponseAs, SttpFile }
import sttp.client4.wrappers.DelegateBackend
import sttp.client4.ws.{ GotAWebSocketException, NotAWebSocketException }
import sttp.model.{ Header, HttpVersion, ResponseMetadata, StatusCode }
import sttp.monad.{ IdentityMonad, MonadError }
import sttp.monad.syntax._
import sttp.shared.Identity

import java.nio.file.Files
import java.time.OffsetDateTime
import scala.collection.immutable
import scala.util.Try

/** Configuration for [[VcrBackend]]. */
final case class VcrSettings(
  storage: VcrStorage,
  recordOptions: RecordOptions = RecordOptions.default,
  matcher: VcrMatcher = VcrMatcher.default
)

/**
 * Wraps an sttp4 backend. Create one with the `VcrBackend.apply` overload for your backend type, for example
 * `VcrBackend(DefaultSyncBackend(), VcrSettings(storage))`. Web socket requests are passed through without recording.
 */
abstract class VcrBackend[F[_], P] private (delegate: GenericBackend[F, P], settings: VcrSettings)
    extends DelegateBackend(delegate)
    with VcrClient {
  import VcrBackend._

  type R = P with Effect[F]

  override val storage: VcrStorage          = settings.storage
  override val recordOptions: RecordOptions = settings.recordOptions
  override val matcher: VcrMatcher          = settings.matcher

  override def send[T](request: GenericRequest[T, R]): F[Response[T]] =
    monad.suspend {
      (bufferBody(request), withRecording(request)) match {
        case (Some((bufferedRequest, bytes)), Some(_)) => sendBuffered(bufferedRequest, bytes)
        case _                                         =>
          // Web socket requests, streaming and multipart bodies can't be recorded.
          if (recordOptions.notRecordedThrowsErrors)
            monad.error[Response[T]](
              new VcrException(s"Cannot record `${request.method} ${request.uri}` (web socket, stream or multipart).")
            )
          else delegate.send(request)
      }
    }

  private def sendBuffered[T](request: GenericRequest[T, R], body: Array[Byte]): F[Response[T]] = {
    val vcrRequest = VcrRequest.fromBytes(
      request.method.method,
      request.uri.toJavaUri,
      body,
      toPlainHeaders(request.headers),
      request.httpVersion.map(showHttpVersion)
    )

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

      case VcrAction.Record =>
        withRecording(request) match {
          case Some(recordingRequest) =>
            delegate.send(recordingRequest).map { response =>
              addNewEntry(
                VcrEntry(
                  vcrRequest,
                  VcrResponse.fromBytes(
                    response.code.code,
                    response.statusText,
                    toPlainHeaders(response.headers),
                    response.body._2.getOrElse(Array.emptyByteArray),
                    response.contentType,
                    None // sttp doesn't expose the protocol version of responses
                  ),
                  OffsetDateTime.now
                )
              )

              response.copy(body = response.body._1)
            }
          case None                   => delegate.send(request)
        }

      case VcrAction.PassThrough => delegate.send(request)

      case VcrAction.Fail(error) => monad.error(error)
    }
  }

  override def close(): F[Unit] = {
    Try(save()).failed.foreach(_.printStackTrace())
    delegate.close()
  }
}

object VcrBackend {
  def apply(delegate: SyncBackend, settings: VcrSettings): SyncBackend with VcrClient =
    new VcrBackend(delegate, settings) with SyncBackend {}

  def apply[F[_]](delegate: Backend[F], settings: VcrSettings): Backend[F] with VcrClient =
    new VcrBackend(delegate, settings) with Backend[F] {}

  def apply[F[_], S](delegate: StreamBackend[F, S], settings: VcrSettings): StreamBackend[F, S] with VcrClient =
    new VcrBackend(delegate, settings) with StreamBackend[F, S] {}

  def apply(delegate: WebSocketSyncBackend, settings: VcrSettings): WebSocketSyncBackend with VcrClient =
    new VcrBackend(delegate, settings) with WebSocketSyncBackend {}

  def apply[F[_]](delegate: WebSocketBackend[F], settings: VcrSettings): WebSocketBackend[F] with VcrClient =
    new VcrBackend(delegate, settings) with WebSocketBackend[F] {}

  def apply[F[_], S](
    delegate: WebSocketStreamBackend[F, S],
    settings: VcrSettings
  ): WebSocketStreamBackend[F, S] with VcrClient =
    new VcrBackend(delegate, settings) with WebSocketStreamBackend[F, S] {}

  /** Returns a copy of the request that also returns the raw response body, or `None` for web socket requests. */
  private def withRecording[T, R](request: GenericRequest[T, R]): Option[GenericRequest[(T, Option[Array[Byte]]), R]] =
    request match {
      case r: Request[T @unchecked]                     => Some(r.response(asBothOption(r.response, asByteArrayAlways)))
      case r: StreamRequest[T @unchecked, R @unchecked] =>
        Some(r.response(asBothOption(r.response, asByteArrayAlways)))
      case _                                            => None
    }

  /**
   * Returns the request body along with a request that can still be sent (one-shot bodies like input streams are
   * replaced with the bytes that were read). Returns `None` for bodies that can't be buffered.
   */
  private def bufferBody[T, R](request: GenericRequest[T, R]): Option[(GenericRequest[T, R], Array[Byte])] =
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
