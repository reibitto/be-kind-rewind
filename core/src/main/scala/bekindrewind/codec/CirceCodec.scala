package bekindrewind.codec

import bekindrewind.VcrEntries
import io.circe.{ Decoder, Encoder }
import io.circe.syntax.EncoderOps

import java.net.URI
import scala.util.Try

object CirceCodec extends Codec {
  override def decode(text: String): Either[Throwable, VcrEntries] =
    for {
      json    <- io.circe.parser.parse(text)
      entries <- json.as[VcrEntries]
    } yield entries

  override def encode(entries: VcrEntries): String =
    entries.asJson.spaces2

  implicit val uriEncoder: Encoder[URI] =
    Encoder.encodeString.contramap(_.toString)

  implicit val uriDecoder: Decoder[URI] = Decoder.decodeString.emap { s =>
    Try(URI.create(s)).toEither.left.map(_.getMessage)
  }
}
