package bekindrewind.sttpclient

import bekindrewind._
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.{ FileVcrStorage, InMemoryVcrStorage }
import bekindrewind.testing.TestHttpServer
import munit._
import sttp.client3._
import sttp.client3.testing.SttpBackendStub
import sttp.model.{ Header, StatusCode }

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable

class VcrBackendSpec extends FunSuite {

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def cached(response: Response[_]): Boolean =
    response.header(VcrClient.vcrCacheHeaderName).contains("true")

  test("Save and load with a stub backend") {
    val counter = new AtomicInteger(0)

    val binaryEntity = Array[Byte](1, 2, 3)
    val jsonEntity   = """{"a":1}"""

    val testingBackend = SttpBackendStub.synchronous.whenAnyRequest.thenRespond(
      Response(
        counter.incrementAndGet() match {
          case 1 => binaryEntity
          case 2 => jsonEntity
        },
        StatusCode.Ok
      )
    )

    val storage    = new InMemoryVcrStorage()
    val vcrBackend = VcrBackend(testingBackend, storage)

    val req1 = basicRequest.response(asByteArray).post(uri"/files/new").body(Array[Byte](1, 2, 3))
    val req2 = basicRequest.put(uri"/messages/1").body("hello")

    val res1 = req1.send(vcrBackend)
    val res2 = req2.send(vcrBackend)
    vcrBackend.close()

    val recordedBackend = VcrBackend(testingBackend, storage, matcher = VcrMatcher.exact)

    val res3 = req1.send(recordedBackend)
    val res4 = req2.send(recordedBackend)

    assertEquals(
      res1.copy(body = res1.body.map(_.toSeq)),
      Response(Right(binaryEntity.toSeq): Either[String, Seq[Byte]], StatusCode.Ok)
    )
    assertEquals(res2, Response(Right(jsonEntity): Either[String, String], StatusCode.Ok))

    assertEquals(res3.body.map(_.toSeq), Right(binaryEntity.toSeq))
    assertEquals(res3.code, StatusCode.Ok)
    assertEquals(res3.headers, immutable.Seq(Header(VcrClient.vcrCacheHeaderName, "true")))

    assertEquals(res4.body, Right(jsonEntity))
    assertEquals(res4.headers, immutable.Seq(Header(VcrClient.vcrCacheHeaderName, "true")))

    assertEquals(counter.get(), 2)
  }

  server.test("Records real responses and replays them without hitting the server") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/json")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    val recorded = request.send(recorder)
    recorder.close()

    assertEquals(recorded.body, Right("""{"hello":"world","unicode":"日本語"}"""))
    assert(!cached(recorded))
    assertEquals(server.hits, 1)

    val replayer = VcrBackend(HttpClientSyncBackend(), storage)
    val replayed = request.send(replayer)
    replayer.close()

