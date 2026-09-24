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

  test("fromName") {
    assertEquals(BodyEncoding.all.map(e => BodyEncoding.fromName(e.name)), BodyEncoding.all.map(Some(_)))
    assertEquals(BodyEncoding.fromName("gzip"), None)
  }
}
