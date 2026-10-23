package bekindrewind.xml

import bekindrewind.VcrRequest
import bekindrewind.testing.TestData._
import munit._

class XmlMatchersSpec extends FunSuite {
  private def post(body: String): VcrRequest = request(method = "POST", body = body)

  private val matcher =
    XmlMatchers.methodUriAndXmlBodyIgnoring(elements = Set("Timestamp"), attributes = Set("ORDERID"))

  test("Ignores whitespace, comments, and the specified elements and attributes at any depth") {
    val a = post("""<root><item ORDERID="1" AMT="100"/><meta><Timestamp>1</Timestamp></meta></root>""")
    val b = post(
      """<?xml version="1.0" encoding="UTF-8"?>
        |<root>
        |  <!-- comment -->
        |  <item AMT="100" ORDERID="2" />
        |  <meta>
        |    <Timestamp>2</Timestamp>
        |  </meta>
        |</root>""".stripMargin
    )

    assertEquals(matcher.group(a), matcher.group(b))
  }

  test("Differences in other elements, attributes or text still matter") {
    val base = post("""<root><item ORDERID="1" AMT="100">x</item></root>""")

    assertNotEquals(matcher.group(base), matcher.group(post("""<root><item ORDERID="1" AMT="200">x</item></root>""")))
    assertNotEquals(matcher.group(base), matcher.group(post("""<root><item ORDERID="1" AMT="100">y</item></root>""")))
    assertNotEquals(matcher.group(base), matcher.group(post("""<root><other ORDERID="1" AMT="100">x</other></root>""")))
  }

  test("Bodies that aren't XML are compared as is") {
    assertEquals(XmlMatchers.xmlBody(post("a=1")), Left("a=1"))
    assertNotEquals(matcher.group(post("a=1")), matcher.group(post("a=2")))
  }

  test("Works with ignoreQueryParams") {
    val withQuery = matcher.ignoreQueryParams("sign")
    val a         = request(method = "POST", uri = "https://a.com/api?sign=1", body = "<a/>")
    val b         = request(method = "POST", uri = "https://a.com/api?sign=2", body = "<a></a>")

    assertEquals(withQuery.group(a), withQuery.group(b))
  }
}
