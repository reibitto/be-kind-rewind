package bekindrewind.sttpclient

import bekindrewind._
import bekindrewind.storage.VcrStorage
import sttp.capabilities
import sttp.client3._
import sttp.client3.internal.{ BodyFromResponseAs, SttpFile }
import sttp.client3.monad.IdMonad
import sttp.client3.ws.{ GotAWebSocketException, NotAWebSocketException }
import sttp.model.{ Header, HeaderNames, HttpVersion, RequestMetadata, ResponseMetadata, StatusCode }
import sttp.monad.MonadError
import sttp.monad.syntax._

import java.nio.file.Files
import java.time.OffsetDateTime
import scala.collection.immutable
import scala.util.Try

class VcrBackend[F[_], P](
  val underlyingBackend: SttpBackend[F, P],
  val storage: VcrStorage,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
) extends SttpBackend[F, P]
    with VcrClient {
  import VcrBackend._

  implicit private val monad: MonadError[F] = underlyingBackend.responseMonad

  override def send[T, R >: P with capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] =
    monad.flatten(monad.eval {
      // Checked before buffering, since buffering consumes one-shot bodies of requests that are then sent as is.
      (if (replayable(request.response)) bufferBody(request) else None) match {
        case Some((bufferedRequest, bytes)) => sendBuffered(bufferedRequest, bytes)
        case None                           =>
          val vcrRequest = toVcrRequest(request, Array.emptyByteArray)
          resolveUnrecordable(vcrRequest, "its body or response is a stream, multipart or a web socket") match {
            case VcrAction.Fail(error) => monad.error[Response[T]](error)
            case _                     => underlyingBackend.send(request)
          }
      }
    })

  private def sendBuffered[T, R >: P with capabilities.Effect[F]](
    request: Request[T, R],
    body: Array[Byte]
  ): F[Response[T]] = {
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
          Response(
            body,
            meta.code,
            meta.statusText,
            meta.headers,
            Nil,
            RequestMetadata(request.method, request.uri, request.headers)
          )
        }

      // The body is read as bytes and then converted the same way as when replaying, so both behave the same.
      case VcrAction.Record =>
        request.response(asByteArrayAlways).send(underlyingBackend).map { response =>
          addNewEntry(
            VcrEntry(
              vcrRequest,
              VcrResponse.fromBytes(
                response.code.code,
                response.statusText,
                toPlainHeaders(recordedHeaders(response.headers, decompressed = !request.autoDecompressionDisabled)),
                response.body,
                response.contentType,
                None // sttp doesn't expose the protocol version of responses
              ),
              OffsetDateTime.now
            )
          )

          response.copy(body = bodyFromResponseAs.apply[T](request.response, response, Left(response.body)))
        }

      case VcrAction.PassThrough => underlyingBackend.send(request)

      case VcrAction.Fail(error) => monad.error(error)
    }
  }

  /** Closes the underlying backend, then fails if saving failed (e.g. because of unused entries). */
  def close(): F[Unit] = {
    val saved = Try(save())
    underlyingBackend.close().flatMap(_ => saved.fold(monad.error[Unit], _ => monad.unit(())))
  }

  def responseMonad: MonadError[F] = monad
}

object VcrBackend {
  def apply[F[_], P](
    underlyingClient: SttpBackend[F, P],
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  ): VcrBackend[F, P] =
    new VcrBackend[F, P](underlyingClient, storage, recordOptions, matcher)

  def apply[F[_], P](underlyingClient: SttpBackend[F, P], settings: VcrSettings): VcrBackend[F, P] =
    apply(underlyingClient, settings.storage, settings.recordOptions, settings.matcher)

  private def toVcrRequest(request: RequestT[Identity, _, _], body: Array[Byte]): VcrRequest =
    VcrRequest.fromBytes(
      request.method.method,
      request.uri.toJavaUri,
      body,
      toPlainHeaders(request.headers),
      request.httpVersion.map(showHttpVersion)
    )

  /** Whether responses of this type can be replayed from recorded bytes (streams and web sockets can't). */
  private def replayable(responseAs: ResponseAs[_, _]): Boolean =
    responseAs match {
      case _: ResponseAsStream[_, _, _, _] | _: ResponseAsStreamUnsafe[_, _] | _: WebSocketResponseAs[_, _] => false
      case ResponseAsFromMetadata(conditions, default)                                                      =>
        conditions.forall(c => replayable(c.responseAs)) && replayable(default)
      case MappedResponseAs(raw, _, _)                                                                      => replayable(raw)
      case ResponseAsBoth(l, r)                                                                             => replayable(l) && replayable(r)
      case _                                                                                                => true
    }

  /**
   * Returns the request body as a string along with a request that can still be sent (one-shot bodies like input
   * streams are replaced with the bytes that were read). Returns `None` for bodies that can't be buffered.
   */
  private def bufferBody[T, R](request: Request[T, R]): Option[(Request[T, R], Array[Byte])] =
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
        Some((request.copy(body = ByteArrayBody(bytes, ct)), bytes))
      case FileBody(f, _)            => Some((request, f.readAsByteArray))
      case StreamBody(_)             => None
      case MultipartBody(_)          => None
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

  private[sttpclient] def toPlainHeaders(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name).map { case (k, vs) => (k, vs.map(_.value)) }

  private[sttpclient] def toSttpHeaders(headers: Map[String, Seq[String]]): immutable.Seq[Header] =
    headers.flatMap { case (k, vs) => vs.map(Header(k, _)) }.toVector

  private val bodyFromResponseAs: BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing] = {
    implicit val idMonad: MonadError[Identity] = IdMonad

    new BodyFromResponseAs[Identity, Array[Byte], Nothing, Nothing] {
      override protected def withReplayableBody(
        response: Array[Byte],
        replayableBody: Either[Array[Byte], SttpFile]
      ): Identity[Array[Byte]] = response.unit

      override protected def regularIgnore(response: Array[Byte]): Identity[Unit] = ()

      override protected def regularAsByteArray(response: Array[Byte]): Identity[Array[Byte]] = response

      override protected def regularAsFile(response: Array[Byte], file: SttpFile): Identity[SttpFile] = {
        Files.write(file.toPath, response)
        file
      }

      override protected def regularAsStream(response: Array[Byte]): (Nothing, () => Identity[Unit]) =
        throw new IllegalStateException("VcrBackend does not support streaming responses")

      override protected def handleWS[U](
        responseAs: WebSocketResponseAs[U, _],
        meta: ResponseMetadata,
        ws: Nothing
      ): Identity[U] = ws

      override protected def cleanupWhenNotAWebSocket(
        response: Array[Byte],
        e: NotAWebSocketException
      ): Identity[Unit] =
        ().unit

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): Identity[Unit] =
        ().unit
    }
  }
}
