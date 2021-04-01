package bekindrewind.akkahttp

import bekindrewind._

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import munit._

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class VcrHttpSpec extends FunSuite {
  test("Save and load") {
    implicit val system = ActorSystem.create()

    val counter = new AtomicInteger(0)

    val binaryEntity    = HttpEntity.apply(Array[Byte](1, 2, 3))
    val jsonEntity      = HttpEntity(ContentTypes.`application/json`, """{"a":1}""")
    val stubSendRequest = (_: HttpRequest) => {
      Future.successful(HttpResponse(entity = counter.incrementAndGet() match {
        case 1 => binaryEntity
        case 2 => jsonEntity
      }))
    }

    val recordingPath = Files.createTempFile("test", ".vcr")
    val vcrClient     = VcrHttp.create(stubSendRequest, recordingPath, matcher = VcrMatcher(identity))
    val req           = HttpRequest()
    for {
      res1          <- vcrClient.send(req)
      res2          <- vcrClient.send(req)
      _              = vcrClient.close()
      recordedClient = VcrHttp.create(stubSendRequest, recordingPath, matcher = VcrMatcher(identity))
      res3          <- recordedClient.send(req)
      res4          <- recordedClient.send(req)
    } yield {
      assertEquals(res1, HttpResponse(entity = binaryEntity))
      assertEquals(res2, HttpResponse(entity = jsonEntity))

      assertEquals(res3, HttpResponse(entity = binaryEntity))
      assertEquals(res4, HttpResponse(entity = jsonEntity))

      assertEquals(counter.get(), 2)
    }
  }
}
