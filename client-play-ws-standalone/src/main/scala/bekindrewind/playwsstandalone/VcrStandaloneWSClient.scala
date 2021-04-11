package bekindrewind.playwsstandalone

import akka.stream.Materializer
import bekindrewind.{ RecordOptions, VcrClient, VcrKey, VcrMatcher }
import play.api.libs.ws.{ StandaloneWSClient, StandaloneWSRequest }

import java.nio.file.Path
import scala.util.Try

class VcrStandaloneWSClient(
  val underlyingClient: StandaloneWSClient,
  val recordingPath: Path,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
)(implicit val materializer: Materializer)
    extends StandaloneWSClient
    with VcrClient {

  override def underlying[T]: T =
    underlyingClient.underlying[T]

  override def url(url: String): StandaloneWSRequest =
    new VcrStandaloneWSRequest(underlyingClient.url(url), this)

  override def close(): Unit = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlyingClient.close()
  }
}

object VcrStandaloneWSClient {
  def apply(
    underlyingClient: StandaloneWSClient,
    recordingPath: Path,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => VcrKey(r.method, r.uri))
  )(implicit materializer: Materializer) =
    new VcrStandaloneWSClient(underlyingClient, recordingPath, recordOptions, matcher)
}
