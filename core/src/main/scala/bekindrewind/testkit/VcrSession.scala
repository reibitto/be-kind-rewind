package bekindrewind.testkit

import bekindrewind.storage.{ SharedRecordingStorage, VcrStorage }
import bekindrewind.{ RecordOptions, VcrClient, VcrEntries, VcrMatcher, VcrRecording }

import java.util.concurrent.atomic.AtomicReference

/**
 * Tracks the running test of a suite, so that clients created outside of tests (for example by dependency injection and
 * shared by every test) record to the running test. Unlike a thread-local, this works no matter which thread or fiber
 * makes the request, but it means the suite's tests must run one at a time.
 *
 * Create clients with [[storage]] (or [[client]]), and have the test framework integration start and finish tests
 * (`VcrSessionSuite` for munit and ScalaTest, `VcrSessionSpec` for zio-test).
 */
final class VcrSession(val suiteName: String) {
  private val active = new AtomicReference[Option[VcrTestContext]](None)

  def start(context: VcrTestContext): Unit =
    if (!active.compareAndSet(None, Some(context)))
      throw new IllegalStateException(
        s"VCR test `${context.testName}` started while `${current.fold("another test")(_.testName)}` is still " +
          s"running in $suiteName. Tests using a VcrSession must run one at a time."
      )

  /** Saves the running test's recording. */
  def finish(succeeded: Boolean): Unit =
    active.getAndSet(None).foreach(_.recording.save(succeeded))

  /** Saves the test's recording if it's still the running test. Does nothing otherwise (e.g. after it timed out). */
  def finish(context: VcrTestContext, succeeded: Boolean): Unit = {
    val running = active.get
    if (running.exists(_ eq context) && active.compareAndSet(running, None)) context.recording.save(succeeded)
  }

  def current: Option[VcrTestContext] = active.get()

  def context: VcrTestContext =
    current.getOrElse(throw new IllegalStateException(s"No VCR test is running in $suiteName"))

  /** Storage that always refers to the running test's recording. The test's record options are used. */
  val storage: VcrStorage = new SharedRecordingStorage {
    override def recording: VcrRecording = context.recording

    override def currentRecording: Option[VcrRecording] = current.map(_.recording)

    override def read(): Either[Throwable, Option[VcrEntries]] = context.storage.read()

    override def write(entries: VcrEntries): Unit = context.storage.write(entries)

    override def testName: Option[String] = current.map(_.testName)
  }

  /** A client for adapting HTTP libraries that aren't supported out of the box. It records to the running test. */
  def client(matcher: VcrMatcher): VcrClient = VcrClient(storage, matcher, RecordOptions.default)
}
