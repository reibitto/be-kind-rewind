package bekindrewind

import java.time.Duration

final case class RecordOptions(
  notRecordedThrowsErrors: Boolean,
  overwriteAll: Boolean,
  expiresAfter: Option[Duration]
) {
  def notRecordedThrowsErrors(notRecordedThrowsErrors: Boolean): RecordOptions =
    copy(notRecordedThrowsErrors = notRecordedThrowsErrors)

  def overwriteAll(overwriteAll: Boolean): RecordOptions =
    copy(overwriteAll = overwriteAll)
}

object RecordOptions {
  val default: RecordOptions = RecordOptions(notRecordedThrowsErrors = false, overwriteAll = false, expiresAfter = None)

  val off: RecordOptions = RecordOptions(notRecordedThrowsErrors = false, overwriteAll = false, expiresAfter = None)
}
