package bekindrewind

import scala.concurrent.duration.FiniteDuration

final case class RecordOptions(
  notRecordedThrowsErrors: Boolean,
  overwriteAll: Boolean,
  expiresAfter: Option[FiniteDuration]
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
