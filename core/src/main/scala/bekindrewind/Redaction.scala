package bekindrewind

import java.net.{ URI, URLDecoder }
import java.nio.charset.StandardCharsets
import scala.util.Try

/** Helpers for removing sensitive data from recordings. See [[VcrMatcher.redactHeaders]]. */
object Redaction {
  val placeholder: String = "REDACTED"

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
        if (nameSet.contains(Try(URLDecoder.decode(key, StandardCharsets.UTF_8)).getOrElse(key)))
          (key, value.map(_ => placeholder))
        else (key, value)
      }
    }
  }
}
