package bekindrewind.json

import bekindrewind.{ BodyEncoding, VcrMatcher, VcrRequest }
import io.circe.Json

object JsonMatchers {

  /**
   * Matches on HTTP method + URI + JSON body, ignoring the specified fields at any depth (for example timestamps or
   * nonces) as well as formatting and field order. Bodies that aren't JSON are compared as is.
   */
  def methodUriAndJsonBody(ignoredFields: String*): VcrMatcher = {
    val ignored = ignoredFields.toSet
    VcrMatcher.groupBy(req => (req.method, req.uri, jsonBody(req, ignored)))
  }

  /** The body parsed as JSON without the specified fields, or the raw body if it isn't JSON. */
  def jsonBody(request: VcrRequest, ignoredFields: Set[String]): Either[String, Json] =
    request.bodyEncoding match {
      case BodyEncoding.Text =>
        io.circe.parser.parse(request.body).map(removeFields(_, ignoredFields)).left.map(_ => request.body)
      case _                 => Left(request.body)
    }

  private[json] def removeFields(json: Json, fields: Set[String]): Json =
    json.fold(
      json,
      _ => json,
      _ => json,
      _ => json,
      array => Json.fromValues(array.map(removeFields(_, fields))),
      obj => Json.fromJsonObject(obj.filterKeys(k => !fields.contains(k)).mapValues(removeFields(_, fields)))
    )
}
