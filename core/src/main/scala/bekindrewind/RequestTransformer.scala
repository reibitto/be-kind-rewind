package bekindrewind

trait RequestTransformer  {
  def apply(request: VcrRecordRequest): VcrRecordRequest
}
object RequestTransformer {
  val noop: RequestTransformer = identity
}
