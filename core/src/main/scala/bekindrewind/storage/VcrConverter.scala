package bekindrewind.storage

import bekindrewind.VcrEntries
import bekindrewind.codec.Codec

import bekindrewind.util.{ Directories, Eithers }

import java.nio.file.{ FileAlreadyExistsException, Files, Path }
import scala.util.Try

/**
 * Converts recordings between formats (such as JSON and YAML) without re-recording them. Converting with the same codec
 * rewrites recordings in the codec's current output format.
 */
object VcrConverter {

  def convert(text: String, from: Codec, to: Codec): Either[Throwable, String] =
    from.decode(text).map(to.encode)

  /**
   * Converts a recording file. The new file is written next to it with `to`'s extension, unless the extensions are the
   * same, in which case the file is rewritten in place.
   *
   * @return
   *   The path of the converted file.
   */
  def convertFile(source: Path, from: Codec, to: Codec, deleteSource: Boolean = false): Either[Throwable, Path] =
    for {
      entries <- read(source, from)
      target  <- targetPath(source, from, to)
      _       <- write(entries, source, target, to, deleteSource)
    } yield target

  /**
   * Converts every recording with `from`'s extension in the directory and its subdirectories. All files are read before
   * any are written, so nothing is converted if any of them can't be read or a converted file would overwrite another.
   *
   * @return
   *   The converted files, as `source -> target`.
   */
  def convertDirectory(
    directory: Path,
    from: Codec,
    to: Codec,
    deleteSources: Boolean = false
  ): Either[Throwable, Vector[(Path, Path)]] = {
    val extension = s".${from.fileExtension}"

    for {
      paths     <- Directories.walk(directory)
      sources    = paths.filter(p => Files.isRegularFile(p) && p.toString.endsWith(extension))
      converted <- Eithers.traverse(sources) { source =>
                     for {
                       entries <- read(source, from)
                       target  <- targetPath(source, from, to)
                     } yield (source, target, entries)
                   }
      _         <- Eithers.traverse(converted) { case (source, target, entries) =>
                     write(entries, source, target, to, deleteSources)
                   }
    } yield converted.map { case (source, target, _) => source -> target }
  }

  private def read(source: Path, codec: Codec): Either[Throwable, VcrEntries] =
    FileVcrStorage(source, codec)
      .read()
      .flatMap {
        case Some(entries) => Right(entries)
        case None          => Left(new IllegalArgumentException(s"$source doesn't exist"))
      }
      .left
      .map(e => new IllegalArgumentException(s"Couldn't read $source: ${e.getMessage}", e))

  private def targetPath(source: Path, from: Codec, to: Codec): Either[Throwable, Path] = {
    val name   = source.getFileName.toString
    val base   = name.stripSuffix(s".${from.fileExtension}")
    val target = source.resolveSibling(s"$base.${to.fileExtension}")

    if (target != source && Files.exists(target)) Left(new FileAlreadyExistsException(target.toString))
    else Right(target)
  }

  private def write(
    entries: VcrEntries,
    source: Path,
    target: Path,
    to: Codec,
    deleteSource: Boolean
  ): Either[Throwable, Unit] =
    Try {
      FileVcrStorage(target, to).write(entries)
      if (deleteSource && target != source) Files.delete(source)
    }.toEither
}
