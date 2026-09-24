package bekindrewind

import java.time.Duration

/**
 * @param mode
 *   When to replay and when to record. See [[RecordMode]].
 * @param notRecordedThrowsErrors
 *   Fail requests rejected by the matcher's `shouldRecord` instead of sending them without recording.
 * @param expiresAfter
 *   Re-record once the oldest recorded entry (of the test, if the storage belongs to a single test) is this old.
 * @param unusedEntries
 *   What to do with recorded entries that were never replayed. See [[UnusedEntries]].
 */
final case class RecordOptions(
  mode: RecordMode,
  notRecordedThrowsErrors: Boolean,
  expiresAfter: Option[Duration],
  unusedEntries: UnusedEntries = UnusedEntries.Warn
) {
  def unusedEntries(unusedEntries: UnusedEntries): RecordOptions = copy(unusedEntries = unusedEntries)

  def mode(mode: RecordMode): RecordOptions = copy(mode = mode)

  def notRecordedThrowsErrors(notRecordedThrowsErrors: Boolean): RecordOptions =
    copy(notRecordedThrowsErrors = notRecordedThrowsErrors)

  def expiresAfter(expiresAfter: Duration): RecordOptions =
    copy(expiresAfter = Some(expiresAfter))

  @deprecated("Use `mode(RecordMode.RecordAll)` instead.", "0.2.0")
  def overwriteAll(overwriteAll: Boolean): RecordOptions =
    copy(mode = if (overwriteAll) RecordMode.RecordAll else RecordMode.NewEpisodes)
}

object RecordOptions {
  val default: RecordOptions =
    RecordOptions(mode = RecordMode.NewEpisodes, notRecordedThrowsErrors = false, expiresAfter = None)

  @deprecated("Identical to `RecordOptions.default`. Use that instead.", "0.2.0")
  val off: RecordOptions = default
}
