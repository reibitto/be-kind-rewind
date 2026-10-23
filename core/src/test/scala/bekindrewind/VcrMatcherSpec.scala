package bekindrewind

import bekindrewind.testing.TestData._
import bekindrewind.util.Generators
import munit._
import org.scalacheck.Prop._

import java.net.URI

class VcrMatcherSpec extends ScalaCheckSuite {

  property("VcrMatcher.exact should allow all requests") {
    forAll(Generators.vcrRequest) { (req: VcrRequest) =>
      assert(VcrMatcher.exact.shouldRecord(req))
    }
  }

  property("VcrMatcher.default groups by method and URI only") {
    forAll(Generators.vcrRequest, Generators.vcrRequest) { (a: VcrRequest, b: VcrRequest) =>
      assertEquals(VcrMatcher.default.group(a), VcrKey((a.method, a.uri)))
      assertEquals(
        VcrMatcher.default.group(a) == VcrMatcher.default.group(b.copy(method = a.method, uri = a.uri)),
        true
      )
    }
  }

  test("recordOnly") {
    val matcher = VcrMatcher.exact.recordOnly(_.method == "GET")

    assert(matcher.shouldRecord(request(method = "GET")))
    assert(!matcher.shouldRecord(request(method = "POST")))
    assertEquals(matcher.group(request(method = "POST")), VcrKey.Ungrouped)
  }

  test("recordOnly combines predicates") {
    val matcher = VcrMatcher.default.recordOnly(_.method == "GET").recordOnly(_.uri.getHost == "a.com")

    assert(matcher.shouldRecord(request(uri = "https://a.com")))
    assert(!matcher.shouldRecord(request(uri = "https://b.com")))
    assert(!matcher.shouldRecord(request(method = "POST", uri = "https://a.com")))
  }

  test("append falls through to the next matcher") {
    val uriA     = new URI("https://a.com")
    val uriB     = new URI("https://b.com")
    val matcher  = VcrMatcher.default.recordOnly(_.uri == uriA)
    val matcher2 = VcrMatcher.default.recordOnly(_.uri == uriB)

    val combinedMatcher = matcher :+ matcher2

    val requestA = request(uri = "https://a.com")
    val requestB = request(uri = "https://b.com")
    val requestC = request(uri = "https://c.com")

    assertEquals(matcher.group(requestA), VcrKey(("GET", uriA)))
    assertEquals(matcher2.group(requestA), VcrKey.Ungrouped)
    assertEquals(combinedMatcher.group(requestA), VcrKey(("GET", uriA)))

    assertEquals(matcher.group(requestB), VcrKey.Ungrouped)
    assertEquals(combinedMatcher.group(requestB), VcrKey(("GET", uriB)))

    assert(!combinedMatcher.shouldRecord(requestC))
    assertEquals(combinedMatcher.group(requestC), VcrKey.Ungrouped)
  }

  test("append flattens matchers") {
    val a = VcrMatcher.default
    val b = VcrMatcher.exact
    val c = VcrMatcher.groupBy(_.uri)

    def size(matcher: VcrMatcher) = matcher match {
      case VcrMatcher.Many(matchers) => matchers.size
      case _: VcrMatcher.One         => 1
    }

    assertEquals(size((a :+ b) :+ c), 3)
    assertEquals(size(a :+ (b :+ c)), 3)
    assertEquals(size((a :+ b) :+ (b :+ c)), 4)
  }

  test("Combined matchers use the grouping of the matcher that accepted the request") {
    val byBody  = VcrMatcher.groupBy(_.body).recordOnly(_.method == "POST")
    val matcher = byBody :+ VcrMatcher.default

    assertEquals(matcher.group(request(method = "POST", body = "x")), VcrKey("x"))
    assertEquals(matcher.group(request(body = "x")), VcrKey(("GET", new URI("https://example.com/foo.json"))))
  }

  test("recordOnly on combined matchers keeps each matcher's own predicate") {
    val matcher = (VcrMatcher.default.recordOnly(_.uri.getHost == "a.com") :+
      VcrMatcher.default.recordOnly(_.uri.getHost == "b.com")).recordOnly(_.method == "GET")

    assert(matcher.shouldRecord(request(uri = "https://a.com")))
    assert(matcher.shouldRecord(request(uri = "https://b.com")))
    assert(!matcher.shouldRecord(request(uri = "https://c.com")))
    assert(!matcher.shouldRecord(request(method = "POST", uri = "https://a.com")))
  }

  test("Combined matchers apply the transformer of the matcher that accepted the entry") {
    val matcher = VcrMatcher.default.recordOnly(_.uri.getHost == "a.com").withTransformer { e =>
      e.copy(response = e.response.copy(body = "redacted-a"))
    } :+ VcrMatcher.default.withTransformer(e => e.copy(response = e.response.copy(body = "redacted-other")))

    assertEquals(matcher.transform(entry(request(uri = "https://a.com"))).response.body, "redacted-a")
    assertEquals(matcher.transform(entry(request(uri = "https://b.com"))).response.body, "redacted-other")
  }

  test("withGrouper replaces the grouping function") {
    val matcher = VcrMatcher.default.withGrouper(req => VcrKey(req.body))

    assertEquals(matcher.group(request(body = "abc")), VcrKey("abc"))
  }

  test("Ungrouped keys are never considered the same") {
    assert(!VcrKey.Ungrouped.areSameGroupedKey(VcrKey.Ungrouped))
    assert(!VcrKey("a").areSameGroupedKey(VcrKey.Ungrouped))
    assert(VcrKey("a").areSameGroupedKey(VcrKey("a")))
    assert(!VcrKey("a").areSameGroupedKey(VcrKey("b")))
  }
}
