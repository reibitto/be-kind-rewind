package bekindrewind.codec

import bekindrewind.VcrEntries
import io.circe.syntax.EncoderOps

trait Codec {
  def decode(text: String): Either[Throwable, VcrEntries]

  def encode(entries: VcrEntries): String
}

object CirceCodec extends Codec {
  override def decode(text: String): Either[Throwable, VcrEntries] =
    for {
      json    <- io.circe.parser.parse(text)
      entries <- json.as[VcrEntries]
    } yield entries

  override def encode(entries: VcrEntries): String =
    entries.asJson.spaces2
}
