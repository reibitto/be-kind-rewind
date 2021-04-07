package bekindrewind.util

import bekindrewind.VcrRecords
import io.circe.syntax._

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

object VcrIO {
  def read(path: Path): Either[Throwable, VcrRecords] =
    for {
      text    <- Try(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).toEither
      json    <- io.circe.parser.parse(text)
      records <- json.as[VcrRecords]
    } yield records

  def write(path: Path, records: VcrRecords): Unit = {
    // Ensure directory exists
    Option(path.getParent).foreach { directory =>
      Files.createDirectories(directory)
    }

    Files.write(path, records.asJson.spaces2.getBytes(StandardCharsets.UTF_8))
  }
}
