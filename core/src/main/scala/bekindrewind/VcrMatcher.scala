package bekindrewind

sealed trait VcrMatcher {

  /**
   * Appends another VcrMatcher to this VcrMatcher. The specified VcrMatcher is attached to the end, meaning if the
   * 1st VcrMatcher matches the request, the 2nd one
   */
  def append(other: VcrMatcher): VcrMatcher

  /** Alias for [[append]] */
  def :+(other: VcrMatcher): VcrMatcher = append(other)

  /**
   * Returns a VcrKey which is used to group/bucket requests that are considered equivalent (based on the specified
   * "groupBy" function.
   */
  def group(request: VcrRequest): VcrKey

  def matcherFor(request: VcrRequest): Option[VcrMatcher]

  /** Returns whether the specified request will be recorded by this matcher or not. */
  def shouldRecord(request: VcrRequest): Boolean = matcherFor(request).isDefined

  def transform(record: VcrEntry): VcrEntry

  def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher

  /**
   * Applies the specified predicate to determine whether the request should be recorded or not.
   */
  def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher

  def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher
}

object VcrMatcher {
  def default: VcrMatcher  = VcrMatcher.One(req => VcrKey(req.method, req.uri), _ => true, r => r)
  def identity: VcrMatcher = VcrMatcher.groupBy(VcrKey(_))

  def groupBy[K](groupFn: VcrRequest => K): VcrMatcher =
    VcrMatcher.One(req => VcrKey.Grouped(groupFn(req)), _ => true, r => r)

  final case class One(
    grouper: VcrRequest => VcrKey,
    shouldRecordPredicate: VcrRequest => Boolean,
    transformer: VcrEntry => VcrEntry
  ) extends VcrMatcher {
    override def group(request: VcrRequest): VcrKey =
      if (shouldRecord(request))
        grouper(request)
      else
        VcrKey.Ungrouped

    override def matcherFor(request: VcrRequest): Option[VcrMatcher] =
      if (shouldRecordPredicate(request)) Some(this) else None

    override def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher =
      copy(shouldRecordPredicate = r => shouldRecordPredicate(r) && pred(r))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One   => VcrMatcher.Many(Vector(this, m))
      case VcrMatcher.Many(ms) => VcrMatcher.Many(this +: ms)
    }

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher =
      copy(transformer = transformer)

    override def transform(record: VcrEntry): VcrEntry = transformer(record)

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher = copy(grouper = grouper)
  }

  final case class Many(matchers: Vector[VcrMatcher.One]) extends VcrMatcher {
    override def group(request: VcrRequest): VcrKey =
      matcherFor(request).map(_.group(request)).getOrElse(VcrKey.Ungrouped)

    override def matcherFor(request: VcrRequest): Option[VcrMatcher] =
      matchers.find(_.shouldRecordPredicate(request))

    override def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher =
      VcrMatcher.Many(
        matchers.map { matcher =>
          matcher.copy(shouldRecordPredicate = pred)
        }
      )

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One         => VcrMatcher.Many(this.matchers :+ m)
      case VcrMatcher.Many(matchers) => VcrMatcher.Many(this.matchers ++ matchers)
    }

    override def transform(record: VcrEntry): VcrEntry =
      matcherFor(record.request).map(_.transform(record)).getOrElse(record)

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher =
      VcrMatcher.Many(
        matchers.map { matcher =>
          matcher.copy(transformer = transformer)
        }
      )

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher =
      VcrMatcher.Many(
        matchers.map { matcher =>
          matcher.copy(grouper = grouper)
        }
      )
  }
}
