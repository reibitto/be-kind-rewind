package bekindrewind.util

import bekindrewind.VcrEntries
import io.circe.syntax._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

object VcrIO {
  def read(path: Path): Either[Throwable, VcrEntries] =
    for {
      text    <- Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither
      json    <- io.circe.parser.parse(text)
      entries <- json.as[VcrEntries]
    } yield entries

  def write(path: Path, entries: VcrEntries): Unit = {
    // Ensure directory exists
    Option(path.getParent).foreach { directory =>
      Files.createDirectories(directory)
    }

    Files.write(path, entries.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
  }
}
