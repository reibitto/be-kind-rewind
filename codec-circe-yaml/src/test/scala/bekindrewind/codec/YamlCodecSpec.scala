package bekindrewind.codec

import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestData
import bekindrewind.util.Generators
import bekindrewind.{ BuildInfo, VcrEntries, VcrResponse }
import munit._
import org.scalacheck.Prop._

import java.nio.file.Files

class YamlCodecSpec extends ScalaCheckSuite {

  property("encode/decode round trip") {
    forAll(Generators.vcrEntries) { (entries: VcrEntries) =>
      assertEquals(YamlCodec.decode(YamlCodec.encode(entries)), Right(entries))
    }
  }

  test("Decodes files written by 0.1.0 (JSON is valid YAML)") {
    val json =
      """{
        |  "entries": [
        |    {
        |      "request": {
        |        "method": "GET",
        |        "uri": "https://example.com/foo.json",
        |        "body": "",
        |        "headers": {},
        |        "httpVersion": "HTTP/1.1"
        |      },
        |      "response": {
        |        "statusCode": 200,
        |        "statusText": "OK",
        |        "headers": {},
        |        "body": "{}",
        |        "contentType": "application/json"
        |      },
        |      "recordedAt": "2100-05-06T12:34:56.789Z"
        |    }
        |  ],
        |  "version": "0.1.0",
        |  "expiration": "2100-01-01T00:00:00Z"
        |}""".stripMargin

    assertEquals(YamlCodec.decode(json), Right(VcrEntries(Vector(TestData.entry()), "0.1.0")))
  }

  test("Invalid input is returned as an error") {
    assert(YamlCodec.decode("key: [unclosed").isLeft)
    assert(YamlCodec.decode("""{"entries": []}""").isLeft)
    assert(YamlCodec.decode("""{"entries": [], "version": "0.1.0"}""").isRight)
  }

  test("Handles large recordings") {
    val entries = VcrEntries(Vector(TestData.entry(response = TestData.response("x" * 5000000))), BuildInfo.version)

    assertEquals(YamlCodec.decode(YamlCodec.encode(entries)), Right(entries))
  }

  test("Binary bodies are stored as base64 and text bodies without an encoding field") {
    val binary  = TestData.entry(response = VcrResponse.fromBytes(200, "OK", Map.empty, Array[Byte](0, -1), None, None))
    val encoded = YamlCodec.encode(VcrEntries(Vector(TestData.entry(), binary), "0.2.0"))

    assertEquals(encoded.split("bodyEncoding").length - 1, 1, encoded)
    assert(encoded.contains("base64"), encoded)
    assert(!encoded.contains("null"), encoded)
    assertEquals(
      YamlCodec.decode(encoded).map(_.entries.map(_.response.bodyBytes.toList)),
      Right(Vector(List(123, 125), List(0, -1)).map(_.map(_.toByte)))
    )
  }

  test("Headers with one value are written as plain strings and both forms can be read") {
    val headers = Map("Set-Cookie" -> Seq("a=1", "b=2"), "Content-Type" -> Seq("application/json"))
    val entries = VcrEntries(Vector(TestData.entry(TestData.request().copy(headers = headers))), "0.2.0")
    val encoded = YamlCodec.encode(entries)

    assertEquals(YamlCodec.decode(encoded), Right(entries))
    assert(encoded.indexOf("Content-Type") < encoded.indexOf("Set-Cookie"), encoded)
    assert(YamlCodec.decode(encoded.replace("application/json", "text/plain")).isRight)
  }

  test("Test names are written and read") {
    val entries = VcrEntries(Vector(TestData.entry().copy(testName = Some("gets a user"))), "0.2.0")
    val encoded = YamlCodec.encode(entries)

    assert(encoded.contains("gets a user"), encoded)
    assertEquals(YamlCodec.decode(encoded), Right(entries))
  }

  test("Works with FileVcrStorage") {
    val path    = Files.createTempFile("vcr", ".yml")
    val storage = FileVcrStorage(path, YamlCodec)
    val entries = VcrEntries(Vector(TestData.entry()), BuildInfo.version)

    try {
      storage.write(entries)
      assertEquals(storage.read(), Right(Some(entries)))
    } finally Files.delete(path)
  }
}

class YamlCodecOutputSpec extends FunSuite {
  test("Output is YAML rather than JSON") {
    val encoded = YamlCodec.encode(VcrEntries(Vector(TestData.entry()), "0.2.0"))

    assert(encoded.contains("version: 0.2.0"), encoded)
    assert(!encoded.trim.startsWith("{"), encoded)
  }
}
