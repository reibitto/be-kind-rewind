package bekindrewind.munit

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
 *   val vcrBackend = vcr(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions))
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

    // Test bodies can be synchronous or return a future, so the client is saved once either completes.
    def test(options: TestOptions)(body: C => Any)(implicit loc: Location): Unit =
      VcrSuite.this.test(options) {
        val client = create(vcrContext(options.name))
        saveOnFailure(client)(body(client)) match {
          case future: Future[_] => saveOnCompletion(client, future)
          case result            =>
            vcrSave(client, succeeded = true)
            result
        }
      }
  }

  /**
   * Creates a VCR client for the named test and saves it once `use` completes. Whether `use` succeeded determines
   * whether unused entries are reported. Use [[withVcrAsync]] if `use` returns a future.
   */
  def withVcr[C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => A): A = {
    val client = create(vcrContext(testName))
    val result = saveOnFailure(client)(use(client))
    vcrSave(client, succeeded = true)
    result
  }

  /** Like [[withVcr]], but saves the client once the returned future completes. */
  def withVcrAsync[C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => Future[A]): Future[A] = {
    val client = create(vcrContext(testName))
    saveOnCompletion(client, saveOnFailure(client)(use(client)))
  }

  private def saveOnFailure[A](client: VcrClient)(use: => A): A =
    vcrOnFailure(use)(vcrSave(client, succeeded = false))

  private def saveOnCompletion[A](client: VcrClient, future: Future[A]): Future[A] =
    future.transform(outcome => Try(vcrSave(client, outcome.isSuccess)).flatMap(_ => outcome))(munitExecutionContext)
}
