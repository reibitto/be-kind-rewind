package bekindrewind

import bekindrewind.storage.VcrStorage

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.collection.immutable

/**
 * The state of one recording while it's in use: the entries loaded from storage, how far each group of requests has
 * been replayed, and newly recorded entries. Clients recording to the same storage during a test should share one
 * recording (see [[storage.SharedRecordingStorage]]), otherwise each would overwrite the others' new entries on save.
 *
 * Clients with different matchers can share a recording. Each matcher groups the entries its own way, and an entry is
 * replayed at most once no matter which client's matcher found it.
 */
final class VcrRecording(val storage: VcrStorage, val recordOptions: RecordOptions) {

  /** Entries loaded from storage. `None` if nothing was loaded (missing, empty, expired, or in `RecordAll` mode). */
  lazy val loaded: Option[VcrEntries] =
    if (recordOptions.mode == RecordMode.RecordAll || expired) None else stored

  private lazy val stored: Option[VcrEntries] =
    storage.read() match {
      case Left(error)    => throw new IllegalStateException("Failed to read VCR entries from storage", error)
      case Right(entries) => entries.filter(_.entries.nonEmpty)
    }

  // Entries expire together so that a test never mixes old and newly recorded responses.
  private lazy val expired: Boolean = {
    val now = OffsetDateTime.now()
    recordOptions.expiresAfter.exists { duration =>
      stored.exists(_.entries.exists(_.recordedAt.plus(duration).isBefore(now)))
    }
  }

  private val groupsByMatcher = TrieMap.empty[VcrMatcher, Map[VcrKey, StatefulVcrEntries]]

  private val newlyRecordedRef = new AtomicReference(Vector.empty[VcrEntry])

  // Positions (in `loaded`) of the entries that have been replayed. Positions rather than entries, since identical
  // entries can be recorded more than once.
  private val replayed = new AtomicReference(Set.empty[Int])

  private val lastSaved = new AtomicReference[Option[VcrEntries]](None)

  /** Loaded entries grouped by the matcher, along with how far each group has been replayed. */
  def groups(matcher: VcrMatcher): Map[VcrKey, StatefulVcrEntries] =
    groupsByMatcher.getOrElseUpdate(
      matcher,
      loaded.fold(Map.empty[VcrKey, StatefulVcrEntries]) { entries =>
        entries.entries.zipWithIndex.groupBy { case (entry, _) => matcher.group(entry.request) }.map {
          case (key, group) => key -> StatefulVcrEntries(group.map(_._1), group.map(_._2))
        }
      }
    )

  def addNewEntry(matcher: VcrMatcher, entry: VcrEntry): Unit = {
    val transformed = matcher.transform(entry)
    newlyRecordedRef.updateAndGet(_ :+ transformed)
    ()
  }

  def newlyRecorded(): immutable.Seq[VcrEntry] = newlyRecordedRef.get()

  /**
   * Finds the recorded entry to replay for the given request. Identical requests are replayed in the order they were
   * recorded. Returns `None` once all recorded entries for that request have been replayed.
   */
  def findMatch(matcher: VcrMatcher, request: VcrRequest): Option[VcrEntry] =
    matcher.group(request) match {
      case VcrKey.Ungrouped => None
      case key              =>
        groups(matcher).get(key).flatMap(claimNext)
    }

  /** Marks the group's first entry that hasn't been replayed yet (by any matcher) as replayed, and returns it. */
  @tailrec
  private def claimNext(group: StatefulVcrEntries): Option[VcrEntry] = {
    val current = replayed.get
    group.positions.zip(group.entries).find { case (position, _) => !current.contains(position) } match {
      case None                                                                           => None
      case Some((position, entry)) if replayed.compareAndSet(current, current + position) => Some(entry)
      case Some(_)                                                                        => claimNext(group)
    }
  }

  /** Decides whether the request should be replayed, recorded, sent as is, or rejected. */
  def resolve(matcher: VcrMatcher, request: VcrRequest): VcrAction =
    if (!matcher.shouldRecord(request)) {
      if (recordOptions.notRecordedThrowsErrors) VcrAction.Fail(VcrClient.notRecordedError(matcher.redact(request)))
      else VcrAction.PassThrough
    } else if (recordOptions.mode == RecordMode.RecordAll) VcrAction.Record
    else
      findMatch(matcher, request) match {
        case Some(entry) => VcrAction.Replay(entry)
        case None        =>
          recordOptions.mode match {
            case RecordMode.Once if loaded.isEmpty => VcrAction.Record
            case RecordMode.NewEpisodes            => VcrAction.Record
            case mode                              =>
              VcrAction.Fail(VcrClient.noMatchError(matcher.redact(request), mode, expired))
          }
      }

