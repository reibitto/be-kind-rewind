package bekindrewind.codec

import bekindrewind.VcrEntries
import bekindrewind.codec.CirceInstances._
import io.circe.syntax.EncoderOps
import io.circe.yaml.{ Parser, Printer }

object YamlCodec extends Codec {
  // SnakeYAML rejects documents over 3 MB by default, which recordings with large bodies easily exceed.
  private val parser = Parser(codePointLimit = Int.MaxValue)

  // Multi-line strings are written as literal blocks when they round-trip exactly, and long strings aren't wrapped.
  private val printer = Printer(preserveOrder = true, dropNullKeys = true, splitLines = false)

  override def decode(text: String): Either[Throwable, VcrEntries] =
    parser.parse(text).flatMap(_.as[VcrEntries])

  override def encode(entries: VcrEntries): String =
    printer.pretty(entries.asJson)

  override val fileExtension: String = "yml"
}
