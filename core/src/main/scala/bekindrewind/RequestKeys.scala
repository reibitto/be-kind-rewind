package bekindrewind

import java.net.URI

/** Helpers for building grouping keys in [[VcrMatcher.groupBy]]. */
object RequestKeys {

  /** The URI with its query parameters sorted by name, so that parameter order doesn't affect matching. */
  def sortedQueryParams(uri: URI): URI = mapQueryParams(uri)(_.sortBy(_._1))

  /** The URI without the specified query parameters (names are compared after URL-decoding). */
  def withoutQueryParams(uri: URI, names: String*): URI = {
    val nameSet = names.toSet
    mapQueryParams(uri)(_.filterNot { case (key, _) => nameSet.contains(decode(key)) })
  }

  private[bekindrewind] def decode(key: String): String =
    scala.util.Try(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8)).getOrElse(key)

  /** The values of the specified headers, keyed by lowercase header name. Missing headers are left out. */
  def headers(request: VcrRequest, names: String*): Map[String, Seq[String]] = {
    val lowerNames = names.map(_.toLowerCase).toSet
    request.headers.toSeq.collect {
      case (name, values) if lowerNames.contains(name.toLowerCase) => name.toLowerCase -> values
    }
      .groupMapReduce(_._1)(_._2)(_ ++ _)
  }

  /** Transforms the raw (still encoded) query parameters of the URI. Parameters without `=` have no value. */
  def mapQueryParams(uri: URI)(f: Seq[(String, Option[String])] => Seq[(String, Option[String])]): URI =
    Option(uri.getRawQuery) match {
      case None        => uri
      case Some(query) =>
        val params   = query.split('&').toSeq.filter(_.nonEmpty).map { param =>
          param.split("=", 2) match {
            case Array(key, value) => (key, Some(value))
            case Array(key)        => (key, None)
          }
        }
        val newQuery = f(params).map { case (key, value) => key + value.fold("")("=" + _) }.mkString("&")
        val full     = uri.toString
        val start    = full.indexOf('?')
        val rest     = full.substring(start + 1 + query.length)
        URI.create(full.substring(0, start) + (if (newQuery.isEmpty) "" else s"?$newQuery") + rest)
    }
}
