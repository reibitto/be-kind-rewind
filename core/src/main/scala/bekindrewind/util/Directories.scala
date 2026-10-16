package bekindrewind.util

import java.nio.file.{ Files, Path }
import java.util.stream.{ Stream => JStream }
import scala.jdk.CollectionConverters._
import scala.util.{ Try, Using }

private[bekindrewind] object Directories {

  /** The directory's entries, sorted. Empty if it doesn't exist. */
  def list(directory: Path): Either[Throwable, Vector[Path]] =
    if (Files.isDirectory(directory)) sorted(Files.list(directory)) else Right(Vector.empty)

  /** The directory and everything below it, sorted. */
  def walk(directory: Path): Either[Throwable, Vector[Path]] = sorted(Files.walk(directory))

  // The streams hold open directory handles, so they must be closed.
  private def sorted(paths: => JStream[Path]): Either[Throwable, Vector[Path]] =
    Try(Using.resource(paths)(_.iterator.asScala.toVector.sorted)).toEither
}
