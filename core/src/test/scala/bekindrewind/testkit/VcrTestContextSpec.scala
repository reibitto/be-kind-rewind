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

  test("PerTest layout gives each test its own file under the suite's directory") {
    val ctx = new Support(VcrLayout.PerTest).vcrContext("gets a user")

    assertEquals(ctx.path, Paths.get("vcr", "MySpec", "gets-a-user.json"))
    assertEquals(ctx.storage.testName, Some("gets a user"))
  }

  test("PerSuite layout shares one file per suite, scoped to each test") {
    val support = new Support(VcrLayout.PerSuite)
    val a       = support.vcrContext("test a")
    val b       = support.vcrContext("test b")

    assertEquals(a.path, Paths.get("vcr", "MySpec.json"))
    assertEquals(b.path, a.path)
    assertEquals(a.storage.testName, Some("test a"))
  }

  test("withTestName labels entries that don't have a test name yet") {
    val underlying = new InMemoryVcrStorage()
    val labeled    = entry().copy(testName = Some("other test"))

    underlying.write(VcrEntries(Vector(labeled), "0.2.0"))
    val client = MockClient(underlying.withTestName("my test"), RecordOptions.default, VcrMatcher.exact)
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

class VcrTestSupportDefaultsSpec extends FunSuite {
  private object Support extends VcrTestSupport {
    override def vcrCodec: bekindrewind.codec.Codec = throw new UnsupportedOperationException
  }

  test("vcrDirectory defaults to src/test/resources/vcr in the suite's module, found from its build output") {
    // This suite's classes are in core/target/..., so its module is core.
    val core = Paths.get(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    assert(core.toString.contains("target"), core.toString)

    val expected = Iterator
      .unfold(Option(core))(_.map(path => (path, Option(path.getParent))))
      .find(_.getFileName.toString == "target")
      .flatMap(target => Option(target.getParent))
      .getOrElse(fail(s"No target directory above $core"))
    assertEquals(Support.vcrDirectory, expected.resolve("src/test/resources/vcr"))
    assertEquals(expected.getFileName.toString, "core")
  }

  test("vcr.base.directory overrides the module directory") {
    val previous = sys.props.get("vcr.base.directory")
    sys.props("vcr.base.directory") = "/somewhere/module"
    try assertEquals(VcrTestSupport.moduleDirectory(getClass), Paths.get("/somewhere/module"))
    finally {
      previous match {
        case Some(value) => sys.props("vcr.base.directory") = value
        case None        => sys.props -= "vcr.base.directory"
      }
      ()
    }
  }

  test("vcrDefaultRecordMode sets the record mode") {
    object ReplayOnlySupport extends VcrTestSupport {
      override def vcrCodec: bekindrewind.codec.Codec = throw new UnsupportedOperationException
      override def vcrDefaultRecordMode: RecordMode   = RecordMode.ReplayOnly
    }

    // Unless VCR_RECORD_MODE is set, which it isn't in this build.
    assertEquals(
      ReplayOnlySupport.vcrRecordOptions.mode,
      VcrTestSupport.recordModeOverride.getOrElse(RecordMode.ReplayOnly)
    )
    assertEquals(Support.vcrRecordOptions.mode, VcrTestSupport.recordModeOverride.getOrElse(RecordMode.NewEpisodes))
  }
}
