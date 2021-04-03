package bekindrewind.akkahttp

import bekindrewind._

import akka.actor.ClassicActorSystemProvider
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.util.ByteString
import io.circe.parser._
import io.circe.syntax._

import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.time.OffsetDateTime
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object VcrHttp {
  def create(
    sendRequest: HttpRequest => Future[HttpResponse],
    recordingPath: Path,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => (r.method, r.uri))
  )(implicit executionContext: ExecutionContext, materializer: Materializer): VcrHttp =
    new VcrHttp(sendRequest, recordingPath, recordOptions, matcher)

  def useClassicActorSystem(
    recordingPath: Path,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => (r.method, r.uri)),
    executionContext: Option[ExecutionContext] = None
  )(implicit system: ClassicActorSystemProvider): VcrHttp = {
    implicit val ec = executionContext.getOrElse(system.classicSystem.dispatcher)
    create(Http().singleRequest(_), recordingPath, recordOptions, matcher)
  }

  private[akkahttp] def toVcrRequest(
    request: HttpRequest
  )(implicit executionContext: ExecutionContext, materializer: Materializer): Future[VcrRecordRequest] =
    Unmarshal(request).to[String].map { requestBody =>
      VcrRecordRequest(
        method = request.method.value,
        uri = URI.create(request.uri.toString()),
        body = requestBody,
        headers = toVcrHeaders(request.headers),
        httpVersion = request.protocol.value
      )
    }

  private[akkahttp] def showContentType(contentType: ContentType): Option[String] =
    Option.when(contentType != ContentTypes.NoContentType)(contentType.value)

  private[akkahttp] def toAkkaResponse(
    vcrRecordRequest: VcrRecordRequest,
    vcrResponse: VcrRecordResponse
  ): HttpResponse =
    HttpResponse(
      status = StatusCode.int2StatusCode(vcrResponse.statusCode),
      headers = toAkkaHeaders(vcrResponse.headers),
      entity = vcrResponse.contentType match {
        case Some(value) =>
          HttpEntity.Strict(
            ContentType
              .parse(value)
              .getOrElse(
                throw new IllegalStateException(s"Content-type of a recorded response can not be parsed: ${value}")
              ),
            ByteString.apply(vcrResponse.body)
          )
        case None        => HttpEntity(vcrResponse.body)
      },
      protocol = HttpProtocol.apply(vcrRecordRequest.httpVersion)
    )

  private[akkahttp] def toVcrResponse(
    response: HttpResponse
  )(implicit executionContext: ExecutionContext, materializer: Materializer): Future[VcrRecordResponse] =
    Unmarshal(response).to[String].map { responseBody =>
      VcrRecordResponse(
        statusCode = response.status.intValue(),
        statusText = response.status.reason(),
        headers = toVcrHeaders(response.headers),
        body = responseBody,
        contentType = showContentType(response.entity.contentType)
      )
    }

  private[akkahttp] def toVcrHeaders(headers: Seq[HttpHeader]): Map[String, Seq[String]] =
    headers.groupBy(header => header.name()).view.mapValues(_.map(_.value())).toMap

  private[akkahttp] def toAkkaHeaders(headers: Map[String, Seq[String]]): Seq[HttpHeader] =
    (for {
      (key, group) <- headers.iterator
      value        <- group.iterator
    } yield HttpHeader.parse(key, value)).collect { case HttpHeader.ParsingResult.Ok(header, _) =>
      header
    }.toSeq
}

// Akka Http does not have "Client" concept.
class VcrHttp private (
  sendRequest: HttpRequest => Future[HttpResponse],
  val recordingPath: Path,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
)(implicit val ec: ExecutionContext, materializer: Materializer)
    extends VcrClient
    with Closeable {
  import VcrHttp._

  override def close(): Unit =
    Try(save()).failed.foreach(_.printStackTrace())

  def send(request: HttpRequest): Future[HttpResponse] =
    toVcrRequest(request).flatMap { vcrRequest =>
      this.findMatch(vcrRequest) match {
        case Some(VcrRecord(_, response, _)) => Future.successful(toAkkaResponse(vcrRequest, response))
        case None                            =>
          if (this.recordOptions.shouldRecord(vcrRequest)) {
            sendRequest(request).flatMap { response =>
              toVcrResponse(response).map { vcrResponse =>
                this.newlyRecorded.updateAndGet { records =>
                  records :+ VcrRecord(vcrRequest, vcrResponse, OffsetDateTime.now())
                }
                response
              }
            }
          } else if (this.recordOptions.notRecordedThrowsErrors) {
            Future.failed(
              new Exception(
                s"Recording is disabled for `${vcrRequest.method} ${vcrRequest.uri}`. The HTTP request was not executed."
              )
            )
          } else {
            sendRequest(request)
          }
      }
    }
}
