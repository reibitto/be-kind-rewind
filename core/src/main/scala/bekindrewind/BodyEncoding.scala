package bekindrewind

import java.nio.ByteBuffer
import java.nio.charset.{ Charset, CodingErrorAction, StandardCharsets }
import scala.util.Try

/**
 * How a body's bytes are stored as a string. Text is used whenever the bytes can be restored exactly, so recordings
 * stay readable.
 */
sealed abstract class BodyEncoding(val name: String) {
  def decode(body: String): Array[Byte]

  /** Whether the stored body is the text itself, as opposed to an encoding of the bytes like base64. */
  def isText: Boolean
}

object BodyEncoding {

  /** UTF-8 text. */
  case object Text extends BodyEncoding("text") {
    override def decode(body: String): Array[Byte] = body.getBytes(StandardCharsets.UTF_8)
    override def isText: Boolean                   = true
  }

  case object Base64 extends BodyEncoding("base64") {
    override def decode(body: String): Array[Byte] = java.util.Base64.getDecoder.decode(body)
    override def isText: Boolean                   = false
  }

  /** Text in another charset (from the `Content-Type` header), such as `Shift_JIS`. */
  final case class TextIn(charset: Charset) extends BodyEncoding(charset.name) {
    override def decode(body: String): Array[Byte] = body.getBytes(charset)
    override def isText: Boolean                   = true
  }

  def fromName(name: String): Option[BodyEncoding] =
    name match {
      case Text.name   => Some(Text)
      case Base64.name => Some(Base64)
      case other       => Try(Charset.forName(other)).toOption.map(textIn)
    }

  private def textIn(charset: Charset): BodyEncoding =
    if (charset == StandardCharsets.UTF_8) Text else TextIn(charset)

  /**
   * Stores bytes as text if they are valid UTF-8 (or valid in `charset`, if specified) without control characters, and
   * as base64 otherwise.
   */
  def encode(bytes: Array[Byte], charset: Option[Charset] = None): (String, BodyEncoding) =
    readable(bytes, StandardCharsets.UTF_8)
      .map(_ -> (Text: BodyEncoding))
      .orElse(charset.filter(_ != StandardCharsets.UTF_8).flatMap(c => readable(bytes, c).map(_ -> textIn(c))))
      .getOrElse(java.util.Base64.getEncoder.encodeToString(bytes) -> Base64)

  /** The charset from a `Content-Type` value such as `text/plain; charset=Shift_JIS`. */
  def charsetOf(contentType: String): Option[Charset] =
    contentType
      .split(';')
      .map(_.trim)
      .collectFirst { case param if param.toLowerCase.startsWith("charset=") => param.drop("charset=".length) }
      .flatMap(name => Try(Charset.forName(name.replace("\"", "").trim)).toOption)

  /** The charset from the `Content-Type` header, if any. */
  def charsetOf(headers: Map[String, Seq[String]]): Option[Charset] =
    headers.collectFirst { case (name, values) if name.equalsIgnoreCase("Content-Type") => values }
      .flatMap(_.headOption)
      .flatMap(charsetOf)

  // Only if the text converts back to exactly the same bytes and has no control characters that would be unreadable.
  private def readable(bytes: Array[Byte], charset: Charset): Option[String] =
    Try(
      charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    ).toOption
      .filter(text => java.util.Arrays.equals(text.getBytes(charset), bytes))
      .filterNot(_.exists(c => c.isControl && c != '\n' && c != '\r' && c != '\t'))
}
