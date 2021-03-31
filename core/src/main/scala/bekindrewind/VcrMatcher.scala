package bekindrewind

// TODO: I don't like this `Any`. Can I take a different approach?
final case class VcrMatcher(groupFn: VcrRecordRequest => Any)

object VcrMatcher {
  def groupBy(groupFn: VcrRecordRequest => Any): VcrMatcher =
    VcrMatcher(groupFn)
}
