package bekindrewind.playws

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import bekindrewind.{ VcrClient, VcrRecord, VcrRecordRequest, VcrRecordResponse }
import play.api.libs.ws._
import play.api.mvc.MultipartFormData

import java.io.File
import java.net.URI
import java.time.OffsetDateTime
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContextExecutor, Future }

class VcrWSRequest(req: WSRequest, owner: VcrWSClient) extends WSRequest {
  implicit def toVcrWSRequest(req: WSRequest): VcrWSRequest =
    new VcrWSRequest(req, owner)

  override def url: String = req.url

  override def uri: URI = req.uri

  override def contentType: Option[String] = req.contentType

  override def method: String = req.method

  override def body: WSBody = req.body

  override def headers: Map[String, Seq[String]] = req.headers

  override def queryString: Map[String, Seq[String]] = req.queryString

  override def cookies: Seq[WSCookie] = req.cookies

  override def calc: Option[WSSignatureCalculator] = req.calc

  override def auth: Option[(String, String, WSAuthScheme)] = req.auth

  override def followRedirects: Option[Boolean] = req.followRedirects

  override def requestTimeout: Option[Duration] = req.requestTimeout

  override def virtualHost: Option[String] = req.virtualHost

  override def proxyServer: Option[WSProxyServer] = req.proxyServer

  override def sign(calc: WSSignatureCalculator): VcrWSRequest = req.sign(calc)

  override def withAuth(username: String, password: String, scheme: WSAuthScheme): VcrWSRequest =
    req.withAuth(username, password, scheme)

  override def withHttpHeaders(headers: (String, String)*): VcrWSRequest = req.withHttpHeaders(headers: _*)

  override def withQueryStringParameters(parameters: (String, String)*): VcrWSRequest =
    req.withQueryStringParameters(parameters: _*)

  override def withCookies(cookies: WSCookie*): VcrWSRequest = req.withCookies(cookies: _*)

  override def withFollowRedirects(follow: Boolean): VcrWSRequest = req.withFollowRedirects(follow)

  override def withRequestTimeout(timeout: Duration): VcrWSRequest = req.withRequestTimeout(timeout)

  override def withRequestFilter(filter: WSRequestFilter): VcrWSRequest = req.withRequestFilter(filter)

  override def withVirtualHost(vh: String): VcrWSRequest = req.withVirtualHost(vh)

  override def withProxyServer(proxyServer: WSProxyServer): VcrWSRequest = req.withProxyServer(proxyServer)

  override def withUrl(url: String): VcrWSRequest = req.withUrl(url)

  override def withMethod(method: String): VcrWSRequest = req.withMethod(method)

  override def withBody[T: BodyWritable](body: T): VcrWSRequest = req.withBody(body)

  override def get(): Future[Response] = execute("GET")

  override def patch[T: BodyWritable](body: T): Future[Response] = withBody(body).execute("PATCH")

  override def post[T: BodyWritable](body: T): Future[Response] = withBody(body).execute("POST")

  override def put[T: BodyWritable](body: T): Future[Response] = withBody(body).execute("PUT")

  override def delete(): Future[Response] = execute("DELETE")

  override def head(): Future[Response] = execute("HEAD")

  override def options(): Future[Response] = execute("OPTIONS")

  override def execute(method: String): Future[Response] =
    withMethod(method).execute()

  override def execute(): Future[Response] = {
    val recordRequest = VcrRecordRequest(
      req.method,
      req.uri,
      req.body.toString,
      req.headers,
      "HTTP/1.1" // FIXME: Support HTTP/1.0 and HTTP/2.0
    )

    owner.findMatch(recordRequest) match {
      case Some(r) =>
        Future.successful(
          VcrWSResponse(
            r.request.uri,
            r.response.statusCode,
            r.response.statusText,
            r.response.headers + (VcrClient.vcrCacheHeaderName -> Seq("true")),
            r.response.body
          )
        )

      case None =>
        implicit val ec: ExecutionContextExecutor = owner.materializer.executionContext
        implicit val mat: Materializer            = owner.materializer

        if (owner.matcher.shouldRecord(recordRequest)) {
          println(s"Performing actual HTTP request: ${req.method} ${req.uri}")

          for {
            requestBody <- req.body match {
                             case EmptyBody          => Future.successful("")
                             case InMemoryBody(ws)   => Future.successful(ws.utf8String)
                             case SourceBody(source) => source.runFold("")(_ + _.utf8String)
                           }
            res         <- req.execute(method).map { res =>
                             val record = VcrRecord(
                               VcrRecordRequest(
                                 req.method,
                                 req.uri,
                                 requestBody,
                                 req.headers,
                                 "HTTP/1.1" // FIXME: Support HTTP/1.0 and HTTP/2.0
                               ),
                               VcrRecordResponse(
                                 res.status,
                                 res.statusText,
                                 res.headers.map { case (k, v) =>
                                   (k, v.toSeq)
                                 },
                                 res.body,
                                 Some(res.contentType)
                               ),
                               OffsetDateTime.now
                             )

                             owner.newlyRecorded.updateAndGet { records =>
                               records :+ record
                             }

                             res
                           }
          } yield res

        } else if (owner.recordOptions.notRecordedThrowsErrors) {
          Future.failed(
            new Exception(
              s"Recording is disabled for `${recordRequest.method} ${recordRequest.uri}`. The HTTP request was not executed."
            )
          )
        } else {
          req.execute(method)
        }
    }
  }

  override def stream(): Future[Response] = req.stream()

  override def withHeaders(headers: (String, String)*): VcrWSRequest = req.withHttpHeaders(headers: _*)

  override def withQueryString(parameters: (String, String)*): VcrWSRequest = withQueryStringParameters(parameters: _*)

  override def post(body: File): Future[Response] = post[File](body)

  override def post(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[Response] =
    post[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)

  override def patch(body: File): Future[Response] = patch[File](body)

  override def patch(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[Response] =
    patch[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)

  override def put(body: File): Future[Response] = put[File](body)

  override def put(body: Source[MultipartFormData.Part[Source[ByteString, _]], _]): Future[Response] =
    put[Source[MultipartFormData.Part[Source[ByteString, _]], _]](body)
}
