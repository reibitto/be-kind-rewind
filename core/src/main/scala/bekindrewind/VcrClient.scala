package bekindrewind

import bekindrewind.storage.{ SharedRecordingStorage, VcrStorage }

import scala.collection.immutable

trait VcrClient {

  /**
   * Used to match HTTP requests with stored VCR entries to determine whether they should be replayed or not.
   */
  def matcher: VcrMatcher

  /**
   * Used to specify recording options such as the record mode or expiring VCR entries after some time. Ignored when
   * `storage` is a [[storage.SharedRecordingStorage]], which has its own record options.
   */
  def recordOptions: RecordOptions

  /**
   * Responsible for storing/persisting recorded VCR entries to a file and so on (and which format: JSON, YAML, etc.)
   */
  def storage: VcrStorage

  /**
   * The recording this client reads from and writes to. Clients whose storage is a [[storage.SharedRecordingStorage]]
   * (such as the storage of a test context or a [[testkit.VcrSession]]) share that storage's recording.
   */
  def recording: VcrRecording =
    storage match {
      case shared: SharedRecordingStorage => shared.recording
      case _                              => ownRecording
    }

  private lazy val ownRecording: VcrRecording = new VcrRecording(storage, recordOptions)

  protected[bekindrewind] def loaded: Option[VcrEntries] = recording.loaded

  protected[bekindrewind] def previouslyRecorded: Map[VcrKey, StatefulVcrEntries] = recording.groups(matcher)

  def addNewEntry(entry: VcrEntry): Unit = recording.addNewEntry(matcher, entry)

  def newlyRecorded(): immutable.Seq[VcrEntry] = recording.newlyRecorded()

  /** See [[VcrRecording.findMatch]]. */
  def findMatch(vcrRequest: VcrRequest): Option[VcrEntry] = recording.findMatch(matcher, vcrRequest)

  /**
   * Decides whether the request should be replayed, recorded, sent as is, or rejected. Requests the matcher doesn't
   * record don't need a recording, so they're sent as is even when no test of a `VcrSession` is running.
   */
  def resolve(vcrRequest: VcrRequest): VcrAction =
    if (matcher.shouldRecord(vcrRequest)) recording.resolve(matcher, vcrRequest)
    else currentRecording.fold[VcrAction](VcrAction.PassThrough)(_.resolve(matcher, vcrRequest))

  private def currentRecording: Option[VcrRecording] =
    storage match {
      case shared: SharedRecordingStorage => shared.currentRecording
      case _                              => Some(ownRecording)
    }

  /** See [[VcrRecording.unusedEntries]]. */
  def unusedEntries(): Vector[VcrEntry] = recording.unusedEntries()

  /** Saves recordings, treating the test (if any) as successful. */
  def save(): Unit = save(succeeded = true)

  /** See [[VcrRecording.save]]. */
  def save(succeeded: Boolean): Unit = recording.save(succeeded)
}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"

  /** A client without an HTTP library, for adapting HTTP clients that the library doesn't support. */
  def apply(
    storage: VcrStorage,
    matcher: VcrMatcher,
    recordOptions: RecordOptions = RecordOptions.default
  ): VcrClient = {
    val (s, m, o) = (storage, matcher, recordOptions)
    new VcrClient {
      override val storage: VcrStorage          = s
      override val matcher: VcrMatcher          = m
      override val recordOptions: RecordOptions = o
    }
  }

  def notRecordedError(request: VcrRequest): VcrException =
    new VcrException(
      s"Recording is disabled for `${request.method} ${request.uri}`. The HTTP request was not executed."
    )

  def noMatchError(request: VcrRequest, mode: RecordMode): VcrException =
    new VcrException(
      s"No recorded entry matches `${request.method} ${request.uri}` and record mode $mode doesn't allow recording " +
        "new entries. The HTTP request was not executed."
    )
}
