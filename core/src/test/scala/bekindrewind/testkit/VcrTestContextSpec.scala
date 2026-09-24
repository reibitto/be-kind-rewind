package bekindrewind.testkit

import bekindrewind._
import bekindrewind.codec.Codec
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.MockClient
import bekindrewind.testing.TestData._
import munit._

import java.nio.file.{ Path, Paths }

class VcrTestContextSpec extends FunSuite {
  private val codec = new Codec {
    override def decode(text: String): Either[Throwable, VcrEntries] = Left(new UnsupportedOperationException)
    override def encode(entries: VcrEntries): String                 = ""
    override val fileExtension: String                               = "json"
  }

  private class Support(layout: VcrLayout) extends VcrTestSupport {
    override def vcrDirectory: Path   = Paths.get("vcr")
    override def vcrLayout: VcrLayout = layout
    override def vcrCodec: Codec      = codec
    override def vcrSuiteName: String = "MySpec"
  }

  test("fileName turns names into safe file names") {
    assertEquals(VcrTestContext.fileName("gets a user (v2)"), "gets-a-user-v2")
    assertEquals(VcrTestContext.fileName("../etc/passwd"), "etc-passwd")
    assertEquals(VcrTestContext.fileName("Ünïcode ✓"), "n-code")
    assertEquals(VcrTestContext.fileName("!!!"), "unnamed")
    assertEquals(VcrTestContext.fileName("x" * 500).length, 150)
  }

  test("PerTest layout gives each test its own file under the suite's directory") {
    val ctx = new Support(VcrLayout.PerTest).vcrContext("gets a user")

    assertEquals(ctx.path, Paths.get("vcr", "MySpec", "gets-a-user.json"))
    assertEquals(ctx.storage.testName, Some("gets a user"))
    assert(ctx.storage.isInstanceOf[storage.VcrStorage.TestNamed])
  }

  test("PerSuite layout shares one file per suite, scoped to each test") {
    val support = new Support(VcrLayout.PerSuite)
    val a       = support.vcrContext("test a")
    val b       = support.vcrContext("test b")

    assertEquals(a.path, Paths.get("vcr", "MySpec.json"))
    assertEquals(b.path, a.path)
    assertEquals(a.storage.testName, Some("test a"))
    assert(a.storage.isInstanceOf[storage.VcrStorage.TestScoped])
  }

  test("withTestName labels entries that don't have a test name yet") {
    val underlying = new InMemoryVcrStorage()
    val labeled    = entry().copy(testName = Some("other test"))

    underlying.write(VcrEntries(Vector(labeled), "0.2.0"))
    val client = MockClient(underlying.withTestName("my test"), RecordOptions.default, VcrMatcher.identity)
    client.findMatch(labeled.request)
    client.addNewEntry(entry(request(uri = "https://example.com/new")))
    client.save()

    assertEquals(
      underlying.read().toOption.flatten.map(_.entries.map(_.testName)),
      Some(Vector(Some("other test"), Some("my test")))
    )
  }

  test("RecordMode.fromName accepts different spellings") {
    assertEquals(RecordMode.fromName("replay-only"), Some(RecordMode.ReplayOnly))
    assertEquals(RecordMode.fromName("RECORD_ALL"), Some(RecordMode.RecordAll))
    assertEquals(RecordMode.fromName("NewEpisodes"), Some(RecordMode.NewEpisodes))
    assertEquals(RecordMode.fromName("once"), Some(RecordMode.Once))
    assertEquals(RecordMode.fromName("sometimes"), None)
  }
}
