package bekindrewind

/** Determines when requests are replayed from storage and when they are sent and recorded. */
sealed trait RecordMode

object RecordMode {

  /** Replay recorded requests and record new ones. */
  case object NewEpisodes extends RecordMode

  /** Record everything if nothing was recorded yet. Otherwise only replay and fail on unrecorded requests. */
  case object Once extends RecordMode

  /** Only replay. Unrecorded requests fail instead of being sent. Useful for CI. */
  case object ReplayOnly extends RecordMode

  /** Ignore previous recordings and re-record everything. */
  case object RecordAll extends RecordMode

  val all: Seq[RecordMode] = Seq(NewEpisodes, Once, ReplayOnly, RecordAll)

  /** Parses names like `ReplayOnly`, `replay-only` or `replay_only`. */
  def fromName(name: String): Option[RecordMode] = {
    def normalize(s: String) = s.replaceAll("[-_ ]", "").toLowerCase
    all.find(mode => normalize(mode.toString) == normalize(name))
  }
}
