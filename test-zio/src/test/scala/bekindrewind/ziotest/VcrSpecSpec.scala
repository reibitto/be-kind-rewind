package bekindrewind.ziotest

import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer
import bekindrewind.{ RecordOptions, VcrClient }
import zio._
import zio.test._

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest }
import java.nio.file.{ Files, Path }

object VcrSpecSpec extends ZIOSpecDefault with VcrSpec {
  override val vcrDirectory: Path              = Files.createTempDirectory("vcr-zio")
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  private val server = TestHttpServer.start()
  private val http   = HttpClient.newHttpClient()

  private def create(ctx: bekindrewind.testkit.VcrTestContext) = VcrHttpClient(http, ctx.storage, ctx.recordOptions)

  private def get(client: HttpClient, path: String) =
    ZIO.attemptBlocking(client.send(HttpRequest.newBuilder(server.uri(path)).build(), BodyHandlers.ofString()))

  private def testNames(file: String) =
    ZIO.attempt(
      FileVcrStorage(vcrDirectory.resolve("VcrSpecSpec").resolve(file), JsonCodec)
        .read()
        .toOption
        .flatten
        .map(_.entries.map(_.testName))
    )

  def spec = suite("VcrSpec")(
    vcrTest("records a request")(create) { client =>
      get(client, "/json").map(response => assertTrue(response.statusCode() == 200))
    },
    test("vcrTest saved the recording to a file named after the test") {
      testNames("records-a-request.json").map(names => assertTrue(names == Some(Vector(Some("records a request")))))
    },
    test("recordings are replayed by later clients for the same test") {
      for {
        hits     <- ZIO.succeed(server.hits)
        recorded <- withVcr("counter test")(create)(get(_, "/counter"))
        replayed <- withVcr("counter test")(create)(get(_, "/counter"))
      } yield assertTrue(
        recorded.body() == replayed.body(),
        replayed.headers().firstValue(VcrClient.vcrCacheHeaderName).orElse("") == "true",
        server.hits - hits == 1
      )
    }
  ) @@ TestAspect.sequential @@ TestAspect.afterAll(ZIO.succeed(server.close()))
}

object VcrSpecPerSuiteSpec extends ZIOSpecDefault with VcrSpec {
  override val vcrDirectory: Path                        = Files.createTempDirectory("vcr-zio-suite")
  override def vcrLayout: bekindrewind.testkit.VcrLayout = bekindrewind.testkit.VcrLayout.PerSuite
  override def vcrRecordOptions: RecordOptions           =
    RecordOptions.default.unusedEntries(bekindrewind.UnusedEntries.Prune)

  private val server = TestHttpServer.start()
  private val http   = HttpClient.newHttpClient()

  private def create(ctx: bekindrewind.testkit.VcrTestContext) = VcrHttpClient(http, ctx.storage, ctx.recordOptions)

  private def get(client: HttpClient, path: String) =
    ZIO.attemptBlocking(client.send(HttpRequest.newBuilder(server.uri(path)).build(), BodyHandlers.ofString()))

  private val testNames =
    ZIO.attempt(
      FileVcrStorage(vcrDirectory.resolve("VcrSpecPerSuiteSpec.json"), JsonCodec)
        .read()
        .toOption
        .flatten
        .fold(Vector.empty[Option[String]])(_.entries.map(_.testName))
    )

  def spec = suite("VcrSpec with one file per suite")(
    test("tests running in parallel record into the same file without losing entries") {
      for {
        _     <- ZIO.foreachParDiscard(1 to 20)(i => withVcr(s"parallel $i")(create)(get(_, "/json")))
        names <- testNames
      } yield assertTrue(names.flatten.toSet == (1 to 20).map(i => s"parallel $i").toSet)
    },
    vcrTest("parallel 1")(create)(_ => ZIO.succeed(assertTrue(false))) @@ TestAspect.failing,
    test("a failing assertion doesn't prune the test's entries") {
      testNames.map(names => assertTrue(names.contains(Some("parallel 1"))))
    },
    vcrTest("parallel 2")(create)(_ => ZIO.succeed(assertTrue(true))),
    test("a successful test prunes its unused entries") {
      testNames.map(names => assertTrue(!names.contains(Some("parallel 2")), names.size == 19))
    }
  ) @@ TestAspect.sequential @@ TestAspect.afterAll(ZIO.succeed(server.close()))
}