  /**
   * Like [[resolve]], for requests that can't be recorded (such as ones with streaming bodies). They're sent as is
   * unless the record mode only allows replaying or `notRecordedThrowsErrors` is set.
   */
  def resolveUnrecordable(matcher: VcrMatcher, request: VcrRequest, reason: String): VcrAction =
    if (!matcher.shouldRecord(request)) resolve(matcher, request)
    else {
      val replayOnly = recordOptions.mode match {
        case RecordMode.ReplayOnly => true
        case RecordMode.Once       => loaded.isDefined
        case _                     => false
      }

      if (replayOnly || recordOptions.notRecordedThrowsErrors)
        VcrAction.Fail(VcrClient.unrecordableError(matcher.redact(request), reason))
      else VcrAction.PassThrough
    }

  /**
   * Previously recorded entries that haven't been replayed (so far), in the order they were recorded. This includes
   * entries that no matcher accepts, since those can never be replayed.
   */
  def unusedEntries(): Vector[VcrEntry] = unusedPositions().map(_._1)

  private def unusedPositions(): Vector[(VcrEntry, Int)] = {
    val used = replayed.get
    loaded.fold(Vector.empty[(VcrEntry, Int)])(_.entries.zipWithIndex.filterNot { case (_, i) => used.contains(i) })
  }

  /**
   * Saves new entries and handles unused entries (see `RecordOptions.unusedEntries`). Saving again without changes does
   * nothing, so it's safe for every client sharing the recording to call this.
   *
   * @param succeeded
   *   Whether the test using this recording succeeded. Unused entries are only reported or pruned when it did.
   */
  def save(succeeded: Boolean): Unit = synchronized {
    val newEntries = newlyRecordedRef.get
    val previous   = loaded.fold(Vector.empty[VcrEntry])(_.entries)
    val unusedAt   = unusedPositions()
    val unused     = if (succeeded) unusedAt.map(_._1) else Vector.empty

    val prune = recordOptions.unusedEntries == UnusedEntries.Prune &&
      recordOptions.mode == RecordMode.NewEpisodes &&
      storage.testName.isDefined

    val kept    =
      if (prune && unused.nonEmpty) {
        val removed = unusedAt.map(_._2).toSet
        previous.zipWithIndex.collect { case (entry, i) if !removed.contains(i) => entry }
      } else previous
    val updated = VcrEntries(kept ++ newEntries, BuildInfo.version)

    if (!lastSaved.get.contains(updated)) {
      // `RecordAll` doesn't load entries, so it only writes to clear old entries when there are some.
      val shouldWrite = newEntries.nonEmpty ||
        (recordOptions.mode == RecordMode.RecordAll && storage.read().fold(_ => true, _.exists(_.entries.nonEmpty))) ||
        loaded.exists(_.copy(version = updated.version) != updated)

      if (shouldWrite) storage.write(updated)
      lastSaved.set(Some(updated))

      if (unused.nonEmpty) reportUnused(unused, pruned = prune)
    }
  }

  private def reportUnused(unused: Vector[VcrEntry], pruned: Boolean): Unit = {
    def message(verb: String) = {
      val test = storage.testName.fold("")(name => s" by test `$name`")
      val list = unused.map(e => s"  ${e.request.method} ${e.request.uri}").mkString("\n")
      s"${unused.size} recorded VCR entries were not used$test and $verb:\n$list"
    }

    recordOptions.unusedEntries match {
      case UnusedEntries.Ignore                     => ()
      case UnusedEntries.Fail                       => throw new VcrException(message("the test was configured to fail"))
      case UnusedEntries.Prune if pruned            => System.err.println(s"[be-kind-rewind] ${message("were removed")}")
      case UnusedEntries.Warn | UnusedEntries.Prune =>
        System.err.println(s"[be-kind-rewind] ${message("could be removed")}")
    }
  }
}
