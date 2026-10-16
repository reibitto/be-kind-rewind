package bekindrewind.storage

import bekindrewind.VcrEntries
import bekindrewind.codec.Codec

import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.{ AtomicMoveNotSupportedException, Files, Path, Paths, StandardCopyOption }
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

    // A short name, since a long recording name plus the random suffix could exceed Windows path limits.
    val temp = Files.createTempFile(directory, ".vcr", ".tmp")
    try {
      Files.write(temp, codec.encode(entries).getBytes(charset))
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
