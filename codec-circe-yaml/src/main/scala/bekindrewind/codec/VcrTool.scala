package bekindrewind.codec

import bekindrewind.storage.VcrConverter
import bekindrewind.testkit.VcrFileNaming

import java.nio.file.Paths

/**
 * Command line tool for maintaining recordings, e.g. from sbt:
 *
 * {{{
 * sbt "Test/runMain bekindrewind.codec.VcrTool convert src/test/resources/vcr yaml json --delete-sources"
 * sbt "Test/runMain bekindrewind.codec.VcrTool rename src/test/resources/vcr json --naming by-hash --dry-run"
 * }}}
 */
object VcrTool {
  private val usage =
    """Usage:
      |  convert <directory> <from: json|yaml> <to: json|yaml> [--delete-sources]
      |      Converts recordings between formats. Converting to the same format rewrites them in the current format.
      |  rename <directory> <json|yaml> [--naming by-test-name[:<max length>]|by-hash[:<length>]] [--dry-run]
      |      Renames per-test recordings (<directory>/<suite>/<test>.<extension>) to match a file naming strategy,
      |      using the test names stored in them. The default is by-test-name with the default max length.""".stripMargin

  def main(args: Array[String]): Unit =
    run(args.toList) match {
      case Right(output) => println(output)
      case Left(error)   =>
        System.err.println(error)
        sys.exit(1)
    }

  def run(args: List[String]): Either[String, String] =
    args match {
      case "convert" :: directory :: from :: to :: flags if flags.forall(_ == "--delete-sources") =>
        for {
          from      <- codec(from)
          to        <- codec(to)
          converted <- VcrConverter
                         .convertDirectory(Paths.get(directory), from, to, deleteSources = flags.nonEmpty)
                         .left
                         .map(_.getMessage)
        } yield s"Converted ${converted.size} recordings" + converted.map { case (s, t) => s"\n  $s -> $t" }.mkString

      case "rename" :: directory :: format :: flags =>
        for {
          codec   <- codec(format)
          naming  <- flags
                       .sliding(2)
                       .collectFirst { case "--naming" :: name :: Nil => name }
                       .fold[Either[String, VcrFileNaming]](
                         Right(VcrFileNaming.ByTestName())
                       )(naming)
          dryRun   = flags.contains("--dry-run")
          renamed <- VcrFileNaming.renameRecordings(Paths.get(directory), codec, naming, dryRun).left.map(_.getMessage)
        } yield s"${if (dryRun) "Would rename" else "Renamed"} ${renamed.size} recordings" +
          renamed.map { case (s, t) => s"\n  $s -> ${t.getFileName}" }.mkString

      case _ => Left(usage)
    }

  private def codec(name: String): Either[String, Codec] =
    name.toLowerCase match {
      case "json"         => Right(JsonCodec)
      case "yaml" | "yml" => Right(YamlCodec)
      case other          => Left(s"Unknown format `$other`. Use json or yaml.")
    }

  private def naming(value: String): Either[String, VcrFileNaming] = {
    def number(n: String) = n.toIntOption.toRight(s"`$n` isn't a number")

    def create(f: => VcrFileNaming) = scala.util.Try(f).toEither.left.map(_.getMessage)

    value.split(":", 2).toList match {
      case "by-test-name" :: Nil      => Right(VcrFileNaming.ByTestName())
      case "by-test-name" :: n :: Nil => number(n).flatMap(n => create(VcrFileNaming.ByTestName(n)))
      case "by-hash" :: Nil           => Right(VcrFileNaming.ByHash())
      case "by-hash" :: n :: Nil      => number(n).flatMap(n => create(VcrFileNaming.ByHash(n)))
      case _                          => Left(s"Unknown naming `$value`. Use by-test-name[:<max length>] or by-hash[:<length>].")
    }
  }
}
