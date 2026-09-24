package bekindrewind.testing

import bekindrewind._
import bekindrewind.storage.VcrStorage

import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

object TestData {
  val recordedAt: OffsetDateTime = OffsetDateTime.parse("2100-05-06T12:34:56.789Z")

  def request(method: String = "GET", uri: String = "https://example.com/foo.json", body: String = ""): VcrRequest =
    VcrRequest(method, new URI(uri), body, Map.empty, Some("HTTP/1.1"))

  def response(body: String = "{}", statusCode: Int = 200): VcrResponse =
    VcrResponse(statusCode, "OK", Map.empty, body, Some("application/json"))

  def entry(request: VcrRequest = TestData.request(), response: VcrResponse = TestData.response()): VcrEntry =
    VcrEntry(request, response, recordedAt)
}

final case class MockClient(storage: VcrStorage, recordOptions: RecordOptions, matcher: VcrMatcher) extends VcrClient

/** Wraps a storage and counts writes. */
final class CountingStorage(underlying: VcrStorage) extends VcrStorage {
  val writes = new AtomicInteger(0)

  override def read(): Either[Throwable, Option[VcrEntries]] = underlying.read()

  override def write(entries: VcrEntries): Unit = {
    writes.incrementAndGet()
    underlying.write(entries)
  }
}
