package bekindrewind.storage

import bekindrewind.VcrEntries

import java.util.concurrent.atomic.AtomicReference

/**
 * Intended for testing purpose. Do not use in production.
 */
final class InMemoryVcrStorage() extends VcrStorage {

  private val entriesRef: AtomicReference[VcrEntries] = new AtomicReference[VcrEntries]()

  override def read(): Either[Throwable, VcrEntries] = entriesRef.get() match {
    case null    => Left(new IllegalStateException("storage is empty"))
    case entries => Right(entries)
  }

  override def write(entries: VcrEntries): Unit = entriesRef.set(entries)
}
