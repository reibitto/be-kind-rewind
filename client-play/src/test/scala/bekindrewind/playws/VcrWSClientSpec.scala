package bekindrewind.playws

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import bekindrewind.testing.TestHttpServer
import munit._
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import org.apache.pekko.util.ByteString
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.json.Json
import play.api.libs.ws.WSResponse
import play.api.libs.ws.ahc.AhcWSClient

import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext }

class VcrWSClientSpec extends FunSuite {
  implicit private lazy val system: ActorSystem = ActorSystem("VcrWSClientSpec")

  implicit private def materializer: Materializer = SystemMaterializer(system).materializer

  implicit private def ec: ExecutionContext = system.dispatcher

  override def afterAll(): Unit = Await.result(system.terminate(), 30.seconds)

  private val server = FunFixture[TestHttpServer](_ => TestHttpServer.start(), _.close())

  private def client(storage: InMemoryVcrStorage, matcher: VcrMatcher = VcrMatcher.default): VcrWSClient =
    VcrWSClient(AhcWSClient(), storage, matcher = matcher)

  private def cached(response: WSResponse): Boolean =
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
      assert(!cached(recorded))
      assert(cached(replayed))
      assertEquals(replayed.status, 200)
      assertEquals(replayed.json, Json.obj("hello" -> "world", "unicode" -> "日本語"))
      assertEquals(replayed.json, recorded.json)
      assertEquals(replayed.header("content-type"), Some("application/json"))
      assertEquals(server.hits, 1)
    }
  }

  server.test("Cookies are replayed") { server =>
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
      assertEquals(replayed.cookie("session").map(_.value), Some("abc123"))
    }
  }

  server.test("Requests built with query parameters and headers are matched") { server =>
    val storage = new InMemoryVcrStorage()
    val matcher = VcrMatcher.groupBy(r => (r.method, r.uri, r.headers.get("X-Test")))

    def send(vcr: VcrWSClient) =
      vcr.url(server.url("/counter")).withQueryStringParameters("a" -> "1").addHttpHeaders("X-Test" -> "yes").get()

    val recorder = client(storage, matcher)

    for {
      first   <- send(recorder)
      _        = recorder.close()
      replayer = client(storage, matcher)
      again   <- send(replayer)
      _        = replayer.close()
    } yield {
      assertEquals(first.body, "1")
      assertEquals(again.body, "1")
      assert(cached(again))
      assertEquals(storage.read().toOption.flatten.map(_.entries.map(_.request.uri.getQuery)), Some(Vector("a=1")))
    }
  }

  server.test("Streamed request bodies are recorded and still sent intact") { server =>
    val vcr    = client(new InMemoryVcrStorage(), VcrMatcher.methodUriAndBody)
    val source = Source(List("a", "b", "✓").map(ByteString(_)))

    for {
      response <-
        vcr.url(server.url("/echo")).addHttpHeaders("Content-Type" -> "text/plain; charset=UTF-8").post(source)
      _         = vcr.close()
    } yield {
      assertEquals(response.body, "ab✓")
      assertEquals(vcr.newlyRecorded().map(_.request.body), Vector("ab✓"))
    }
  }

  server.test("Binary bodies are replayed byte for byte") { server =>
    val storage  = new InMemoryVcrStorage()
    val recorder = client(storage)

    for {
      _        <- recorder.url(server.url("/binary")).get()
      _         = recorder.close()
      replayer  = client(storage)
      replayed <- replayer.url(server.url("/binary")).get()
      _         = replayer.close()
    } yield {
      assertEquals(replayed.bodyAsBytes.toList, TestHttpServer.binaryBody.toList)
      assert(cached(replayed))
    }
  }
}
