package bekindrewind.scalatest

import bekindrewind.VcrClient
import bekindrewind.codec.{ Codec, JsonCodec }
import bekindrewind.testkit.{ VcrTestContext, VcrTestSupport }
import org.scalatest.{ AsyncTestSuite, FutureOutcome, Outcome, TestSuite }

import scala.concurrent.Future
import scala.util.{ DynamicVariable, Try }

/**
 * Creates VCR clients for tests, stored according to [[vcrLayout]] (one file per test by default).
 *
 * {{{
 * class UserApiSpec extends AnyFunSuite with VcrSuite {
 *   val backend = HttpClientSyncBackend()
 *
 *   test("gets a user") {
 *     withVcr(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)) { backend => ... }
 *   }
 * }
 * }}}
 */
trait VcrSuite extends TestSuite with VcrTestSupport {
  override def vcrCodec: Codec = JsonCodec

  private val currentTestName = new DynamicVariable[Option[String]](None)

  override protected def withFixture(test: NoArgTest): Outcome =
    currentTestName.withValue(Some(test.name))(super.withFixture(test))

  /**
   * Creates a VCR client for the current test and saves its recordings once `use` completes. Whether `use` succeeded
   * determines whether unused entries are reported, so put the test's assertions inside it.
   */
  def withVcr[C <: VcrClient, A](create: VcrTestContext => C)(use: C => A): A = {
    val client = create(vcrContext(VcrSuite.testName(currentTestName.value)))

    val result = vcrOnFailure(use(client))(vcrSave(client, succeeded = false))
    vcrSave(client, succeeded = true)
    result
  }
}

/** [[VcrSuite]] for async suites such as `AsyncFunSuite`. */
trait AsyncVcrSuite extends AsyncTestSuite with VcrTestSupport {
  override def vcrCodec: Codec = JsonCodec

  private val currentTestName = new DynamicVariable[Option[String]](None)

  override def withFixture(test: NoArgAsyncTest): FutureOutcome =
    currentTestName.withValue(Some(test.name))(super.withFixture(test))

  /**
   * Creates a VCR client for the current test and saves its recordings once the returned future completes. Must be
   * called when the test starts rather than inside a callback, since that's when the test name is known.
   */
  def withVcr[C <: VcrClient, A](create: VcrTestContext => C)(use: C => Future[A]): Future[A] = {
    val client = create(vcrContext(VcrSuite.testName(currentTestName.value)))
    Future.fromTry(Try(use(client))).flatten.transform { result =>
      Try(vcrSave(client, result.isSuccess)).flatMap(_ => result)
    }
  }
}

private object VcrSuite {
  def testName(current: Option[String]): String =
    current.getOrElse(throw new IllegalStateException("withVcr must be called from within a test"))
}
