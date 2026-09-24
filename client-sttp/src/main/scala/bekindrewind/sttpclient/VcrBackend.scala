package bekindrewind.sttpclient

import bekindrewind._
import bekindrewind.storage.VcrStorage
import sttp.capabilities
import sttp.client3._
import sttp.client3.internal.{ BodyFromResponseAs, SttpFile }
import sttp.client3.monad.IdMonad
import sttp.client3.ws.{ GotAWebSocketException, NotAWebSocketException }
import sttp.model.{ Header, HttpVersion, RequestMetadata, ResponseMetadata, StatusCode }
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
      bufferBody(request) match {
        case None                           =>
          // Streaming and multipart bodies can't be recorded.
          if (recordOptions.notRecordedThrowsErrors)
            monad.error[Response[T]](
              new VcrException(
                s"Cannot record `${request.method} ${request.uri}` because its body is a stream or multipart."
              )
            )
          else underlyingBackend.send(request)
        case Some((bufferedRequest, bytes)) =>
          sendBuffered(bufferedRequest, bytes)
      }
    })

  private def sendBuffered[T, R >: P with capabilities.Effect[F]](
    request: Request[T, R],
    body: Array[Byte]
  ): F[Response[T]] = {
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
          Response(
            body,
            meta.code,
            meta.statusText,
            meta.headers,
            Nil,
            RequestMetadata(request.method, request.uri, request.headers)
          )
        }

      case VcrAction.Record =>
        request.response(asBothOption(request.response, asByteArrayAlways)).send(underlyingBackend).map { response =>
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

      case VcrAction.PassThrough => underlyingBackend.send(request)

      case VcrAction.Fail(error) => monad.error(error)
    }
  }

  def close(): F[Unit] = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlyingBackend.close()
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
