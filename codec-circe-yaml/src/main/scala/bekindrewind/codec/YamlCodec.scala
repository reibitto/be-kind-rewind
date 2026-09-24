package bekindrewind.codec

import bekindrewind.VcrEntries
import bekindrewind.codec.CirceInstances._
import io.circe.syntax.EncoderOps
import io.circe.yaml.Parser
import io.circe.yaml.syntax._

object YamlCodec extends Codec {
  // SnakeYAML rejects documents over 3 MB by default, which recordings with large bodies easily exceed.
  private val parser = Parser(codePointLimit = Int.MaxValue)

  override def decode(text: String): Either[Throwable, VcrEntries] =
    parser.parse(text).flatMap(_.as[VcrEntries])

  override def encode(entries: VcrEntries): String =
    entries.asJson.deepDropNullValues.asYaml.spaces2

  override val fileExtension: String = "yml"
}
