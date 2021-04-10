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

    val record = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewRecord(record)
    client.save()
    assert(client.previouslyRecorded.isEmpty)
    assertEquals(client.newlyRecorded().size, 1)

    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrRecords](savedJson).map(_.records)
    assertEquals(decoded, Right(Vector(record)))
  }

  test("Request and response can be transformed") {
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(
      recordingPath,
      RecordOptions.default,
      VcrMatcher.identity.withTransformer { case record @ VcrRecord(req, res, _) =>
        record.copy(
          request = req.copy(uri = new URI("https://example.com/SAFE")),
          response = res.copy(headers = res.headers.removed("SENSITIVE_DATA"))
        )
      }
    )

    val original = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/DANGER"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map("SENSITIVE_DATA" -> Seq("DO_NOT_RECORD_ME")), "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewRecord(original)

    val expected = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/SAFE"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    assertEquals(client.newlyRecorded(), Seq(expected))

    client.save()
    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrRecords](savedJson).map(_.records)
    assertEquals(decoded, Right(Vector(expected)))
  }

  test("Client loads the previous record when being constructed") {
    val record  = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val rawJson = VcrRecords(Vector(record), BuildInfo.version).asJson.spaces2

    val recordingPath = Files.createTempFile("test", ".json")
    Files.write(recordingPath, rawJson.getBytes(StandardCharsets.UTF_8))

    val client = MockClient(recordingPath, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)

    client.previouslyRecorded.get(VcrKey("bucket")) match {
      case None           => fail("Should load the record !!")
      case Some(previous) =>
        assertEquals(previous.records, Vector(record))
        assertEquals(previous.currentIndex.get(), 0)
    }
  }

  test("Record file should have expiration field if option specified") {
    import scala.concurrent.duration._
    val recordingPath = Files.createTempFile("test", ".json")
    val client        = MockClient(
      recordingPath,
      RecordOptions.default.copy(
        expiresAfter = Some(90 days)
      ),
      VcrMatcher.identity
    )
    assert(client.previouslyRecorded.isEmpty)
    assert(client.newlyRecorded().isEmpty)

    val record = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    client.addNewRecord(record)
    client.save()

    val savedJson = new String(Files.readAllBytes(recordingPath), StandardCharsets.UTF_8)
    val decoded   = decode[VcrRecords](savedJson)
    assertEquals(decoded.map(_.records), Right(Vector(record)))
    assert(decoded.toOption.flatMap(_.expiration).isDefined)
  }

  test("The previous records are not loaded if current date time is after expiration") {
    val record  = VcrRecord(
      VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrRecordResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val rawJson = VcrRecords(
      Vector(record),
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
