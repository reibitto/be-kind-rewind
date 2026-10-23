package bekindrewind.testkit

import bekindrewind.codec.Codec
import bekindrewind.storage.{ FileVcrStorage, SharedRecordingStorage }
import bekindrewind.{ RecordMode, RecordOptions, VcrClient }

import java.nio.file.{ Files, Path, Paths }
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

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

  // Contexts of tests with clients opened by `vcrOpen` that aren't closed yet. Guarded by itself.
  private val openVcrContexts = mutable.Map.empty[String, VcrTestSupport.OpenContext]

  /**
   * The context for the named test. While the test has clients open (see [[vcrOpen]]) or is the running [[VcrSession]]
   * test, that context is returned, so that clients created for the test share its recording.
   */
  def vcrContext(testName: String): VcrTestContext =
    vcrSession.current
      .filter(_.testName == testName)
      .orElse(openVcrContexts.synchronized(openVcrContexts.get(testName)).map(_.context))
      .getOrElse(newVcrContext(testName))

  /**
   * Creates a client for the named test, returning it with a function that closes it (given whether the test
   * succeeded). Clients of the same test that are open at the same time, such as nested `withVcr` calls, share one
   * recording, which is saved when the last of them is closed. Recordings of the running `VcrSession` test are left for
   * the session to save.
   */
  protected def vcrOpen[C <: VcrClient](testName: String)(create: VcrTestContext => C): (C, Boolean => Unit) = {
    val session                             = vcrSession.current.filter(_.testName == testName)
    val context                             = session.getOrElse(retainVcrContext(testName))
    val release: Boolean => Option[Boolean] =
      if (session.isDefined) _ => None else releaseVcrContext(context, _)
    val client                              = vcrOnFailure(create(context))(release(false))
    val closed                              = new AtomicBoolean(false)
    val close: Boolean => Unit              = succeeded =>
      if (closed.compareAndSet(false, true)) {
        val testSucceeded = release(succeeded)
        // Clients created with storage other than the context's have a recording of their own.
        try testSucceeded.foreach(context.recording.save)
        finally if (!(client.storage eq context.storage)) client.save(succeeded)
      }
    (client, close)
  }

  private def retainVcrContext(testName: String): VcrTestContext =
    openVcrContexts.synchronized {
      val open = openVcrContexts.getOrElseUpdate(testName, new VcrTestSupport.OpenContext(newVcrContext(testName)))
      open.clients += 1
      open.context
    }

  // Returns whether every client of the test succeeded, if this was the last one open.
  private def releaseVcrContext(context: VcrTestContext, succeeded: Boolean): Option[Boolean] =
    openVcrContexts.synchronized {
      openVcrContexts.get(context.testName).filter(_.context eq context).flatMap { open =>
        open.clients -= 1
        open.failed = open.failed || !succeeded
        if (open.clients > 0) None
        else {
          openVcrContexts -= context.testName
          Some(!open.failed)
        }
      }
    }

  /**
   * Runs `body`. If it throws, runs `onFailure` (such as saving the recording as failed) and rethrows, keeping any
   * error from `onFailure` as a suppressed exception.
   */
  protected def vcrOnFailure[A](body: => A)(onFailure: => Unit): A =
    Try(body).fold(
      error => {
        Try(onFailure).failed.foreach(error.addSuppressed)
        throw error
      },
      identity
    )

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
  private final class OpenContext(val context: VcrTestContext) {
    var clients: Int    = 0
    var failed: Boolean = false
  }

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
    Try(Paths.get(cls.getProtectionDomain.getCodeSource.getLocation.toURI)).toOption.flatMap { location =>
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
