package bekindrewind.storage

import bekindrewind.codec.Codec
import bekindrewind.testing.TestData
import bekindrewind.{ BuildInfo, VcrEntries }
import munit._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

class FileVcrStorageSpec extends FunSuite {

  /**
   * Stores entries in memory and writes a marker to the file, so the storage logic can be tested without a real codec.
   */
  private class StubCodec extends Codec {
    val last = new AtomicReference[VcrEntries]()

    override def decode(text: String): Either[Throwable, VcrEntries] =
      if (text == "stub") Right(last.get()) else Left(new IllegalArgumentException(s"bad file: $text"))

    override val fileExtension: String = "stub"

    override def encode(entries: VcrEntries): String = {
      last.set(entries)
      "stub"
    }
  }

  private val tempDir = FunFixture[Path](
    _ => Files.createTempDirectory("vcr-storage"),
    dir => Files.walk(dir).iterator().asScala.toVector.sorted.reverse.foreach(Files.delete)
  )

  tempDir.test("Missing file reads as empty") { dir =>
    assertEquals(FileVcrStorage(dir.resolve("missing.json"), new StubCodec).read(), Right(None))
  }

  tempDir.test("Recordings get the same permissions as other new files") { dir =>
    assume(dir.getFileSystem.supportedFileAttributeViews().contains("posix"), "POSIX permissions only")

    val path = dir.resolve("recording.json")
    FileVcrStorage(path, new StubCodec).write(VcrEntries(Vector.empty, BuildInfo.version))

    assertEquals(
      Files.getPosixFilePermissions(path),
      Files.getPosixFilePermissions(Files.createFile(dir.resolve("other.json")))
    )
  }

  tempDir.test("Write creates parent directories and can be read back") { dir =>
    val path    = dir.resolve("nested/dir/example.json")
    val storage = FileVcrStorage(path, new StubCodec)
    val entries = VcrEntries(Vector(TestData.entry()), BuildInfo.version)

    storage.write(entries)

    assert(Files.exists(path))
    assertEquals(new String(Files.readAllBytes(path), StandardCharsets.UTF_8), "stub")
    assertEquals(storage.read(), Right(Some(entries)))
  }

  tempDir.test("Decoding failures are returned as errors") { dir =>
    val path = dir.resolve("corrupt.json")
    Files.write(path, "garbage".getBytes(StandardCharsets.UTF_8))

    assert(FileVcrStorage(path, new StubCodec).read().isLeft)
  }

  tempDir.test("Writes replace the file without leaving temporary files behind") { dir =>
    val path    = dir.resolve("example.json")
    val storage = FileVcrStorage(path, new StubCodec)

    storage.write(VcrEntries(Vector(TestData.entry()), BuildInfo.version))
    storage.write(VcrEntries(Vector.empty, BuildInfo.version))

    assertEquals(storage.read(), Right(Some(VcrEntries(Vector.empty, BuildInfo.version))))
    assertEquals(Files.list(dir).count(), 1L)
  }

  test("Relative paths without a parent directory work") {
    val name = s"vcr-${System.nanoTime()}.json"
    val path = java.nio.file.Paths.get(name)
    try {
      FileVcrStorage(path, new StubCodec).write(VcrEntries(Vector.empty, BuildInfo.version))
      assert(Files.exists(path))
    } finally Files.deleteIfExists(path)
  }
}
