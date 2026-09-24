package bekindrewind.ziotest

import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer
import bekindrewind.RecordOptions
import zio._
import zio.test._

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest }
import java.nio.file.{ Files, Path }

object VcrSessionSpecSpec extends ZIOSpecDefault with VcrSessionSpec {
  override val vcrDirectory: Path              = Files.createTempDirectory("vcr-zio-session")
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  private val server = TestHttpServer.start()

  // Created once for the whole spec, like a client provided by dependency injection.
  private val client = VcrHttpClient(HttpClient.newHttpClient(), vcrSession.storage)

  private def get(path: String) =
    ZIO.fromCompletableFuture(
      client.sendAsync(HttpRequest.newBuilder(server.uri(path)).build(), BodyHandlers.ofString())
    )

  private def testNames(file: String) =
    ZIO.attempt(
      FileVcrStorage(vcrDirectory.resolve("VcrSessionSpecSpec").resolve(s"$file.json"), JsonCodec)
        .read()
        .toOption
        .flatten
        .map(_.entries.map(_.testName))
    )

  def spec = suite("VcrSessionSpecSpec")(
    suite("payments")(
      test("duplicate")(get("/json").as(assertCompletes)),
      test("duplicate")(get("/counter").as(assertCompletes))
    ),
    test("tests are named after their labels and duplicates are numbered") {
      for {
        first  <- testNames("payments-duplicate")
        second <- testNames("payments-duplicate-2")
      } yield assertTrue(
        first == Some(Vector(Some("payments / duplicate"))),
        second == Some(Vector(Some("payments / duplicate (2)")))
      )
    }
  ) @@ TestAspect.afterAll(ZIO.succeed(server.close()))
}
