package bekindrewind.storage

import bekindrewind.{ RecordOptions, VcrEntries, VcrRecording }

/** Storage whose clients all share one [[VcrRecording]]. See [[bekindrewind.VcrClient.recording]]. */
trait SharedRecordingStorage extends VcrStorage {
  def recording: VcrRecording

  /** The recording if one is available right now (e.g. `None` when no test of a `VcrSession` is running). */
  def currentRecording: Option[VcrRecording] = Some(recording)
}

object SharedRecordingStorage {
  def apply(underlying: VcrStorage, recordOptions: RecordOptions): SharedRecordingStorage =
    new SharedRecordingStorage {
      override val recording: VcrRecording = new VcrRecording(underlying, recordOptions)

      override def read(): Either[Throwable, Option[VcrEntries]] = underlying.read()

      override def write(entries: VcrEntries): Unit = underlying.write(entries)

      override def testName: Option[String] = underlying.testName
    }
}
