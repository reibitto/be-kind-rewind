package bekindrewind

import io.circe.parser._
import io.circe.syntax._
import munit._

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.time.{Duration, OffsetDateTime}
import scala.collection.immutable

class VcrClientSpec extends FunSuite {

  test("Request and response are saved as JSON") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(recordingPath, RecordOptions.default, VcrMatcher.default)
    assert(client.previouslyRecorded.isEmpty)
    assert(client.newlyRecorded().isEmpty)

    val entry = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewEntry(entry)
    client.save()
    assert(client.previouslyRecorded.isEmpty)
    assertEquals(client.newlyRecorded().size, 1)

    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrEntries](savedJson).map(_.entries)
    assertEquals(decoded, Right(Vector(entry)))
  }

  test("Request and response can be transformed") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(
      recordingPath,
      RecordOptions.default,
      VcrMatcher.identity.withTransformer { case entry @ VcrEntry(req, res, _) =>
        entry.copy(
          request = req.copy(uri = new URI("https://example.com/SAFE")),
          response = res.copy(headers = res.headers - "SENSITIVE_DATA")
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
    assertEquals(client.newlyRecorded(), immutable.Seq(expected))

    client.save()
    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrEntries](savedJson).map(_.entries)
    assertEquals(decoded, Right(Vector(expected)))
  }

  test("Client loads the previous entry when being constructed") {
    val entry   = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val rawJson = VcrEntries(Vector(entry), BuildInfo.version).asJson.spaces2

    val recordingPath = Files.createTempFile("test", ".json")
    Files.write(recordingPath, rawJson.getBytes(StandardCharsets.UTF_8))

    val client = MockClient(recordingPath, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)

    client.previouslyRecorded.get(VcrKey("bucket")) match {
      case None           => fail("Should load the VCR entry !!")
      case Some(previous) =>
        assertEquals(previous.entries, Vector(entry))
        assertEquals(previous.currentIndex.get(), 0)
    }
  }

  test("Record file should have expiration field if option specified") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(
      recordingPath,
      RecordOptions.default.copy(
        expiresAfter = Some(Duration.ofDays(90))
      ),
      VcrMatcher.identity
    )
    assert(client.previouslyRecorded.isEmpty)
    assert(client.newlyRecorded().isEmpty)

    val entry = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewEntry(entry)
    client.save()

    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrEntries](savedJson)
    assertEquals(decoded.map(_.entries), Right(Vector(entry)))
    assert(decoded.toOption.flatMap(_.expiration).isDefined)
  }

  test("The previous entries are not loaded if current date time is after expiration") {
    val entry   = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val rawJson = VcrEntries(
      Vector(entry),
      BuildInfo.version,
      expiration = Some(OffsetDateTime.parse("2010-01-01T12:00:00Z"))
    ).asJson.spaces2

    val recordingPath = Files.createTempFile("test", ".json")
    Files.write(recordingPath, rawJson.getBytes(StandardCharsets.UTF_8))

    val client = MockClient(recordingPath, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)
    assertEquals(client.previouslyRecorded.get(VcrKey("bucket")), None)
  }
}

final case class MockClient(recordingPath: Path, recordOptions: RecordOptions, matcher: VcrMatcher) extends VcrClient
