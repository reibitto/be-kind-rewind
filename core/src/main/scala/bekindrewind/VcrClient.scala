package bekindrewind

import bekindrewind.storage.VcrStorage

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable

trait VcrClient {

  /**
   * Used to match HTTP requests with stored VCR entries to determine whether they should be replayed or not.
   */
  def matcher: VcrMatcher

  /**
   * Used to specify recording options such as the record mode or expiring VCR entries after some time.
   */
  def recordOptions: RecordOptions

  /**
   * Responsible for storing/persisting recorded VCR entries to a file and so on (and which format: JSON, YAML, etc.)
   */
  def storage: VcrStorage

  /** Entries loaded from storage. `None` if nothing was loaded (missing, expired, or in `RecordAll` mode). */
  protected[bekindrewind] lazy val loaded: Option[VcrEntries] =
    if (recordOptions.mode == RecordMode.RecordAll) None
    else
      storage.read() match {
        case Left(error)    =>
          throw new IllegalStateException("Failed to read VCR entries from storage", error)
        case Right(entries) =>
          // Entries expire together so that a test never mixes old and newly recorded responses.
          val now = OffsetDateTime.now()
          entries.filterNot { e =>
            recordOptions.expiresAfter.exists(duration => e.entries.exists(_.recordedAt.plus(duration).isBefore(now)))
          }
      }

  protected[bekindrewind] lazy val previouslyRecorded: Map[VcrKey, StatefulVcrEntries] =
    loaded.fold(Map.empty[VcrKey, StatefulVcrEntries]) { entries =>
      entries.entries.groupBy(rec => matcher.group(rec.request)).map { case (key, entries) =>
        key -> StatefulVcrEntries.create(entries)
      }
    }

  private val newlyRecordedRef: AtomicReference[Vector[VcrEntry]] =
    new AtomicReference(Vector.empty)

  def addNewEntry(entry: VcrEntry): Unit = {
    val transformed = matcher.transform(entry)
    newlyRecordedRef.updateAndGet(_ :+ transformed)
    ()
  }

  def newlyRecorded(): immutable.Seq[VcrEntry] = newlyRecordedRef.get()

  /**
   * Finds the recorded entry to replay for the given request. Identical requests are replayed in the order they were
   * recorded. Returns `None` once all recorded entries for that request have been replayed.
   */
  def findMatch(vcrRequest: VcrRequest): Option[VcrEntry] =
    matcher.group(vcrRequest) match {
      case VcrKey.Ungrouped => None
      case key              =>
        previouslyRecorded.get(key).flatMap { entries =>
          entries.entries.lift(entries.currentIndex.getAndIncrement())
        }
    }

  /** Decides whether the request should be replayed, recorded, sent as is, or rejected. */
  def resolve(vcrRequest: VcrRequest): VcrAction =
    if (!matcher.shouldRecord(vcrRequest)) {
      if (recordOptions.notRecordedThrowsErrors) VcrAction.Fail(VcrClient.notRecordedError(vcrRequest))
      else VcrAction.PassThrough
    } else if (recordOptions.mode == RecordMode.RecordAll) VcrAction.Record
    else
      findMatch(vcrRequest) match {
        case Some(entry) => VcrAction.Replay(entry)
        case None        =>
          recordOptions.mode match {
            case RecordMode.Once if loaded.isEmpty => VcrAction.Record
            case RecordMode.NewEpisodes            => VcrAction.Record
            case _                                 => VcrAction.Fail(VcrClient.noMatchError(vcrRequest, recordOptions.mode))
          }
      }

  /**
   * Previously recorded entries that haven't been replayed (so far), in the order they were recorded. This includes
   * entries the matcher no longer accepts, since those can never be replayed.
   */
  def unusedEntries(): Vector[VcrEntry] = {
    val unused = unusedSet()
    loaded.fold(Vector.empty[VcrEntry])(_.entries.filter(unused.contains))
  }

  private def unusedSet(): java.util.Set[VcrEntry] = {
    // Identity-based, since identical entries can be recorded more than once.
    val unused = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[VcrEntry, java.lang.Boolean]())
    previouslyRecorded.values.foreach(group => group.entries.drop(group.currentIndex.get()).foreach(unused.add))
    unused
  }

  /** Saves recordings, treating the test (if any) as successful. */
  def save(): Unit = save(succeeded = true)

  /**
   * Saves new recordings and handles unused entries (see `RecordOptions.unusedEntries`).
   *
   * @param succeeded
   *   Whether the test using this client succeeded. Unused entries are only reported or pruned when it did.
   */
  def save(succeeded: Boolean): Unit = {
    val newEntries = newlyRecordedRef.get
    val previous   = loaded.fold(Vector.empty[VcrEntry])(_.entries)
    val unusedIds  = unusedSet()
    val unused     = if (succeeded) previous.filter(unusedIds.contains) else Vector.empty

    val prune = recordOptions.unusedEntries == UnusedEntries.Prune &&
      recordOptions.mode == RecordMode.NewEpisodes &&
      storage.testName.isDefined

    val kept    = if (prune && unused.nonEmpty) previous.filterNot(unusedIds.contains) else previous
    val updated = VcrEntries(kept ++ newEntries, BuildInfo.version)

    val shouldWrite = newEntries.nonEmpty ||
      recordOptions.mode == RecordMode.RecordAll ||
      loaded.exists(_.copy(version = updated.version) != updated)

    if (shouldWrite) storage.write(updated)

    if (unused.nonEmpty) {
      def message(verb: String) = {
        val test = storage.testName.fold("")(name => s" by test `$name`")
        val list = unused.map(e => s"  ${e.request.method} ${e.request.uri}").mkString("\n")
        s"${unused.size} recorded VCR entries were not used$test and $verb:\n$list"
      }

      recordOptions.unusedEntries match {
        case UnusedEntries.Ignore                     => ()
        case UnusedEntries.Fail                       => throw new VcrException(message("the test was configured to fail"))
        case UnusedEntries.Prune if prune             => System.err.println(s"[be-kind-rewind] ${message("were removed")}")
        case UnusedEntries.Warn | UnusedEntries.Prune =>
          System.err.println(s"[be-kind-rewind] ${message("could be removed")}")
      }
    }
  }

}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"

  def notRecordedError(request: VcrRequest): VcrException =
    new VcrException(
      s"Recording is disabled for `${request.method} ${request.uri}`. The HTTP request was not executed."
    )

  def noMatchError(request: VcrRequest, mode: RecordMode): VcrException =
    new VcrException(
      s"No recorded entry matches `${request.method} ${request.uri}` and record mode $mode doesn't allow recording " +
        "new entries. The HTTP request was not executed."
    )
}
