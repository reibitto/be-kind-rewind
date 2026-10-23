package bekindrewind.scalatest

import bekindrewind.codec.JsonCodec
import bekindrewind.javahttp.VcrHttpClient
import bekindrewind.storage.FileVcrStorage
import bekindrewind.testing.TestHttpServer
import bekindrewind.{ RecordOptions, VcrClient }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.{ AnyFunSuite, AsyncFunSuite }

import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.file.{ Files, Path }
import scala.jdk.FutureConverters._

trait VcrSpecFixtures extends BeforeAndAfterAll { self: org.scalatest.Suite =>
  val vcrTempDirectory: Path = Files.createTempDirectory("vcr-scalatest")
  val server: TestHttpServer = TestHttpServer.start()
  val http: HttpClient       = HttpClient.newHttpClient()

  override def afterAll(): Unit = server.close()

  def request(path: String): HttpRequest = HttpRequest.newBuilder(server.uri(path)).build()

  def cached(response: HttpResponse[_]): Boolean =
    response.headers().firstValue(VcrClient.vcrCacheHeaderName).orElse("") == "true"

  def testNamesIn(suite: String, file: String): Option[Vector[Option[String]]] =
    FileVcrStorage(vcrTempDirectory.resolve(suite).resolve(file), JsonCodec)
      .read()
      .toOption
      .flatten
      .map(_.entries.map(_.testName))
}

class VcrSuiteSpec extends AnyFunSuite with VcrSuite with VcrSpecFixtures {
  override def vcrDirectory: Path              = vcrTempDirectory
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  test("records to a file named after the test and replays from it") {
    val hits = server.hits

    val recorded = withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { client =>
      client.send(request("/counter"), BodyHandlers.ofString())
    }
    val replayed = withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { client =>
      client.send(request("/counter"), BodyHandlers.ofString())
    }

    assert(recorded.body() == replayed.body())
    assert(!cached(recorded) && cached(replayed))
    assert(server.hits - hits == 1)
    assert(
      testNamesIn("VcrSuiteSpec", "records-to-a-file-named-after-the-test-and-replays-from-it.json") ==
        Some(Vector(Some("records to a file named after the test and replays from it")))
    )
  }

  test("recordings are saved even if the test fails") {
    intercept[RuntimeException] {
      withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { client =>
        client.send(request("/json"), BodyHandlers.ofString())
        throw new RuntimeException("test failure")
      }
    }

    assert(testNamesIn("VcrSuiteSpec", "recordings-are-saved-even-if-the-test-fails.json").exists(_.size == 1))
  }
}

class AsyncVcrSuiteSpec extends AsyncFunSuite with AsyncVcrSuite with VcrSpecFixtures {
  override def vcrDirectory: Path              = vcrTempDirectory
  override def vcrRecordOptions: RecordOptions = RecordOptions.default

  test("records asynchronously to a file named after the test") {
    withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { client =>
      client.sendAsync(request("/json"), BodyHandlers.ofString()).asScala
    }.map { response =>
      assert(response.statusCode() == 200)
      assert(
        testNamesIn("AsyncVcrSuiteSpec", "records-asynchronously-to-a-file-named-after-the-test.json") ==
          Some(Vector(Some("records asynchronously to a file named after the test")))
      )
    }
  }
}

class PerSuiteVcrSuiteSpec extends AnyFunSuite with VcrSuite with VcrSpecFixtures {
  override def vcrDirectory: Path                        = vcrTempDirectory
  override def vcrLayout: bekindrewind.testkit.VcrLayout = bekindrewind.testkit.VcrLayout.PerSuite
  override def vcrRecordOptions: RecordOptions           =
    RecordOptions.default.unusedEntries(bekindrewind.UnusedEntries.Prune)

  private def record() =
    withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { client =>
      client.send(request("/json"), BodyHandlers.ofString())
    }

  test("first test") {
    record()
  }

  test("second test") {
    record()
    assert(
      testNamesIn(".", "PerSuiteVcrSuiteSpec.json") == Some(Vector(Some("first test"), Some("second test")))
    )
  }

  test("second test's entries survive a failure but are pruned on success") {
    intercept[RuntimeException] {
      withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions)) { _ =>
        throw new RuntimeException("test failure")
      }
    }
    // This test has no entries of its own; the other tests' entries must be untouched either way.
    withVcr(ctx => VcrHttpClient(http, ctx.storage, ctx.recordOptions))(_ => ())

    assert(
      testNamesIn(".", "PerSuiteVcrSuiteSpec.json") == Some(Vector(Some("first test"), Some("second test")))
    )
  }
}
