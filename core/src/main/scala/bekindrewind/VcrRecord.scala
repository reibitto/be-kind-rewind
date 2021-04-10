package bekindrewind

import bekindrewind.codec.Codecs._
import io.circe.{ Decoder, Encoder }

import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

final case class VcrRecord(request: VcrRecordRequest, response: VcrRecordResponse, recordedAt: OffsetDateTime)

object VcrRecord {
  implicit val encoder: Encoder[VcrRecord] =
    Encoder.forProduct3("request", "response", "recordedAt")(o => (o.request, o.response, o.recordedAt))

  implicit val decoder: Decoder[VcrRecord] = Decoder.forProduct3("request", "response", "recordedAt")(VcrRecord.apply)
}

final case class VcrRecordRequest(
  method: String,
  uri: URI,
  body: String,
  headers: Map[String, Seq[String]],
  httpVersion: String
)

object VcrRecordRequest {
  implicit val encoder: Encoder[VcrRecordRequest] =
    Encoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(o =>
      (o.method, o.uri, o.body, o.headers, o.httpVersion)
    )

  implicit val decoder: Decoder[VcrRecordRequest] =
    Decoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(VcrRecordRequest.apply)
}

final case class VcrRecordResponse(
  statusCode: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String,
  contentType: Option[String]
)

object VcrRecordResponse {
  implicit val encoder: Encoder[VcrRecordResponse] =
    Encoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(o =>
      (o.statusCode, o.statusText, o.headers, o.body, o.contentType)
    )

  implicit val decoder: Decoder[VcrRecordResponse] =
    Decoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(VcrRecordResponse.apply)
}

final case class VcrRecords(records: Vector[VcrRecord], version: String, expiration: Option[OffsetDateTime] = None)

object VcrRecords {
  implicit val encoder: Encoder[VcrRecords] =
    Encoder.forProduct3("records", "version", "expiration")(o => (o.records, o.version, o.expiration))

  implicit val decoder: Decoder[VcrRecords] = Decoder.forProduct3("records", "version", "expiration")(VcrRecords.apply)
}

final case class StatefulVcrRecords(records: Vector[VcrRecord], currentIndex: AtomicInteger) {
  def append(record: VcrRecord): StatefulVcrRecords =
    copy(records = records :+ record)
}

object StatefulVcrRecords {
  def create(records: Vector[VcrRecord]): StatefulVcrRecords =
    StatefulVcrRecords(records, new AtomicInteger(0))
}
