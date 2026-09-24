package bekindrewind.playws

import bekindrewind.playwsstandalone.PlayVcr
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.libs.json.{ JsValue, Json }
import play.api.libs.ws.{ WSCookie, WSResponse }

import java.net.URI
import scala.xml.{ Elem, XML }

final case class VcrWSResponse(
  uri: URI,
  status: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  bodyAsBytes: ByteString
) extends WSResponse {
  override def underlying[T]: T = throw new Exception("VcrWSResponse does not have an underlying response.")

  override def cookies: Seq[WSCookie] = PlayVcr.cookies(headers)

  override def cookie(name: String): Option[WSCookie] = cookies.find(_.name == name)

  override def body: String = PlayVcr.bodyAsString(headers, bodyAsBytes)

  override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)

  override def allHeaders: Map[String, collection.Seq[String]] = headers

  override def xml: Elem = XML.loadString(body)

  override def json: JsValue = Json.parse(body)
}

object VcrWSResponse {

  /** The replayed response for a recorded entry, as returned for `uri`. */
  def replay(entry: bekindrewind.VcrEntry, uri: URI): VcrWSResponse =
    VcrWSResponse(
      uri,
      entry.response.statusCode,
      entry.response.statusText,
      bekindrewind.playwsstandalone.PlayVcr.replayHeaders(entry),
      ByteString(entry.response.bodyBytes)
    )
}
