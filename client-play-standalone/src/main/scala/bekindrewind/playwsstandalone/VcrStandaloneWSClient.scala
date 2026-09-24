package bekindrewind.playwsstandalone

import bekindrewind.storage.VcrStorage
import bekindrewind.{ RecordOptions, VcrClient, VcrMatcher }
import org.apache.pekko.stream.Materializer
import play.api.libs.ws.{ StandaloneWSClient, StandaloneWSRequest }

import scala.util.Try

class VcrStandaloneWSClient(
  val underlyingClient: StandaloneWSClient,
  val storage: VcrStorage,
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
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  )(implicit materializer: Materializer): VcrStandaloneWSClient =
    new VcrStandaloneWSClient(underlyingClient, storage, recordOptions, matcher)
}
