package bekindrewind

import bekindrewind.storage.{ InMemoryVcrStorage, VcrStorage }
import munit._

import java.net.URI
import java.time.{ Duration, OffsetDateTime }
import scala.collection.immutable

class VcrClientSpec extends FunSuite {

  test("Request and response are saved as JSON") {
    val storage = new InMemoryVcrStorage()
    val client  = MockClient(storage, RecordOptions.default, VcrMatcher.default)
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

    val decoded = storage.read().map(_.entries)
    assertEquals(decoded, Right(Vector(entry)))
  }

  test("Request and response can be transformed") {
    val storage = new InMemoryVcrStorage()
    val client  = MockClient(
      storage,
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
    val decoded = storage.read().map(_.entries)
    assertEquals(decoded, Right(Vector(expected)))
  }

  test("Client loads the previous entry when being constructed") {
    val entry   = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val entries = VcrEntries(Vector(entry), BuildInfo.version)

    val storage = new InMemoryVcrStorage()
    storage.write(entries)

    val client = MockClient(storage, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)

    client.previouslyRecorded.get(VcrKey("bucket")) match {
      case None           => fail("Should load the VCR entry !!")
      case Some(previous) =>
        assertEquals(previous.entries, Vector(entry))
        assertEquals(previous.currentIndex.get(), 0)
    }
  }

  test("Record file should have expiration field if option specified") {
    val storage = new InMemoryVcrStorage()
    val client  = MockClient(
      storage,
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

    val decoded = storage.read()
    assertEquals(decoded.map(_.entries), Right(Vector(entry)))
    assert(decoded.toOption.flatMap(_.expiration).isDefined)
  }

  test("The previous entries are not loaded if current date time is after expiration") {
    val entry   = VcrEntry(
      VcrRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty, "HTTP/1.1"),
      VcrResponse(200, "ok", Map.empty, "{}", Some("text/json")),
      OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
    )
    val entries = VcrEntries(
      Vector(entry),
      BuildInfo.version,
      expiration = Some(OffsetDateTime.parse("2010-01-01T12:00:00Z"))
    )

    val storage = new InMemoryVcrStorage()
    storage.write(entries)

    val client = MockClient(storage, RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)
    assertEquals(client.previouslyRecorded.get(VcrKey("bucket")), None)
  }
}

final case class MockClient(storage: VcrStorage, recordOptions: RecordOptions, matcher: VcrMatcher) extends VcrClient
