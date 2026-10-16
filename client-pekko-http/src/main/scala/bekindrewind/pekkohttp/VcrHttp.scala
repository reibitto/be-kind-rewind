package bekindrewind.pekkohttp

import bekindrewind._
import bekindrewind.storage.VcrStorage
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString

import java.io.Closeable
import java.net.URI
import java.time.OffsetDateTime
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object VcrHttp {
  val defaultStrictTimeout: FiniteDuration = 30.seconds

  def create(
    sendRequest: HttpRequest => Future[HttpResponse],
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default,
    strictTimeout: FiniteDuration = defaultStrictTimeout
  )(implicit executionContext: ExecutionContext, materializer: Materializer): VcrHttp =
    new VcrHttp(sendRequest, storage, recordOptions, matcher, strictTimeout)

  def useClassicActorSystem(
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default,
    executionContext: Option[ExecutionContext] = None,
    strictTimeout: FiniteDuration = defaultStrictTimeout
  )(implicit system: ClassicActorSystemProvider): VcrHttp = {
    implicit val ec: ExecutionContext = executionContext.getOrElse(system.classicSystem.dispatcher)
    create(Http().singleRequest(_), storage, recordOptions, matcher, strictTimeout)
  }

  private[pekkohttp] def toVcrRequest(request: HttpRequest, body: ByteString): VcrRequest =
    VcrRequest.fromBytes(
      method = request.method.value,
      uri = URI.create(request.uri.toString()),
      body = body.toArray,
      headers = withContentType(toVcrHeaders(request.headers), request.entity),
      httpVersion = Some(request.protocol.value)
    )

  private[pekkohttp] def toVcrResponse(response: HttpResponse, body: ByteString): VcrResponse =
    VcrResponse.fromBytes(
      statusCode = response.status.intValue(),
      statusText = response.status.reason(),
      headers = withContentType(toVcrHeaders(response.headers), response.entity),
      body = body.toArray,
      contentType = showContentType(response.entity.contentType),
      httpVersion = Some(response.protocol.value)
    )

  private[pekkohttp] def toPekkoResponse(vcrRequest: VcrRequest, vcrResponse: VcrResponse): HttpResponse = {
    val contentType = vcrResponse.contentType.fold[ContentType](ContentTypes.NoContentType) { value =>
      ContentType
        .parse(value)
        .getOrElse(throw new IllegalStateException(s"Content-Type of a recorded response cannot be parsed: $value"))
    }

    HttpResponse(
      status = StatusCodes
        .getForKey(vcrResponse.statusCode)
        .getOrElse(StatusCodes.custom(vcrResponse.statusCode, vcrResponse.statusText)),
      headers = toPekkoHeaders(vcrResponse.headers + (VcrClient.vcrCacheHeaderName -> Seq("true"))),
      entity = HttpEntity(contentType, vcrResponse.bodyBytes),
      protocol = vcrResponse.httpVersion
        .orElse(vcrRequest.httpVersion)
        .flatMap(HttpProtocols.getForKey)
        .getOrElse(HttpProtocols.`HTTP/1.1`)
    )
  }

  private[pekkohttp] def showContentType(contentType: ContentType): Option[String] =
    if (contentType == ContentTypes.NoContentType) None else Some(contentType.value)

  // Pekko keeps Content-Type on the entity, but other clients (and header matchers) expect it as a header.
  private def withContentType(headers: Map[String, Seq[String]], entity: HttpEntity): Map[String, Seq[String]] =
    showContentType(entity.contentType).fold(headers)(value => headers + ("Content-Type" -> Seq(value)))

  private[pekkohttp] def toVcrHeaders(headers: Seq[HttpHeader]): Map[String, Seq[String]] =
    headers.groupBy(_.name()).map { case (name, headers) => name -> headers.map(_.value()) }

  // Content-Type and Content-Length come from the entity. Pekko ignores them as headers, with a warning.
  private[pekkohttp] def toPekkoHeaders(headers: Map[String, Seq[String]]): immutable.Seq[HttpHeader] =
    (for {
      (key, group) <- headers.iterator
      if !key.equalsIgnoreCase("Content-Type") && !key.equalsIgnoreCase("Content-Length")
      value        <- group.iterator
    } yield HttpHeader.parse(key, value)).collect { case HttpHeader.ParsingResult.Ok(header, _) =>
      header
    }.toVector
}

/** Pekko HTTP has no "client" concept, so this wraps a `HttpRequest => Future[HttpResponse]` function instead. */
class VcrHttp private (
  sendRequest: HttpRequest => Future[HttpResponse],
  val storage: VcrStorage,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher,
  strictTimeout: FiniteDuration
)(implicit val ec: ExecutionContext, materializer: Materializer)
    extends VcrClient
    with Closeable {
  import VcrHttp._

  override def close(): Unit =
    Try(save()).failed.foreach(_.printStackTrace())

  def send(request: HttpRequest): Future[HttpResponse] =
    // Entities are made strict so that they can be both recorded and passed along (streamed ones can only be read once).
    request.entity.toStrict(strictTimeout).flatMap { requestEntity =>
      val strictRequest = request.withEntity(requestEntity)
      val vcrRequest    = toVcrRequest(strictRequest, requestEntity.data)

      resolve(vcrRequest) match {
        case VcrAction.Replay(entry) => Future.successful(toPekkoResponse(vcrRequest, entry.response))

        case VcrAction.Record =>
          for {
            response       <- sendRequest(strictRequest)
            responseEntity <- response.entity.toStrict(strictTimeout)
          } yield {
            val strictResponse = response.withEntity(responseEntity)
            addNewEntry(VcrEntry(vcrRequest, toVcrResponse(strictResponse, responseEntity.data), OffsetDateTime.now()))
            strictResponse
          }

        case VcrAction.PassThrough => sendRequest(strictRequest)

        case VcrAction.Fail(error) => Future.failed(error)
      }
    }
}
