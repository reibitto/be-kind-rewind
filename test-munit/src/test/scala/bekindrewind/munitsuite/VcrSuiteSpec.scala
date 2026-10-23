package bekindrewind.munitsuite

import bekindrewind.RecordOptions
import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest }
import java.nio.file.{ Files, Path }
import scala.concurrent.Future

class VcrSuiteSpec extends VcrSuite {
  override val vcrDirectory: Path = Files.createTempDirectory("vcr-munit")

  // Keep the tests independent of the VCR_RECORD_MODE environment variable.
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  // Like cats-effect `IO`: a value that munit runs after the test body returns it.
  override def munitValueTransforms: List[ValueTransform] =
    super.munitValueTransforms :+ new ValueTransform(
      "lazy",
      { case l: VcrSuiteSpec.Lazy => Future(l.run())(munitExecutionContext) }
    )

  private val server = TestHttpServer.start()
  private val http   = HttpClient.newHttpClient()

  override def afterAll(): Unit = server.close()

  private val vcrClient = vcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions))

  private def get(client: HttpClient, path: String) =
    client.send(HttpRequest.newBuilder(server.uri(path)).build(), BodyHandlers.ofString())

  private def recordingOf(suite: String, test: String) =
    FileVcrStorage(vcrDirectory.resolve(suite).resolve(test), JsonCodec).read().toOption.flatten

  vcrClient.test("records a request (fixture usage)") { client =>
    assertEquals(get(client, "/json").statusCode(), 200)
  }

  test("the previous test's recording was saved to its own file with the test name") {
    val entries = recordingOf("VcrSuiteSpec", "records-a-request-fixture-usage.json").map(_.entries)

    assertEquals(entries.map(_.map(_.testName)), Some(Vector(Some("records a request (fixture usage)"))))
  }

  vcrClient.test("saves effects that munit runs after the body returns") { client =>
    VcrSuiteSpec.Lazy(() => assertEquals(get(client, "/json").statusCode(), 200))
  }

  test("the previous test's recording was saved after its effect ran") {
    val entries = recordingOf("VcrSuiteSpec", "saves-effects-that-munit-runs-after-the-body-returns.json")

    assertEquals(entries.map(_.entries.size), Some(1))
  }

  test("clients of a test that are open at the same time share its recording") {
    withVcr("nested clients")(ctx => VcrHttpClient(http, ctx.settings)) { outer =>
      get(outer, "/json")
      withVcr("nested clients")(ctx => VcrHttpClient(http, ctx.settings))(inner => get(inner, "/binary"))
    }

    val paths = recordingOf("VcrSuiteSpec", "nested-clients.json").map(_.entries.map(_.request.uri.getPath))
    assertEquals(paths, Some(Vector("/json", "/binary")))
  }

  test("recordings are replayed by later clients for the same test") {
    def client() = {
      val ctx = vcrContext("gets a counter")
      VcrHttpClient(http, ctx.storage, ctx.recordOptions)
    }
    val hits     = server.hits

    val recorder = client()
    assertEquals(get(recorder, "/counter").body(), "1")
    recorder.save()

    val replayer = client()
    assertEquals(get(replayer, "/counter").body(), "1")

    assertEquals(server.hits - hits, 1)
    assert(Files.exists(vcrDirectory.resolve("VcrSuiteSpec").resolve("gets-a-counter.json")))
  }
}

object VcrSuiteSpec {
  final case class Lazy(run: () => Any)
}
