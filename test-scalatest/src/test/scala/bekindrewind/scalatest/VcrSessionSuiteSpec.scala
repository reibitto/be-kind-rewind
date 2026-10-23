package bekindrewind.scalatest

import bekindrewind.RecordOptions
import bekindrewind.javahttp.VcrHttpClient
import org.scalatest.funsuite.{ AnyFunSuite, AsyncFunSuite }

import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import scala.concurrent.Future
import scala.jdk.FutureConverters._

// Tests run in order and later tests check what earlier ones recorded.
class VcrSessionSuiteSpec extends AnyFunSuite with VcrSessionSuite with VcrSpecFixtures {
  override def vcrDirectory: Path              = vcrTempDirectory
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  // Created once for the whole suite, like a client provided by dependency injection.
  private val client = VcrHttpClient(http, vcrSession.storage)

  test("request on another thread") {
    Future(client.send(request("/json"), BodyHandlers.ofString()))(
      scala.concurrent.ExecutionContext.global
    ).asJava.toCompletableFuture
      .get()
  }

  test("recorded to the test's file") {
    assert(
      testNamesIn("VcrSessionSuiteSpec", "request-on-another-thread.json") ==
        Some(Vector(Some("request on another thread")))
    )
  }
}

class AsyncVcrSessionSuiteSpec extends AsyncFunSuite with AsyncVcrSessionSuite with VcrSpecFixtures {
  override def vcrDirectory: Path              = vcrTempDirectory
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  private val client = VcrHttpClient(http, vcrSession.storage)

  test("async request") {
    client
      .sendAsync(request("/json"), BodyHandlers.ofString())
      .asScala
      .map(response => assert(response.statusCode() == 200))
  }

  test("recorded once the async test completed") {
    assert(testNamesIn("AsyncVcrSessionSuiteSpec", "async-request.json") == Some(Vector(Some("async request"))))
  }
}
