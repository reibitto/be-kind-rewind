package bekindrewind.munitsuite

import bekindrewind.VcrClient
import bekindrewind.codec.{ Codec, JsonCodec }
import bekindrewind.testkit.{ VcrTestContext, VcrTestSupport }
import munit.{ FunSuite, Location, TestOptions }

import scala.concurrent.Future
import scala.util.Try

/**
 * Creates a VCR client for each test, stored according to [[vcrLayout]] (one file per test by default).
 *
 * {{{
 * class UserApiSpec extends VcrSuite {
 *   val backend = HttpClientSyncBackend()
 *   val vcrBackend = vcr(ctx => VcrBackend(backend, ctx.settings))
 *
 *   vcrBackend.test("gets a user") { backend => ... }
 * }
 * }}}
 */
trait VcrSuite extends FunSuite with VcrTestSupport {
  override def vcrCodec: Codec = JsonCodec

  /** Defines tests that get their own VCR client, saved when the test ends. */
  def vcr[C <: VcrClient](create: VcrTestContext => C): VcrFixture[C] = new VcrFixture(create)

  final class VcrFixture[C <: VcrClient] private[VcrSuite] (create: VcrTestContext => C) {
    def test(name: String)(body: C => Any)(implicit loc: Location): Unit = test(new TestOptions(name))(body)

    // munit's value transforms run effects such as futures or cats-effect `IO`, so the client is saved after they run.
    def test(options: TestOptions)(body: C => Any)(implicit loc: Location): Unit =
      VcrSuite.this.test(options) {
        val (client, close) = vcrOpen(options.name)(create)
        closeOnCompletion(close, munitValueTransform(body(client)))
      }
  }

  /**
   * Creates a VCR client for the named test and saves it once `use` completes. Whether `use` succeeded determines
   * whether unused entries are reported. Use [[withVcrAsync]] if `use` returns a future.
   */
  def withVcr[C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => A): A = {
    val (client, close) = vcrOpen(testName)(create)
    val result          = vcrOnFailure(use(client))(close(false))
    close(true)
    result
  }

  /** Like [[withVcr]], but saves the client once the returned future completes. */
  def withVcrAsync[C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => Future[A]): Future[A] = {
    val (client, close) = vcrOpen(testName)(create)
    closeOnCompletion(close, Future.fromTry(Try(use(client))).flatten)
  }

  private def closeOnCompletion[A](close: Boolean => Unit, future: Future[A]): Future[A] =
    future.transform(outcome => Try(close(outcome.isSuccess)).flatMap(_ => outcome))(munitExecutionContext)
}
