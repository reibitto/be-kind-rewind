package bekindrewind.testkit

import bekindrewind._
import bekindrewind.codec.Codec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestData._
import bekindrewind.testkit.VcrFileNaming._
import munit._
import org.scalacheck.Gen
import org.scalacheck.Prop._

import java.nio.file.{ Files, Path }
import scala.collection.concurrent.TrieMap

class VcrFileNamingSpec extends ScalaCheckSuite {
  private def byName(testName: String, naming: VcrFileNaming = ByTestName()) =
    naming.fileName(Input("MySpec", testName), "json")

  test("ByTestName turns test names into safe file names") {
    assertEquals(byName("gets a user (v2)"), "gets-a-user-v2.json")
    assertEquals(byName("../etc/passwd"), "etc-passwd.json")
    assertEquals(byName("Ünïcode ✓"), "n-code.json")
    assertEquals(byName("!!!"), "unnamed.json")
  }

  test("Windows device names get a suffix") {
    assertEquals(byName("con"), "con_.json")
    assertEquals(byName("NUL"), "NUL_.json")
    assertEquals(byName("com1.backup"), "com1.backup_.json")
    assertEquals(byName("console"), "console.json")
  }

  property("Names never exceed maxLength and only use safe characters") {
    forAll(Gen.asciiStr, Gen.choose(minMaxLength, 255), Gen.oneOf("json", "yml")) {
      (testName: String, maxLength: Int, extension: String) =>
        for (naming <- Seq(ByTestName(maxLength), ByHash(maxLength = maxLength), Custom(_.testName * 3, maxLength))) {
          val name = naming.fileName(Input("Suite", testName), extension)
          assert(name.length <= maxLength, name)
          assert(name.matches("[A-Za-z0-9._-]+"), name)
          assert(!name.startsWith(".") && !name.startsWith("-"), name)
        }
    }
  }

  test("Long names are truncated with a hash of the full name, so names starting the same way stay unique") {
    val prefix = "x" * 200
    val a      = byName(prefix + " a")
    val b      = byName(prefix + " b")

    assertEquals(a.length, defaultMaxLength - 8 + ".json".length)
    assertNotEquals(a, b)
    assertEquals(a, byName(prefix + " a"))
    assert(a.matches("x+-[0-9a-f]{8}\\.json"), a)
  }

  test("Names up to the limit (leaving room for any extension) are unchanged") {
    val name = "y" * (defaultMaxLength - 8)
    assertEquals(byName(name), s"$name.json")
    assert(byName(name + "y").matches("y+-[0-9a-f]{8}\\.json"))
  }

  test("Truncated names are the same for every format, so converted recordings still match") {
    val long = "z" * 200
    assertEquals(
      ByTestName().fileName(Input("MySpec", long), "json").stripSuffix(".json"),
      ByTestName().fileName(Input("MySpec", long), "yml").stripSuffix(".yml")
    )
  }

  test("ByHash names are the same length for every test and stable") {
    val a = byName("gets a user", ByHash())
    assert(a.matches("[0-9a-f]{16}\\.json"), a)
    assertEquals(a, byName("gets a user", ByHash()))
    assertNotEquals(a, byName("gets another user", ByHash()))
    assertEquals(byName("gets a user", ByHash(length = 8)).length, 8 + ".json".length)
  }

  test("Custom names get the suite and test names, and are still made safe") {
    val naming = Custom(input => s"${input.suiteName}/${input.testName.stripPrefix("The API should ")}")

    assertEquals(byName("The API should create a user", naming), "MySpec-create-a-user.json")
  }

  test("maxLength must leave room for the hash and extension") {
    intercept[IllegalArgumentException](ByTestName(10))
    intercept[IllegalArgumentException](ByHash(length = 0))
  }

  test("Suite directories are capped like file names") {
    val support = new VcrTestSupport {
      override def vcrCodec: Codec              = stubCodec
      override def vcrSuiteName: String         = "S" * 300
      override def vcrFileNaming: VcrFileNaming = ByHash()
    }
    val path    = support.vcrContext("a test").path

    assertEquals(path.getParent.getFileName.toString.length, defaultMaxLength - 8)
    assert(path.getFileName.toString.matches("[0-9a-f]{16}\\.stub"), path.toString)
  }

  private lazy val stubCodec: Codec = new Codec {
    private val store = TrieMap.empty[String, VcrEntries]

    override def decode(text: String): Either[Throwable, VcrEntries] =
      store.get(text).toRight(new IllegalArgumentException(text))

    override def encode(entries: VcrEntries): String = {
      val key = java.util.UUID.randomUUID().toString
      store.update(key, entries)
      key
    }

    override val fileExtension: String = "stub"
  }

  private def write(path: Path, testNames: Option[String]*): Unit = {
    Files.createDirectories(path.getParent)
    FileVcrStorage(path, stubCodec).write(VcrEntries(testNames.map(n => entry().copy(testName = n)).toVector, "0.2.0"))
  }

  test("renameRecordings renames files to match the naming, using the stored test names") {
    val dir   = Files.createTempDirectory("vcr-rename")
    val long  = "t" * 150
    val suite = dir.resolve("MySpec")
    write(suite.resolve(s"$long.stub"), Some(long))
    write(suite.resolve("short.stub"), Some("short"))
    write(suite.resolve("unlabeled.stub"), None)
    write(suite.resolve("merged.stub"), Some("a"), Some("b"))

    val dryRun = renameRecordings(dir, stubCodec, ByTestName(), dryRun = true)
    assertEquals(dryRun.map(_.map(_._1.getFileName.toString)), Right(Vector(s"$long.stub")))
    assert(Files.exists(suite.resolve(s"$long.stub")))

    val renamed = renameRecordings(dir, stubCodec, ByTestName()).fold(throw _, identity)
    val target  = suite.resolve(ByTestName().fileName(Input("MySpec", long), "stub"))
    assertEquals(renamed, Vector(suite.resolve(s"$long.stub") -> target))
    assert(Files.exists(target) && !Files.exists(suite.resolve(s"$long.stub")))
    assert(Files.exists(suite.resolve("unlabeled.stub")) && Files.exists(suite.resolve("merged.stub")))
  }

  test("renameRecordings renames nothing if a target already exists") {
    val dir   = Files.createTempDirectory("vcr-rename")
    val suite = dir.resolve("MySpec")
    write(suite.resolve("one.stub"), Some("same name"))
    write(suite.resolve("two.stub"), Some("same name"))

    assert(renameRecordings(dir, stubCodec, ByTestName()).isLeft)
    assert(Files.exists(suite.resolve("one.stub")) && Files.exists(suite.resolve("two.stub")))
  }
}
