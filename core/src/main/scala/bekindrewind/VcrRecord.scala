package bekindrewind

import io.circe.{ Decoder, Encoder }

import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

final case class VcrRecord(request: VcrRecordRequest, response: VcrRecordResponse, recordedAt: OffsetDateTime)

object VcrRecord {
  implicit val encoder: Encoder[VcrRecord] =
    Encoder.forProduct3("request", "response", "recordedAt")(o => (o.request, o.response, o.recordedAt))

  implicit val decoder: Decoder[VcrRecord] = Decoder.forProduct3("request", "response", "recordedAt")(VcrRecord.apply)
}

final case class VcrRecordRequest(method: String, uri: String, body: String, headers: Map[String, Seq[String]])

object VcrRecordRequest {
  implicit val encoder: Encoder[VcrRecordRequest] =
    Encoder.forProduct4("method", "uri", "body", "headers")(o => (o.method, o.uri, o.body, o.headers))

  implicit val decoder: Decoder[VcrRecordRequest] =
    Decoder.forProduct4("method", "uri", "body", "headers")(VcrRecordRequest.apply)
}

final case class VcrRecordResponse(statusCode: Int, statusText: String, headers: Map[String, Seq[String]], body: String)

object VcrRecordResponse {
  implicit val encoder: Encoder[VcrRecordResponse] =
    Encoder.forProduct4("statusCode", "statusText", "headers", "body")(o =>
      (o.statusCode, o.statusText, o.headers, o.body)
    )

  implicit val decoder: Decoder[VcrRecordResponse] =
    Decoder.forProduct4("statusCode", "statusText", "headers", "body")(VcrRecordResponse.apply)
}

final case class VcrRecords(records: Vector[VcrRecord], version: String)

object VcrRecords {
  implicit val encoder: Encoder[VcrRecords] =
    Encoder.forProduct2("records", "version")(o => (o.records, o.version))

  implicit val decoder: Decoder[VcrRecords] = Decoder.forProduct2("records", "version")(VcrRecords.apply)
}

final case class StatefulVcrRecords(records: Vector[VcrRecord], currentIndex: AtomicInteger) {
  def append(record: VcrRecord): StatefulVcrRecords =
    copy(records = records :+ record)
}

object StatefulVcrRecords {
  def create(records: Vector[VcrRecord]): StatefulVcrRecords =
    StatefulVcrRecords(records, new AtomicInteger(0))
}
