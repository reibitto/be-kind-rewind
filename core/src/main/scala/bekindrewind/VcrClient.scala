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
   * Used to specify recording options such as whether to throw errors or expire VCR entries after some time.
   */
  def recordOptions: RecordOptions

  /**
   * Responsible for storing/persisting recorded VCR entries to a file and so on (and which format: JSON, YAML, etc.)
   */
  def storage: VcrStorage

  protected[bekindrewind] val previouslyRecorded: Map[VcrKey, StatefulVcrEntries] = {
    val entryMap = if (recordOptions.overwriteAll) {
      Map.empty[VcrKey, StatefulVcrEntries]
    } else {
      storage.read() match {
        case Left(_) =>
          Map.empty[VcrKey, StatefulVcrEntries]

        case Right(entries) =>
          entries.expiration match {
            case Some(expiration) if OffsetDateTime.now().isAfter(expiration) =>
              Map.empty[VcrKey, StatefulVcrEntries]

            case _ =>
              entries.entries.groupBy(rec => matcher.group(rec.request)).map { case (key, entries) =>
                key -> StatefulVcrEntries.create(entries)
              }
          }
      }
    }

    entryMap
  }

  def addNewEntry(entry: VcrEntry): Unit =
    newlyRecordedRef.updateAndGet { entries =>
      entries :+ matcher.transform(entry)
    }

  def newlyRecorded(): immutable.Seq[VcrEntry] = newlyRecordedRef.get()

  private val newlyRecordedRef: AtomicReference[Vector[VcrEntry]] =
    new AtomicReference(Vector.empty)

  def findMatch[T, R](vcrRequest: VcrRequest): Option[VcrEntry] =
    previouslyRecorded.get(matcher.group(vcrRequest)).flatMap { entries =>
      val i = entries.currentIndex.getAndIncrement()
      entries.entries.filter(r => matcher.group(r.request) areSameGroupedKey matcher.group(vcrRequest)).lift(i)
    }

  def save(): Unit = {
    val previousEntries = previouslyRecorded.values.flatMap(_.entries).toVector.sortBy(_.recordedAt)
    val newEntries      = newlyRecordedRef.get
    val allEntries      = previousEntries ++ newEntries

    val expiration = recordOptions.expiresAfter.map(duration => OffsetDateTime.now().plus(duration))

    storage.write(VcrEntries(allEntries, BuildInfo.version, expiration))
  }

}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"
}
