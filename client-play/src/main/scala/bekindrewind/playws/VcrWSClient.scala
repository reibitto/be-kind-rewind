package bekindrewind.playws

import akka.stream.Materializer
import bekindrewind.{ RecordOptions, VcrClient, VcrMatcher }
import play.api.libs.ws.{ WSClient, WSRequest }

import java.nio.file.Path
import scala.util.Try

class VcrWSClient(
  val underlyingClient: WSClient,
  val recordingPath: Path,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
)(implicit val materializer: Materializer)
    extends WSClient
    with VcrClient {

  override def underlying[T]: T =
    underlyingClient.underlying[T]

  override def url(url: String): WSRequest =
    new VcrWSRequest(underlyingClient.url(url), this)

  override def close(): Unit = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlyingClient.close()
  }
}

object VcrWSClient {
  def apply(
    underlyingClient: WSClient,
    recordingPath: Path,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => (r.method, r.uri))
  )(implicit materializer: Materializer) =
    new VcrWSClient(underlyingClient, recordingPath, recordOptions, matcher)
}
