package bekindrewind

sealed trait VcrMatcher {

  /**
   * Appends another VcrMatcher to this VcrMatcher. The specified VcrMatcher is attached to the end, meaning if the 1st
   * VcrMatcher matches the request, the 2nd one
   */
  def append(other: VcrMatcher): VcrMatcher

  /** Alias for [[append]] */
  def :+(other: VcrMatcher): VcrMatcher = append(other)

  /**
   * Returns a VcrKey which is used to group/bucket requests that are considered equivalent (based on the specified
   * "groupBy" function.
   */
  def group(request: VcrRequest): VcrKey

  /** Finds the first VcrMatcher that matches the specified VcrRequest. */
  def matcherFor(request: VcrRequest): Option[VcrMatcher]

  /** Returns whether the specified request will be recorded by this matcher or not. */
  def shouldRecord(request: VcrRequest): Boolean = matcherFor(request).isDefined

  /**
   * Transforms a VcrEntry with the transformer attached to this matcher. One usage for this is to filter sensitive data
   * from a request/response.
   */
  def transform(entry: VcrEntry): VcrEntry

  /**
   * Attached the specified grouping function to this matcher.
   */
  def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher

  /**
   * Attaches the specified predicate to determine whether the request should be recorded or not to this matcher.
   */
  def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher

  /**
   * Attaches the specified VCR entry transformer to this matcher.
   */
  def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher
}

object VcrMatcher {

  /**
   * The default VcrMatcher which matches on HTTP method + URI. This does not match on the request body, HTTP headers,
   * etc.
   */
  def default: VcrMatcher = VcrMatcher.One(
    grouper = req => VcrKey(req.method, req.uri),
    shouldRecordPredicate = _ => true,
    transformer = entry => entry
  )

  /**
   * A VcrMatcher that matches on the entire VcrRequest as is. In other words, it matches on every single field.
   */
  def identity: VcrMatcher = VcrMatcher.groupBy(req => req)

  /**
   * Create a VcrMatcher with the specified grouping function. Example:
   *
   * {{{
   * VcrMatcher.groupBy(req => (req.method, req.uri))
   * }}}
   */
  def groupBy[K](groupFn: VcrRequest => K): VcrMatcher =
    VcrMatcher.One(
      grouper = req => VcrKey.Grouped(groupFn(req)),
      shouldRecordPredicate = _ => true,
      transformer = entry => entry
    )

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

    override def transform(entry: VcrEntry): VcrEntry = transformer(entry)

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

    override def transform(entry: VcrEntry): VcrEntry =
      matcherFor(entry.request).map(_.transform(entry)).getOrElse(entry)

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
