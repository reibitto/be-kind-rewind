package bekindrewind

import bekindrewind.testing.TestData._
import munit._

import java.net.URI

class RequestKeysSpec extends FunSuite {

  test("sortedQueryParams sorts by name and keeps repeated parameters in order") {
    assertEquals(
      RequestKeys.sortedQueryParams(URI.create("https://a.com/p?b=2&a=1&c&a=0#frag")),
      URI.create("https://a.com/p?a=1&a=0&b=2&c#frag")
    )
    assertEquals(RequestKeys.sortedQueryParams(URI.create("https://a.com/p")), URI.create("https://a.com/p"))
  }

  test("sortedQueryParams keeps encoding intact") {
    assertEquals(
      RequestKeys.sortedQueryParams(URI.create("https://a.com/?z=%20x&y=a%26b")),
      URI.create("https://a.com/?y=a%26b&z=%20x")
    )
  }

  test("headers selects headers case-insensitively") {
    val req = request().copy(headers = Map("Accept" -> Seq("json"), "X-Other" -> Seq("x"), "x-version" -> Seq("2")))

    assertEquals(
      RequestKeys.headers(req, "accept", "X-Version", "Missing"),
      Map("accept" -> Seq("json"), "x-version" -> Seq("2"))
    )
  }

  test("Redaction.queryParams handles encoded names and parameters without values") {
    assertEquals(
      Redaction.queryParams(URI.create("https://a.com/?api%5Fkey=secret&flag&token=x"), Seq("api_key", "flag")),
      URI.create("https://a.com/?api%5Fkey=REDACTED&flag&token=x")
    )
  }

  test("Redaction.headers is case-insensitive") {
    assertEquals(
      Redaction.headers(Map("X-Api-Key" -> Seq("a", "b"), "Accept" -> Seq("json")), Seq("x-api-key")),
      Map("X-Api-Key" -> Seq("REDACTED", "REDACTED"), "Accept" -> Seq("json"))
    )
  }

  test("Prebuilt matchers") {
    val a = request(uri = "https://a.com/?x=1&y=2").copy(headers = Map("Accept" -> Seq("json")))
    val b = request(uri = "https://a.com/?y=2&x=1").copy(headers = Map("accept" -> Seq("json")), body = "other")

    assertEquals(VcrMatcher.default.ignoreQueryParamOrder.group(a), VcrMatcher.default.ignoreQueryParamOrder.group(b))
    assertNotEquals(VcrMatcher.default.group(a), VcrMatcher.default.group(b))

    val byAccept = VcrMatcher.methodUriAndHeaders("Accept")
    assertEquals(byAccept.group(a), byAccept.group(a.copy(headers = Map("ACCEPT" -> Seq("json")))))
    assertNotEquals(byAccept.group(a), byAccept.group(a.copy(headers = Map("Accept" -> Seq("xml")))))

    assertNotEquals(VcrMatcher.methodUriAndBody.group(a), VcrMatcher.methodUriAndBody.group(a.copy(body = "x")))
  }

  test("Filters survive withTransformer and withGrouper") {
    val matcher = VcrMatcher.default
      .redactHeaders("Secret")
      .withTransformer(e => e.copy(response = e.response.copy(body = "transformed")))
      .withGrouper(r => VcrKey(r.headers))

    val transformed = matcher.transform(
      entry(request().copy(headers = Map("Secret" -> Seq("x"))), response().copy(headers = Map("Secret" -> Seq("y"))))
    )

    assertEquals(transformed.request.headers, Map("Secret" -> Seq("REDACTED")))
    assertEquals(transformed.response.headers, Map("Secret" -> Seq("REDACTED")))
    assertEquals(transformed.response.body, "transformed")
    assertEquals(
      matcher.group(request().copy(headers = Map("Secret" -> Seq("x")))),
      VcrKey(Map("Secret" -> Seq("REDACTED")))
    )
  }

  test("mapEntry composes transformers") {
    val matcher = VcrMatcher.default
      .withTransformer(e => e.copy(response = e.response.copy(body = e.response.body + "1")))
      .mapEntry(e => e.copy(response = e.response.copy(body = e.response.body + "2")))

    assertEquals(matcher.transform(entry(response = response("0"))).response.body, "012")
  }

  test("withoutQueryParams removes parameters and the question mark when none are left") {
    assertEquals(
      RequestKeys.withoutQueryParams(URI.create("https://a.com/p?sign=x&a=1&nonce=2#f"), "sign", "nonce"),
      URI.create("https://a.com/p?a=1#f")
    )
    assertEquals(
      RequestKeys.withoutQueryParams(URI.create("https://a.com/p?sign=x"), "sign"),
      URI.create("https://a.com/p")
    )
  }

  test("ignoreQueryParams affects matching only, not what's recorded") {
    val matcher  = VcrMatcher.default.ignoreQueryParams("nonce")
    val recorded = request(uri = "https://a.com/p?a=1&nonce=123")

    assertEquals(matcher.group(recorded), matcher.group(request(uri = "https://a.com/p?a=1&nonce=456")))
    assertNotEquals(matcher.group(recorded), matcher.group(request(uri = "https://a.com/p?a=2&nonce=123")))
    assertEquals(matcher.transform(entry(recorded)).request.uri, recorded.uri)
  }

  test("mapUriForMatching affects matching only, and survives withGrouper") {
    val matcher = VcrMatcher.default
      .mapUriForMatching(uri => URI.create(uri.toString.replaceAll("/orders/[0-9]+", "/orders/ID")))
      .withGrouper(r => VcrKey(r.uri))

    assertEquals(
      matcher.group(request(uri = "https://a.com/orders/1")),
      matcher.group(request(uri = "https://a.com/orders/2"))
    )
    assertEquals(matcher.transform(entry(request(uri = "https://a.com/orders/1"))).request.uri.getPath, "/orders/1")
  }
}
