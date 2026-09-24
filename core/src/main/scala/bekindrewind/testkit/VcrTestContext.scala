package bekindrewind.testkit

import bekindrewind.RecordOptions
import bekindrewind.storage.VcrStorage

import java.nio.file.Path

/**
 * Everything needed to create a VCR client for a single test.
 *
 * {{{
 * ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)
 * }}}
 *
 * @param path
 *   The recording file. Shared with other tests of the suite when using [[VcrLayout.PerSuite]].
 */
final case class VcrTestContext(
  suiteName: String,
  testName: String,
  path: Path,
  storage: VcrStorage,
  recordOptions: RecordOptions
)

object VcrTestContext {

  /** Turns a suite or test name into a safe file name, e.g. `gets a user (v2)` becomes `gets-a-user-v2`. */
  def fileName(name: String): String = {
    val sanitized = name.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+|-+$", "").take(150)
    if (sanitized.isEmpty) "unnamed" else sanitized
  }
}
