package bekindrewind

trait ResponseTransformer  {
  def apply(response: VcrRecordResponse): VcrRecordResponse
}
object ResponseTransformer {
  val noop: ResponseTransformer = identity
}
