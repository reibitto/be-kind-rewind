package bekindrewind.testkit

import bekindrewind._
import bekindrewind.codec.Codec
import bekindrewind.storage.{ FileVcrStorage, InMemoryVcrStorage, SharedRecordingStorage }
import bekindrewind.testing.TestData._
import bekindrewind.testing.{ CountingStorage, MockClient }
import munit._

import java.nio.file.{ Files, Path }
import java.util.concurrent.{ Executors, TimeUnit }

class VcrSessionSpec extends FunSuite {

  /** Stores entries as Java-serialized-ish text so the tests don't need a real codec. */
  private object TestCodec extends Codec {
    private val store = scala.collection.concurrent.TrieMap.empty[String, VcrEntries]

    override def decode(text: String): Either[Throwable, VcrEntries] =
      store.get(text).toRight(new IllegalArgumentException(s"unknown: $text"))

    override def encode(entries: VcrEntries): String = {
      val key = java.util.UUID.randomUUID().toString
      store.update(key, entries)
      key
    }

    override val fileExtension: String = "test"
  }

  private class Support(dir: Path, layout: VcrLayout = VcrLayout.PerTest) extends VcrTestSupport {
    override def vcrDirectory: Path              = dir
    override def vcrLayout: VcrLayout            = layout
    override def vcrCodec: Codec                 = TestCodec
    override def vcrSuiteName: String            = "SessionSpec"
    override def vcrRecordOptions: RecordOptions = RecordOptions.default
  }

  private val tempDir = FunFixture[Path](_ => Files.createTempDirectory("vcr-session"), _ => ())

  private def read(path: Path): Vector[VcrEntry] =
    FileVcrStorage(path, TestCodec).read().toOption.flatten.fold(Vector.empty[VcrEntry])(_.entries)

  private def uri(path: String) = s"https://example.com/$path"

  tempDir.test("A client created before any test records to whichever test is running") { dir =>
    val support = new Support(dir)
    val client  = VcrClient(support.vcrSession.storage, VcrMatcher.default)

    support.vcrSession.start(support.vcrContext("first"))
    client.addNewEntry(entry(request(uri = uri("a"))))
    support.vcrSession.finish(succeeded = true)

    support.vcrSession.start(support.vcrContext("second"))
    client.addNewEntry(entry(request(uri = uri("b"))))
    support.vcrSession.finish(succeeded = true)

    assertEquals(read(support.vcrContext("first").path).map(_.testName), Vector(Some("first")))
    assertEquals(read(support.vcrContext("second").path).map(_.request.uri.toString), Vector(uri("b")))
  }

  tempDir.test("Requests made on other threads record to the running test") { dir =>
    val support  = new Support(dir)
    val client   = support.vcrSession.client(VcrMatcher.default)
    val executor = Executors.newFixedThreadPool(4)

    support.vcrSession.start(support.vcrContext("threads"))
    (1 to 20).foreach(i => executor.execute(() => client.addNewEntry(entry(request(uri = uri(i.toString))))))
    executor.shutdown()
    assert(executor.awaitTermination(10, TimeUnit.SECONDS))
    support.vcrSession.finish(succeeded = true)

    assertEquals(read(support.vcrContext("threads").path).size, 20)
  }

  tempDir.test("Clients with separate matcher instances replay each entry once") { dir =>
    val support = new Support(dir)
    val first   = support.vcrContext("shared")
    val storage = FileVcrStorage(first.path, TestCodec)
    storage.write(
      VcrEntries(
        Vector(1, 2).map(i =>
          entry(request(uri = uri("a")), response(body = i.toString)).copy(testName = Some("shared"))
        ),
        "test"
      )
    )

    support.vcrSession.start(support.vcrContext("shared"))
    val bodies = (1 to 2).map { _ =>
      support.vcrSession.client(VcrMatcher.default).resolve(request(uri = uri("a"))) match {
        case VcrAction.Replay(e) => e.response.body
        case other               => fail(s"Expected a replay: $other")
      }
    }
    support.vcrSession.finish(succeeded = true)

    assertEquals(bodies, Vector("1", "2"))
  }

  tempDir.test("Requests the matcher doesn't record pass through when no test is running") { dir =>
    val support = new Support(dir)
    val client  = support.vcrSession.client(VcrMatcher.default.withShouldRecord(_.uri.getHost != "passthrough.com"))

    assertEquals(client.resolve(request(uri = "https://passthrough.com/a")), VcrAction.PassThrough)
    intercept[IllegalStateException](client.resolve(request(uri = uri("a"))))
  }

