package bekindrewind

import java.nio.ByteBuffer
import java.nio.charset.{ CodingErrorAction, StandardCharsets }
import scala.util.Try

/** How a body's bytes are stored as a string. Text is used when possible so recordings stay readable. */
sealed abstract class BodyEncoding(val name: String) {
  def decode(body: String): Array[Byte]
}

object BodyEncoding {
  case object Text extends BodyEncoding("text") {
    override def decode(body: String): Array[Byte] = body.getBytes(StandardCharsets.UTF_8)
  }

  case object Base64 extends BodyEncoding("base64") {
    override def decode(body: String): Array[Byte] = java.util.Base64.getDecoder.decode(body)
  }

  val all: Seq[BodyEncoding] = Seq(Text, Base64)

  def fromName(name: String): Option[BodyEncoding] = all.find(_.name == name)

  /** Stores bytes as text if they are valid UTF-8 without control characters, otherwise as base64. */
  def encode(bytes: Array[Byte]): (String, BodyEncoding) =
    utf8(bytes).filterNot(_.exists(c => c.isControl && c != '\n' && c != '\r' && c != '\t')) match {
      case Some(text) => (text, Text)
      case None       => (java.util.Base64.getEncoder.encodeToString(bytes), Base64)
    }

  private def utf8(bytes: Array[Byte]): Option[String] =
    Try(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    ).toOption
}
