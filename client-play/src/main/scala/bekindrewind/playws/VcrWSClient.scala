package bekindrewind.playws

import akka.stream.Materializer
import bekindrewind.storage.VcrStorage
import bekindrewind.{ RecordOptions, VcrClient, VcrKey, VcrMatcher }
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

  override def close(): Unit = {
    Try(save()).failed.foreach(_.printStackTrace())
    underlyingClient.close()
  }
}

object VcrWSClient {
  def apply(
    underlyingClient: WSClient,
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.groupBy(r => VcrKey(r.method, r.uri))
  )(implicit materializer: Materializer) =
    new VcrWSClient(underlyingClient, storage, recordOptions, matcher)
}
