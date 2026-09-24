package bekindrewind.munit

import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer
import bekindrewind.testkit.{ VcrLayout, VcrTestContext }
import bekindrewind.{ RecordOptions, UnusedEntries, VcrClient }

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.file.{ Files, Path }

// Tests run in order and later tests check what earlier ones recorded.
class VcrSuitePerSuiteSpec extends VcrSuite {
  override val vcrDirectory: Path              = Files.createTempDirectory("vcr-munit-suite")
  override def vcrLayout: VcrLayout            = VcrLayout.PerSuite
  override def vcrRecordOptions: RecordOptions = RecordOptions.default.unusedEntries(UnusedEntries.Prune)

  private val server = TestHttpServer.start()
  private val http   = HttpClient.newHttpClient()

  override def afterAll(): Unit = server.close()

  private def create(ctx: VcrTestContext) = VcrHttpClient(http, ctx.storage, ctx.recordOptions)
  private val vcrClient                   = vcr(create)

  private def get(client: HttpClient, path: String): HttpResponse[String] =
    client.send(HttpRequest.newBuilder(server.uri(path)).build(), BodyHandlers.ofString())

  private def cached(response: HttpResponse[_]): Boolean =
    response.headers().firstValue(VcrClient.vcrCacheHeaderName).orElse("") == "true"

  private def recorded: Vector[(Option[String], String)] =
    FileVcrStorage(vcrDirectory.resolve("VcrSuitePerSuiteSpec.json"), JsonCodec)
      .read()
      .toOption
      .flatten
      .fold(Vector.empty[(Option[String], String)])(_.entries.map(e => (e.testName, e.response.body)))

  vcrClient.test("test a")(client => assertEquals(get(client, "/counter").body(), "1"))

  vcrClient.test("test b")(client => assertEquals(get(client, "/counter").body(), "2"))

  test("both tests are recorded into the suite's file with their names") {
    assertEquals(recorded, Vector(Some("test a") -> "1", Some("test b") -> "2"))
    assertEquals(Files.list(vcrDirectory).count(), 1L)
  }

  test("each test replays its own entries even though they're identical requests") {
    val b = withVcr("test b")(create)(get(_, "/counter"))
    val a = withVcr("test a")(create)(get(_, "/counter"))

    assertEquals((a.body(), b.body()), ("1", "2"))
    assert(cached(a) && cached(b))
  }

  test("a failed test doesn't prune its unused entries") {
    intercept[RuntimeException](withVcr("test a")(create)(_ => throw new RuntimeException("test failure")))

    assertEquals(recorded, Vector(Some("test a") -> "1", Some("test b") -> "2"))
  }

  test("a successful test prunes only its own unused entries") {
    withVcr("test a")(create)(_ => ())

    assertEquals(recorded, Vector(Some("test b") -> "2"))
  }
}

class VcrSuiteAsyncSpec extends VcrSuite {
  override val vcrDirectory: java.nio.file.Path = java.nio.file.Files.createTempDirectory("vcr-munit-async")
  override def vcrRecordOptions: RecordOptions  = RecordOptions.default

  private val server = TestHttpServer.start()
  private val http   = HttpClient.newHttpClient()

  override def afterAll(): Unit = server.close()

  private def create(ctx: VcrTestContext) = VcrHttpClient(http, ctx.storage, ctx.recordOptions)

  private def getAsync(client: HttpClient) =
    scala.jdk.FutureConverters
      .CompletionStageOps(
        client.sendAsync(HttpRequest.newBuilder(server.uri("/counter")).build(), BodyHandlers.ofString())
      )
      .asScala

  test("withVcrAsync saves once the future completes, so a later client replays it") {
    implicit val ec: scala.concurrent.ExecutionContext = munitExecutionContext

    for {
      recorded <- withVcrAsync("async")(create)(getAsync)
      replayed <- withVcrAsync("async")(create)(getAsync)
    } yield {
      assertEquals(replayed.body(), recorded.body())
      assertEquals(replayed.headers().firstValue(VcrClient.vcrCacheHeaderName).orElse(""), "true")
    }
  }

  vcr(create).test("fixture tests can return futures") { client =>
    getAsync(client).map(response => assertEquals(response.statusCode(), 200))(munitExecutionContext)
  }
}
