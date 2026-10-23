package bekindrewind.javahttp

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.TestHttpServer
import munit._

import java.io.ByteArrayInputStream
import java.net.http.HttpClient.Version
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.FutureConverters._

class VcrHttpClientSpec extends FunSuite {

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def client(storage: InMemoryVcrStorage, options: RecordOptions = RecordOptions.default): VcrHttpClient =
    VcrHttpClient(HttpClient.newBuilder().version(Version.HTTP_1_1).build(), storage, options)

  private def get(server: TestHttpServer, path: String): HttpRequest =
    HttpRequest.newBuilder(server.uri(path)).GET().build()

  private def cached(response: HttpResponse[_]): Boolean =
    response.headers().firstValue(VcrClient.vcrCacheHeaderName).orElse("") == "true"

  server.test("Records real responses and replays them without hitting the server") { server =>
    val storage = new InMemoryVcrStorage()

    val recorder = client(storage)
    val recorded = recorder.send(get(server, "/json"), BodyHandlers.ofString())
    recorder.close()

    val replayed = client(storage).send(get(server, "/json"), BodyHandlers.ofString())

    assertEquals(recorded.body(), """{"hello":"world","unicode":"日本語"}""")
    assert(!cached(recorded))
    assertEquals(replayed.body(), recorded.body())
    assertEquals(replayed.statusCode(), 200)
    assertEquals(replayed.headers().firstValue("content-type").orElse(""), "application/json")
    assertEquals(replayed.uri(), server.uri("/json"))
    assert(cached(replayed))
    assertEquals(server.hits, 1)
  }

  test("shutdown and awaitTermination reach the underlying client") {
    assume(Runtime.version().feature() >= 21, "HttpClient.shutdown requires JDK 21+")

    val vcr = client(new InMemoryVcrStorage())
    assert(!vcr.isTerminated())
    vcr.shutdown()

    assert(vcr.awaitTermination(java.time.Duration.ofSeconds(10)))
    assert(vcr.isTerminated())
  }

  server.test("Works with sendAsync and streaming body handlers") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      recorded <- recorder.sendAsync(get(server, "/counter"), BodyHandlers.ofLines()).asScala
      _         = recorder.close()
      replayed <- client(storage).sendAsync(get(server, "/counter"), BodyHandlers.ofInputStream()).asScala
    } yield {
      assertEquals(recorded.body().iterator().asScala.toList, List("1"))
      assertEquals(new String(replayed.body().readAllBytes(), StandardCharsets.UTF_8), "1")
      assert(cached(replayed))
      assertEquals(server.hits, 1)
    }
  }

  server.test("Binary bodies are replayed byte for byte") { server =>
    val storage = new InMemoryVcrStorage()

    val recorder = client(storage)
    assertEquals(
      recorder.send(get(server, "/binary"), BodyHandlers.ofByteArray()).body().toList,
      TestHttpServer.binaryBody.toList
    )
    recorder.close()

    val replayed = client(storage).send(get(server, "/binary"), BodyHandlers.ofByteArray())
    assertEquals(replayed.body().toList, TestHttpServer.binaryBody.toList)
    assert(cached(replayed))
    assertEquals(
      storage.read().toOption.flatten.map(_.entries.map(_.response.bodyEncoding)),
      Some(Vector(BodyEncoding.Base64))
    )
  }

  server.test("One-shot request bodies are recorded and still sent intact") { server =>
    val vcr     = client(new InMemoryVcrStorage())
    val bytes   = "stream body ✓".getBytes(StandardCharsets.UTF_8)
    val request = HttpRequest
      .newBuilder(server.uri("/echo"))
      .header("Content-Type", "text/plain; charset=UTF-8")
      .POST(BodyPublishers.ofInputStream(() => new ByteArrayInputStream(bytes)))
      .build()

    val response = vcr.send(request, BodyHandlers.ofString())

    assertEquals(response.body(), "stream body ✓")
    assertEquals(response.headers().firstValue("X-Request-Method").orElse(""), "POST")
    assertEquals(vcr.newlyRecorded().map(_.request.body), Vector("stream body ✓"))
  }

  server.test("The response HTTP version is recorded and replayed") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)
    recorder.send(get(server, "/json"), BodyHandlers.discarding())
    recorder.close()

    assertEquals(
      storage.read().toOption.flatten.map(_.entries.map(_.response.httpVersion)),
      Some(Vector(Some("HTTP/1.1")))
    )
    assertEquals(client(storage).send(get(server, "/json"), BodyHandlers.discarding()).version(), Version.HTTP_1_1)
  }

  server.test("Error responses are replayed with their status") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)
    recorder.send(get(server, "/missing"), BodyHandlers.ofString())
    recorder.close()

    val replayed = client(storage).send(get(server, "/missing"), BodyHandlers.ofString())
    assertEquals(replayed.statusCode(), 404)
    assertEquals(replayed.body(), "not found")
    assertEquals(server.hits, 1)
  }

  server.test("ReplayOnly fails unrecorded requests without sending them") { server =>
    val vcr = client(new InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))

    intercept[VcrException](vcr.send(get(server, "/counter"), BodyHandlers.ofString()))
    assertEquals(server.hits, 0)
  }

  server.test("Requests rejected by shouldRecord are sent but not recorded") { server =>
    val vcr = VcrHttpClient(
      HttpClient.newHttpClient(),
      new InMemoryVcrStorage(),
      matcher = VcrMatcher.default.recordOnly(_ => false)
    )

    assertEquals(vcr.send(get(server, "/counter"), BodyHandlers.ofString()).body(), "1")
    assert(vcr.newlyRecorded().isEmpty)
  }
}
