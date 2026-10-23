package bekindrewind.storage

import bekindrewind.VcrEntries

import java.util.concurrent.atomic.AtomicReference

/**
 * Intended for testing purpose. Do not use in production.
 */
final class InMemoryVcrStorage() extends VcrStorage {

  private val entriesRef: AtomicReference[Option[VcrEntries]] = new AtomicReference(None)

  override def read(): Either[Throwable, Option[VcrEntries]] = Right(entriesRef.get())

  override def write(entries: VcrEntries): Unit = entriesRef.set(Some(entries))
}
