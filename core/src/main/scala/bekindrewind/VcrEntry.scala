package bekindrewind

import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

final case class VcrEntry(request: VcrRequest, response: VcrResponse, recordedAt: OffsetDateTime)

final case class VcrRequest(
  method: String,
  uri: URI,
  body: String,
  headers: Map[String, Seq[String]],
  httpVersion: String
)

final case class VcrResponse(
  statusCode: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String,
  contentType: Option[String]
)

final case class VcrEntries(entries: Vector[VcrEntry], version: String, expiration: Option[OffsetDateTime] = None)

final case class StatefulVcrEntries(entries: Vector[VcrEntry], currentIndex: AtomicInteger) {
  def append(entry: VcrEntry): StatefulVcrEntries =
    copy(entries = entries :+ entry)
}

object StatefulVcrEntries {
  def create(entries: Vector[VcrEntry]): StatefulVcrEntries =
    StatefulVcrEntries(entries, new AtomicInteger(0))
}