  tempDir.test("Starting a test while another is running fails, and no running test fails too") { dir =>
    val support = new Support(dir)
    val client  = support.vcrSession.client(VcrMatcher.default)

    intercept[IllegalStateException](client.findMatch(request()))

    support.vcrSession.start(support.vcrContext("a"))
    val error = intercept[IllegalStateException](support.vcrSession.start(support.vcrContext("b")))
    assert(error.getMessage.contains("one at a time"), error.getMessage)
    assertEquals(support.vcrSession.current.map(_.testName), Some("a"))
    support.vcrSession.finish(succeeded = true)
  }

  tempDir.test("Clients sharing a test's storage don't overwrite each other's entries") { dir =>
    val ctx    = new Support(dir).vcrContext("two clients")
    val first  = VcrClient(ctx.storage, VcrMatcher.default)
    val second = VcrClient(ctx.storage, VcrMatcher.methodUriAndBody)

    first.addNewEntry(entry(request(uri = uri("a"))))
    second.addNewEntry(entry(request(uri = uri("b"))))
    first.save()
    second.save()

    assertEquals(read(ctx.path).map(_.request.uri.toString), Vector(uri("a"), uri("b")))
  }

  test("Saving a shared recording again without changes doesn't write again") {
    val storage = new CountingStorage(new InMemoryVcrStorage())
    val shared  = SharedRecordingStorage(storage, RecordOptions.default)
    val client  = MockClient(shared, RecordOptions.default, VcrMatcher.default)

    client.addNewEntry(entry())
    client.save()
    client.save()
    MockClient(shared, RecordOptions.default, VcrMatcher.identity).save()

    assertEquals(storage.writes.get(), 1)
  }

  test("An entry replayed through any matcher of a shared recording isn't unused") {
    val underlying = new InMemoryVcrStorage()
    underlying.write(VcrEntries(Vector(entry(request(uri = uri("a"))), entry(request(uri = uri("b")))), "0.2.0"))
    val shared     = SharedRecordingStorage(underlying, RecordOptions.default)

    val a = MockClient(shared, RecordOptions.default, VcrMatcher.default.withShouldRecord(_.uri.getPath == "/a"))
    val b = MockClient(shared, RecordOptions.default, VcrMatcher.default)
    assert(a.findMatch(request(uri = uri("a"))).isDefined)
    assertEquals(b.unusedEntries().map(_.request.uri.toString), Vector(uri("b")))
    assert(b.findMatch(request(uri = uri("b"))).isDefined)
    assertEquals(a.unusedEntries(), Vector.empty)
  }

  tempDir.test("Tests whose names map to the same per-test file don't affect each other") { dir =>
    val support = new Support(dir)
    val a       = support.vcrContext("gets a user")
    val b       = support.vcrContext("gets-a-user")
    assertEquals(a.path, b.path)

    VcrClient(a.storage, VcrMatcher.default).addNewEntry(entry(request(uri = uri("a"))))
    a.recording.save(succeeded = true)
    VcrClient(b.storage, VcrMatcher.default).addNewEntry(entry(request(uri = uri("b"))))
    b.recording.save(succeeded = true)

    assertEquals(read(a.path).map(_.testName), Vector(Some("gets a user"), Some("gets-a-user")))
    assertEquals(
      VcrClient(support.vcrContext("gets a user").storage, VcrMatcher.default).findMatch(request(uri = uri("b"))),
      None
    )
  }

  tempDir.test("Entries without a test name in per-test files still belong to the test") { dir =>
    val support = new Support(dir)
    val path    = support.vcrContext("legacy").path
    Files.createDirectories(path.getParent)
    FileVcrStorage(path, TestCodec).write(VcrEntries(Vector(entry()), "0.1.0"))

    assertEquals(
      VcrClient(support.vcrContext("legacy").storage, VcrMatcher.default).findMatch(request()),
      Some(entry())
    )
  }

  tempDir.test("Contexts created for the running session test share its recording") { dir =>
    val support = new Support(dir)
    val context = support.vcrContext("a")
    support.vcrSession.start(context)

    assert(support.vcrContext("a") eq context)
    assert(!(support.vcrContext("b") eq context))
    support.vcrSession.finish(succeeded = true)
  }

  tempDir.test("Finishing a test that's no longer running does nothing") { dir =>
    val support  = new Support(dir)
    val timedOut = support.vcrContext("timed out")
    support.vcrSession.start(timedOut)
    support.vcrSession.finish(timedOut, succeeded = false)

    val next = support.vcrContext("next")
    support.vcrSession.start(next)
    support.vcrSession.finish(timedOut, succeeded = true)
    assertEquals(support.vcrSession.current, Some(next))
    support.vcrSession.finish(next, succeeded = true)
    assertEquals(support.vcrSession.current, None)
  }

  tempDir.test("Saving a session client without a running test does nothing") { dir =>
    new Support(dir).vcrSession.client(VcrMatcher.default).save()
  }
}
