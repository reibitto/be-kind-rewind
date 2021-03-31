package bekindrewind

import zio.test._
import zio.test.Assertion._
import io.circe.parser._
import io.circe.syntax._

import java.net.URI
import java.nio.file._
import java.time.OffsetDateTime
import scala.jdk.CollectionConverters._

object VcrClientSpec extends DefaultRunnableSpec {
  def spec = suite("VcrClientSpec")(
    test("Request and response are saved as JSON") {
      val recordingPath = Files.createTempFile("test", ".json")
      val client        = MockClient(recordingPath, RecordOptions.default, VcrMatcher(_ => true))
      val record        = VcrRecord(
        VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty),
        VcrRecordResponse(200, "ok", Map.empty, "{}"),
        OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
      )
      client.newlyRecorded.set(Vector(record))
      client.save()

      val savedJson = Files.readAllLines(recordingPath).asScala.mkString("")
      val decoded   = decode[VcrRecords](savedJson).map(_.records)
      assert(decoded)(equalTo(Right(Vector(record))))
    },
    test("Client loads the previous record when being constructed") {
      val req     = VcrRecordRequest("GET", new URI("https://example.com/foo.json"), "{}", Map.empty)
      val res     = VcrRecordResponse(200, "ok", Map.empty, "{}")
      val at      = OffsetDateTime.parse("2100-05-06T12:34:56.789Z")
      val rawJson = s"""{ "version": "0.1.0",
                       |  "records": [
                       |    { "request": ${req.asJson},
                       |      "response": ${res.asJson},
                       |      "recordedAt": ${at.asJson}
                       |    }
                       |  ]
                       |}""".stripMargin

      val recordingPath = Files.createTempFile("test", ".json")
      Files.write(recordingPath, Seq(rawJson).asJava)

      val client               = MockClient(recordingPath, RecordOptions.default, VcrMatcher(_ => true))
      val maybeStatefulRecords = client.previouslyRecorded.get(true)
      assert(maybeStatefulRecords.map(_.records))(equalTo(Some(Vector(VcrRecord(req, res, at))))) &&
      assert(maybeStatefulRecords.map(_.currentIndex.get()))(equalTo(Some(0)))
    }
  )
}

case class MockClient(recordingPath: Path, recordOptions: RecordOptions, matcher: VcrMatcher) extends VcrClient
