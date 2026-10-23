package bekindrewind.json

import bekindrewind.{ VcrMatcher, VcrRequest }
import io.circe.Json

object JsonMatchers {

  /**
   * Matches on HTTP method + URI + JSON body, ignoring formatting and field order. Bodies that aren't JSON are compared
   * as is.
   */
  def methodUriAndJsonBody: VcrMatcher = methodUriAndJsonBodyIgnoring(Set.empty[String], parseNestedJson = false)

  /**
   * Like [[methodUriAndJsonBody]], but also ignores the specified fields at any depth (for example timestamps or
   * nonces).
   */
  def methodUriAndJsonBodyIgnoring(fields: String*): VcrMatcher =
    methodUriAndJsonBodyIgnoring(fields.toSet, parseNestedJson = false)

  /**
   * Like `methodUriAndJsonBodyIgnoring(fields*)`.
   *
   * @param parseNestedJson
   *   Also parse string values that contain JSON objects or arrays (such as a request wrapped in another request's
   *   `body` field), so that their formatting doesn't matter and their fields can be ignored too.
   */
  def methodUriAndJsonBodyIgnoring(fields: Set[String], parseNestedJson: Boolean): VcrMatcher =
    VcrMatcher.groupBy(req => (req.method, req.uri, jsonBody(req, fields, parseNestedJson)))

  /** The body parsed as JSON without the specified fields, or the raw body if it isn't JSON. */
  def jsonBody(request: VcrRequest, ignoredFields: Set[String]): Either[String, Json] =
    jsonBody(request, ignoredFields, parseNestedJson = false)

  def jsonBody(request: VcrRequest, ignoredFields: Set[String], parseNestedJson: Boolean): Either[String, Json] =
    request.bodyEncoding match {
      case encoding if encoding.isText =>
        io.circe.parser
          .parse(request.body)
          .map(normalize(_, ignoredFields, parseNestedJson))
          .left
          .map(_ => request.body)
      case _                           => Left(request.body)
    }

  private def normalize(json: Json, fields: Set[String], parseNestedJson: Boolean): Json =
    json.fold(
      json,
      _ => json,
      _ => json,
      string => if (parseNestedJson) nestedJson(string).fold(json)(normalize(_, fields, parseNestedJson)) else json,
      array => Json.fromValues(array.map(normalize(_, fields, parseNestedJson))),
      obj =>
        Json.fromJsonObject(obj.filterKeys(k => !fields.contains(k)).mapValues(normalize(_, fields, parseNestedJson)))
    )

  // Only objects and arrays, so that strings like "123" or "true" stay strings.
  private def nestedJson(string: String): Option[Json] = {
    val trimmed = string.trim
    if (trimmed.startsWith("{") || trimmed.startsWith("["))
      io.circe.parser.parse(trimmed).toOption.filter(json => json.isObject || json.isArray)
    else None
  }
}
