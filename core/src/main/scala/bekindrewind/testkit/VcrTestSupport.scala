package bekindrewind.testkit

import bekindrewind.codec.Codec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.{ RecordMode, RecordOptions }

import java.nio.file.{ Path, Paths }
import java.util.concurrent.ConcurrentHashMap

/** Settings shared by the test framework integrations. Override the members to customize them. */
trait VcrTestSupport {

  /** Directory that recordings are stored in. See [[vcrLayout]] for how files are named. */
  def vcrDirectory: Path = Paths.get("src", "test", "resources", "vcr")

  /** Whether to use one recording file per test (the default) or one per suite. */
  def vcrLayout: VcrLayout = VcrLayout.PerTest

  def vcrCodec: Codec

  def vcrSuiteName: String = getClass.getSimpleName.stripSuffix("$")

  /**
   * Record options for every test in the suite. The record mode can be overridden with the `vcr.record.mode` system
   * property or `VCR_RECORD_MODE` environment variable (e.g. `VCR_RECORD_MODE=replay-only` on CI).
   */
  def vcrRecordOptions: RecordOptions =
    VcrTestSupport.recordModeOverride.fold(RecordOptions.default)(RecordOptions.default.mode)

  def vcrContext(testName: String): VcrTestContext = {
    val suiteFile = VcrTestContext.fileName(vcrSuiteName)
    val extension = vcrCodec.fileExtension

    vcrLayout match {
      case VcrLayout.PerTest  =>
        val path = vcrDirectory.resolve(suiteFile).resolve(s"${VcrTestContext.fileName(testName)}.$extension")
        VcrTestContext(
          vcrSuiteName,
          testName,
          path,
          FileVcrStorage(path, vcrCodec).withTestName(testName),
          vcrRecordOptions
        )
      case VcrLayout.PerSuite =>
        val path = vcrDirectory.resolve(s"$suiteFile.$extension")
        VcrTestContext(
          vcrSuiteName,
          testName,
          path,
          VcrTestSupport.sharedStorage(path, vcrCodec).scopedToTest(testName),
          vcrRecordOptions
        )
    }
  }
}

object VcrTestSupport {
  // Tests sharing a suite file must share the storage instance, since access to it is synchronized on that instance.
  private val suiteStorages = new ConcurrentHashMap[Path, FileVcrStorage]()

  private def sharedStorage(path: Path, codec: Codec): FileVcrStorage =
    suiteStorages.computeIfAbsent(path.toAbsolutePath.normalize, p => FileVcrStorage(p, codec))

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
