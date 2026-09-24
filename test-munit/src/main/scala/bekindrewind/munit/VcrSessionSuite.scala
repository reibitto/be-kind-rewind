package bekindrewind.munit

import scala.concurrent.Future
import scala.util.control.NonFatal

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
          vcrSession.start(vcrContext(test.name))

          val result =
            try test.body()
            catch { case NonFatal(e) => Future.failed(e) }

          result.transform { outcome =>
            scala.util.Try(vcrSession.finish(outcome.isSuccess)).flatMap(_ => outcome)
          }(munitExecutionContext)
        }
    )
}
