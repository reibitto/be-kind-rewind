package bekindrewind.codec

import bekindrewind.{ VcrEntries, VcrEntry, VcrRequest, VcrResponse }
import io.circe.syntax.EncoderOps
import io.circe.yaml.syntax._
import io.circe.{ Decoder, Encoder }

import java.net.URI
import scala.util.Try

object YamlCodec extends Codec {
  override def decode(text: String): Either[Throwable, VcrEntries] =
    for {
      json    <- io.circe.yaml.parser.parse(text)
      entries <- json.as[VcrEntries]
    } yield entries

  override def encode(entries: VcrEntries): String =
    entries.asJson.asYaml.spaces2

  implicit val uriEncoder: Encoder[URI] =
    Encoder.encodeString.contramap(_.toString)

  implicit val uriDecoder: Decoder[URI] = Decoder.decodeString.emap { s =>
    Try(URI.create(s)).toEither.left.map(_.getMessage)
  }

  implicit val vcrRequestEncoder: Encoder[VcrRequest] =
    Encoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(o =>
      (o.method, o.uri, o.body, o.headers, o.httpVersion)
    )

  implicit val vcrRequestDecoder: Decoder[VcrRequest] =
    Decoder.forProduct5("method", "uri", "body", "headers", "httpVersion")(VcrRequest.apply)

  implicit val vcrResponseEncoder: Encoder[VcrResponse] =
    Encoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(o =>
      (o.statusCode, o.statusText, o.headers, o.body, o.contentType)
    )

  implicit val vcrResponseDecoder: Decoder[VcrResponse] =
    Decoder.forProduct5("statusCode", "statusText", "headers", "body", "contentType")(VcrResponse.apply)

  implicit val vcrEntryEncoder: Encoder[VcrEntry] =
    Encoder.forProduct3("request", "response", "recordedAt")(o => (o.request, o.response, o.recordedAt))

  implicit val vcrEntryDecoder: Decoder[VcrEntry] =
    Decoder.forProduct3("request", "response", "recordedAt")(VcrEntry.apply)

  implicit val vcrEntriesEncoder: Encoder[VcrEntries] =
    Encoder.forProduct3("entries", "version", "expiration")(o => (o.entries, o.version, o.expiration))

  implicit val vcrEntriesDecoder: Decoder[VcrEntries] =
    Decoder.forProduct3("entries", "version", "expiration")(VcrEntries.apply)
}
