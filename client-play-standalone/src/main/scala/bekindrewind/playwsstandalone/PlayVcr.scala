package bekindrewind.playwsstandalone

import bekindrewind._
import org.apache.pekko.stream.Materializer
import org.apache.pekko.util.ByteString
import play.api.libs.ws._

import java.net.HttpCookie
import java.nio.charset.{ Charset, StandardCharsets }
import java.time.OffsetDateTime
import scala.collection.immutable.TreeMap
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.Try

/** Logic shared between the play-ws and play-ws-standalone clients. */
private[bekindrewind] object PlayVcr {

  // AsyncHttpClient (the implementation behind play-ws) only speaks HTTP/1.1.
  private val httpVersion = Some("HTTP/1.1")

  /**
   * Runs the request through the VCR. Streamed request bodies must be buffered with [[bufferSourceBody]] first, since
   * they would otherwise have to be read twice (once to record, once to send).
   */
  def execute[R <: StandaloneWSResponse](
    client: VcrClient,
    req: StandaloneWSRequest,
    sendRequest: () => Future[R],
    replay: VcrEntry => R
  )(implicit materializer: Materializer): Future[R] = {
    implicit val ec: ExecutionContext = materializer.executionContext

    val body = req.body match {
      case InMemoryBody(bytes) => bytes
      case _                   => ByteString.empty
    }

    val vcrRequest = VcrRequest.fromBytes(req.method, req.uri, body.toArray, req.headers, httpVersion)

    client.resolve(vcrRequest) match {
      case VcrAction.Replay(entry) => Future.successful(replay(entry))

      case VcrAction.Record =>
        sendRequest().map { res =>
          client.addNewEntry(VcrEntry(vcrRequest, toVcrResponse(res), OffsetDateTime.now))
          res
        }

      case VcrAction.PassThrough => sendRequest()

      case VcrAction.Fail(error) => Future.failed(error)
    }
  }

  /** Reads a streamed request body into memory. Returns `None` if the body isn't streamed. */
  def bufferSourceBody(req: StandaloneWSRequest)(implicit materializer: Materializer): Option[Future[req.Self]] =
    req.body match {
      case SourceBody(source) =>
        val writable = BodyWritable[ByteString](InMemoryBody(_), req.contentType.getOrElse("application/octet-stream"))
        Some(
          source
            .runFold(ByteString.empty)(_ ++ _)
            .map(bytes => req.withBody(bytes)(writable))(materializer.executionContext)
        )
      case _                  => None
    }

  def replayHeaders(entry: VcrEntry): Map[String, Seq[String]] =
    caseInsensitive(entry.response.headers + (VcrClient.vcrCacheHeaderName -> Seq("true")))

  /** Play expects response headers to be looked up case-insensitively. */
  def caseInsensitive(headers: Map[String, Seq[String]]): Map[String, Seq[String]] =
    TreeMap(headers.toSeq: _*)(Ordering.comparatorToOrdering(String.CASE_INSENSITIVE_ORDER))

  def cookies(headers: Map[String, Seq[String]]): Seq[WSCookie] =
    for {
      header <- caseInsensitive(headers).getOrElse("Set-Cookie", Nil)
      cookie <- Try(HttpCookie.parse(header).asScala).getOrElse(Nil)
    } yield DefaultWSCookie(
      name = cookie.getName,
      value = cookie.getValue,
      domain = Option(cookie.getDomain),
      path = Option(cookie.getPath),
      maxAge = Some(cookie.getMaxAge).filter(_ >= 0),
      secure = cookie.getSecure,
      httpOnly = cookie.isHttpOnly
    )

  /** Decodes the body with the charset from the Content-Type header, defaulting to UTF-8. */
  def bodyAsString(headers: Map[String, Seq[String]], body: ByteString): String = {
    val charset = for {
      contentType <- caseInsensitive(headers).get("Content-Type").flatMap(_.headOption)
      param       <- contentType.split(';').map(_.trim).find(_.toLowerCase.startsWith("charset="))
      charset     <- Try(Charset.forName(param.drop("charset=".length).replace("\"", ""))).toOption
    } yield charset

    body.decodeString(charset.getOrElse(StandardCharsets.UTF_8))
  }

  private def toVcrResponse(res: StandaloneWSResponse): VcrResponse =
    VcrResponse.fromBytes(
      res.status,
      res.statusText,
      res.headers.map { case (k, v) => (k, v.toSeq) },
      res.bodyAsBytes.toArray,
      Some(res.contentType),
      httpVersion
    )
}
