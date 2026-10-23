package bekindrewind.codec

import bekindrewind._
import io.circe.{ Decoder, Encoder, Json }

import java.net.URI
import scala.util.Try

/** Circe instances shared by the circe-based codecs (JSON, YAML). */
object CirceInstances {
  implicit val uriEncoder: Encoder[URI] =
    Encoder.encodeString.contramap(_.toString)

  implicit val uriDecoder: Decoder[URI] =
    Decoder.decodeString.emapTry(s => Try(URI.create(s)))

  implicit val bodyEncodingEncoder: Encoder[BodyEncoding] =
    Encoder.encodeString.contramap(_.name)

  implicit val bodyEncodingDecoder: Decoder[BodyEncoding] =
    Decoder.decodeString.emap(s => BodyEncoding.fromName(s).toRight(s"Unknown body encoding: $s"))

  /** Single-value headers are written as plain strings (sorted by name) to keep recordings compact. */
  implicit val headersEncoder: Encoder[Map[String, Seq[String]]] =
    Encoder.instance { headers =>
      Json.fromFields(headers.toSeq.sortBy(_._1.toLowerCase).map {
        case (name, Seq(value)) => name -> Json.fromString(value)
        case (name, values)     => name -> Json.fromValues(values.map(Json.fromString))
      })
    }

  implicit val headersDecoder: Decoder[Map[String, Seq[String]]] =
    Decoder.decodeMap(implicitly, Decoder.decodeString.map(Seq(_)).or(Decoder.decodeSeq[String]))

  // Text encoding and empty headers are the common case, so they're left out of the output to keep recordings small.
  private def nonDefault(encoding: BodyEncoding): Option[BodyEncoding] =
    Some(encoding).filter(_ != BodyEncoding.Text)

  private def nonEmpty(headers: Map[String, Seq[String]]): Option[Map[String, Seq[String]]] =
    Some(headers).filter(_.nonEmpty)

  implicit val vcrRequestEncoder: Encoder[VcrRequest] =
    Encoder.forProduct6("method", "uri", "body", "bodyEncoding", "headers", "httpVersion")(o =>
      (o.method, o.uri, o.body, nonDefault(o.bodyEncoding), nonEmpty(o.headers), o.httpVersion)
    )

  implicit val vcrRequestDecoder: Decoder[VcrRequest] =
    Decoder.forProduct6[
      VcrRequest,
      String,
      URI,
      String,
      Option[BodyEncoding],
      Option[Map[String, Seq[String]]],
      Option[String]
    ]("method", "uri", "body", "bodyEncoding", "headers", "httpVersion") {
      (method, uri, body, bodyEncoding, headers, httpVersion) =>
        VcrRequest(
          method,
          uri,
          body,
          headers.getOrElse(Map.empty),
          httpVersion,
          bodyEncoding.getOrElse(BodyEncoding.Text)
        )
    }

  implicit val vcrResponseEncoder: Encoder[VcrResponse] =
    Encoder.forProduct7("statusCode", "statusText", "headers", "body", "bodyEncoding", "contentType", "httpVersion")(
      o =>
        (
          o.statusCode,
          o.statusText,
          nonEmpty(o.headers),
          o.body,
          nonDefault(o.bodyEncoding),
          o.contentType,
          o.httpVersion
        )
    )

  implicit val vcrResponseDecoder: Decoder[VcrResponse] =
    Decoder.forProduct7[
      VcrResponse,
      Int,
      String,
      Option[Map[String, Seq[String]]],
      String,
      Option[BodyEncoding],
      Option[String],
      Option[String]
    ]("statusCode", "statusText", "headers", "body", "bodyEncoding", "contentType", "httpVersion") {
      (statusCode, statusText, headers, body, bodyEncoding, contentType, httpVersion) =>
        VcrResponse(
          statusCode,
          statusText,
          headers.getOrElse(Map.empty),
          body,
          contentType,
          httpVersion,
          bodyEncoding.getOrElse(BodyEncoding.Text)
        )
    }

  implicit val vcrEntryEncoder: Encoder[VcrEntry] =
    Encoder.forProduct4("testName", "request", "response", "recordedAt")(o =>
      (o.testName, o.request, o.response, o.recordedAt)
    )

  implicit val vcrEntryDecoder: Decoder[VcrEntry] =
    Decoder.forProduct4[VcrEntry, Option[String], VcrRequest, VcrResponse, java.time.OffsetDateTime](
      "testName",
      "request",
      "response",
      "recordedAt"
    )((testName, request, response, recordedAt) => VcrEntry(request, response, recordedAt, testName))

  implicit val vcrEntriesEncoder: Encoder[VcrEntries] =
    Encoder.forProduct2("entries", "version")(o => (o.entries, o.version))

  // Files written by 0.1.0 may also have an `expiration` field. It's ignored since entries now expire individually.
  implicit val vcrEntriesDecoder: Decoder[VcrEntries] =
    Decoder.forProduct2("entries", "version")(VcrEntries.apply)
}
