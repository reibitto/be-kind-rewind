package bekindrewind.testkit

import bekindrewind.storage.SharedRecordingStorage
import bekindrewind.{ RecordOptions, VcrRecording }

import java.nio.file.Path

/**
 * Everything needed to create a VCR client for a single test.
 *
 * {{{
 * ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)
 * }}}
 *
 * @param path
 *   The recording file. Shared with other tests of the suite when using [[VcrLayout.PerSuite]].
 * @param storage
 *   The test's storage. Every client created with it shares the test's [[recording]].
 */
final case class VcrTestContext(
  suiteName: String,
  testName: String,
  path: Path,
  storage: SharedRecordingStorage,
  recordOptions: RecordOptions
) {
  def recording: VcrRecording = storage.recording
}
