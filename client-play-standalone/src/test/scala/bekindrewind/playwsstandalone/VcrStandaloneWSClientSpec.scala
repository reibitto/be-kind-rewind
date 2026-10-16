package bekindrewind.playwsstandalone

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.TestHttpServer
import munit._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.util.ByteString
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.StandaloneWSResponse
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

class VcrStandaloneWSClientSpec extends FunSuite {
  implicit private var system: ActorSystem = _

  implicit private def materializer: Materializer = SystemMaterializer(system).materializer

  implicit private def ec: ExecutionContext = system.dispatcher

  override def beforeAll(): Unit = system = ActorSystem("VcrStandaloneWSClientSpec")

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def client(
    storage: InMemoryVcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  ): VcrStandaloneWSClient =
    VcrStandaloneWSClient(StandaloneAhcWSClient(), storage, recordOptions, matcher)

  private def cached(response: StandaloneWSResponse): Boolean =
    response.header(VcrClient.vcrCacheHeaderName).contains("true")

  server.test("Records real responses and replays them without hitting the server") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      recorded <- recorder.url(server.url("/json")).get()
      _         = recorder.close()
      replayer  = client(storage)
      replayed <- replayer.url(server.url("/json")).get()
      _         = replayer.close()
    } yield {
      assertEquals(recorded.body, """{"hello":"world","unicode":"日本語"}""")
      assert(!cached(recorded))
      assertEquals(replayed.body, recorded.body)
      assertEquals(replayed.status, 200)
      assertEquals(replayed.contentType, "application/json")
      assert(cached(replayed))
      assertEquals(server.hits, 1)
    }
  }

  server.test("Replayed headers are case-insensitive and cookies are parsed") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      _        <- recorder.url(server.url("/cookie")).get()
      _         = recorder.close()
      replayer  = client(storage)
      replayed <- replayer.url(server.url("/cookie")).get()
      _         = replayer.close()
    } yield {
      assert(cached(replayed))
      assertEquals(replayed.header("content-type"), Some("text/plain; charset=UTF-8"))
      assertEquals(replayed.header("CONTENT-TYPE"), Some("text/plain; charset=UTF-8"))

      val cookie = replayed.cookie("session").getOrElse(fail("cookie should be replayed"))
      assertEquals(cookie.value, "abc123")
      assertEquals(cookie.path, Some("/"))
      assert(cookie.httpOnly)
    }
  }

  server.test("Request bodies are recorded and sent") { server =>
    val vcr = client(new InMemoryVcrStorage(), matcher = VcrMatcher.identity)

    for {
      response <- vcr.url(server.url("/echo")).post("hello ✓")
      _         = vcr.close()
    } yield {
      assertEquals(response.body, "hello ✓")
      assertEquals(response.header("X-Request-Method"), Some("POST"))
      assertEquals(vcr.newlyRecorded().map(r => (r.request.method, r.request.body)), Vector(("POST", "hello ✓")))
    }
  }

  server.test("Identical requests are replayed in the order they were recorded") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      a       <- recorder.url(server.url("/counter")).get()
      b       <- recorder.url(server.url("/counter")).get()
      _        = recorder.close()
      replayer = client(storage)
      c       <- replayer.url(server.url("/counter")).get()
      d       <- replayer.url(server.url("/counter")).get()
      _        = replayer.close()
    } yield {
      assertEquals(List(a, b, c, d).map(_.body), List("1", "2", "1", "2"))
      assertEquals(server.hits, 2)
    }
  }

  server.test("Error responses are replayed with their status") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      _        <- recorder.url(server.url("/missing")).get()
      _         = recorder.close()
      replayer  = client(storage)
      replayed <- replayer.url(server.url("/missing")).get()
      _         = replayer.close()
    } yield {
      assertEquals(replayed.status, 404)
      assertEquals(replayed.body, "not found")
      assertEquals(server.hits, 1)
    }
  }

  server.test("notRecordedThrowsErrors fails without sending the request") { server =>
    val vcr = client(
      new InMemoryVcrStorage(),
      RecordOptions.default.notRecordedThrowsErrors(true),
      VcrMatcher.default.withShouldRecord(_ => false)
    )

    vcr.url(server.url("/counter")).get().failed.map { error =>
      vcr.close()
      assert(error.getMessage.contains("Recording is disabled"), error.getMessage)
      assertEquals(server.hits, 0)
    }
  }

  server.test("Streamed request bodies are recorded and still sent intact") { server =>
    val vcr    = client(new InMemoryVcrStorage(), matcher = VcrMatcher.methodUriAndBody)
    val source = Source(List("chunk-1 ", "chunk-2 ", "✓").map(ByteString(_)))

    for {
      response <-
        vcr.url(server.url("/echo")).addHttpHeaders("Content-Type" -> "text/plain; charset=UTF-8").post(source)
      _         = vcr.close()
    } yield {
      assertEquals(response.body, "chunk-1 chunk-2 ✓")
      assertEquals(vcr.newlyRecorded().map(_.request.body), Vector("chunk-1 chunk-2 ✓"))
    }
  }

  server.test("Binary bodies are replayed byte for byte") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      recorded <- recorder.url(server.url("/binary")).get()
      _         = recorder.close()
      replayer  = client(storage)
      replayed <- replayer.url(server.url("/binary")).get()
      _         = replayer.close()
    } yield {
      assertEquals(recorded.bodyAsBytes.toList, TestHttpServer.binaryBody.toList)
      assertEquals(replayed.bodyAsBytes.toList, TestHttpServer.binaryBody.toList)
      assert(cached(replayed))
      assertEquals(
        storage.read().toOption.flatten.map(_.entries.map(_.response.bodyEncoding)),
        Some(Vector(BodyEncoding.Base64))
      )
    }
  }

  server.test("ReplayOnly fails unrecorded requests without sending them") { server =>
    val vcr = client(new InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))

    vcr.url(server.url("/counter")).get().failed.map { error =>
      vcr.close()
      assert(error.isInstanceOf[VcrException], error.toString)
      assertEquals(server.hits, 0)
    }
  }

  server.test("Streamed responses are recorded and replayed") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    def body(response: StandaloneWSResponse) = response.bodyAsSource.runFold(ByteString.empty)(_ ++ _).map(_.utf8String)

    for {
      recorded     <- recorder.url(server.url("/json")).withMethod("GET").stream()
      recordedBody <- body(recorded)
      _             = recorder.close()
      replayed     <- client(storage).url(server.url("/json")).withMethod("GET").stream()
      replayedBody <- body(replayed)
    } yield {
      assertEquals(recordedBody, """{"hello":"world","unicode":"日本語"}""")
      assertEquals(replayedBody, recordedBody)
      assert(cached(replayed))
      assertEquals(server.hits, 1)
    }
  }
}
