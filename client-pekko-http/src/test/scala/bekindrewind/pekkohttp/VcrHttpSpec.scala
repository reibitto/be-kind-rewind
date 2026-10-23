package bekindrewind.pekkohttp

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.TestHttpServer
import munit._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }

class VcrHttpSpec extends FunSuite {
  implicit private lazy val system: ActorSystem = ActorSystem("VcrHttpSpec")

  implicit private def ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def bodyOf(response: HttpResponse): Future[String] =
    response.entity.toStrict(10.seconds).map(_.data.utf8String)

  private def cached(response: HttpResponse): Boolean =
    response.headers.exists(h => h.name() == VcrClient.vcrCacheHeaderName && h.value() == "true")

  test("Save and load with a stubbed sendRequest") {
    val counter = new AtomicInteger(0)

    val binaryEntity    = HttpEntity(Array[Byte](1, 2, 3))
    val jsonEntity      = HttpEntity(ContentTypes.`application/json`, """{"a":1}""")
    val noContentType   = HttpEntity(ContentTypes.NoContentType, ByteString("raw"))
    val stubSendRequest = (_: HttpRequest) =>
      Future.successful(HttpResponse(entity = counter.incrementAndGet() match {
        case 1 => binaryEntity
        case 2 => jsonEntity
        case 3 => noContentType
      }))

    val storage   = new InMemoryVcrStorage()
    val vcrClient = VcrHttp.create(stubSendRequest, storage, RecordOptions.default, VcrMatcher.exact)
    val req1      = HttpRequest(HttpMethods.POST, Uri("/files/new"), entity = HttpEntity(Array[Byte](1, 2, 3)))
    val req2      = HttpRequest(HttpMethods.PUT, Uri("/messages/1"), entity = HttpEntity("hello"))
    val req3      = HttpRequest(HttpMethods.GET, Uri("/raw"))

    val cacheHeader = RawHeader(VcrClient.vcrCacheHeaderName, "true")

    for {
      res1          <- vcrClient.send(req1)
      res2          <- vcrClient.send(req2)
      res3          <- vcrClient.send(req3)
      _              = vcrClient.close()
      recordedClient = VcrHttp.create(stubSendRequest, storage, matcher = VcrMatcher.exact)
      res4          <- recordedClient.send(req1)
      res5          <- recordedClient.send(req2)
      res6          <- recordedClient.send(req3)
    } yield {
      assertEquals(res1, HttpResponse(entity = binaryEntity))
      assertEquals(res2, HttpResponse(entity = jsonEntity))
      assertEquals(res3, HttpResponse(entity = noContentType))

      assertEquals(res4, HttpResponse(entity = binaryEntity, headers = Vector(cacheHeader)))
      assertEquals(res5, HttpResponse(entity = jsonEntity, headers = Vector(cacheHeader)))
      assertEquals(res6, HttpResponse(entity = noContentType, headers = Vector(cacheHeader)))

      assertEquals(counter.get(), 3)
    }
  }

  test("The settings overloads use the given strict timeout") {
    val settings  = VcrSettings(new InMemoryVcrStorage())
    val neverEnds = HttpRequest(
      HttpMethods.POST,
      Uri("/slow"),
      entity = HttpEntity(ContentTypes.`application/octet-stream`, Source.maybe[ByteString])
    )
    val clients   = Seq(
      VcrHttp.create(_ => Future.failed(new IllegalStateException("not sent")), settings, 100.millis),
      VcrHttp.useClassicActorSystem(settings, None, 100.millis)
    )

    Future.traverse(clients)(_.send(neverEnds).failed).map { errors =>
      assert(errors.forall(_.isInstanceOf[TimeoutException]), errors.toString)
    }
  }

  server.test("Records real responses and replays them without hitting the server") { server =>
    val storage = new InMemoryVcrStorage()
    val request = HttpRequest(uri = server.url("/json"))

    val recorder = VcrHttp.useClassicActorSystem(storage)
    for {
      recorded     <- recorder.send(request)
      recordedBody <- bodyOf(recorded)
      _             = recorder.close()
      replayed     <- VcrHttp.useClassicActorSystem(storage).send(request)
      replayedBody <- bodyOf(replayed)
    } yield {
      assertEquals(recordedBody, """{"hello":"world","unicode":"日本語"}""")
      assert(!cached(recorded))
      assertEquals(replayedBody, recordedBody)
      assertEquals(replayed.status, StatusCodes.OK)
      assertEquals(replayed.entity.contentType, ContentTypes.`application/json`)
      assert(cached(replayed))
      assertEquals(server.hits, 1)
    }
  }

  server.test("Streamed response entities can still be read by the caller after being recorded") { server =>
    val client = VcrHttp.useClassicActorSystem(new InMemoryVcrStorage())

    for {
      response <- client.send(HttpRequest(uri = server.url("/large")))
      body     <- bodyOf(response)
    } yield {
      assertEquals(body, TestHttpServer.largeBody)
      assertEquals(client.newlyRecorded().map(_.response.body), Vector(TestHttpServer.largeBody))
    }
  }

