package bekindrewind

import java.net.{ URI, URLEncoder }
import java.nio.charset.StandardCharsets

/** Helpers for removing sensitive data from recordings. See [[VcrMatcher.redactHeaders]]. */
object Redaction {
  val placeholder: String = "REDACTED"

  /**
   * Values found in redacted headers and query parameters shorter than this aren't replaced elsewhere in the entry,
   * since short values (like `1` or `true`) are unlikely to be secrets and would be replaced all over bodies.
   */
  val minSecretLength: Int = 8

  /** Replaces the values of the specified headers (case-insensitive). */
  def headers(headers: Map[String, Seq[String]], names: Seq[String]): Map[String, Seq[String]] = {
    val lowerNames = names.map(_.toLowerCase).toSet
    headers.map { case (name, values) =>
      if (lowerNames.contains(name.toLowerCase)) name -> values.map(_ => placeholder) else name -> values
    }
  }

  /** Replaces the values of the specified query parameters, keeping everything else in the URI as is. */
  def queryParams(uri: URI, names: Seq[String]): URI = {
    val nameSet = names.toSet
    RequestKeys.mapQueryParams(uri) { params =>
      params.map { case (key, value) =>
        if (nameSet.contains(RequestKeys.decode(key)))
          (key, value.map(_ => placeholder))
        else (key, value)
      }
    }
  }

  /**
   * What a matcher redacts. Besides the named headers and query parameters themselves, their values are replaced
   * wherever else they appear in the entry (bodies, URIs and other headers, of both the request and the response), for
   * example when a server echoes the request back.
   */
  final case class Rules(headers: Set[String], queryParams: Set[String], values: Set[String]) {
    def isEmpty: Boolean = headers.isEmpty && queryParams.isEmpty && values.isEmpty

    def ++(other: Rules): Rules =
      Rules(headers ++ other.headers, queryParams ++ other.queryParams, values ++ other.values)

    /** Redacts a request, using only the secrets it contains itself (so live and recorded requests still match). */
    def request(request: VcrRequest): VcrRequest =
      if (isEmpty) request else redact(request, secrets(request, None))

    def entry(original: VcrEntry, transformed: VcrEntry): VcrEntry =
      if (isEmpty) transformed
      else {
        val all = secrets(original.request, Some(original.response)) ++
          secrets(transformed.request, Some(transformed.response))
        transformed.copy(
          request = redact(transformed.request, secrets(transformed.request, None) ++ secrets(original.request, None)),
          response = redact(transformed.response, all)
        )
      }

    private def secrets(request: VcrRequest, response: Option[VcrResponse]): Set[String] = {
      def headerValues(all: Map[String, Seq[String]]) =
        all.collect { case (name, values) if headers.contains(name.toLowerCase) => values }.flatten

      val queryValues = Option(request.uri.getRawQuery).toSeq.flatMap(_.split('&')).flatMap {
        _.split("=", 2) match {
          case Array(key, value) if queryParams.contains(RequestKeys.decode(key)) => Seq(RequestKeys.decode(value))
          case _                                                                  => Nil
        }
      }

      // Values such as `Bearer <token>` are also replaced part by part, since the parts can appear on their own.
      val found = (headerValues(request.headers) ++ response.toSeq.flatMap(r => headerValues(r.headers)) ++ queryValues)
        .flatMap(value => value +: value.split("\\s+").toSeq)
        .filter(value => value.length >= minSecretLength && value != placeholder)

      found.toSet ++ values.filter(_.nonEmpty)
    }

    private def redact(request: VcrRequest, secrets: Set[String]): VcrRequest =
      request.copy(
        uri = URI.create(scrub(Redaction.queryParams(request.uri, queryParams.toSeq).toString, secrets, inUri = true)),
        headers = scrubHeaders(Redaction.headers(request.headers, headers.toSeq), secrets),
        body = scrubBody(request.body, request.bodyEncoding, secrets)
      )

    private def redact(response: VcrResponse, secrets: Set[String]): VcrResponse =
      response.copy(
        headers = scrubHeaders(Redaction.headers(response.headers, headers.toSeq), secrets),
        body = scrubBody(response.body, response.bodyEncoding, secrets)
      )

    private def scrubHeaders(all: Map[String, Seq[String]], secrets: Set[String]) =
      all.map { case (name, values) => name -> values.map(scrub(_, secrets, inUri = false)) }

    // Binary bodies (base64) are left as is.
    private def scrubBody(body: String, encoding: BodyEncoding, secrets: Set[String]) =
      if (encoding.isText) scrub(body, secrets, inUri = false) else body

    private def scrub(text: String, secrets: Set[String], inUri: Boolean): String =
      // Longest first, so that a secret containing another is replaced as a whole.
      secrets.toSeq.sortBy(-_.length).foldLeft(text) { (acc, secret) =>
        val replaced = acc.replace(secret, placeholder)
        if (inUri) replaced.replace(URLEncoder.encode(secret, StandardCharsets.UTF_8), placeholder) else replaced
      }
  }

  object Rules {
    val empty: Rules = Rules(Set.empty, Set.empty, Set.empty)
  }
}
