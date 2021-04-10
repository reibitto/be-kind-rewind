package bekindrewind

import io.circe.parser._
import io.circe.syntax._
import munit._

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.time.OffsetDateTime

class VcrClientSpec extends FunSuite {

  test("Request and response are saved as JSON") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(recordingPath, RecordOptions.default, VcrMatcher.default)
    assert(client.previouslyRecorded.isEmpty)
    assert(client.newlyRecorded().isEmpty)

    val record = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewEntry(record)
    client.save()
    assert(client.previouslyRecorded.isEmpty)
    assertEquals(client.newlyRecorded().size, 1)

    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrEntries](savedJson).map(_.entries)
    assertEquals(decoded, Right(Vector(record)))
  }

  test("Request and response can be transformed") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(
      recordingPath,
      RecordOptions.default,
      VcrMatcher.identity.withTransformer { case record @ VcrEntry(req, res, _) =>
        record.copy(
          request = req.copy(uri = new URI("https://example.com/SAFE")),
          response = res.copy(headers = res.headers.removed("SENSITIVE_DATA"))
        )
      }
    )

    val original = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/DANGER"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map("SENSITIVE_DATA" -> Seq("DO_NOT_RECORD_ME")), "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewEntry(original)

    val expected = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/SAFE"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    assertEquals(client.newlyRecorded(), Seq(expected))

    client.save()
    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrEntries](savedJson).map(_.entries)
    assertEquals(decoded, Right(Vector(expected)))
  }

  test("Client loads the previous record when being constructed") {
    val record  = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val rawJson = VcrEntries(Vector(record), BuildInfo.version).asJson.spaces2

    val recordingPath = Files.createTempFile("test", ".json")
    Files.write(recordingPath, rawJson.getBytes(StandardCharsets.UTF_8))

    val client = MockClient(recordingPath, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)

    client.previouslyRecorded.get(VcrKey("bucket")) match {
      case None           => fail("Should load the record !!")
      case Some(previous) =>
        assertEquals(previous.entries, Vector(record))
        assertEquals(previous.currentIndex.get(), 0)
    }
  }

}

final case class MockClient(recordingPath: Path, recordOptions: RecordOptions, matcher: VcrMatcher) extends VcrClient
