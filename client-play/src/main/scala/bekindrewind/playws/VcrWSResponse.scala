package bekindrewind.playws

import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.json.{ JsValue, Json }
import play.api.libs.ws.{ WSCookie, WSResponse }

import java.net.URI
import scala.xml.{ Elem, XML }

final case class VcrWSResponse(
  url: String,
  status: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String
) extends WSResponse {
  override def uri: URI = URI.create(url)

  override def underlying[T]: T = throw new Exception("VcrWSResponse does not have an underlying response.")

  override def cookies: collection.Seq[WSCookie] = throw new NotImplementedError("`cookies` are not recorded")

  override def cookie(name: String): Option[WSCookie] = cookies.find(_.name == name)

  override def bodyAsBytes: ByteString = ByteString(body)

  override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)

  override def allHeaders: Map[String, collection.Seq[String]] = headers

  override def xml: Elem = XML.loadString(body)

  override def json: JsValue = Json.parse(body)
}
