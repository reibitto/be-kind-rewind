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

  private[akkahttp] def toVcrRequest(request: HttpRequest): VcrRecordRequest =
    VcrRecordRequest(
      method = request.method.value,
      uri = URI.create(request.uri.toString()),
      body = request.entity.toString,
      headers = toVcrHeaders(request.headers)
    )

  private[akkahttp] def toAkkaResponse(vcrResponse: VcrRecordResponse): HttpResponse = {
    val store = decode[AkkaHttpResponseStore](vcrResponse.body).toOption.get
    HttpResponse(
      status = StatusCode.int2StatusCode(vcrResponse.statusCode),
      headers = toAkkaHeaders(vcrResponse.headers),
      entity = HttpEntity.Strict(
        ContentType.parse(store.contentType).getOrElse(???),
        ByteString.apply(store.data)
      ),
      protocol = HttpProtocol.apply(store.httpProtocol)
    )
  }

  private[akkahttp] def toVcrResponse(
    response: HttpResponse
  )(implicit executionContext: ExecutionContext, materializer: Materializer): Future[VcrRecordResponse] =
    Unmarshal(response).to[String].map { data =>
      VcrRecordResponse(
        statusCode = response.status.intValue(),
        statusText = response.status.reason(),
        headers = toVcrHeaders(response.headers),
        body = AkkaHttpResponseStore(
          httpProtocol = response.protocol.value,
          contentType = response.entity.contentType.value,
          data = data
        ).asJson.noSpaces
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

  def send(request: HttpRequest): Future[HttpResponse] = {
    val vcrRequest = toVcrRequest(request)

    this.findMatch(vcrRequest) match {
      case Some(VcrRecord(_, response, _)) => Future.successful(toAkkaResponse(response))
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
