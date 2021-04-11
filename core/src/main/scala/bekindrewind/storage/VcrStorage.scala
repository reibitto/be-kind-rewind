package bekindrewind.storage

import bekindrewind.VcrEntries

trait VcrStorage {
  def read(): Either[Throwable, VcrEntries]

  def write(entries: VcrEntries): Unit
}
