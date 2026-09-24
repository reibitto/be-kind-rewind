package bekindrewind.testkit

/** How recordings are split into files by the test framework integrations. */
sealed trait VcrLayout

object VcrLayout {

  /** One file per test: `<directory>/<suite>/<test>.<extension>`. */
  case object PerTest extends VcrLayout

  /**
   * One file per suite: `<directory>/<suite>.<extension>`. Each test only sees and replaces its own entries (by test
   * name), so running a subset of tests or running tests in parallel works the same as with [[PerTest]].
   */
  case object PerSuite extends VcrLayout
}
