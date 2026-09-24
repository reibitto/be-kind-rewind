package bekindrewind

sealed trait VcrMatcher {

  /**
   * Appends another VcrMatcher to this VcrMatcher. The specified VcrMatcher is attached to the end, meaning it is only
   * used for requests that the preceding matchers don't accept (via `shouldRecord`).
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
  def matcherFor(request: VcrRequest): Option[VcrMatcher.One]

  /** Returns whether the specified request will be recorded by this matcher or not. */
  def shouldRecord(request: VcrRequest): Boolean = matcherFor(request).isDefined

  /**
   * Transforms a VcrEntry before it is saved: the transformer runs first, then the request and response filters (see
   * [[mapRequest]] and [[mapResponse]]).
   */
  def transform(entry: VcrEntry): VcrEntry

  /**
   * Attaches the specified grouping function to this matcher.
   */
  def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher

  /**
   * Attaches the specified predicate to determine whether the request should be recorded or not to this matcher.
   */
  def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher

  /**
   * Replaces the VCR entry transformer of this matcher.
   */
  def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher

  /** Composes the specified function after the existing VCR entry transformer. */
  def andThenTransform(f: VcrEntry => VcrEntry): VcrMatcher

  /**
   * Applies `f` to requests both before they are grouped and before they are saved. Since live requests go through the
   * same function as recorded ones, `f` can remove data (such as API keys in a query string) without breaking matching.
   * `f` should be idempotent.
   */
  def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher

  /** Applies `f` to responses before they are saved. */
  def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher

  /** Replaces the values of the specified request and response headers (case-insensitive) with a placeholder. */
  def redactHeaders(names: String*): VcrMatcher =
    mapRequest(r => r.copy(headers = Redaction.headers(r.headers, names)))
      .mapResponse(r => r.copy(headers = Redaction.headers(r.headers, names)))

  /** Replaces the values of the specified query parameters with a placeholder. */
  def redactQueryParams(names: String*): VcrMatcher =
    mapRequest(r => r.copy(uri = Redaction.queryParams(r.uri, names)))
}

object VcrMatcher {

  /**
   * The default VcrMatcher which matches on HTTP method + URI. This does not match on the request body, HTTP headers,
   * etc.
   */
  def default: VcrMatcher = VcrMatcher.groupBy(req => (req.method, req.uri))

  /**
   * A VcrMatcher that matches on the entire VcrRequest as is. In other words, it matches on every single field.
   */
  def identity: VcrMatcher = VcrMatcher.groupBy(req => req)

  /** Matches on HTTP method + URI + body. */
  def methodUriAndBody: VcrMatcher = VcrMatcher.groupBy(req => (req.method, req.uri, req.body))

  /** Matches on HTTP method + URI, where the order of query parameters doesn't matter. */
  def ignoringQueryParamOrder: VcrMatcher =
    VcrMatcher.groupBy(req => (req.method, RequestKeys.sortedQueryParams(req.uri)))

  /** Matches on HTTP method + URI + the values of the specified headers (header names are case-insensitive). */
  def methodUriAndHeaders(headerNames: String*): VcrMatcher =
    VcrMatcher.groupBy(req => (req.method, req.uri, RequestKeys.headers(req, headerNames: _*)))

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
    transformer: VcrEntry => VcrEntry,
    requestFilter: VcrRequest => VcrRequest = r => r,
    responseFilter: VcrResponse => VcrResponse = r => r
  ) extends VcrMatcher {
    override def group(request: VcrRequest): VcrKey =
      if (shouldRecordPredicate(request))
        grouper(requestFilter(request))
      else
        VcrKey.Ungrouped

    override def matcherFor(request: VcrRequest): Option[VcrMatcher.One] =
      if (shouldRecordPredicate(request)) Some(this) else None

    override def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher =
      copy(shouldRecordPredicate = r => shouldRecordPredicate(r) && pred(r))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One   => VcrMatcher.Many(Vector(this, m))
      case VcrMatcher.Many(ms) => VcrMatcher.Many(this +: ms)
    }

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher =
      copy(transformer = transformer)

    override def andThenTransform(f: VcrEntry => VcrEntry): VcrMatcher =
      copy(transformer = transformer.andThen(f))

    override def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher =
      copy(requestFilter = requestFilter.andThen(f))

    override def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher =
      copy(responseFilter = responseFilter.andThen(f))

    override def transform(entry: VcrEntry): VcrEntry = {
      val transformed = transformer(entry)
      transformed.copy(
        request = requestFilter(transformed.request),
        response = responseFilter(transformed.response)
      )
    }

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher = copy(grouper = grouper)
  }

  final case class Many(matchers: Vector[VcrMatcher.One]) extends VcrMatcher {
    override def group(request: VcrRequest): VcrKey =
      matcherFor(request).map(_.group(request)).getOrElse(VcrKey.Ungrouped)

    override def matcherFor(request: VcrRequest): Option[VcrMatcher.One] =
      matchers.find(_.shouldRecordPredicate(request))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One         => VcrMatcher.Many(this.matchers :+ m)
      case VcrMatcher.Many(matchers) => VcrMatcher.Many(this.matchers ++ matchers)
    }

    override def transform(entry: VcrEntry): VcrEntry =
      matcherFor(entry.request).map(_.transform(entry)).getOrElse(entry)

    override def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher = mapEach(_.withShouldRecord(pred))

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher =
      mapEach(_.withTransformer(transformer))

    override def andThenTransform(f: VcrEntry => VcrEntry): VcrMatcher = mapEach(_.andThenTransform(f))

    override def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher = mapEach(_.mapRequest(f))

    override def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher = mapEach(_.mapResponse(f))

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher = mapEach(_.withGrouper(grouper))

    private def mapEach(f: VcrMatcher.One => VcrMatcher): VcrMatcher =
      VcrMatcher.Many(matchers.map(m => f(m).asInstanceOf[VcrMatcher.One]))
  }
}
