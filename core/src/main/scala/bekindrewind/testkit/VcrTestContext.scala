package bekindrewind.testkit

import bekindrewind.storage.SharedRecordingStorage
import bekindrewind.{ RecordOptions, VcrRecording, VcrSettings }

import java.nio.file.Path

/**
 * Everything needed to create a VCR client for a single test.
 *
 * {{{
 * ctx => VcrBackend(backend, ctx.settings)
 * }}}
 *
 * @param path
 *   The recording file. Shared with other tests of the suite when using [[VcrLayout.PerSuite]].
 * @param storage
 *   The test's storage. Every client created with it shares the test's [[recording]].
 * @param recordOptions
 *   The suite's record options, which the test's recording uses. Clients can't override them.
 */
final case class VcrTestContext(
  suiteName: String,
  testName: String,
  path: Path,
  storage: SharedRecordingStorage,
  recordOptions: RecordOptions
) {
  def recording: VcrRecording = storage.recording

  /** The test's storage and record options with the default matcher. Use `withMatcher` to change it. */
  def settings: VcrSettings = VcrSettings(storage, recordOptions)
}
