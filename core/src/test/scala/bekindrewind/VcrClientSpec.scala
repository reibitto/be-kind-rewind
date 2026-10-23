package bekindrewind

import bekindrewind.storage.{ InMemoryVcrStorage, VcrStorage }
import bekindrewind.testing.TestData._
import bekindrewind.testing.{ CountingStorage, MockClient }
import munit._

import java.net.URI
import java.time.{ Duration, OffsetDateTime }
import scala.collection.immutable

class VcrClientSpec extends FunSuite {

  private def isFail(action: VcrAction): Boolean = PartialFunction.cond(action) { case VcrAction.Fail(_) => true }

  private def storageWith(entries: VcrEntry*): InMemoryVcrStorage = {
    val storage = new InMemoryVcrStorage()
    storage.write(VcrEntries(entries.toVector, BuildInfo.version))
    storage
  }

  test("New entries are saved to storage") {
    val storage = new InMemoryVcrStorage()
    val client  = MockClient(storage, RecordOptions.default, VcrMatcher.default)
    assert(client.previouslyRecorded.isEmpty)
    assert(client.newlyRecorded().isEmpty)

    client.addNewEntry(entry())
    client.save()
    assert(client.previouslyRecorded.isEmpty)
    assertEquals(client.newlyRecorded().size, 1)

    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(Vector(entry()))))
  }

  test("Request and response can be transformed") {
    val storage = new InMemoryVcrStorage()
    val client  = MockClient(
      storage,
      RecordOptions.default,
      VcrMatcher.exact.withTransformer { case entry @ VcrEntry(req, res, _, _) =>
        entry.copy(
          request = req.copy(uri = new URI("https://example.com/SAFE")),
          response = res.copy(headers = res.headers - "SENSITIVE_DATA")
        )
      }
    )

    client.addNewEntry(
      entry(
        request(uri = "https://example.com/DANGER"),
        response().copy(headers = Map("SENSITIVE_DATA" -> Seq("DO_NOT_RECORD_ME")))
      )
    )

    val expected = entry(request(uri = "https://example.com/SAFE"))
    assertEquals(client.newlyRecorded(), immutable.Seq(expected))

    client.save()
    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(Vector(expected))))
  }

  test("Previous entries are loaded and grouped by the matcher") {
    val client = MockClient(storageWith(entry()), RecordOptions.default, VcrMatcher.groupBy(_ => "bucket"))
    assert(client.newlyRecorded().isEmpty)

    client.previouslyRecorded.get(VcrKey("bucket")) match {
      case None           => fail("Should load the VCR entry")
      case Some(previous) =>
        assertEquals(previous.entries, Vector(entry()))
        assertEquals(previous.positions, Vector(0))
    }
  }

  test("nextMatch replays identical requests in recorded order and then stops matching") {
    val first  = entry(response = response("first"))
    val second = entry(response = response("second"))
    val other  = entry(request(uri = "https://example.com/other"), response("other"))
    val client = MockClient(storageWith(first, other, second), RecordOptions.default, VcrMatcher.default)

    assertEquals(client.nextMatch(request()), Some(first))
    assertEquals(client.nextMatch(request(uri = "https://example.com/other")), Some(other))
    assertEquals(client.nextMatch(request()), Some(second))
    assertEquals(client.nextMatch(request()), None)
    assertEquals(client.nextMatch(request(uri = "https://example.com/unknown")), None)
  }

  test("nextMatch ignores the body with the default matcher but not with the exact matcher") {
    val recorded = entry(request(method = "POST", body = "a"))

    val default = MockClient(storageWith(recorded), RecordOptions.default, VcrMatcher.default)
    assertEquals(default.nextMatch(request(method = "POST", body = "b")), Some(recorded))

    val exact = MockClient(storageWith(recorded), RecordOptions.default, VcrMatcher.exact)
    assertEquals(exact.nextMatch(request(method = "POST", body = "b")), None)
    assertEquals(exact.nextMatch(request(method = "POST", body = "a")), Some(recorded))
  }

  test("nextMatch never matches requests rejected by shouldRecord") {
    val client = MockClient(
      storageWith(entry()),
      RecordOptions.default,
      VcrMatcher.default.recordOnly(_.method == "POST")
    )

    assertEquals(client.nextMatch(request()), None)
  }

  test("RecordAll ignores and replaces previously recorded entries") {
    val storage = storageWith(entry())
    val client  = MockClient(storage, RecordOptions.default.mode(RecordMode.RecordAll), VcrMatcher.default)

    assertEquals(client.nextMatch(request()), None)

    val newEntry = entry(response = response("new"))
    client.addNewEntry(newEntry)
    client.save()

    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(Vector(newEntry))))
  }

  test("Previously recorded entries are kept in order and new entries are appended") {
    val a       = entry(request(uri = "https://example.com/a"))
    val b       = entry(request(uri = "https://example.com/b"))
    val storage = storageWith(b, a)
    val client  = MockClient(storage, RecordOptions.default, VcrMatcher.default)

    val c = entry(request(uri = "https://example.com/c"))
    client.addNewEntry(c)
    client.save()

    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(Vector(b, a, c))))
  }

  test("save doesn't write when nothing changed") {
    val storage = new CountingStorage(storageWith(entry()))
    val client  = MockClient(storage, RecordOptions.default, VcrMatcher.default)

    client.nextMatch(request())
    client.save()

    assertEquals(storage.writes.get(), 0)
  }

  test("save writes when a new entry is recorded") {
    val storage = new CountingStorage(storageWith(entry()))
    val client  = MockClient(storage, RecordOptions.default, VcrMatcher.default)

    client.addNewEntry(entry(request(uri = "https://example.com/new")))
    client.save()

    assertEquals(storage.writes.get(), 1)
  }

  private def recordedDaysAgo(days: Long, uri: String = "https://example.com/foo.json"): VcrEntry =
    entry(request(uri = uri)).copy(recordedAt = OffsetDateTime.now().minusDays(days))

  test("Entries are replayed until the oldest one is older than expiresAfter") {
    val fresh   = recordedDaysAgo(10)
    val options = RecordOptions.default.expiresAfter(Duration.ofDays(90))

    val client = MockClient(storageWith(fresh), options, VcrMatcher.default)
    assertEquals(client.nextMatch(request()), Some(fresh))
  }

  test("All entries are re-recorded together once the oldest one expires") {
    val old     = recordedDaysAgo(100)
    val fresh   = recordedDaysAgo(1, "https://example.com/fresh")
    val storage = storageWith(old, fresh)
    val options = RecordOptions.default.expiresAfter(Duration.ofDays(90))

    val client = MockClient(storage, options, VcrMatcher.default)
    assertEquals(client.nextMatch(request(uri = "https://example.com/fresh")), None)
    assertEquals(client.resolve(request()), VcrAction.Record)

    val rerecorded = entry()
    client.addNewEntry(rerecorded)
    client.save()

    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(Vector(rerecorded))))
  }

  test("Old entries don't expire without expiresAfter") {
    val old = recordedDaysAgo(10000)
    assertEquals(
      MockClient(storageWith(old), RecordOptions.default, VcrMatcher.default).nextMatch(request()),
      Some(old)
    )
  }

  test("Storage read failures are raised (or returned by resolve) instead of silently overwriting the recording") {
    val failing = new VcrStorage {
      override def read(): Either[Throwable, Option[VcrEntries]] = Left(new RuntimeException("corrupt file"))

      override def write(entries: VcrEntries): Unit = fail("should not write")
    }

    val client = MockClient(failing, RecordOptions.default, VcrMatcher.default)

    val error = intercept[IllegalStateException](client.nextMatch(request()))
    assertEquals(error.getCause.getMessage, "corrupt file")
    assert(isFail(client.resolve(request())))
    intercept[IllegalStateException](client.save())
  }

  test("Storage isn't read for replaying in RecordAll mode") {
    val failing = new VcrStorage {
      override def read(): Either[Throwable, Option[VcrEntries]] = Left(new RuntimeException("corrupt file"))

      override def write(entries: VcrEntries): Unit = ()
    }

    val client = MockClient(failing, RecordOptions.default.mode(RecordMode.RecordAll), VcrMatcher.default)
    assertEquals(client.nextMatch(request()), None)
    client.save()
  }

  test("Nothing is written when nothing was recorded and nothing was stored") {
    val storage = new CountingStorage(new InMemoryVcrStorage())
    MockClient(storage, RecordOptions.default, VcrMatcher.default).save()

    assertEquals(storage.writes.get(), 0)
  }

  private def resolveWith(mode: RecordMode, stored: VcrEntry*)(request: VcrRequest): VcrAction = {
    val storage = if (stored.isEmpty) new InMemoryVcrStorage() else storageWith(stored: _*)
    MockClient(storage, RecordOptions.default.mode(mode), VcrMatcher.default).resolve(request)
  }

  private val other = request(uri = "https://example.com/other")

  test("NewEpisodes replays recorded requests and records new ones") {
    assertEquals(resolveWith(RecordMode.NewEpisodes, entry())(request()), VcrAction.Replay(entry()))
    assertEquals(resolveWith(RecordMode.NewEpisodes, entry())(other), VcrAction.Record)
  }

  test("Once records when nothing is stored") {
    assertEquals(resolveWith(RecordMode.Once)(request()), VcrAction.Record)
  }

  test("Once only replays when something is stored") {
    assertEquals(resolveWith(RecordMode.Once, entry())(request()), VcrAction.Replay(entry()))
    assert(isFail(resolveWith(RecordMode.Once, entry())(other)))
  }

  test("ReplayOnly never records") {
    assertEquals(resolveWith(RecordMode.ReplayOnly, entry())(request()), VcrAction.Replay(entry()))

    resolveWith(RecordMode.ReplayOnly)(other) match {
      case VcrAction.Fail(error) => assert(error.getMessage.contains("No recorded entry matches"), error.getMessage)
      case action                => fail(s"Expected failure, got $action")
    }
  }

  test("RecordAll records even when a matching entry is stored") {
    assertEquals(resolveWith(RecordMode.RecordAll, entry())(request()), VcrAction.Record)
  }

  test("Requests rejected by shouldRecord pass through, or fail with notRecordedThrowsErrors") {
    val matcher = VcrMatcher.default.recordOnly(_ => false)
    val storage = storageWith(entry())

    assertEquals(MockClient(storage, RecordOptions.default, matcher).resolve(request()), VcrAction.PassThrough)
    assert(
      isFail(MockClient(storage, RecordOptions.default.notRecordedThrowsErrors(true), matcher).resolve(request()))
    )
  }

  test("Redacted requests are saved redacted and still match live requests") {
    val storage = new InMemoryVcrStorage()
    val matcher = VcrMatcher.default.redactQueryParams("apiKey").redactHeaders("Authorization")
    val live    = request(uri = "https://example.com/data?apiKey=secret&page=1")
      .copy(headers = Map("authorization" -> Seq("Bearer secret")))

    val recorder = MockClient(storage, RecordOptions.default, matcher)
    recorder.addNewEntry(entry(live, response().copy(headers = Map("Authorization" -> Seq("echoed-secret")))))
    recorder.save()

    val saved = storage.read().toOption.flatten.flatMap(_.entries.headOption).getOrElse(fail("should be saved"))
    assertEquals(saved.request.uri.toString, "https://example.com/data?apiKey=REDACTED&page=1")
    assertEquals(saved.request.headers, Map("authorization" -> Seq("REDACTED")))
    assertEquals(saved.response.headers, Map("Authorization" -> Seq("REDACTED")))
    assert(!saved.toString.contains("secret"))

    val replayer = MockClient(storage, RecordOptions.default, matcher)
    assertEquals(replayer.nextMatch(live), Some(saved))
  }

  test("Once records for a test whose scoped storage only has other tests' entries") {
    val shared = storageWith(entry().copy(testName = Some("other")))
    val client = MockClient(shared.scopedToTest("new"), RecordOptions.default.mode(RecordMode.Once), VcrMatcher.default)

    assertEquals(client.resolve(request()), VcrAction.Record)
  }

  test("RecordAll only writes when something was recorded or something was stored") {
    val empty = new CountingStorage(new InMemoryVcrStorage())
    MockClient(empty, RecordOptions.default.mode(RecordMode.RecordAll), VcrMatcher.default).save()
    assertEquals(empty.writes.get(), 0)

    val stored = new CountingStorage(storageWith(entry()))
    MockClient(stored, RecordOptions.default.mode(RecordMode.RecordAll), VcrMatcher.default).save()
    assertEquals(stored.writes.get(), 1)
    assertEquals(stored.read().map(_.map(_.entries)), Right(Some(Vector.empty)))
  }

  test("ReplayOnly says when the recording expired") {
    val options = RecordOptions.default.mode(RecordMode.ReplayOnly).expiresAfter(Duration.ofDays(1))

    MockClient(storageWith(recordedDaysAgo(2)), options, VcrMatcher.default).resolve(request()) match {
      case VcrAction.Fail(error) => assert(error.getMessage.contains("expired"), error.getMessage)
      case action                => fail(s"Expected failure, got $action")
    }
  }

  test("Unrecordable requests fail when only replaying is allowed and pass through otherwise") {
    def resolve(options: RecordOptions, stored: VcrEntry*) = {
      val storage = if (stored.isEmpty) new InMemoryVcrStorage() else storageWith(stored: _*)
      MockClient(storage, options, VcrMatcher.default).resolveUnrecordable(request(), "it's a stream")
    }

    assertEquals(resolve(RecordOptions.default), VcrAction.PassThrough)
    assertEquals(resolve(RecordOptions.default.mode(RecordMode.Once)), VcrAction.PassThrough)
    assertEquals(resolve(RecordOptions.default.mode(RecordMode.RecordAll)), VcrAction.PassThrough)
    assert(isFail(resolve(RecordOptions.default.mode(RecordMode.Once), entry())))
    assert(isFail(resolve(RecordOptions.default.notRecordedThrowsErrors(true))))

    resolve(RecordOptions.default.mode(RecordMode.ReplayOnly)) match {
      case VcrAction.Fail(error) => assert(error.getMessage.contains("because it's a stream"), error.getMessage)
      case action                => fail(s"Expected failure, got $action")
    }
  }

  test("Errors for requests no combined matcher accepts are redacted by every matcher") {
    val matcher = VcrMatcher.default.recordOnly(_ => false).redactQueryParams("api_key") :+
      VcrMatcher.default.recordOnly(_ => false).redactHeaders("Authorization")
    val live    = request(uri = "https://example.com/data?api_key=supersecret123")
      .copy(headers = Map("Authorization" -> Seq("Bearer supersecret456")))

    MockClient(new InMemoryVcrStorage(), RecordOptions.default.notRecordedThrowsErrors(true), matcher)
      .resolve(live) match {
      case VcrAction.Fail(error) => assert(!error.getMessage.contains("supersecret"), error.getMessage)
      case action                => fail(s"Expected failure, got $action")
    }
    assertEquals(matcher.redacted(live).headers, Map("Authorization" -> Seq("REDACTED")))
  }
}
