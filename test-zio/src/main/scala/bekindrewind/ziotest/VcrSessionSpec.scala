package bekindrewind.ziotest

import zio.test._
import zio.{ Chunk, Exit, Trace, ZIO }

/**
 * Starts a VCR test for every test in the spec, so that clients created with `vcrSession.storage` outside of tests (for
 * example by dependency injection) record to the running test. Tests run sequentially and are named as described in
 * [[VcrSpec]].
 *
 * {{{
 * object PaymentsSpec extends ZIOSpecDefault with VcrSessionSpec {
 *   val client = VcrBackend(backend, vcrSession.storage)   // or injected
 *
 *   def spec = suite("PaymentsSpec")(test("creates a payment") { ... })
 * }
 * }}}
 */
trait VcrSessionSpec extends VcrSpec {

  override def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
    super.aspects :+ vcrSessionAspect

  /** Include this when overriding `aspects` without calling `super.aspects`. */
  protected def vcrSessionAspect: TestAspectAtLeastR[Any] =
    new TestAspect[Nothing, Any, Nothing, Any] {
      override def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        TestAspect.sequential.some(VcrSpec.mapTests(spec) { (name, test) =>
          ZIO.acquireReleaseExitWith(ZIO.succeed {
            val context = vcrContext(name)
            vcrSession.start(context)
            context
          }) { (context, exit: Exit[TestFailure[E], TestSuccess]) =>
            ZIO.attempt(vcrSession.finish(context, exit.isSuccess)).orDie
          }(_ => test)
        })
    }
}