    assertEquals(replayed.body, recorded.body)
    assertEquals(replayed.code, StatusCode.Ok)
    assertEquals(replayed.contentType, Some("application/json"))
    assertEquals(replayed.request.uri, request.uri)
    assert(cached(replayed))
    assertEquals(server.hits, 1)
  }

  server.test("Identical requests are replayed in the order they were recorded") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/counter")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    assertEquals(List.fill(3)(request.send(recorder).body), List(Right("1"), Right("2"), Right("3")))
    recorder.close()

    val replayer = VcrBackend(HttpClientSyncBackend(), storage)
    assertEquals(List.fill(3)(request.send(replayer).body), List(Right("1"), Right("2"), Right("3")))
    assertEquals(server.hits, 3)

    // Once recorded entries run out, new requests go through and get recorded.
    assertEquals(request.send(replayer).body, Right("4"))
    replayer.close()
    assertEquals(storage.read().toOption.flatten.map(_.entries.size), Some(4))
  }

  server.test("Error responses are recorded and replayed") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/missing")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    assertEquals(request.send(recorder).code, StatusCode.NotFound)
    recorder.close()

    val replayed = request.send(VcrBackend(HttpClientSyncBackend(), storage))
    assertEquals(replayed.code, StatusCode.NotFound)
    assertEquals(replayed.body, Left("not found"))
    assertEquals(server.hits, 1)
  }

  server.test("Request bodies are recorded and still sent intact") { server =>
    val storage = new InMemoryVcrStorage()
    val backend = VcrBackend(HttpClientSyncBackend(), storage, matcher = VcrMatcher.exact)
    val uri     = uri"${server.uri("/echo")}"

    val bytes      = "stream body ✓".getBytes(StandardCharsets.UTF_8)
    val buffer     = ByteBuffer.allocate(64).put("buffer body".getBytes(StandardCharsets.UTF_8))
    buffer.flip()
    val fromStream = basicRequest.post(uri).body(new ByteArrayInputStream(bytes)).send(backend)
    val fromBuffer = basicRequest.post(uri).body(buffer).send(backend)
    val fromString = basicRequest.post(uri).body("string body").send(backend)

    assertEquals(fromStream.body, Right("stream body ✓"))
    assertEquals(fromBuffer.body, Right("buffer body"))
    assertEquals(fromString.body, Right("string body"))
    assertEquals(backend.newlyRecorded().map(_.request.body), Vector("stream body ✓", "buffer body", "string body"))
  }

  server.test("Requests rejected by shouldRecord are sent but not recorded") { server =>
    val storage = new InMemoryVcrStorage()
    val backend =
      VcrBackend(HttpClientSyncBackend(), storage, matcher = VcrMatcher.default.withShouldRecord(_ => false))
    val request = basicRequest.get(uri"${server.uri("/counter")}")

    assertEquals(request.send(backend).body, Right("1"))
    assertEquals(request.send(backend).body, Right("2"))
    assert(backend.newlyRecorded().isEmpty)
  }

  server.test("notRecordedThrowsErrors fails without sending the request") { server =>
    val backend = VcrBackend(
      HttpClientSyncBackend(),
      new InMemoryVcrStorage(),
      RecordOptions.default.notRecordedThrowsErrors(true),
      VcrMatcher.default.withShouldRecord(_ => false)
    )

    val error = intercept[Exception](basicRequest.get(uri"${server.uri("/counter")}").send(backend))
    assert(error.getMessage.contains("Recording is disabled"), error.getMessage)
    assertEquals(server.hits, 0)
  }

  server.test("Multipart requests are passed through without being recorded") { server =>
    val backend = VcrBackend(HttpClientSyncBackend(), new InMemoryVcrStorage())

    val response = basicRequest.post(uri"${server.uri("/echo")}").multipartBody(multipart("a", "b")).send(backend)

    assertEquals(response.code, StatusCode.Ok)
    assert(backend.newlyRecorded().isEmpty)
  }

  server.test("Recordings persist across backends with FileVcrStorage") { server =>
    val path    = Files.createTempDirectory("vcr").resolve("sttp.json")
    val request = basicRequest.get(uri"${server.uri("/json")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), FileVcrStorage(path, JsonCodec))
    request.send(recorder)
    recorder.close()
    assert(Files.exists(path))

    val replayed = request.send(VcrBackend(HttpClientSyncBackend(), FileVcrStorage(path, JsonCodec)))
    assert(cached(replayed))
    assertEquals(server.hits, 1)
  }

  server.test("Binary responses and requests are replayed byte for byte") { server =>
    val storage = new InMemoryVcrStorage()
    val binary  = basicRequest.get(uri"${server.uri("/binary")}").response(asByteArrayAlways)
    val echo    =
      basicRequest.post(uri"${server.uri("/echo")}").body(TestHttpServer.binaryBody).response(asByteArrayAlways)

    val recorder = VcrBackend(HttpClientSyncBackend(), storage, matcher = VcrMatcher.methodUriAndBody)
    assertEquals(binary.send(recorder).body.toList, TestHttpServer.binaryBody.toList)
    assertEquals(echo.send(recorder).body.toList, TestHttpServer.binaryBody.toList)
    recorder.close()

    val replayer = VcrBackend(HttpClientSyncBackend(), storage, matcher = VcrMatcher.methodUriAndBody)
    val replayed = binary.send(replayer)
    assert(cached(replayed))
    assertEquals(replayed.body.toList, TestHttpServer.binaryBody.toList)
    assertEquals(echo.send(replayer).body.toList, TestHttpServer.binaryBody.toList)
    assertEquals(server.hits, 2)
    assertEquals(
      storage.read().toOption.flatten.map(_.entries.map(_.response.bodyEncoding)),
      Some(Vector.fill(2)(BodyEncoding.Base64))
    )
  }

  server.test("ReplayOnly fails unrecorded requests without sending them") { server =>
    val backend =
      VcrBackend(HttpClientSyncBackend(), new InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))

    val error = intercept[VcrException](basicRequest.get(uri"${server.uri("/counter")}").send(backend))
    assert(error.getMessage.contains("No recorded entry matches"), error.getMessage)
    assertEquals(server.hits, 0)
  }

  server.test("The requested HTTP version is recorded") { server =>
    val backend = VcrBackend(HttpClientSyncBackend(), new InMemoryVcrStorage())
    basicRequest.get(uri"${server.uri("/json")}").httpVersion(sttp.model.HttpVersion.HTTP_1_1).send(backend)
    basicRequest.get(uri"${server.uri("/counter")}").send(backend)

    assertEquals(backend.newlyRecorded().map(_.request.httpVersion), Vector(Some("HTTP/1.1"), None))
  }

  server.test("Decompressed responses are recorded without their Content-Encoding and Content-Length") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/gzip")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    assertEquals(request.send(recorder).body, Right("""{"compressed":true}"""))
    recorder.close()

    val headers =
      storage.read().toOption.flatten.flatMap(_.entries.headOption).map(_.response.headers.keySet.map(_.toLowerCase))
    assert(headers.exists(h => !h.contains("content-encoding") && !h.contains("content-length")), headers.toString)
    assertEquals(request.send(VcrBackend(HttpClientSyncBackend(), storage)).body, Right("""{"compressed":true}"""))
  }

  server.test("Ignored responses still record the body") { server =>
    val storage = new InMemoryVcrStorage()
    val ignored = basicRequest.get(uri"${server.uri("/counter")}").response(ignore)

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    ignored.send(recorder)
    recorder.close()

    val replayer = VcrBackend(HttpClientSyncBackend(), storage)
    val replayed = ignored.send(replayer)
    assert(cached(replayed))
    assertEquals(storage.read().toOption.flatten.map(_.entries.map(_.response.body)), Some(Vector("1")))
  }

  server.test("Multipart requests fail in ReplayOnly without being sent") { server =>
    val backend =
      VcrBackend(HttpClientSyncBackend(), new InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))

    val error = intercept[VcrException](
      basicRequest.post(uri"${server.uri("/echo")}").multipartBody(multipart("a", "b")).send(backend)
    )
    assert(error.getMessage.contains("Cannot record"), error.getMessage)
    assertEquals(server.hits, 0)
  }

  server.test("close fails when saving fails") { server =>
    val storage = new InMemoryVcrStorage()
    val request = basicRequest.get(uri"${server.uri("/json")}")

    val recorder = VcrBackend(HttpClientSyncBackend(), storage)
    request.send(recorder)
    recorder.close()

    val options = RecordOptions.default.unusedEntries(UnusedEntries.Fail)
    intercept[VcrException](VcrBackend(HttpClientSyncBackend(), storage, options).close())
  }
}
