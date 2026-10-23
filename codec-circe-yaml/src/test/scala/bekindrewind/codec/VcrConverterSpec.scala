package bekindrewind.codec

import bekindrewind.codec.CirceInstances._
import bekindrewind.storage.{ FileVcrStorage, VcrConverter }
import bekindrewind.testing.TestData
import bekindrewind.util.Generators
import bekindrewind.{ VcrEntries, VcrResponse }
import munit._
import org.scalacheck.Prop._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }

class VcrConverterSpec extends ScalaCheckSuite {

  property("Converting between JSON and YAML keeps every entry exactly") {
    forAll(Generators.vcrEntries) { (entries: VcrEntries) =>
      val yaml = VcrConverter.convert(JsonCodec.encode(entries), JsonCodec, YamlCodec).fold(throw _, identity)
      val json = VcrConverter.convert(yaml, YamlCodec, JsonCodec).fold(throw _, identity)

      assertEquals(YamlCodec.decode(yaml), Right(entries))
      assertEquals(JsonCodec.decode(json), Right(entries))
    }
  }

  private val entries = VcrEntries(
    Vector(
      TestData.entry().copy(testName = Some("text body")),
      TestData.entry(response = VcrResponse.fromBytes(200, "OK", Map.empty, Array[Byte](0, -1), None, None))
    ),
    "0.1.0"
  )

  private def write(path: Path, text: String): Path = {
    Files.createDirectories(path.getParent)
    Files.write(path, text.getBytes(StandardCharsets.UTF_8))
  }

  test("convertFile writes the new format next to the original and keeps the recorded version") {
    val dir    = Files.createTempDirectory("vcr-convert")
    val source = write(dir.resolve("Suite/test.yml"), YamlCodec.encode(entries))

    val target = VcrConverter.convertFile(source, YamlCodec, JsonCodec).fold(throw _, identity)

    assertEquals(target, dir.resolve("Suite/test.json"))
    assertEquals(FileVcrStorage(target, JsonCodec).read(), Right(Some(entries)))
    assert(Files.exists(source))
  }

  test("convertDirectory converts recordings in subdirectories and can delete the originals") {
    val dir = Files.createTempDirectory("vcr-convert")
    write(dir.resolve("A/one.json"), JsonCodec.encode(entries))
    write(dir.resolve("B/two.json"), JsonCodec.encode(entries))
    write(dir.resolve("B/notes.txt"), "not a recording")

    val converted =
      VcrConverter.convertDirectory(dir, JsonCodec, YamlCodec, deleteSources = true).fold(throw _, identity)

    assertEquals(converted.map(_._2), Vector(dir.resolve("A/one.yml"), dir.resolve("B/two.yml")))
    assertEquals(FileVcrStorage(dir.resolve("B/two.yml"), YamlCodec).read(), Right(Some(entries)))
    assert(!Files.exists(dir.resolve("A/one.json")) && Files.exists(dir.resolve("B/notes.txt")))
  }

  test("convertDirectory converts nothing if any recording can't be read") {
    val dir  = Files.createTempDirectory("vcr-convert")
    val good = write(dir.resolve("A/good.json"), JsonCodec.encode(entries))
    write(dir.resolve("A/bad.json"), "{ not json")

    val result = VcrConverter.convertDirectory(dir, JsonCodec, YamlCodec, deleteSources = true)

    assert(result.left.exists(_.getMessage.contains("bad.json")), result.toString)
    assert(Files.exists(good) && !Files.exists(dir.resolve("A/good.yml")))
  }

  test("Converting to a format that already has a file there fails instead of overwriting it") {
    val dir = Files.createTempDirectory("vcr-convert")
    write(dir.resolve("A/test.json"), JsonCodec.encode(entries))
    write(dir.resolve("A/test.yml"), "existing")

    assert(VcrConverter.convertDirectory(dir, JsonCodec, YamlCodec).isLeft)
    assertEquals(new String(Files.readAllBytes(dir.resolve("A/test.yml")), StandardCharsets.UTF_8), "existing")
  }

  test("Converting to the same format rewrites recordings in the current format") {
    val dir       = Files.createTempDirectory("vcr-convert")
    // Written like YamlCodec did before it preserved field order.
    val oldFormat = io.circe.yaml.Printer.spaces2.pretty(io.circe.Encoder[VcrEntries].apply(entries))
    val source    = write(dir.resolve("A/test.yml"), oldFormat)
    assertNotEquals(oldFormat, YamlCodec.encode(entries))

    assertEquals(VcrConverter.convertFile(source, YamlCodec, YamlCodec), Right(source))
    assertEquals(new String(Files.readAllBytes(source), StandardCharsets.UTF_8), YamlCodec.encode(entries))
  }

  test("VcrTool converts and renames from the command line") {
    val dir = Files.createTempDirectory("vcr-tool")
    write(dir.resolve("Suite/test.json"), JsonCodec.encode(entries.copy(entries = entries.entries.take(1))))

    val converted = VcrTool.run(List("convert", dir.toString, "json", "yaml", "--delete-sources"))
    assert(converted.exists(_.startsWith("Converted 1 recordings")), converted.toString)

    val renamed = VcrTool.run(List("rename", dir.toString, "yaml", "--naming", "by-hash:12"))
    assert(renamed.exists(_.startsWith("Renamed 1 recordings")), renamed.toString)
    assert(Files.list(dir.resolve("Suite")).iterator().next().getFileName.toString.matches("[0-9a-f]{12}\\.yml"))

    assert(VcrTool.run(List("convert", dir.toString, "xml", "json")).isLeft)
    assert(VcrTool.run(List("rename", dir.toString, "yaml", "--naming", "by-hash:999")).isLeft)
    assert(VcrTool.run(List("rename", dir.toString, "yaml", "--dryrun")).left.exists(_.startsWith("Unknown option")))
    assert(VcrTool.run(List("rename", dir.toString, "yaml", "--naming")).isLeft)
    assert(VcrTool.run(Nil).left.exists(_.startsWith("Usage")))
  }
}
