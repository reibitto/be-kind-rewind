package bekindrewind.storage

import bekindrewind.testing.MockClient
import bekindrewind.testing.TestData._
import bekindrewind.{ BuildInfo, RecordOptions, VcrEntries, VcrEntry, VcrMatcher }
import munit._

import java.util.concurrent.{ Executors, TimeUnit }

class TestScopedStorageSpec extends FunSuite {
  private def named(uri: String, test: Option[String]): VcrEntry = entry(request(uri = uri)).copy(testName = test)

  private val a1       = named("https://example.com/a1", Some("a"))
  private val b1       = named("https://example.com/b1", Some("b"))
  private val a2       = named("https://example.com/a2", Some("a"))
  private val unnamed  = named("https://example.com/legacy", None)
  private val original = Vector(a1, b1, a2, unnamed)

  private def shared(): InMemoryVcrStorage = {
    val storage = new InMemoryVcrStorage()
    storage.write(VcrEntries(original, BuildInfo.version))
    storage
  }

  test("Only the test's own entries are read") {
    assertEquals(shared().scopedToTest("a").read().map(_.map(_.entries)), Right(Some(Vector(a1, a2))))
    assertEquals(shared().scopedToTest("c").read(), Right(None))
  }

  test("Writing replaces only the test's entries, in place") {
    val storage = shared()
    val a3      = named("https://example.com/a3", None)

    storage.scopedToTest("a").write(VcrEntries(Vector(a3), BuildInfo.version))

    assertEquals(
      storage.read().map(_.map(_.entries)),
      Right(Some(Vector(a3.copy(testName = Some("a")), b1, unnamed)))
    )
  }

  test("New tests are appended") {
    val storage = shared()
    val c1      = named("https://example.com/c1", None)

    storage.scopedToTest("c").write(VcrEntries(Vector(c1), BuildInfo.version))

    assertEquals(storage.read().map(_.map(_.entries)), Right(Some(original :+ c1.copy(testName = Some("c")))))
  }

  test("Clients only match their own test's entries") {
    val storage = shared()
    val client  = MockClient(storage.scopedToTest("b"), RecordOptions.default, VcrMatcher.default)

    assertEquals(client.findMatch(request(uri = "https://example.com/a1")), None)
    assertEquals(client.findMatch(request(uri = "https://example.com/b1")), Some(b1))
  }

  test("Tests saving to the same storage concurrently don't lose each other's entries") {
    val storage  = new InMemoryVcrStorage()
    val executor = Executors.newFixedThreadPool(8)

    (1 to 50).foreach { i =>
      executor.execute { () =>
        val client = MockClient(storage.scopedToTest(s"test $i"), RecordOptions.default, VcrMatcher.default)
        client.addNewEntry(entry(request(uri = s"https://example.com/$i")))
        client.save()
      }
    }
    executor.shutdown()
    assert(executor.awaitTermination(30, TimeUnit.SECONDS))

    assertEquals(storage.read().toOption.flatten.map(_.entries.flatMap(_.testName).toSet.size), Some(50))
  }
}
