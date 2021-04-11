package bekindrewind.storage

import bekindrewind.VcrEntries
import bekindrewind.codec.Codec

import java.nio.charset.{ Charset, StandardCharsets }
import java.nio.file.{ Files, Path }
import scala.util.Try

class FileVcrStorage(path: Path, codec: Codec, charset: Charset) extends VcrStorage {
  def read(): Either[Throwable, VcrEntries] =
    for {
      text    <- Try(new String(Files.readAllBytes(path), charset)).toEither
      entries <- codec.decode(text)
    } yield entries

  def write(entries: VcrEntries): Unit = {
    // Ensure directory exists
    Option(path.getParent).foreach { directory =>
      Files.createDirectories(directory)
    }

    Files.write(path, codec.encode(entries).getBytes(charset))
  }
}

object FileVcrStorage {
  def apply(recordingPath: Path, codec: Codec, charset: Charset = StandardCharsets.UTF_8): FileVcrStorage =
    new FileVcrStorage(recordingPath, codec, charset)
}
