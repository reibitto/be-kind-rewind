package bekindrewind.testkit

import bekindrewind.codec.Codec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.util.{ Directories, Eithers }

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.security.MessageDigest
import scala.util.Try

/**
 * How per-test recording files are named. Whatever the strategy returns is made safe for every OS: only letters,
 * digits, `.`, `_` and `-` are kept, Windows device names (`CON`, `NUL`, …) are avoided, and names longer than
 * `maxLength` (including the extension) are truncated and get a hash of the full name so they stay unique.
 */
sealed trait VcrFileNaming {

  /** Maximum length of a file or directory name, including the extension. */
  def maxLength: Int

  require(maxLength >= VcrFileNaming.minMaxLength, s"maxLength must be at least ${VcrFileNaming.minMaxLength}")

  protected def name(input: VcrFileNaming.Input): String

  /** The file name for a test, including the extension. */
  def fileName(input: VcrFileNaming.Input, extension: String): String =
    VcrFileNaming.safeName(name(input), s".$extension", maxLength)
}

object VcrFileNaming {

  /**
   * 100 characters leave room for a checkout directory and suite directory within Windows' 260 character path limit.
   */
  val defaultMaxLength: Int = 100

  /** Enough for a few characters, the hash that keeps truncated names unique, and an extension. */
  val minMaxLength: Int = 32

  /** @param suiteName When renaming existing recordings, this is the name of the suite's directory. */
  final case class Input(suiteName: String, testName: String)

  /** The test name, e.g. `gets a user (v2)` becomes `gets-a-user-v2.json`. */
  final case class ByTestName(maxLength: Int = defaultMaxLength) extends VcrFileNaming {
    override protected def name(input: Input): String = input.testName
  }

  /** A hash of the test name. Short and always the same length, but the test name is only visible in the file. */
  final case class ByHash(length: Int = 16, maxLength: Int = defaultMaxLength) extends VcrFileNaming {
    require(length > 0 && length <= 64, "length must be between 1 and 64")

    override protected def name(input: Input): String = hash(input.testName, length)
  }

  /** Names files with a custom function, e.g. to shorten or drop common prefixes of test names. */
  final case class Custom(f: Input => String, maxLength: Int = defaultMaxLength) extends VcrFileNaming {
    override protected def name(input: Input): String = f(input)
  }

  private val hashLength = 8

  // Room kept for the extension (a dot and up to 7 characters), so that names are the same for every format and
  // converting recordings (e.g. from JSON to YAML) keeps them matching their tests.
  private val extensionReserve = 8

  private val windowsDeviceNames =
    Set("CON", "PRN", "AUX", "NUL") ++ (1 to 9).flatMap(i => Seq(s"COM$i", s"LPT$i"))

  /** Turns `name` into a safe name ending with `suffix` (such as `.json`, or empty for directories). */
  def safeName(name: String, suffix: String, maxLength: Int): String = {
    val budget = maxLength - math.max(extensionReserve, suffix.length)
    require(budget > hashLength + 1, s"maxLength $maxLength is too short for names ending with `$suffix`")

    val sanitized = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "") match {
      case ""                                                                  => "unnamed"
      case s if windowsDeviceNames.contains(s.takeWhile(_ != '.').toUpperCase) => s"${s}_"
      case s                                                                   => s
    }

    val base =
      if (sanitized.length <= budget) sanitized
      else s"${sanitized.take(budget - hashLength - 1)}-${hash(name, hashLength)}"

    base + suffix
  }

  private def hash(value: String, length: Int): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
      .take(length)

  /**
   * Renames per-test recordings (`<vcrDirectory>/<suite>/<test>.<extension>`) to the names `naming` gives them, based
   * on the test name stored in each recording. Files without exactly one test name are left alone. Nothing is renamed
   * if any target already exists.
   *
   * @return
   *   The renames, as `source -> target`.
   */
  def renameRecordings(
    vcrDirectory: Path,
    codec: Codec,
    naming: VcrFileNaming,
    dryRun: Boolean = false
  ): Either[Throwable, Vector[(Path, Path)]] = {
    val extension = s".${codec.fileExtension}"

    // The target for a recording that belongs to exactly one test, if it isn't already named that way.
    def rename(suiteDir: Path, file: Path): Either[Throwable, Option[(Path, Path)]] =
      FileVcrStorage(file, codec).read().map { stored =>
        stored.fold(Vector.empty[Option[String]])(_.entries.map(_.testName)).flatten.distinct match {
          case Vector(testName) =>
            val input = Input(suiteDir.getFileName.toString, testName)
            Some(file -> suiteDir.resolve(naming.fileName(input, codec.fileExtension))).filter { case (_, t) =>
              t != file
            }
          case _                => None
        }
      }

    def renamesIn(suiteDir: Path): Either[Throwable, Vector[(Path, Path)]] =
      for {
        files   <- Directories.list(suiteDir)
        renames <- Eithers.traverse(files.filter(_.getFileName.toString.endsWith(extension)))(rename(suiteDir, _))
      } yield renames.flatten

    def checkConflicts(renames: Vector[(Path, Path)]): Either[Throwable, Vector[(Path, Path)]] = {
      val targets   = renames.map(_._2)
      val conflicts = (targets.filter(Files.exists(_)) ++ targets.diff(targets.distinct)).distinct

      if (conflicts.isEmpty) Right(renames)
      else
        Left(
          new IllegalStateException(
            s"Not renaming anything, since these files would be overwritten: ${conflicts.mkString(", ")}"
          )
        )
    }

    for {
      suiteDirs <- Directories.list(vcrDirectory).map(_.filter(Files.isDirectory(_)))
      renames   <- Eithers.traverse(suiteDirs)(renamesIn).map(_.flatten)
      _         <- checkConflicts(renames)
      _         <- if (dryRun) Right(Vector.empty)
                   else Eithers.traverse(renames) { case (source, target) => Try(Files.move(source, target)).toEither }
    } yield renames
  }
}
