package bekindrewind.ziotest

import bekindrewind.VcrClient
import bekindrewind.codec.{ Codec, JsonCodec }
import bekindrewind.testkit.{ VcrTestContext, VcrTestSupport }
import zio.internal.stacktracer.SourceLocation
import zio.test._
import zio.{ Chunk, Exit, FiberRef, Trace, Unsafe, ZIO }

import scala.collection.mutable

/**
 * Creates VCR clients for tests, stored according to [[vcrLayout]] (one file per test by default).
 *
 * Tests are named after their labels below the root suite, e.g. `users / gets a user`, so that tests with the same
 * label in different suites get separate recordings. zio-test allows duplicate names, so later duplicates are numbered
 * (`gets a user (2)`).
 *
 * {{{
 * object UserApiSpec extends ZIOSpecDefault with VcrSpec {
 *   val backend = HttpClientSyncBackend()
 *
 *   def spec = suite("UserApi")(
 *     vcrTest("gets a user")(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)) { backend =>
 *       ZIO.attempt(...).map(user => assertTrue(user.name == "foo"))
 *     }
 *   )
 * }
 * }}}
 */
trait VcrSpec extends ZIOSpecAbstract with VcrTestSupport {
  override def vcrCodec: Codec = JsonCodec

  override def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
    super.aspects :+ vcrTestNameAspect

  /** Include this when overriding `aspects` without calling `super.aspects`. Otherwise tests are named by label. */
  protected def vcrTestNameAspect: TestAspectAtLeastR[Any] =
    new TestAspect[Nothing, Any, Nothing, Any] {
      override def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        VcrSpec.mapTests(spec)((name, test) => VcrSpec.currentTestName.locally(Some(name))(test))
    }

  /** Like `test`, but also creates a VCR client for the test and saves its recordings when the test ends. */
  def vcrTest[R, E, C <: VcrClient](label: String)(create: VcrTestContext => C)(
    assertion: C => ZIO[R, E, TestResult]
  )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[R, E] =
    zio.test.test(label) {
      VcrSpec.currentTestName.get.flatMap { name =>
        withVcrChecked(name.getOrElse(label))(create)(assertion)(_.isSuccess)
      }
    }

  /**
   * Creates a VCR client for the named test and saves its recordings once `use` completes. Whether `use` succeeded
   * determines whether unused entries are reported.
   */
  def withVcr[R, E, C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(use: C => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    withVcrChecked(testName)(create)(use)(_ => true)

  private def withVcrChecked[R, E, C <: VcrClient, A](testName: String)(create: VcrTestContext => C)(
    use: C => ZIO[R, E, A]
  )(succeeded: A => Boolean)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.acquireReleaseExitWith(ZIO.attempt(create(vcrContext(testName))).orDie) { (client: C, exit: Exit[E, A]) =>
      ZIO.attempt(vcrSave(client, exit.exists(succeeded))).orDie
    }(use)
}

object VcrSpec {
  private val currentTestName: FiberRef[Option[String]] =
    Unsafe.unsafe(implicit unsafe => FiberRef.unsafe.make(Option.empty[String]))

  /** Wraps every test of the spec, along with its name (see [[VcrSpec]]). */
  private[ziotest] def mapTests[R, E](spec: Spec[R, E])(
    f: (String, ZIO[R, TestFailure[E], TestSuccess]) => ZIO[R, TestFailure[E], TestSuccess]
  )(implicit trace: Trace): Spec[R, E] = {
    // Scoped specs are only built when they run, so the counts can't be threaded through as values.
    val seen = mutable.Map.empty[String, Int]

    def loop(spec: Spec[R, E], labels: Chunk[String]): Spec[R, E] =
      spec.caseValue match {
        case Spec.ExecCase(exec, s)           => Spec.exec(exec, loop(s, labels))
        case Spec.LabeledCase(label, s)       => Spec.labeled(label, loop(s, labels :+ label))
        case Spec.ScopedCase(scoped)          => Spec.scoped[R](scoped.map(loop(_, labels)))
        case Spec.MultipleCase(specs)         => Spec.multiple(specs.map(loop(_, labels)))
        case Spec.TestCase(test, annotations) =>
          val name  = (if (labels.size > 1) labels.drop(1) else labels).mkString(" / ")
          val count = seen.updateWith(name)(n => Some(n.fold(1)(_ + 1))).getOrElse(1)
          Spec.test(f(if (count == 1) name else s"$name ($count)", test), annotations)
      }

    loop(spec, Chunk.empty)
  }
}
