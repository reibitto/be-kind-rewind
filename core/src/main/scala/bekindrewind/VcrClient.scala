package bekindrewind

import bekindrewind.storage.{ SharedRecordingStorage, VcrStorage }

import scala.collection.immutable
import scala.util.Try

trait VcrClient {

  /**
   * Used to match HTTP requests with stored VCR entries to determine whether they should be replayed or not.
   */
  def matcher: VcrMatcher

  /**
   * Used to specify recording options such as the record mode or expiring VCR entries after some time. When `storage`
   * is a [[storage.SharedRecordingStorage]] (such as a test context's storage), the shared recording's options are used
   * instead, and requests fail if this client was given different ones (other than the default), since they would be
   * ignored. In the test framework integrations, set them for the suite with `vcrRecordOptions`.
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
      case shared: SharedRecordingStorage => checkOptions(shared.recording)
      case _                              => ownRecording
    }

  private def checkOptions(shared: VcrRecording): VcrRecording =
    if (recordOptions == RecordOptions.default || recordOptions == shared.recordOptions) shared
    else
      throw new VcrException(
        s"This client's record options ($recordOptions) differ from those of the shared recording it uses " +
          s"(${shared.recordOptions}), such as a test context's, so they would be ignored. Set them for the suite " +
          "instead (`vcrRecordOptions` in the test framework integrations), or create the client with `ctx.settings`."
      )

  private lazy val ownRecording: VcrRecording = new VcrRecording(storage, recordOptions)

  protected[bekindrewind] def loaded: Option[VcrEntries] = recording.loaded

  protected[bekindrewind] def previouslyRecorded: Map[VcrKey, VcrEntryGroup] = recording.groups(matcher)

  def addNewEntry(entry: VcrEntry): Unit = recording.addNewEntry(matcher, entry)

  def newlyRecorded(): immutable.Seq[VcrEntry] = recording.newlyRecorded()

  /** See [[VcrRecording.nextMatch]]. */
  def nextMatch(vcrRequest: VcrRequest): Option[VcrEntry] = recording.nextMatch(matcher, vcrRequest)

  @deprecated("Use `nextMatch` instead. It marks the entry it returns as replayed.", "0.2.0")
  def findMatch(vcrRequest: VcrRequest): Option[VcrEntry] = nextMatch(vcrRequest)

  /**
   * Decides whether the request should be replayed, recorded, sent as is, or rejected. Requests the matcher doesn't
   * record don't need a recording, so they're sent as is even when no test of a `VcrSession` is running. Never throws:
   * errors (such as a recording that can't be read) are returned as [[VcrAction.Fail]].
   */
  def resolve(vcrRequest: VcrRequest): VcrAction =
    resolveWith(vcrRequest)(_.resolve(matcher, vcrRequest))

  /** See [[VcrRecording.resolveUnrecordable]]. Never throws, like [[resolve]]. */
  def resolveUnrecordable(vcrRequest: VcrRequest, reason: String): VcrAction =
    resolveWith(vcrRequest)(_.resolveUnrecordable(matcher, vcrRequest, reason))

  private def resolveWith(vcrRequest: VcrRequest)(f: VcrRecording => VcrAction): VcrAction =
    Try {
      if (matcher.shouldRecord(vcrRequest)) f(recording)
      else currentRecording.fold[VcrAction](VcrAction.PassThrough)(f)
    }.fold(VcrAction.Fail(_), identity)

  private def currentRecording: Option[VcrRecording] =
    storage match {
      case shared: SharedRecordingStorage => shared.currentRecording.map(checkOptions)
      case _                              => Some(ownRecording)
    }

  /** See [[VcrRecording.unusedEntries]]. */
  def unusedEntries(): Vector[VcrEntry] = recording.unusedEntries()

  /** Saves recordings, treating the test (if any) as successful. */
  def save(): Unit = save(succeeded = true)

  /** See [[VcrRecording.save]]. Does nothing when no test of a `VcrSession` is running. */
  def save(succeeded: Boolean): Unit = currentRecording.foreach(_.save(succeeded))
}

object VcrClient {

  /** Name of header that specifies if the HTTP response came from the VCR cache or not. */
  val vcrCacheHeaderName: String = "X-VCR-Cache"

  /** A client without an HTTP library, for adapting HTTP clients that the library doesn't support. */
  def apply(
    storage: VcrStorage,
    recordOptions: RecordOptions = RecordOptions.default,
    matcher: VcrMatcher = VcrMatcher.default
  ): VcrClient = {
    val (s, o, m) = (storage, recordOptions, matcher)
    new VcrClient {
      override val storage: VcrStorage          = s
      override val recordOptions: RecordOptions = o
      override val matcher: VcrMatcher          = m
    }
  }

  def apply(settings: VcrSettings): VcrClient = apply(settings.storage, settings.recordOptions, settings.matcher)

  def notRecordedError(request: VcrRequest): VcrException =
    new VcrException(
      s"Recording is disabled for `${request.method} ${request.uri}`. The HTTP request was not executed."
    )

  def noMatchError(request: VcrRequest, mode: RecordMode, expired: Boolean = false): VcrException = {
    val problem =
      if (expired) s"The recording of `${request.method} ${request.uri}` expired (see `expiresAfter`)"
      else s"No recorded entry matches `${request.method} ${request.uri}`"
    new VcrException(
      s"$problem and record mode $mode doesn't allow recording new entries. The HTTP request was not executed."
    )
  }

  def unrecordableError(request: VcrRequest, reason: String): VcrException =
    new VcrException(
      s"Cannot record `${request.method} ${request.uri}` because $reason. The HTTP request was not executed."
    )
}
