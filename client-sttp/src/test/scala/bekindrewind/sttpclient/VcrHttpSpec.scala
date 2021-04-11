package bekindrewind.sttpclient

import bekindrewind._
import bekindrewind.storage.InMemoryVcrStorage
import munit._
import sttp.client3._
import sttp.client3.testing.SttpBackendStub
import sttp.model.{ Header, StatusCode }

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.immutable

class VcrHttpSpec extends FunSuite {
  test("Save and load") {
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

    val storage = new InMemoryVcrStorage()

    val vcrBackend = VcrBackend(
      underlyingClient = testingBackend,
      storage = storage
    )

    val req1 = basicRequest.response(asByteArray).post(uri"/files/new").body(Array[Byte](1, 2, 3))
    val req2 = basicRequest.put(uri"/messages/1").body("hello")

    val res1  = req1.send(vcrBackend)
    val res1b = res1.copy(body = res1.body.map(_.toSeq)) // Quick hack so that array comparison works
    val res2  = req2.send(vcrBackend)
    vcrBackend.close()

    val recordedBackend = VcrBackend(
      underlyingClient = testingBackend,
      storage = storage,
      matcher = VcrMatcher.identity
    )

    val res3  = req1.send(recordedBackend)
    val res3b = res3.copy(body = res3.body.map(_.toSeq)) // Quick hack so that array comparison works
    val res4  = req2.send(recordedBackend)

    assertEquals(res1b, Response(Right(binaryEntity.toSeq): Either[String, Seq[Byte]], StatusCode.Ok))
    assertEquals(res2, Response(Right(jsonEntity): Either[String, String], StatusCode.Ok))

    assertEquals(
      res3b,
      Response(
        Right(binaryEntity.toSeq): Either[String, Seq[Byte]],
        StatusCode.Ok,
        "",
        Vector(Header(VcrClient.vcrCacheHeaderName, "true"))
      )
    )

    assertEquals(
      res4,
      Response(
        Right(jsonEntity): Either[String, String],
        StatusCode.Ok,
        "",
        headers = immutable.Seq(Header(VcrClient.vcrCacheHeaderName, "true"))
      )
    )

    assertEquals(counter.get(), 2)
  }
}
