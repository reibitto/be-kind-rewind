package bekindrewind.munitsuite

import scala.concurrent.Future
import scala.util.Try

/**
 * Starts a VCR test for every test in the suite, so that clients created with `vcrSession.storage` outside of tests
 * (for example by dependency injection) record to the running test.
 *
 * {{{
 * class PaymentsSpec extends VcrSessionSuite {
 *   val client = VcrBackend(backend, vcrSession.storage)   // or injected
 *
 *   test("creates a payment") { ... }
 * }
 * }}}
 */
trait VcrSessionSuite extends VcrSuite {
  override def munitTestTransforms: List[TestTransform] =
    super.munitTestTransforms :+ new TestTransform(
      "vcrSession",
      test =>
        test.withBody { () =>
          // munit runs a suite's tests one at a time, so a test that's still running timed out.
          vcrSession.current.foreach { timedOut =>
            System.err.println(s"[be-kind-rewind] Finishing VCR test `${timedOut.testName}`, which timed out")
            vcrSession.finish(timedOut, succeeded = false)
          }

          val context = vcrContext(test.name)
          vcrSession.start(context)

          Future
            .fromTry(Try(test.body()))
            .flatten
            .transform { outcome =>
              Try(vcrSession.finish(context, outcome.isSuccess)).flatMap(_ => outcome)
            }(munitExecutionContext)
        }
    )
}
