package bekindrewind.sttpclient4

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.TestHttpServer
import munit._
import sttp.client4._
import sttp.client4.httpclient.{ HttpClientFutureBackend, HttpClientSyncBackend }
import sttp.model.StatusCode

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext.Implicits.global

class VcrBackendSpec extends FunSuite {

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def cached(response: Response[_]): Boolean =
    response.header(VcrClient.vcrCacheHeaderName).contains("true")

  private def backend(storage: InMemoryVcrStorage, options: RecordOptions = RecordOptions.default) =
    VcrBackend(HttpClientSyncBackend(), VcrSettings(storage, options))

  server.test("Records real responses and replays them without hitting the server") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/json")}")

    val recorder = backend(storage)
    val recorded = request.send(recorder)
    recorder.close()

    val replayed = request.send(backend(storage))

    assertEquals(recorded.body, Right("""{"hello":"world","unicode":"日本語"}"""))
    assert(!cached(recorded))
    assertEquals(replayed.body, recorded.body)
    assertEquals(replayed.code, StatusCode.Ok)
    assertEquals(replayed.request.uri, request.uri)
    assert(cached(replayed))
    assertEquals(server.hits, 1)
  }

  server.test("Works with asynchronous backends") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/counter")}")

    val recorder = VcrBackend(HttpClientFutureBackend(), VcrSettings(storage))
    for {
      recorded <- request.send(recorder)
      _        <- recorder.close()
      replayed <- request.send(VcrBackend(HttpClientFutureBackend(), VcrSettings(storage)))
    } yield {
      assertEquals(recorded.body, Right("1"))
      assertEquals(replayed.body, Right("1"))
      assert(cached(replayed))
      assertEquals(server.hits, 1)
    }
  }

  server.test("Binary bodies are replayed byte for byte") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/binary")}").response(asByteArrayAlways)

    val recorder = backend(storage)
    assertEquals(request.send(recorder).body.toList, TestHttpServer.binaryBody.toList)
    recorder.close()

    val replayed = request.send(backend(storage))
    assertEquals(replayed.body.toList, TestHttpServer.binaryBody.toList)
    assert(cached(replayed))
  }

  server.test("Input stream request bodies are recorded and still sent intact") { server =>
    val vcr   = backend(new InMemoryVcrStorage())
    val bytes = "stream body ✓".getBytes(StandardCharsets.UTF_8)

    val response = basicRequest.post(uri"${server.uri("/echo")}").body(new ByteArrayInputStream(bytes)).send(vcr)

    assertEquals(response.body, Right("stream body ✓"))
    assertEquals(vcr.newlyRecorded().map(_.request.body), Vector("stream body ✓"))
  }

  server.test("Error responses are replayed with their status") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/missing")}")

    val recorder = backend(storage)
    request.send(recorder)
    recorder.close()

    val replayed = request.send(backend(storage))
    assertEquals(replayed.code, StatusCode.NotFound)
    assertEquals(replayed.body, Left("not found"))
  }

  server.test("ReplayOnly fails unrecorded requests without sending them") { server =>
    val vcr = backend(new InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))

    intercept[VcrException](basicRequest.get(uri"${server.uri("/counter")}").send(vcr))
    assertEquals(server.hits, 0)
  }

  server.test("Multipart requests are passed through without being recorded") { server =>
    val vcr = backend(new InMemoryVcrStorage())

    val response =
      basicRequest.post(uri"${server.uri("/echo")}").multipartBody(multipart("a", "b")).send(vcr)

    assertEquals(response.code, StatusCode.Ok)
    assert(vcr.newlyRecorded().isEmpty)
  }

  server.test("The requested HTTP version is recorded") { server =>
    val vcr = backend(new InMemoryVcrStorage())
    basicRequest.get(uri"${server.uri("/json")}").httpVersion(sttp.model.HttpVersion.HTTP_1_1).send(vcr)

    assertEquals(vcr.newlyRecorded().map(_.request.httpVersion), Vector(Some("HTTP/1.1")))
  }

  server.test("Decompressed responses are recorded without their Content-Encoding and Content-Length") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/gzip")}")

    val recorder = backend(storage)
    assertEquals(request.send(recorder).body, Right("""{"compressed":true}"""))
    recorder.close()

    val headers = storage.read().toOption.flatten.map(_.entries.head.response.headers.keySet.map(_.toLowerCase))
    assert(headers.exists(h => !h.contains("content-encoding") && !h.contains("content-length")), headers.toString)
    assertEquals(request.send(backend(storage)).body, Right("""{"compressed":true}"""))
  }
}
