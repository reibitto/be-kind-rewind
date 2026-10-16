package bekindrewind.xml

import bekindrewind.{ VcrMatcher, VcrRequest }

import scala.util.Try
import scala.xml._

object XmlMatchers {

  /**
   * Matches on HTTP method + URI + XML body, ignoring whitespace between elements and comments. Bodies that aren't XML
   * are compared as is.
   */
  def methodUriAndXmlBody: VcrMatcher = methodUriAndXmlBodyIgnoring()

  /**
   * Like [[methodUriAndXmlBody]], but also ignores the specified elements and attributes (by name, at any depth).
   * {{{
   * XmlMatchers.methodUriAndXmlBodyIgnoring(elements = Set("Timestamp"), attributes = Set("requestId"))
   * }}}
   */
  def methodUriAndXmlBodyIgnoring(elements: Set[String] = Set.empty, attributes: Set[String] = Set.empty): VcrMatcher =
    VcrMatcher.groupBy(req => (req.method, req.uri, xmlBody(req, elements, attributes)))

  /** The body parsed as XML without the specified elements and attributes, or the raw body if it isn't XML. */
  def xmlBody(
    request: VcrRequest,
    ignoredElements: Set[String] = Set.empty,
    ignoredAttributes: Set[String] = Set.empty
  ): Either[String, Node] =
    request.bodyEncoding match {
      case encoding if encoding.isText =>
        Try(XML.loadString(request.body)).toOption
          .flatMap(root => normalize(Utility.trim(root), ignoredElements, ignoredAttributes))
          .toRight(request.body)
      case _                           => Left(request.body)
    }

  private def normalize(node: Node, ignoredElements: Set[String], ignoredAttributes: Set[String]): Option[Node] =
    node match {
      case e: Elem if ignoredElements.contains(e.label) => None
      case e: Elem                                      =>
        Some(
          e.copy(
            attributes = e.attributes.filter(attribute => !ignoredAttributes.contains(attribute.key)),
            child = e.child.flatMap(normalize(_, ignoredElements, ignoredAttributes))
          )
        )
      case _: Comment | _: ProcInstr                    => None
      case other                                        => Some(other)
    }
}
