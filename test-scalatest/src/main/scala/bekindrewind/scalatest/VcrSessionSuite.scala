package bekindrewind.scalatest

import org.scalatest.{ FutureOutcome, Outcome }

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
trait VcrSessionSuite extends VcrSuite {
  override protected def withFixture(test: NoArgTest): Outcome = {
    val context = vcrContext(test.name)
    vcrSession.start(context)

    val outcome = vcrOnFailure(super.withFixture(test))(vcrSession.finish(context, succeeded = false))

    vcrSession.finish(context, outcome.isSucceeded)
    outcome
  }
}

/** [[VcrSessionSuite]] for async suites such as `AsyncFunSuite`. */
trait AsyncVcrSessionSuite extends AsyncVcrSuite {
  override def withFixture(test: NoArgAsyncTest): FutureOutcome = {
    val context = vcrContext(test.name)
    vcrSession.start(context)

    val outcome = vcrOnFailure(super.withFixture(test))(vcrSession.finish(context, succeeded = false))

    outcome.onCompletedThen(result => vcrSession.finish(context, result.toOption.exists(_.isSucceeded)))
  }
}
