package bekindrewind.codec

import bekindrewind.VcrEntries
import bekindrewind.codec.CirceInstances._
import io.circe.Printer
import io.circe.syntax.EncoderOps

object JsonCodec extends Codec {
  private val printer =
    Printer.spaces2.copy(dropNullValues = true, colonLeft = "", lrbracketsEmpty = "")

  override def decode(text: String): Either[Throwable, VcrEntries] =
    io.circe.parser.decode[VcrEntries](text)

  override def encode(entries: VcrEntries): String =
    printer.print(entries.asJson)

  override val fileExtension: String = "json"
}
