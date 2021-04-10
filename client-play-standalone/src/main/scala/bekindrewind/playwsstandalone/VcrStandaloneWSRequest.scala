package bekindrewind.playwsstandalone

import akka.stream.Materializer
import bekindrewind.{ VcrClient, VcrEntry, VcrRequest, VcrResponse }
import play.api.libs.ws._

import java.net.URI
import java.time.OffsetDateTime
import scala.concurrent.duration.Duration
import scala.concurrent.{ ExecutionContextExecutor, Future }

class VcrStandaloneWSRequest(req: StandaloneWSRequest, owner: VcrStandaloneWSClient) extends StandaloneWSRequest {
  override type Self     = VcrStandaloneWSRequest
  override type Response = StandaloneWSResponse // TODO: Change to VcrWSResponse?

  implicit def toVcrWSRequest(req: StandaloneWSRequest): VcrStandaloneWSRequest =
    new VcrStandaloneWSRequest(req, owner)

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

  override def sign(calc: WSSignatureCalculator): Self = req.sign(calc)

  override def withAuth(username: String, password: String, scheme: WSAuthScheme): Self =
    req.withAuth(username, password, scheme)

  override def withHttpHeaders(headers: (String, String)*): Self = req.withHttpHeaders(headers: _*)

  override def withQueryStringParameters(parameters: (String, String)*): Self =
    req.withQueryStringParameters(parameters: _*)

  override def withCookies(cookies: WSCookie*): Self = req.withCookies(cookies: _*)

  override def withFollowRedirects(follow: Boolean): Self = req.withFollowRedirects(follow)

  override def withRequestTimeout(timeout: Duration): Self = req.withRequestTimeout(timeout)

  override def withRequestFilter(filter: WSRequestFilter): Self = req.withRequestFilter(filter)

  override def withVirtualHost(vh: String): Self = req.withVirtualHost(vh)

  override def withProxyServer(proxyServer: WSProxyServer): Self = req.withProxyServer(proxyServer)

  override def withUrl(url: String): Self = req.withUrl(url)

  override def withMethod(method: String): Self = req.withMethod(method)

  override def withBody[T: BodyWritable](body: T): Self = req.withBody(body)

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
    val vcrRequest = VcrRequest(
      req.method,
      req.uri,
      req.body.toString,
      req.headers,
      "HTTP/1.1" // FIXME: Support HTTP/1.0 and HTTP/2.0
    )

    owner.findMatch(vcrRequest) match {
      case Some(r) =>
        Future.successful(
          VcrStandaloneWSResponse(
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

        if (owner.matcher.shouldRecord(vcrRequest)) {
          for {
            requestBody <- req.body match {
                             case EmptyBody          => Future.successful("")
                             case InMemoryBody(ws)   => Future.successful(ws.utf8String)
                             case SourceBody(source) => source.runFold("")(_ + _.utf8String)
                           }
            res         <- req.execute(method).map { res =>
                             val record = VcrEntry(
                               VcrRequest(
                                 req.method,
                                 req.uri,
                                 requestBody,
                                 req.headers,
                                 "HTTP/1.1" // FIXME: Support HTTP/1.0 and HTTP/2.0
                               ),
                               VcrResponse(
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

                             owner.addNewEntry(record)

                             res
                           }
          } yield res

        } else if (owner.recordOptions.notRecordedThrowsErrors) {
          Future.failed(
            new Exception(
              s"Recording is disabled for `${vcrRequest.method} ${vcrRequest.uri}`. The HTTP request was not executed."
            )
          )
        } else {
          req.execute(method)
        }
    }
  }

  override def stream(): Future[Response] = req.stream()
}