  server.test("Streamed request entities are recorded and still sent intact") { server =>
    val client = VcrHttp.useClassicActorSystem(new InMemoryVcrStorage())
    val entity = HttpEntity(
      ContentTypes.`text/plain(UTF-8)`,
      Source(List("chunk-1 ", "chunk-2 ", "✓").map(ByteString(_)))
    )

    for {
      response <- client.send(HttpRequest(HttpMethods.POST, uri = server.url("/echo"), entity = entity))
      body     <- bodyOf(response)
    } yield {
      assertEquals(body, "chunk-1 chunk-2 ✓")
      assertEquals(client.newlyRecorded().map(_.request.body), Vector("chunk-1 chunk-2 ✓"))
    }
  }

  server.test("Error responses are replayed with their status") { server =>
    val storage  = new InMemoryVcrStorage()
    val request  = HttpRequest(uri = server.url("/missing"))
    val recorder = VcrHttp.useClassicActorSystem(storage)

    for {
      _        <- recorder.send(request).flatMap(_.discardEntityBytes().future())
      _         = recorder.close()
      replayed <- VcrHttp.useClassicActorSystem(storage).send(request)
      body     <- bodyOf(replayed)
    } yield {
      assertEquals(replayed.status, StatusCodes.NotFound)
      assertEquals(body, "not found")
      assertEquals(server.hits, 1)
    }
  }

  server.test("notRecordedThrowsErrors fails without sending the request") { server =>
    val client = VcrHttp.useClassicActorSystem(
      new InMemoryVcrStorage(),
      RecordOptions.default.notRecordedThrowsErrors(true),
      VcrMatcher.default.recordOnly(_ => false)
    )

    client.send(HttpRequest(uri = server.url("/counter"))).failed.map { error =>
      assert(error.getMessage.contains("Recording is disabled"), error.getMessage)
      assertEquals(server.hits, 0)
    }
  }

  server.test("Requests rejected by shouldRecord are sent but not recorded") { server =>
    val client =
      VcrHttp.useClassicActorSystem(new InMemoryVcrStorage(), matcher = VcrMatcher.default.recordOnly(_ => false))

    for {
      response <- client.send(HttpRequest(uri = server.url("/counter")))
      body     <- bodyOf(response)
    } yield {
      assertEquals(body, "1")
      assert(client.newlyRecorded().isEmpty)
    }
  }

  server.test("Binary bodies are replayed byte for byte and HTTP versions are recorded") { server =>
    val storage = new InMemoryVcrStorage()
    val request = HttpRequest(uri = server.url("/binary"))

    val recorder = VcrHttp.useClassicActorSystem(storage)
    for {
      recorded      <- recorder.send(request)
      recordedBytes <- recorded.entity.toStrict(10.seconds).map(_.data)
      _              = recorder.close()
      replayed      <- VcrHttp.useClassicActorSystem(storage).send(request)
      replayedBytes <- replayed.entity.toStrict(10.seconds).map(_.data)
    } yield {
      assertEquals(recordedBytes.toList, TestHttpServer.binaryBody.toList)
      assertEquals(replayedBytes.toList, TestHttpServer.binaryBody.toList)
      assertEquals(replayed.entity.contentType.value, "image/png")
      assert(cached(replayed))

      val saved = storage.read().toOption.flatten.flatMap(_.entries.headOption).getOrElse(fail("should be saved"))
      assertEquals(saved.response.bodyEncoding, BodyEncoding.Base64)
      assertEquals(saved.request.httpVersion, Some("HTTP/1.1"))
      assertEquals(saved.response.httpVersion, Some("HTTP/1.1"))
      assertEquals(replayed.protocol, HttpProtocols.`HTTP/1.1`)
    }
  }

  server.test("ReplayOnly fails unrecorded requests without sending them") { server =>
    val client = VcrHttp.useClassicActorSystem(
      new InMemoryVcrStorage(),
      RecordOptions.default.mode(RecordMode.ReplayOnly)
    )

    client.send(HttpRequest(uri = server.url("/counter"))).failed.map { error =>
      assert(PartialFunction.cond(error) { case _: VcrException => true }, error.toString)
      assertEquals(server.hits, 0)
    }
  }

  server.test("Content-Type is recorded as a header and replayed on the entity only") { server =>
    val storage = new InMemoryVcrStorage()
    val request = HttpRequest(
      HttpMethods.POST,
      uri = server.url("/echo"),
      entity = HttpEntity(ContentTypes.`application/json`, """{"a":1}""")
    )

    val recorder = VcrHttp.useClassicActorSystem(storage)
    for {
      _        <- recorder.send(request).flatMap(bodyOf)
      _         = recorder.close()
      replayed <- VcrHttp.useClassicActorSystem(storage).send(request)
    } yield {
      val entry = storage.read().toOption.flatten.flatMap(_.entries.headOption).getOrElse(fail("should be recorded"))
      assertEquals(entry.request.headers.get("Content-Type"), Some(Seq("application/json")))
      assert(entry.response.headers.contains("Content-Type"), entry.response.headers.toString)
      assert(cached(replayed))
      assert(!replayed.headers.exists(_.is("content-type")), replayed.headers.toString)
    }
  }
}
