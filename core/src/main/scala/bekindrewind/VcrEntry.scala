package bekindrewind

import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/** @param testName Name of the test that recorded this entry, to make recordings easier to read. */
final case class VcrEntry(
  request: VcrRequest,
  response: VcrResponse,
  recordedAt: OffsetDateTime,
  testName: Option[String] = None
)

/**
 * @param body
 *   The body as text, or base64 for binary bodies (see `bodyEncoding`). Use `bodyBytes` for the exact bytes.
 * @param httpVersion
 *   Protocol version the request was sent with, if the HTTP client exposes it.
 */
final case class VcrRequest(
  method: String,
  uri: URI,
  body: String,
  headers: Map[String, Seq[String]],
  httpVersion: Option[String] = None,
  bodyEncoding: BodyEncoding = BodyEncoding.Text
) {
  def bodyBytes: Array[Byte] = bodyEncoding.decode(body)
}

object VcrRequest {
  def fromBytes(
    method: String,
    uri: URI,
    body: Array[Byte],
    headers: Map[String, Seq[String]],
    httpVersion: Option[String]
  ): VcrRequest = {
    val (encoded, encoding) = BodyEncoding.encode(body, BodyEncoding.charsetOf(headers))
    VcrRequest(method, uri, encoded, headers, httpVersion, encoding)
  }
}

/**
 * @param body
 *   The body as text, or base64 for binary bodies (see `bodyEncoding`). Use `bodyBytes` for the exact bytes.
 * @param httpVersion
 *   Protocol version of the response, if the HTTP client exposes it.
 */
final case class VcrResponse(
  statusCode: Int,
  statusText: String,
  headers: Map[String, Seq[String]],
  body: String,
  contentType: Option[String],
  httpVersion: Option[String] = None,
  bodyEncoding: BodyEncoding = BodyEncoding.Text
) {
  def bodyBytes: Array[Byte] = bodyEncoding.decode(body)
}

object VcrResponse {
  def fromBytes(
    statusCode: Int,
    statusText: String,
    headers: Map[String, Seq[String]],
    body: Array[Byte],
    contentType: Option[String],
    httpVersion: Option[String]
  ): VcrResponse = {
    val (encoded, encoding) =
      BodyEncoding.encode(body, contentType.flatMap(BodyEncoding.charsetOf).orElse(BodyEncoding.charsetOf(headers)))
    VcrResponse(statusCode, statusText, headers, encoded, contentType, httpVersion, encoding)
  }
}

final case class VcrEntries(entries: Vector[VcrEntry], version: String)

/**
 * A group of equivalent recorded entries and how many of them have been replayed.
 *
 * @param positions
 *   Where each entry is in the recording.
 */
final case class StatefulVcrEntries(entries: Vector[VcrEntry], currentIndex: AtomicInteger, positions: Vector[Int])

object StatefulVcrEntries {
  def create(entries: Vector[VcrEntry], positions: Vector[Int]): StatefulVcrEntries =
    StatefulVcrEntries(entries, new AtomicInteger(0), positions)
}
