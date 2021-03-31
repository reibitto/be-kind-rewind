package bekindrewind.codec

import io.circe.{ Decoder, Encoder }

import java.net.URI
import scala.util.Try

object Codecs {
  implicit val uriEncoder: Encoder[URI] =
    Encoder.encodeString.contramap(_.toString)

  implicit val uriDecoder: Decoder[URI] = Decoder.decodeString.emap { s =>
    Try(URI.create(s)).toEither.left.map(_.getMessage)
  }
}
