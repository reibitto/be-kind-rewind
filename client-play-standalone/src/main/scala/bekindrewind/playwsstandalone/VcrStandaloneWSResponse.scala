package bekindrewind.playwsstandalone

import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import play.api.libs.ws.{ StandaloneWSResponse, WSCookie }

import java.net.URI

final case class VcrStandaloneWSResponse(
  uri: URI,
  status: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  bodyAsBytes: ByteString
) extends StandaloneWSResponse {
  override def underlying[T]: T = throw new Exception("VcrStandaloneWSResponse does not have an underlying response.")

  override def cookies: Seq[WSCookie] = PlayVcr.cookies(headers)

  override def cookie(name: String): Option[WSCookie] = cookies.find(_.name == name)

  override def body: String = PlayVcr.bodyAsString(headers, bodyAsBytes)

  override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)
}
