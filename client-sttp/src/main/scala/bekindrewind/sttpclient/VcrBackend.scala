package bekindrewind.sttpclient

import bekindrewind.util.IOUtils
import bekindrewind._
import sttp.capabilities
import sttp.client3._
import sttp.model.{ Header, StatusCode }
import sttp.monad.MonadError

import java.nio.file.Path
import java.time.OffsetDateTime
import scala.util.Try

class VcrBackend[F[_], P](
  val underlying: SttpBackend[F, P],
  val recordingPath: Path,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
) extends SttpBackend[F, P]
    with VcrClient {

  override def send[T, R >: P with capabilities.Effect[F]](request: Request[T, R]): F[Response[T]] = {
    val recordRequest = VcrRecordRequest(
      request.method.method,
      request.uri.toJavaUri,
      requestBodyToString(request.body),
      toPlainHeaders(request.headers)
    )

    findMatch(recordRequest) match {
      case Some(r) =>
        responseMonad.unit(
          Response(
            Right(r.response.body).asInstanceOf[T], // TODO: This isn't good
            StatusCode(r.response.statusCode),
            r.response.statusText,
            toSttpHeaders(r.response.headers)
          )
        )

      case None =>
        if (recordOptions.shouldRecord(recordRequest)) {
          println(s"Performing actual HTTP request: ${request.method} ${request.uri}")

          val responseF = underlying.send(request)

          underlying.responseMonad.map(responseF) { response =>
            val record = VcrRecord(
              VcrRecordRequest(
                request.method.method,
                request.uri.toJavaUri,
                requestBodyToString(request.body),
                toPlainHeaders(request.headers)
              ),
              VcrRecordResponse(
                response.code.code,
                response.statusText,
                toPlainHeaders(response.headers),
                responseBodyToString(response.body)
              ),
              OffsetDateTime.now
            )

            newlyRecorded.updateAndGet { records =>
              records :+ record
            }
          }

          responseF
        } else if (recordOptions.notRecordedThrowsErrors) {
          underlying.responseMonad.error(
            new Exception(
              s"Recording is disabled for `${recordRequest.method} ${recordRequest.uri}`. The HTTP request was not executed."
            )
          )
        } else {
          underlying.send(request)
        }
    }
  }

  def close(): F[Unit] = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlying.close()
  }

  def responseMonad: MonadError[F] = underlying.responseMonad

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

  private def responseBodyToString[T](response: T): String =
    // TODO: This is really bad. Somebody help!
    response match {
      case a: Either[Any, Any] => a.fold(_.toString, _.toString)
      case other               => other.toString
    }

  def toPlainHeaders(headers: Seq[Header]): Map[String, Seq[String]] =
    headers.groupBy(_.name).map { case (k, vs) =>
      (k, vs.map(_.value))
    }

  def toSttpHeaders(headers: Map[String, Seq[String]]): Seq[Header] =
    headers.flatMap { case (k, vs) =>
      vs.map(v => Header(k, v))
    }.toSeq
}

object VcrBackend {
  def apply[F[_], P](
    underlyingClient: SttpBackend[F, P],
    recordingPath: Path,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => (r.method, r.uri))
  ): VcrBackend[F, P] =
    new VcrBackend[F, P](
      underlyingClient,
      recordingPath,
      recordOptions,
      matcher
    )
}
