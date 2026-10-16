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

  /**
   * Applies `f` to requests before they are grouped, without changing what is recorded. For ignoring parts of requests
   * that change every run (such as generated IDs) while keeping them in the recordings.
   */
  def normalizeForMatching(f: VcrRequest => VcrRequest): VcrMatcher

  /** Ignores the specified query parameters when matching. Unlike [[redactQueryParams]], they're still recorded. */
  def ignoreQueryParams(names: String*): VcrMatcher =
    normalizeForMatching(r => r.copy(uri = RequestKeys.withoutQueryParams(r.uri, names: _*)))

  /** Transforms URIs before matching, e.g. to replace generated IDs in paths. Recorded URIs are unchanged. */
  def mapUri(f: java.net.URI => java.net.URI): VcrMatcher =
    normalizeForMatching(r => r.copy(uri = f(r.uri)))

  /** Adds redaction rules. See [[Redaction.Rules]]. */
  def withRedaction(rules: Redaction.Rules): VcrMatcher

  /**
   * The request as it would be recorded (redacted and filtered), for showing it in messages without leaking secrets.
   */
  def redact(request: VcrRequest): VcrRequest

  /**
   * Replaces the values of the specified request and response headers (case-insensitive) with a placeholder, as well as
   * those values wherever else they appear in the entry (such as a response body echoing the request).
   */
  def redactHeaders(names: String*): VcrMatcher =
    withRedaction(Redaction.Rules.empty.copy(headers = names.map(_.toLowerCase).toSet))

  /** Replaces the values of the specified query parameters with a placeholder, as well as wherever else they appear. */
  def redactQueryParams(names: String*): VcrMatcher =
    withRedaction(Redaction.Rules.empty.copy(queryParams = names.toSet))

  /** Replaces these values (such as API keys known to the test) wherever they appear in the entry. */
  def redactValues(values: String*): VcrMatcher =
    withRedaction(Redaction.Rules.empty.copy(values = values.toSet))
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
    responseFilter: VcrResponse => VcrResponse = r => r,
    matchingFilter: VcrRequest => VcrRequest = r => r,
    redaction: Redaction.Rules = Redaction.Rules.empty
  ) extends VcrMatcher {
    override def group(request: VcrRequest): VcrKey =
      if (shouldRecordPredicate(request))
        grouper(matchingFilter(requestFilter(redaction.request(request))))
      else
        VcrKey.Ungrouped

    override def matcherFor(request: VcrRequest): Option[VcrMatcher.One] =
      if (shouldRecordPredicate(request)) Some(this) else None

    override def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher.One =
      copy(shouldRecordPredicate = r => shouldRecordPredicate(r) && pred(r))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One   => VcrMatcher.Many(Vector(this, m))
      case VcrMatcher.Many(ms) => VcrMatcher.Many(this +: ms)
    }

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher.One =
      copy(transformer = transformer)

    override def andThenTransform(f: VcrEntry => VcrEntry): VcrMatcher.One =
      copy(transformer = transformer.andThen(f))

    override def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher.One =
      copy(requestFilter = requestFilter.andThen(f))

    override def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher.One =
      copy(responseFilter = responseFilter.andThen(f))

    override def normalizeForMatching(f: VcrRequest => VcrRequest): VcrMatcher.One =
      copy(matchingFilter = matchingFilter.andThen(f))

    override def withRedaction(rules: Redaction.Rules): VcrMatcher.One = copy(redaction = redaction ++ rules)

    override def redact(request: VcrRequest): VcrRequest = requestFilter(redaction.request(request))

    override def transform(entry: VcrEntry): VcrEntry = {
      val transformed = redaction.entry(entry, transformer(entry))
      transformed.copy(
        request = requestFilter(transformed.request),
        response = responseFilter(transformed.response)
      )
    }

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher.One = copy(grouper = grouper)
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

    override def normalizeForMatching(f: VcrRequest => VcrRequest): VcrMatcher = mapEach(_.normalizeForMatching(f))

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher = mapEach(_.withGrouper(grouper))

    override def withRedaction(rules: Redaction.Rules): VcrMatcher = mapEach(_.withRedaction(rules))

    // Requests no matcher accepts aren't recorded, but every matcher's redaction applies to them in error messages.
    override def redact(request: VcrRequest): VcrRequest =
      matcherFor(request).fold(matchers.foldLeft(request)((r, m) => m.redaction.request(r)))(_.redact(request))

    private def mapEach(f: VcrMatcher.One => VcrMatcher.One): VcrMatcher = VcrMatcher.Many(matchers.map(f))
  }
}
