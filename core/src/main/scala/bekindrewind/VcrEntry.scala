package bekindrewind

import bekindrewind.codec.Codecs._
import io.circe.{ Decoder, Encoder }

import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

final case class VcrEntry(request: VcrRequest, response: VcrResponse, recordedAt: OffsetDateTime)

object VcrEntry {
  implicit val encoder: Encoder[VcrEntry] =
    Encoder.forProduct3("request", "response", "recordedAt")(o => (o.request, o.response, o.recordedAt))

  implicit val decoder: Decoder[VcrEntry] = Decoder.forProduct3("request", "response", "recordedAt")(VcrEntry.apply)
}

final case class VcrRequest(
  method: String,
  uri: URI,
  body: String,
  headers: Map[String, Seq[String]],
  httpVersion: String
)

object VcrRequest {
  implicit val encoder: Encoder[VcrRequest] =
    Encoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(o =>
      (o.method, o.uri, o.body, o.headers, o.httpVersion)
    )

  implicit val decoder: Decoder[VcrRequest] =
    Decoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(VcrRequest.apply)
}

final case class VcrResponse(
  statusCode: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String,
  contentType: Option[String]
)

object VcrResponse {
  implicit val encoder: Encoder[VcrResponse] =
    Encoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(o =>
      (o.statusCode, o.statusText, o.headers, o.body, o.contentType)
    )

  implicit val decoder: Decoder[VcrResponse] =
    Decoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(VcrResponse.apply)
}

final case class VcrEntries(entries: Vector[VcrEntry], version: String)

object VcrEntries {
  implicit val encoder: Encoder[VcrEntries] =
    Encoder.forProduct2("entries", "version")(o => (o.entries, o.version))

  implicit val decoder: Decoder[VcrEntries] = Decoder.forProduct2("entries", "version")(VcrEntries.apply)
}

final case class StatefulVcrEntries(entries: Vector[VcrEntry], currentIndex: AtomicInteger) {
  def append(entry: VcrEntry): StatefulVcrEntries =
    copy(entries = entries :+ entry)
}

object StatefulVcrEntries {
  def create(entries: Vector[VcrEntry]): StatefulVcrEntries =
    StatefulVcrEntries(entries, new AtomicInteger(0))
}
