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
   * Replaces the grouping function of this matcher.
   */
  def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher

  /**
   * Only records requests that satisfy `pred`, in addition to any previous `recordOnly` conditions. Other requests are
   * sent without being recorded, or fail if `RecordOptions.notRecordedThrowsErrors` is set (see [[shouldRecord]]).
   */
  def recordOnly(pred: VcrRequest => Boolean): VcrMatcher

  @deprecated("Use `recordOnly` instead, which also combines predicates rather than replacing them.", "0.2.0")
  def withShouldRecord(pred: VcrRequest => Boolean): VcrMatcher = recordOnly(pred)

  /**
   * Replaces the VCR entry transformer of this matcher.
   */
  def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher

  /** Applies `f` to entries before they are saved, after the existing transformer (see [[withTransformer]]). */
  def mapEntry(f: VcrEntry => VcrEntry): VcrMatcher

  /**
   * Applies `f` to requests both before they are grouped and before they are saved. Since live requests go through the
   * same function as recorded ones, `f` can remove data (such as API keys in a query string) without breaking matching.
   * `f` should be idempotent.
   */
  def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher

  /** Applies `f` to responses before they are saved. */
  def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher

  /**
   * Applies `f` to requests before they are grouped, without changing what is recorded (unlike [[mapRequest]]). For
   * ignoring parts of requests that change every run (such as generated IDs) while keeping them in the recordings.
   */
  def mapRequestForMatching(f: VcrRequest => VcrRequest): VcrMatcher

  /** Transforms URIs before matching, e.g. to replace generated IDs in paths. Recorded URIs are unchanged. */
  def mapUriForMatching(f: java.net.URI => java.net.URI): VcrMatcher =
    mapRequestForMatching(r => r.copy(uri = f(r.uri)))

  /** Ignores the specified query parameters when matching. Unlike [[redactQueryParams]], they're still recorded. */
  def ignoreQueryParams(names: String*): VcrMatcher =
    mapUriForMatching(RequestKeys.withoutQueryParams(_, names: _*))

  /** Ignores the order of query parameters when matching. */
  def ignoreQueryParamOrder: VcrMatcher =
    mapUriForMatching(RequestKeys.sortedQueryParams)

  /** Adds redaction rules to the existing ones. See [[Redaction.Rules]]. */
  def redact(rules: Redaction.Rules): VcrMatcher

  /**
   * The request as it would be recorded (redacted and filtered), for showing it in messages without leaking secrets.
   */
  def redacted(request: VcrRequest): VcrRequest

  /**
   * Replaces the values of the specified request and response headers (case-insensitive) with a placeholder, as well as
   * those values wherever else they appear: request header values throughout the entry (such as a response body echoing
   * the request), and response header values in the response only. See [[Redaction.Rules]].
   */
  def redactHeaders(names: String*): VcrMatcher =
    redact(Redaction.Rules.empty.copy(headers = names.map(_.toLowerCase).toSet))

  /** Replaces the values of the specified query parameters with a placeholder, as well as wherever else they appear. */
  def redactQueryParams(names: String*): VcrMatcher =
    redact(Redaction.Rules.empty.copy(queryParams = names.toSet))

  /** Replaces these values (such as API keys known to the test) wherever they appear in the entry. */
  def redactValues(values: String*): VcrMatcher =
    redact(Redaction.Rules.empty.copy(values = values.toSet))
}

object VcrMatcher {

  /**
   * The default VcrMatcher which matches on HTTP method + URI. This does not match on the request body, HTTP headers,
   * etc.
   */
  def default: VcrMatcher = VcrMatcher.groupBy(req => (req.method, req.uri))

  /** Matches on every field of the request as is, including headers and body. */
  def exact: VcrMatcher = VcrMatcher.groupBy(req => req)

  @deprecated("Use `VcrMatcher.exact` instead.", "0.2.0")
  def identity: VcrMatcher = exact

  /** Matches on HTTP method + URI + body. */
  def methodUriAndBody: VcrMatcher = VcrMatcher.groupBy(req => (req.method, req.uri, req.body))

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

    override def recordOnly(pred: VcrRequest => Boolean): VcrMatcher.One =
      copy(shouldRecordPredicate = r => shouldRecordPredicate(r) && pred(r))

    override def append(other: VcrMatcher): VcrMatcher = other match {
      case m: VcrMatcher.One   => VcrMatcher.Many(Vector(this, m))
      case VcrMatcher.Many(ms) => VcrMatcher.Many(this +: ms)
    }

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher.One =
      copy(transformer = transformer)

    override def mapEntry(f: VcrEntry => VcrEntry): VcrMatcher.One =
      copy(transformer = transformer.andThen(f))

    override def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher.One =
      copy(requestFilter = requestFilter.andThen(f))

    override def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher.One =
      copy(responseFilter = responseFilter.andThen(f))

    override def mapRequestForMatching(f: VcrRequest => VcrRequest): VcrMatcher.One =
      copy(matchingFilter = matchingFilter.andThen(f))

    override def redact(rules: Redaction.Rules): VcrMatcher.One = copy(redaction = redaction ++ rules)

    override def redacted(request: VcrRequest): VcrRequest = requestFilter(redaction.request(request))

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

    override def recordOnly(pred: VcrRequest => Boolean): VcrMatcher = mapEach(_.recordOnly(pred))

    override def withTransformer(transformer: VcrEntry => VcrEntry): VcrMatcher =
      mapEach(_.withTransformer(transformer))

    override def mapEntry(f: VcrEntry => VcrEntry): VcrMatcher = mapEach(_.mapEntry(f))

    override def mapRequest(f: VcrRequest => VcrRequest): VcrMatcher = mapEach(_.mapRequest(f))

    override def mapResponse(f: VcrResponse => VcrResponse): VcrMatcher = mapEach(_.mapResponse(f))

    override def mapRequestForMatching(f: VcrRequest => VcrRequest): VcrMatcher = mapEach(_.mapRequestForMatching(f))

    override def withGrouper(grouper: VcrRequest => VcrKey): VcrMatcher = mapEach(_.withGrouper(grouper))

    override def redact(rules: Redaction.Rules): VcrMatcher = mapEach(_.redact(rules))

    // Requests no matcher accepts aren't recorded, but every matcher's redaction applies to them in error messages.
    override def redacted(request: VcrRequest): VcrRequest =
      matcherFor(request).fold(matchers.foldLeft(request)((r, m) => m.redaction.request(r)))(_.redacted(request))

    private def mapEach(f: VcrMatcher.One => VcrMatcher.One): VcrMatcher = VcrMatcher.Many(matchers.map(f))
  }
}
