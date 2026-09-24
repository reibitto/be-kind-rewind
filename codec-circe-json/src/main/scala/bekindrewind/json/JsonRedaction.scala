package bekindrewind.json

import bekindrewind.{ Redaction, VcrRequest, VcrResponse }
import io.circe.Json

/**
 * Replaces the values of JSON fields (at any depth) with a placeholder. Use with `VcrMatcher.mapRequest` and
 * `VcrMatcher.mapResponse`:
 *
 * {{{
 * VcrMatcher.default
 *   .mapRequest(JsonRedaction.request("password"))
 *   .mapResponse(JsonRedaction.response("accessToken"))
 * }}}
 */
object JsonRedaction {
  def request(fields: String*): VcrRequest => VcrRequest = {
    val fieldSet = fields.toSet
    req => if (req.bodyEncoding.isText) req.copy(body = redact(req.body, fieldSet)) else req
  }

  def response(fields: String*): VcrResponse => VcrResponse = {
    val fieldSet = fields.toSet
    res => if (res.bodyEncoding.isText) res.copy(body = redact(res.body, fieldSet)) else res
  }

  /** Redacts the fields if the body is JSON containing any of them. Other bodies are returned unchanged. */
  def redact(body: String, fields: Set[String]): String =
    io.circe.parser.parse(body) match {
      case Right(json) =>
        val redacted = redactJson(json, fields)
        if (redacted == json) body else redacted.noSpaces
      case Left(_)     => body
    }

  private def redactJson(json: Json, fields: Set[String]): Json =
    json.fold(
      json,
      _ => json,
      _ => json,
      _ => json,
      array => Json.fromValues(array.map(redactJson(_, fields))),
      obj =>
        Json.fromFields(obj.toIterable.map { case (key, value) =>
          key -> (if (fields.contains(key)) Json.fromString(Redaction.placeholder) else redactJson(value, fields))
        })
    )
}
