package bekindrewind

import munit._
import org.scalacheck.Gen
import org.scalacheck.Prop._

import java.nio.charset.StandardCharsets

class BodyEncodingSpec extends ScalaCheckSuite {

  property("Bytes survive encoding exactly") {
    forAll(Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue))) { (bytes: List[Byte]) =>
      val (body, encoding) = BodyEncoding.encode(bytes.toArray)
      assertEquals(encoding.decode(body).toList, bytes)
    }
  }

  test("UTF-8 text is stored as text") {
    val text = "{\"hello\": \"日本語 ✓\"}\n\ttabbed\r\n"
    assertEquals(BodyEncoding.encode(text.getBytes(StandardCharsets.UTF_8)), (text, BodyEncoding.Text))
  }

  test("Empty bodies are text") {
    assertEquals(BodyEncoding.encode(Array.emptyByteArray), ("", BodyEncoding.Text))
  }

  test("Invalid UTF-8 and control characters are stored as base64") {
    assertEquals(BodyEncoding.encode(Array[Byte](-1, -2)), ("//4=", BodyEncoding.Base64))
    assertEquals(BodyEncoding.encode(Array[Byte](1, 2, 3)), ("AQID", BodyEncoding.Base64))
  }

  test("VcrRequest.fromBytes and bodyBytes round trip") {
    val bytes   = Array[Byte](0, -1, 10)
    val request = VcrRequest.fromBytes("POST", new java.net.URI("https://example.com"), bytes, Map.empty, None)

    assertEquals(request.bodyEncoding, BodyEncoding.Base64)
    assertEquals(request.bodyBytes.toList, bytes.toList)
  }

  private val shiftJis = java.nio.charset.Charset.forName("Shift_JIS")

  test("fromName") {
    assertEquals(BodyEncoding.fromName("text"), Some(BodyEncoding.Text))
    assertEquals(BodyEncoding.fromName("base64"), Some(BodyEncoding.Base64))
    assertEquals(BodyEncoding.fromName("Shift_JIS"), Some(BodyEncoding.TextIn(shiftJis)))
    assertEquals(BodyEncoding.fromName("UTF-8"), Some(BodyEncoding.Text))
    assertEquals(BodyEncoding.fromName("gzip"), None)
  }

  test("Text in the Content-Type's charset is stored as text in that charset") {
    val text  = "半角の「ﾃｽﾄ」"
    val bytes = text.getBytes(shiftJis)

    assertEquals(BodyEncoding.encode(bytes, Some(shiftJis)), (text, BodyEncoding.TextIn(shiftJis)))
    assertEquals(BodyEncoding.TextIn(shiftJis).decode(text).toList, bytes.toList)
    assertEquals(BodyEncoding.encode(bytes)._2, BodyEncoding.Base64)
  }

  test("UTF-8 is preferred, and bytes that aren't valid in the charset are stored as base64") {
    assertEquals(
      BodyEncoding.encode("abc".getBytes(StandardCharsets.UTF_8), Some(shiftJis)),
      ("abc", BodyEncoding.Text)
    )
    assertEquals(BodyEncoding.encode(Array[Byte](-127, -1), Some(shiftJis))._2, BodyEncoding.Base64)
  }

  test("Charsets are read from Content-Type headers") {
    assertEquals(BodyEncoding.charsetOf("text/plain; charset=Shift_JIS"), Some(shiftJis))
    assertEquals(BodyEncoding.charsetOf("""application/json;charset="utf-8""""), Some(StandardCharsets.UTF_8))
    assertEquals(BodyEncoding.charsetOf("text/plain; charset=nope"), None)
    assertEquals(BodyEncoding.charsetOf(Map("content-type" -> Seq("text/plain; charset=Shift_JIS"))), Some(shiftJis))

    val request = VcrRequest.fromBytes(
      "POST",
      new java.net.URI("https://example.com"),
      "ﾃｽﾄ".getBytes(shiftJis),
      Map("Content-Type" -> Seq("text/plain; charset=Shift_JIS")),
      None
    )
    assertEquals((request.body, request.bodyEncoding), ("ﾃｽﾄ", BodyEncoding.TextIn(shiftJis)))
  }
}
