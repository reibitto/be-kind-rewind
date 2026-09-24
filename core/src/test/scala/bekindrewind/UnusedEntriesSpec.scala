package bekindrewind

import bekindrewind.storage.{ InMemoryVcrStorage, VcrStorage }
import bekindrewind.testing.TestData._
import bekindrewind.testing.{ CountingStorage, MockClient }
import munit._

class UnusedEntriesSpec extends FunSuite {
  private val a1 = entry(request(uri = "https://example.com/a"), response("a1"))
  private val a2 = entry(request(uri = "https://example.com/a"), response("a2"))
  private val b  = entry(request(uri = "https://example.com/b"))
  private val c  = entry(request(method = "POST", uri = "https://example.com/c"))

  private def storageWith(entries: VcrEntry*): InMemoryVcrStorage = {
    val storage = new InMemoryVcrStorage()
    storage.write(VcrEntries(entries.toVector, BuildInfo.version))
    storage
  }

  private def stored(storage: VcrStorage): Option[Vector[VcrEntry]] = storage.read().toOption.flatten.map(_.entries)

  private def options(unused: UnusedEntries) = RecordOptions.default.unusedEntries(unused)

  // Replays `a` once and never requests `b`. `c` is rejected by the matcher.
  private def run(storage: VcrStorage, options: RecordOptions): MockClient = {
    val client = MockClient(storage, options, VcrMatcher.default.withShouldRecord(_.method == "GET"))
    assertEquals(client.findMatch(request(uri = "https://example.com/a")), Some(a1))
    client
  }

  test("unusedEntries lists leftover, never requested and no longer matched entries in recorded order") {
    val client = run(storageWith(a1, b, a2, c), RecordOptions.default)

    assertEquals(client.unusedEntries(), Vector(b, a2, c))
  }

  test("Warn leaves the recording unchanged") {
    val storage = new CountingStorage(storageWith(a1, b).withTestName("my test"))
    run(storage, options(UnusedEntries.Warn)).save()

    assertEquals(storage.writes.get(), 0)
  }

  test("Prune removes unused entries and keeps new ones") {
    val storage = storageWith(a1, b, a2, c)
    val client  = run(storage.withTestName("my test"), options(UnusedEntries.Prune))
    val newer   = entry(request(uri = "https://example.com/new"))
    client.addNewEntry(newer)
    client.save()

    assertEquals(stored(storage), Some(Vector(a1, newer).map(_.copy(testName = Some("my test")))))
  }

  test("Prune doesn't remove anything when the test failed") {
    val storage = storageWith(a1, b)
    run(storage.withTestName("my test"), options(UnusedEntries.Prune)).save(succeeded = false)

    assertEquals(stored(storage), Some(Vector(a1, b)))
  }

  test("Prune doesn't remove anything when the storage isn't for a single test") {
    val storage = storageWith(a1, b)
    run(storage, options(UnusedEntries.Prune)).save()

    assertEquals(stored(storage), Some(Vector(a1, b)))
  }

  test("Prune doesn't remove anything outside of NewEpisodes mode") {
    val storage = storageWith(a1, b)
    run(storage.withTestName("my test"), options(UnusedEntries.Prune).mode(RecordMode.ReplayOnly)).save()

    assertEquals(stored(storage), Some(Vector(a1, b)))
  }

  test("Fail throws after saving new entries") {
    val storage = storageWith(a1, b)
    val client  = run(storage, options(UnusedEntries.Fail))
    val newer   = entry(request(uri = "https://example.com/new"))
    client.addNewEntry(newer)

    val error = intercept[VcrException](client.save())
    assert(error.getMessage.contains("GET https://example.com/b"), error.getMessage)
    assertEquals(stored(storage), Some(Vector(a1, b, newer)))
  }

  test("Fail doesn't throw when the test failed") {
    run(storageWith(a1, b), options(UnusedEntries.Fail)).save(succeeded = false)
  }

  test("Nothing is unused when every entry was replayed") {
    val client = MockClient(storageWith(a1), options(UnusedEntries.Fail), VcrMatcher.default)
    client.findMatch(request(uri = "https://example.com/a"))
    client.save()
  }
}
