package bekindrewind

import bekindrewind.util.VcrIO

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

trait VcrClient {
  def recordingPath: Path
  def recordOptions: RecordOptions
  def matcher: VcrMatcher

  val previouslyRecorded: Map[VcrKey, StatefulVcrRecords] = {
    val records = if (recordOptions.overwriteAll) {
      Map.empty[VcrKey, StatefulVcrRecords]
    } else {
      VcrIO.read(recordingPath) match {
        case Left(_) =>
          Map.empty[VcrKey, StatefulVcrRecords]

        case Right(records) =>
          println(s"Loaded ${records.records.length} records")
          records.records.groupBy(rec => matcher.group(rec.request)).map { case (anyKey, records) =>
            anyKey -> StatefulVcrRecords.create(records)
          }
      }
    }

    records
  }

  val newlyRecorded: AtomicReference[Vector[VcrRecord]] =
    new AtomicReference(Vector.empty)

  def findMatch[T, R](recordRequest: VcrRecordRequest): Option[VcrRecord] =
    previouslyRecorded.get(matcher.group(recordRequest)).flatMap { records =>
      val i = records.currentIndex.getAndIncrement()
      records.records.filter(r => matcher.group(r.request) areSameGroupedKey matcher.group(recordRequest)).lift(i)
    }

  def save(): Unit = {
    val previousRecords = previouslyRecorded.values.flatMap(_.records).toVector.sortBy(_.recordedAt)
    val newRecords      = newlyRecorded.get
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
