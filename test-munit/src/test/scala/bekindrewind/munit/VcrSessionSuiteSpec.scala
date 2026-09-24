package bekindrewind.munit

import bekindrewind.RecordOptions
import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest }
import java.nio.file.{ Files, Path }
import scala.jdk.FutureConverters._

// Tests run in order and later tests check what earlier ones recorded.
class VcrSessionSuiteSpec extends VcrSessionSuite {
  override val vcrDirectory: Path              = Files.createTempDirectory("vcr-munit-session")
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  private val server = TestHttpServer.start()

  override def afterAll(): Unit = server.close()

  // Created once for the whole suite, like a client provided by dependency injection.
  private val client = VcrHttpClient(HttpClient.newHttpClient(), vcrSession.storage)

  private def testNames(test: String) =
    FileVcrStorage(vcrDirectory.resolve("VcrSessionSuiteSpec").resolve(s"$test.json"), JsonCodec)
      .read()
      .toOption
      .flatten
      .map(_.entries.map(_.testName))

  test("first test") {
    client.send(HttpRequest.newBuilder(server.uri("/json")).build(), BodyHandlers.ofString())
  }

  test("second test makes its request on another thread") {
    client.sendAsync(HttpRequest.newBuilder(server.uri("/counter")).build(), BodyHandlers.ofString()).asScala
  }

  test("each test recorded to its own file") {
    assertEquals(testNames("first-test"), Some(Vector(Some("first test"))))
    assertEquals(
      testNames("second-test-makes-its-request-on-another-thread"),
      Some(Vector(Some("second test makes its request on another thread")))
    )
  }
}
