package bekindrewind.storage

import bekindrewind.{ VcrEntries, VcrEntry }

trait VcrStorage {

  /**
   * Returns `Right(None)` if nothing has been stored yet. `Left` is reserved for actual failures (such as decoding).
   */
  def read(): Either[Throwable, Option[VcrEntries]]

  def write(entries: VcrEntries): Unit

  /** The test this storage belongs to, if any. */
  def testName: Option[String] = None

  /**
   * For storage used by a single test: labels entries that don't have a test name yet with the specified one when they
   * are written.
   */
  def withTestName(testName: String): VcrStorage = new VcrStorage.TestNamed(this, testName)

  /**
   * For storage shared by several tests (such as one file per suite): only the specified test's entries are read, and
   * writing replaces only that test's entries, leaving the others as they are.
   */
  def scopedToTest(testName: String): VcrStorage = new VcrStorage.TestScoped(this, testName, includeUnlabeled = false)

  /**
   * Like [[scopedToTest]], but entries without a test name are treated as the test's own. For storage that normally
   * belongs to one test, where other tests' entries only show up if names collide.
   */
  def scopedToTestIncludingUnlabeled(testName: String): VcrStorage =
    new VcrStorage.TestScoped(this, testName, includeUnlabeled = true)
}

object VcrStorage {
  private def label(entries: Vector[VcrEntry], testName: String): Vector[VcrEntry] =
    entries.map(entry => if (entry.testName.isDefined) entry else entry.copy(testName = Some(testName)))

  final class TestNamed(underlying: VcrStorage, name: String) extends VcrStorage {
    override def testName: Option[String] = Some(name)

    override def read(): Either[Throwable, Option[VcrEntries]] = underlying.read()

    override def write(entries: VcrEntries): Unit =
      underlying.write(entries.copy(entries = label(entries.entries, name)))
  }

  /** Access is synchronized on the underlying storage, so tests sharing it can run in parallel. */
  final class TestScoped(underlying: VcrStorage, name: String, includeUnlabeled: Boolean) extends VcrStorage {
    override def testName: Option[String] = Some(name)

    private def belongs(entry: VcrEntry): Boolean =
      entry.testName.contains(name) || (includeUnlabeled && entry.testName.isEmpty)

    override def read(): Either[Throwable, Option[VcrEntries]] =
      underlying
        .synchronized(underlying.read())
        .map(_.map(e => e.copy(entries = e.entries.filter(belongs))).filter(_.entries.nonEmpty))

    override def write(entries: VcrEntries): Unit =
      underlying.synchronized {
        val existing = underlying.read().fold(throw _, _.fold(Vector.empty[VcrEntry])(_.entries))
        val others   = existing.filterNot(belongs)
        // Keep the test's entries where they were in the file, so that diffs stay small.
        val position = existing.indexWhere(belongs) match {
          case -1 => others.size
          case i  => i
        }

        underlying.write(entries.copy(entries = others.patch(position, label(entries.entries, name), 0)))
      }
  }
}
