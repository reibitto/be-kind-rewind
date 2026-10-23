package bekindrewind.playwsstandalone

import bekindrewind.storage.VcrStorage
import bekindrewind.{ RecordOptions, VcrClient, VcrMatcher, VcrSettings }
import org.apache.pekko.stream.Materializer
import play.api.libs.ws.{ StandaloneWSClient, StandaloneWSRequest }

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

  /** Closes the underlying client, then rethrows any error from saving (e.g. because of unused entries). */
  override def close(): Unit =
    try save()
    finally underlyingClient.close()
}

object VcrStandaloneWSClient {
  def apply(
    underlyingClient: StandaloneWSClient,
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  )(implicit materializer: Materializer): VcrStandaloneWSClient =
    new VcrStandaloneWSClient(underlyingClient, storage, recordOptions, matcher)

  def apply(underlyingClient: StandaloneWSClient, settings: VcrSettings)(implicit
    materializer: Materializer
  ): VcrStandaloneWSClient =
    apply(underlyingClient, settings.storage, settings.recordOptions, settings.matcher)
}
