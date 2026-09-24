package bekindrewind.ziotest

import bekindrewind.VcrClient
import bekindrewind.codec.{ Codec, JsonCodec }
import bekindrewind.testkit.{ VcrTestContext, VcrTestSupport }
import zio.internal.stacktracer.SourceLocation
import zio.test.{ Spec, TestResult, ZIOSpecAbstract }
import zio.{ Exit, Trace, ZIO }

/**
 * Creates VCR clients for tests, stored according to [[vcrLayout]] (one file per test by default).
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
trait VcrSpec extends VcrTestSupport { self: ZIOSpecAbstract =>
  override def vcrCodec: Codec = JsonCodec

  /** Like `test`, but also creates a VCR client for the test and saves its recordings when the test ends. */
  def vcrTest[R, E, C <: VcrClient](label: String)(create: VcrTestContext => C)(
    assertion: C => ZIO[R, E, TestResult]
  )(implicit sourceLocation: SourceLocation, trace: Trace): Spec[R, E] =
    zio.test.test(label)(withVcrChecked(label)(create)(assertion)(_.isSuccess))

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
      ZIO.attempt(client.save(exit.exists(succeeded))).orDie
    }(use)
}
