package bekindrewind.testkit

import bekindrewind.codec.Codec
import bekindrewind.storage.{ FileVcrStorage, SharedRecordingStorage }
import bekindrewind.{ RecordMode, RecordOptions, VcrClient }

import java.nio.file.{ Files, Path, Paths }
import scala.collection.concurrent.TrieMap

/** Settings shared by the test framework integrations. Override the members to customize them. */
trait VcrTestSupport {

  /**
   * Directory that recordings are stored in (see [[vcrLayout]] for how files are named). Defaults to
   * `src/test/resources/vcr` in the suite's module, found from where its compiled classes are (see
   * `VcrTestSupport.moduleDirectory`), so it doesn't depend on the directory tests run in.
   */
  def vcrDirectory: Path =
    VcrTestSupport.moduleDirectory(getClass).resolve(Paths.get("src", "test", "resources", "vcr"))

  /** Whether to use one recording file per test (the default) or one per suite. */
  def vcrLayout: VcrLayout = VcrLayout.PerTest

  /**
   * How per-test recording files are named (the test name by default). Suite directories and per-suite files are named
   * after the suite, with the same length limit.
   */
  def vcrFileNaming: VcrFileNaming = VcrFileNaming.ByTestName()

  def vcrCodec: Codec

  def vcrSuiteName: String = getClass.getSimpleName.stripSuffix("$")

  /**
   * The record mode unless the `vcr.record.mode` system property or `VCR_RECORD_MODE` environment variable says
   * otherwise. For example `ReplayOnly`, so that tests never send real requests unless asked to with
   * `VCR_RECORD_MODE=new-episodes`.
   */
  def vcrDefaultRecordMode: RecordMode = RecordMode.NewEpisodes

  /** Record options for every test in the suite. See [[vcrDefaultRecordMode]] for the record mode. */
  def vcrRecordOptions: RecordOptions =
    RecordOptions.default.mode(VcrTestSupport.recordModeOverride.getOrElse(vcrDefaultRecordMode))

  /** Tracks the running test for clients created outside of tests. See [[VcrSession]]. */
  lazy val vcrSession: VcrSession = new VcrSession(vcrSuiteName)

  /**
   * The context for the named test. While that test is the running [[VcrSession]] test, its context is returned, so
   * that clients created for the test share the session's recording.
   */
  def vcrContext(testName: String): VcrTestContext =
    vcrSession.current.filter(_.testName == testName).getOrElse(newVcrContext(testName))

  /**
   * Saves a client created for a test, unless it records to the running `VcrSession` test, which saves when it ends.
   */
  protected def vcrSave(client: VcrClient, succeeded: Boolean): Unit =
    if (!vcrSession.current.exists(_.recording eq client.recording)) client.save(succeeded)

  private def newVcrContext(testName: String): VcrTestContext = {
    val naming    = vcrFileNaming
    val extension = vcrCodec.fileExtension

    // Per-test files are scoped too, so that tests whose names map to the same file name can't affect each other.
    val (path, storage) = vcrLayout match {
      case VcrLayout.PerTest  =>
        val suiteDir = VcrFileNaming.safeName(vcrSuiteName, "", naming.maxLength)
        val path     = vcrDirectory
          .resolve(suiteDir)
          .resolve(naming.fileName(VcrFileNaming.Input(vcrSuiteName, testName), extension))
        (path, VcrTestSupport.sharedStorage(path, vcrCodec).scopedToTestIncludingUnlabeled(testName))
      case VcrLayout.PerSuite =>
        val path = vcrDirectory.resolve(VcrFileNaming.safeName(vcrSuiteName, s".$extension", naming.maxLength))
        (path, VcrTestSupport.sharedStorage(path, vcrCodec).scopedToTest(testName))
    }

    val recordOptions = vcrRecordOptions
    VcrTestContext(vcrSuiteName, testName, path, SharedRecordingStorage(storage, recordOptions), recordOptions)
  }
}

object VcrTestSupport {
  // Tests sharing a file must share the storage instance, since access to it is synchronized on that instance.
  private val storages = TrieMap.empty[Path, FileVcrStorage]

  private def sharedStorage(path: Path, codec: Codec): FileVcrStorage =
    storages.getOrElseUpdate(path.toAbsolutePath.normalize, FileVcrStorage(path.toAbsolutePath.normalize, codec))

  /**
   * The base directory of the module that `cls` belongs to: the `vcr.base.directory` system property if set, otherwise
   * the directory containing the build output the class was loaded from (`<module>/target/...` for sbt and Maven,
   * `<module>/build/classes/...` for Gradle), otherwise the working directory.
   */
  def moduleDirectory(cls: Class[_]): Path =
    sys.props
      .get("vcr.base.directory")
      .map(Paths.get(_))
      .orElse(buildOutputModule(cls))
      .getOrElse(Paths.get("").toAbsolutePath)

  private def buildOutputModule(cls: Class[_]): Option[Path] =
    scala.util
      .Try(Paths.get(cls.getProtectionDomain.getCodeSource.getLocation.toURI))
      .toOption
      .flatMap { location =>
        Iterator
          .unfold(Option(location))(_.map(path => (path, Option(path.getParent))))
          .sliding(2)
          .collectFirst {
            case Seq(output, module) if output.getFileName.toString == "target" => module
            case Seq(output, module)
                if output.getFileName.toString == "build" &&
                  location.startsWith(output.resolve("classes")) =>
              module
          }
      }
      .filter(module => Files.isDirectory(module.resolve("src")))

  def recordModeOverride: Option[RecordMode] =
    sys.props
      .get("vcr.record.mode")
      .orElse(sys.env.get("VCR_RECORD_MODE"))
      .map { name =>
        RecordMode
          .fromName(name)
          .getOrElse(
            throw new IllegalArgumentException(s"Unknown record mode: $name. Valid: ${RecordMode.all.mkString(", ")}")
          )
      }
}
