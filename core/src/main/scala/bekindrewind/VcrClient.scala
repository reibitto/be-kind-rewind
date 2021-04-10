package bekindrewind

import bekindrewind.util.VcrIO

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable

trait VcrClient {
  def recordingPath: Path
  def recordOptions: RecordOptions
  def matcher: VcrMatcher

  val previouslyRecorded: Map[Any, StatefulVcrRecords] = {
    val records = if (recordOptions.overwriteAll) {
      Map.empty[Any, StatefulVcrRecords]
    } else {
      VcrIO.read(recordingPath) match {
        case Left(_) =>
          Map.empty[Any, StatefulVcrRecords]

        case Right(records) =>
          println(s"Loaded ${records.records.length} records")
          records.records.groupBy(rec => matcher.groupFn(rec.request)).map { case (anyKey, records) =>
            anyKey -> StatefulVcrRecords.create(records)
          }
      }
    }

    records
  }

  def addNewRecord(recordRequest: VcrRecord): Unit =
    newlyRecordedRef.updateAndGet { records =>
      val transformed = recordOptions.recordTransformer(recordRequest)
      records :+ transformed
    }

  def newlyRecorded(): immutable.Seq[VcrRecord] = newlyRecordedRef.get()

  private val newlyRecordedRef: AtomicReference[Vector[VcrRecord]] =
    new AtomicReference(Vector.empty)

  def findMatch[T, R](recordRequest: VcrRecordRequest): Option[VcrRecord] =
    previouslyRecorded.get(matcher.groupFn(recordRequest)).flatMap { records =>
      val i = records.currentIndex.getAndIncrement()
      records.records.filter(r => matcher.groupFn(r.request) == matcher.groupFn(recordRequest)).lift(i)
    }

  def save(): Unit = {
    val previousRecords = previouslyRecorded.values.flatMap(_.records).toVector.sortBy(_.recordedAt)
    val newRecords      = newlyRecordedRef.get
    val allRecords      = previousRecords ++ newRecords

    println(s"Writing ${allRecords.size} records to ${recordingPath.toAbsolutePath}")

    VcrIO.write(
      recordingPath,
      VcrRecords(allRecords, BuildInfo.version)
    )
  }

}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"
}
