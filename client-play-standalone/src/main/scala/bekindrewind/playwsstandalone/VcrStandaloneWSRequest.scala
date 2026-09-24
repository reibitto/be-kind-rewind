package bekindrewind.playwsstandalone

import org.apache.pekko.util.ByteString
import play.api.libs.ws._

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration.Duration

class VcrStandaloneWSRequest(req: StandaloneWSRequest, owner: VcrStandaloneWSClient) extends StandaloneWSRequest {
  override type Self     = VcrStandaloneWSRequest
  override type Response = StandaloneWSResponse

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

  override def withDisableUrlEncoding(disableUrlEncoding: Boolean): Self =
    req.withDisableUrlEncoding(disableUrlEncoding)

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

  override def execute(): Future[Response] =
    PlayVcr.bufferSourceBody(req)(owner.materializer) match {
      case Some(buffered) =>
        buffered.flatMap(new VcrStandaloneWSRequest(_, owner).execute())(owner.materializer.executionContext)
      case None           =>
        PlayVcr.execute[Response](
          owner,
          req,
          () => req.execute(method),
          entry =>
            VcrStandaloneWSResponse(
              req.uri,
              entry.response.statusCode,
              entry.response.statusText,
              PlayVcr.replayHeaders(entry),
              ByteString(entry.response.bodyBytes)
            )
        )(owner.materializer)
    }

  override def stream(): Future[Response] = req.stream()
}
