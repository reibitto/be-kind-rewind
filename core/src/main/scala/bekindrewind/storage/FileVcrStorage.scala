package bekindrewind.storage

import bekindrewind.VcrEntries
import bekindrewind.codec.Codec

import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.{ AtomicMoveNotSupportedException, Files, Path, Paths, StandardCopyOption, StandardOpenOption }
import java.util.concurrent.ThreadLocalRandom
import scala.util.Try

class FileVcrStorage(val path: Path, val codec: Codec, val charset: Charset) extends VcrStorage {
  def read(): Either[Throwable, Option[VcrEntries]] =
    if (Files.notExists(path)) Right(None)
    else
      for {
        text    <- Try(new String(Files.readAllBytes(path), charset)).toEither
        entries <- codec.decode(text)
      } yield Some(entries)

  /** Writes to a temporary file first so that readers never see a partially written file. */
  def write(entries: VcrEntries): Unit = {
    val directory = Option(path.toAbsolutePath.getParent).getOrElse(Paths.get(""))
    Files.createDirectories(directory)

    // Short, for Windows path limits. Not `Files.createTempFile`, whose files (and so recordings) are owner-only.
    val temp = directory.resolve(f".vcr${ThreadLocalRandom.current().nextLong()}%016x.tmp")
    try {
      Files.write(temp, codec.encode(entries).getBytes(charset), StandardOpenOption.CREATE_NEW)
      try Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
      catch {
        case _: AtomicMoveNotSupportedException => Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
      }
      ()
    } finally Files.deleteIfExists(temp)
  }
}

object FileVcrStorage {
  def apply(recordingPath: Path, codec: Codec, charset: Charset = StandardCharsets.UTF_8): FileVcrStorage =
    new FileVcrStorage(recordingPath, codec, charset)
}
