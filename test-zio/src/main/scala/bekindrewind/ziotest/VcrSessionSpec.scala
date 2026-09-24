package bekindrewind.ziotest

import zio.test._
import zio.{ Chunk, Exit, Trace, ZIO }

import scala.collection.mutable

/**
 * Starts a VCR test for every test in the spec, so that clients created with `vcrSession.storage` outside of tests (for
 * example by dependency injection) record to the running test. Tests run sequentially.
 *
 * Tests are named after their labels below the root suite, e.g. `payments / creates a payment`. zio-test allows
 * duplicate names, so later duplicates are numbered (`creates a payment (2)`) to keep their recordings apart.
 *
 * {{{
 * object PaymentsSpec extends ZIOSpecDefault with VcrSessionSpec {
 *   val client = VcrBackend(backend, vcrSession.storage)   // or injected
 *
 *   def spec = suite("PaymentsSpec")(test("creates a payment") { ... })
 * }
 * }}}
 */
trait VcrSessionSpec extends ZIOSpecAbstract with VcrSpec {

  override def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
    super.aspects :+ vcrSessionAspect

  /** Include this when overriding `aspects` without calling `super.aspects`. */
  protected def vcrSessionAspect: TestAspectAtLeastR[Any] =
    new TestAspect[Nothing, Any, Nothing, Any] {
      override def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        TestAspect.sequential.some(withSession(spec, Chunk.empty, mutable.Map.empty))
    }

  private def withSession[R, E](spec: Spec[R, E], labels: Chunk[String], seen: mutable.Map[String, Int])(implicit
    trace: Trace
  ): Spec[R, E] =
    spec.caseValue match {
      case Spec.ExecCase(exec, s)           => Spec.exec(exec, withSession(s, labels, seen))
      case Spec.LabeledCase(label, s)       => Spec.labeled(label, withSession(s, labels :+ label, seen))
      case Spec.ScopedCase(scoped)          => Spec.scoped[R](scoped.map(withSession(_, labels, seen)))
      case Spec.MultipleCase(specs)         => Spec.multiple(specs.map(withSession(_, labels, seen)))
      case Spec.TestCase(test, annotations) =>
        val name     = (if (labels.size > 1) labels.drop(1) else labels).mkString(" / ")
        val count    = seen.updateWith(name)(n => Some(n.fold(1)(_ + 1))).getOrElse(1)
        val testName = if (count == 1) name else s"$name ($count)"

        Spec.test(
          ZIO.acquireReleaseExitWith(ZIO.succeed(vcrSession.start(vcrContext(testName)))) {
            (_: Unit, exit: Exit[TestFailure[E], TestSuccess]) =>
              ZIO.attempt(vcrSession.finish(exit.isSuccess)).orDie
          }(_ => test),
          annotations
        )
    }
}
