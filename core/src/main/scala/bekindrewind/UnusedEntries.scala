package bekindrewind

/**
 * What to do with previously recorded entries that were never replayed, for example because the test no longer makes
 * that request. Nothing happens if the test failed, since unused entries are expected then.
 */
sealed trait UnusedEntries

object UnusedEntries {
  case object Ignore extends UnusedEntries

  /** Print the unused entries to stderr. */
  case object Warn extends UnusedEntries

  /** Throw a [[VcrException]] when saving, after new entries are saved. Useful on CI to catch stale recordings. */
  case object Fail extends UnusedEntries

  /**
   * Remove unused entries from the recording. Only applies in `NewEpisodes` mode and when the storage belongs to a
   * single test (see `VcrStorage.withTestName`), since entries used by other tests would look unused. Otherwise behaves
   * like `Warn`.
   */
  case object Prune extends UnusedEntries
}
