package bekindrewind.scalatest

import org.scalatest.{ FutureOutcome, Outcome }

import scala.util.control.NonFatal

/**
 * Starts a VCR test for every test in the suite, so that clients created with `vcrSession.storage` outside of tests
 * (for example by dependency injection) record to the running test. The suite's tests must run one at a time.
 *
 * {{{
 * class PaymentsSpec extends AnyFunSuite with VcrSessionSuite {
 *   val client = VcrBackend(backend, vcrSession.storage)   // or injected
 *
 *   test("creates a payment") { ... }
 * }
 * }}}
 */
trait VcrSessionSuite extends VcrTestSuite {
  override protected def withFixture(test: NoArgTest): Outcome = {
    vcrSession.start(vcrContext(test.name))

    val outcome =
      try super.withFixture(test)
      catch {
        case NonFatal(e) =>
          try vcrSession.finish(succeeded = false)
          catch { case NonFatal(saveError) => e.addSuppressed(saveError) }
          throw e
      }

    vcrSession.finish(outcome.isSucceeded)
    outcome
  }
}

/** [[VcrSessionSuite]] for async suites such as `AsyncFunSuite`. */
trait AsyncVcrSessionSuite extends AsyncVcrTestSuite {
  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    vcrSession.start(vcrContext(test.name))

    val outcome =
      try super.withFixture(test)
      catch {
        case NonFatal(e) =>
          vcrSession.finish(succeeded = false)
          throw e
      }

    outcome.onCompletedThen(result => vcrSession.finish(result.toOption.exists(_.isSucceeded)))
  }
}
