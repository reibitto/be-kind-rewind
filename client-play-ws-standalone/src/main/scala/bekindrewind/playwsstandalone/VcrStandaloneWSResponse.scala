package bekindrewind.playwsstandalone

import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.ws.{ StandaloneWSResponse, WSCookie }

import java.net.URI

final case class VcrStandaloneWSResponse(
  uri: URI,
  status: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String
) extends StandaloneWSResponse {
  override def underlying[T]: T = throw new Exception("VcrStandaloneWSResponse does not have an underlying response.")

  override def cookies: collection.Seq[WSCookie] = throw new NotImplementedError("`cookies` are not recorded")

  override def cookie(name: String): Option[WSCookie] = cookies.find(_.name == name)

  override def bodyAsBytes: ByteString = ByteString(body)

  override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)
}
