package bekindrewind.playws

import bekindrewind.storage.VcrStorage
import bekindrewind.{ RecordOptions, VcrClient, VcrMatcher }
import org.apache.pekko.stream.Materializer
import play.api.libs.ws.{ WSClient, WSRequest }

import scala.util.Try

class VcrWSClient(
  val underlyingClient: WSClient,
  val storage: VcrStorage,
  val recordOptions: RecordOptions,
  val matcher: VcrMatcher
)(implicit val materializer: Materializer)
    extends WSClient
    with VcrClient {

  override def underlying[T]: T =
    underlyingClient.underlying[T]

  override def url(url: String): WSRequest =
    new VcrWSRequest(underlyingClient.url(url), this)

  /** Closes the underlying client, then rethrows any error from saving (e.g. because of unused entries). */
  override def close(): Unit = {
    val saved = Try(save())
    underlyingClient.close()
    saved.get
  }
}

object VcrWSClient {
  def apply(
    underlyingClient: WSClient,
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  )(implicit materializer: Materializer): VcrWSClient =
    new VcrWSClient(underlyingClient, storage, recordOptions, matcher)
}
