package bekindrewind.util

import io.circe.syntax._
import bekindrewind.VcrRecords

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.util.Try

object VcrIO {
  def read(path: Path): Either[Throwable, VcrRecords] =
    for {
      text    <- Try(Files.readString(path, StandardCharsets.UTF_8)).toEither
      json    <- io.circe.parser.parse(text)
      records <- json.as[VcrRecords]
    } yield records

  def write(path: Path, records: VcrRecords): Unit = {
    // Ensure directory exists
    Option(path.getParent).foreach { directory =>
      Files.createDirectories(directory)
    }

    Files.writeString(path, records.asJson.spaces2, StandardCharsets.UTF_8)
  }
}
