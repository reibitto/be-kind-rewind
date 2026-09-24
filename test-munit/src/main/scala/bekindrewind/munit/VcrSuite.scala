package bekindrewind.munit

import bekindrewind.VcrClient
import bekindrewind.codec.{ Codec, JsonCodec }
import bekindrewind.testkit.{ VcrTestContext, VcrTestSupport }
import munit.{ FunSuite, Location, TestOptions }

import scala.concurrent.Future
import scala.util.control.NonFatal

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

    def test(options: TestOptions)(body: C => Any)(implicit loc: Location): Unit =
      VcrSuite.this.test(options)(withVcr(options.name)(create)(body))
  }

  /**
   * Creates a VCR client for the named test and saves it once `use` completes (including returned futures). Whether
   * `use` succeeded determines whether unused entries are reported.
   */
  def withVcr[C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => A): A = {
    val client = create(vcrContext(testName))

    val result =
      try use(client)
      catch {
        case NonFatal(e) =>
          try client.save(succeeded = false)
          catch { case NonFatal(saveError) => e.addSuppressed(saveError) }
          throw e
      }

    result match {
      case future: Future[_] =>
        future.transform { outcome =>
          scala.util.Try(client.save(outcome.isSuccess)).flatMap(_ => outcome)
        }(munitExecutionContext).asInstanceOf[A]
      case _                 =>
        client.save()
        result
    }
  }
}
