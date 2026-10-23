package bekindrewind

import bekindrewind.storage.VcrStorage

/**
 * Everything a VCR client needs besides the HTTP client it wraps. Every client accepts it, e.g.
 * `VcrBackend(backend, VcrSettings(storage))`.
 */
final case class VcrSettings(
  storage: VcrStorage,
  recordOptions: RecordOptions = RecordOptions.default,
  matcher: VcrMatcher = VcrMatcher.default
) {
  def withStorage(storage: VcrStorage): VcrSettings = copy(storage = storage)

  def withRecordOptions(recordOptions: RecordOptions): VcrSettings = copy(recordOptions = recordOptions)

  def withMatcher(matcher: VcrMatcher): VcrSettings = copy(matcher = matcher)
}
