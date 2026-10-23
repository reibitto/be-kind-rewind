package bekindrewind

/** What a client should do with a request. Returned by [[VcrClient.resolve]]. */
sealed trait VcrAction

object VcrAction {

  /** Return the recorded response without sending the request. */
  final case class Replay(entry: VcrEntry) extends VcrAction

  /** Send the request and record the response with [[VcrClient.addNewEntry]]. */
  case object Record extends VcrAction

  /** Send the request without recording it. */
  case object PassThrough extends VcrAction

  /** Fail without sending the request, e.g. because the record mode forbids it or the recording can't be read. */
  final case class Fail(error: Throwable) extends VcrAction
}

final class VcrException(message: String) extends RuntimeException(message)
