package bekindrewind.sttpclient

import bekindrewind._
import bekindrewind.storage.VcrStorage
import bekindrewind.util.IOUtils
import sttp.capabilities
import sttp.client3._
import sttp.client3.internal.{ BodyFromResponseAs, SttpFile }
import sttp.client3.monad.IdMonad
import sttp.client3.ws.{ GotAWebSocketException, NotAWebSocketException }
import sttp.model.{ Header, ResponseMetadata, StatusCode }
import sttp.monad.MonadError
import sttp.monad.syntax._

import java.nio.charset.StandardCharsets
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

  override def send[T, R >: P with capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] = {
    val vcrRequest = VcrRequest(
      request.method.method,
      request.uri.toJavaUri,
      requestBodyToString(request.body),
      toPlainHeaders(request.headers),
      "HTTP/1.1" // TODO: This assumes HTTP/1.1 is being used. Find a way to get the protocol from the HTTP library itself
    )

    findMatch(vcrRequest) match {
      case Some(r) =>
        val meta = ResponseMetadata(
          StatusCode(r.response.statusCode),
          r.response.statusText,
          toSttpHeaders(r.response.headers + (VcrClient.vcrCacheHeaderName -> Seq("true")))
        )

        val body = VcrBackend.bodyFromResponseAs(request.response, meta, Left(r.response.body))

        responseMonad.unit(
          Response(
            body,
            meta.code,
            meta.statusText,
            meta.headers
          )
        )

      case None =>
        if (matcher.shouldRecord(vcrRequest)) {
          implicit val monadError: MonadError[F] = underlyingBackend.responseMonad

          for {
            response    <- request.response(asBothOption(request.response, asStringAlways)).send(underlyingBackend)
            bodyAsString = response.body._2.getOrElse("")
            record       = VcrEntry(
                             VcrRequest(
                               request.method.method,
                               request.uri.toJavaUri,
                               requestBodyToString(request.body),
                               toPlainHeaders(request.headers),
                               "HTTP/1.1" // TODO: This assumes HTTP/1.1 is being used. Find a way to get the protocol from the HTTP library itself
                             ),
                             VcrResponse(
                               response.code.code,
                               response.statusText,
                               toPlainHeaders(response.headers),
                               bodyAsString,
                               response.contentType
                             ),
                             OffsetDateTime.now
                           )
            _            = this.addNewEntry(record)
          } yield response.copy(body = response.body._1)
        } else if (recordOptions.notRecordedThrowsErrors) {
          underlyingBackend.responseMonad.error(
            new Exception(
              s"Recording is disabled for `${vcrRequest.method} ${vcrRequest.uri}`. The HTTP request was not executed."
            )
          )
        } else {
          underlyingBackend.send(request)
        }
    }
  }

  def close(): F[Unit] = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlyingBackend.close()
  }

  def responseMonad: MonadError[F] = underlyingBackend.responseMonad

  private def requestBodyToString[R](requestBody: RequestBody[R]): String =
    requestBody match {
      case NoBody                => ""
      case StringBody(s, _, _)   => s
      case ByteArrayBody(b, _)   => new String(b)
      case ByteBufferBody(b, _)  => new String(b.array())
      case InputStreamBody(b, _) => new String(IOUtils.toByteArray(b))
      case FileBody(f, _)        => f.readAsString
      case StreamBody(_)         =>
        throw new IllegalArgumentException("The body of this request is a stream, cannot convert to String")
      case MultipartBody(_)      =>
        throw new IllegalArgumentException("The body of this request is multipart, cannot convert to String")
    }

  def toPlainHeaders(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name).map { case (k, vs) =>
      (k, vs.map(_.value))
    }

  def toSttpHeaders(headers: Map[String, Seq[String]]): immutable.Seq[Header] =
    headers.flatMap { case (k, vs) =>
      vs.map(v => Header(k, v))
    }.toVector
}

object VcrBackend {
  def apply[F[_], P](
    underlyingClient: SttpBackend[F, P],
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => VcrKey(r.method, r.uri))
  ): VcrBackend[F, P] =
    new VcrBackend[F, P](
      underlyingClient,
      storage,
      recordOptions,
      matcher
    )

  val bodyFromResponseAs: BodyFromResponseAs[Identity, String, Nothing, Nothing] = {
    implicit val idMonad: MonadError[Identity] = IdMonad

    new BodyFromResponseAs[Identity, String, Nothing, Nothing] {
      override protected def withReplayableBody(
        response: String,
        replayableBody: Either[Array[Byte], SttpFile]
      ): Identity[String] = response.unit

      override protected def regularIgnore(response: String): Identity[Unit] = ()

      override protected def regularAsByteArray(response: String): Identity[Array[Byte]] =
        response.getBytes(StandardCharsets.UTF_8)

      override protected def regularAsFile(response: String, file: SttpFile): Identity[SttpFile] =
        throw new IllegalStateException("VcrBackend does not support file responses")

      override protected def regularAsStream(response: String): (Nothing, () => Identity[Unit]) =
        throw new IllegalStateException("VcrBackend does not support streaming responses")

      override protected def handleWS[U](
        responseAs: WebSocketResponseAs[U, _],
        meta: ResponseMetadata,
        ws: Nothing
      ): Identity[U] = ws

      override protected def cleanupWhenNotAWebSocket(response: String, e: NotAWebSocketException): Identity[Unit] =
        ().unit

      override protected def cleanupWhenGotWebSocket(response: Nothing, e: GotAWebSocketException): Identity[Unit] =
        ().unit
    }
  }
}
