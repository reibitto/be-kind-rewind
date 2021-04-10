package bekindrewind

sealed trait VcrMatcher {

  /**
   * Returns a VcrKey which is used to group/bucket requests that are considered equivalent (based on the specified
   * "groupBy" function.
   */
  def group(request: VcrRecordRequest): VcrKey

  /** Returns whether the specified request will be recorded by this matcher or not. */
  def shouldRecord(request: VcrRecordRequest): Boolean

  /**
   * Applies the specified predicate to determine whether the request should be recorded or not.
   */
  def filter(pred: VcrRecordRequest => Boolean): VcrMatcher

  /**
   * Appends another VcrMatcher to this VcrMatcher. The specified VcrMatcher is attached to the end, meaning if the
   * 1st VcrMatcher matches the request, the 2nd one
   */
  def append(other: VcrMatcher): VcrMatcher

  /** Alias for [[append]] */
  def :+(other: VcrMatcher): VcrMatcher = append(other)
}

object VcrMatcher {
  def default: VcrMatcher  = VcrMatcher.One(req => VcrKey(req.method, req.uri), _ => true)
  def identity: VcrMatcher = VcrMatcher.groupBy(VcrKey(_))

  def groupBy[K](groupFn: VcrRecordRequest => K): VcrMatcher =
    VcrMatcher.One(req => VcrKey.Grouped(groupFn(req)), _ => true)

  final case class One(groupFn: VcrRecordRequest => VcrKey, shouldRecordFn: VcrRecordRequest => Boolean)
      extends VcrMatcher {
    override def group(request: VcrRecordRequest): VcrKey =
      if (shouldRecord(request))
        groupFn(request)
      else
        VcrKey.Ungrouped

    override def shouldRecord(request: VcrRecordRequest): Boolean = shouldRecordFn(request)

    override def filter(pred: VcrRecordRequest => Boolean): VcrMatcher =
      copy(shouldRecordFn = r => shouldRecordFn(r) && pred(r))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One   => VcrMatcher.Many(Vector(this, m))
      case VcrMatcher.Many(ms) => VcrMatcher.Many(this +: ms)
    }
  }

  final case class Many(matchers: Vector[VcrMatcher.One]) extends VcrMatcher {
    override def group(request: VcrRecordRequest): VcrKey =
      matchers.find(_.shouldRecordFn(request)).map(_.group(request)).getOrElse(VcrKey.Ungrouped)

    override def shouldRecord(request: VcrRecordRequest): Boolean =
      matchers.exists(_.shouldRecordFn(request))

    override def filter(pred: VcrRecordRequest => Boolean): VcrMatcher =
      Many(
        matchers.map { matcher =>
          matcher.copy(shouldRecordFn = { req =>
            matcher.shouldRecordFn(req) && pred(req)
          })
        }
      )

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One         => VcrMatcher.Many(this.matchers :+ m)
      case VcrMatcher.Many(matchers) => VcrMatcher.Many(this.matchers ++ matchers)
    }
  }
}
