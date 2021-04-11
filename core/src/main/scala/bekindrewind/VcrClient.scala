package bekindrewind

import bekindrewind.util.VcrIO

import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable

trait VcrClient {
  def recordingPath: Path
  def recordOptions: RecordOptions
  def matcher: VcrMatcher

  protected[bekindrewind] val previouslyRecorded: Map[VcrKey, StatefulVcrEntries] = {
    val entryMap = if (recordOptions.overwriteAll) {
      Map.empty[VcrKey, StatefulVcrEntries]
    } else {
      VcrIO.read(recordingPath) match {
        case Left(_) =>
          Map.empty[VcrKey, StatefulVcrEntries]

        case Right(entries) =>
          entries.expiration match {
            case Some(expiration) if OffsetDateTime.now().isAfter(expiration) =>
              Map.empty[VcrKey, StatefulVcrEntries]

            case _ =>
              entries.entries.groupBy(rec => matcher.group(rec.request)).map { case (anyKey, entries) =>
                anyKey -> StatefulVcrEntries.create(entries)
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

    VcrIO.write(
      recordingPath,
      VcrEntries(allEntries, BuildInfo.version, expiration)
    )
  }

}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"
}
