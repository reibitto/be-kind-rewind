package bekindrewind

import java.net.URI

/** Helpers for building grouping keys in [[VcrMatcher.groupBy]]. */
object RequestKeys {

  /** The URI with its query parameters sorted by name, so that parameter order doesn't affect matching. */
  def sortedQueryParams(uri: URI): URI = mapQueryParams(uri)(_.sortBy(_._1))

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
        val start    = full.indexOf('?') + 1
        URI.create(full.substring(0, start) + newQuery + full.substring(start + query.length))
    }
}
