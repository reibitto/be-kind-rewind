package bekindrewind.json

import bekindrewind.testing.TestData._
import bekindrewind.{ VcrRequest, VcrResponse }
import munit._

class JsonHelpersSpec extends FunSuite {

  private def post(body: String): VcrRequest = request(method = "POST", body = body)

  test("methodUriAndJsonBody ignores formatting, field order and ignored fields at any depth") {
    val matcher = JsonMatchers.methodUriAndJsonBody("timestamp")

    val a = post("""{"user":"foo","timestamp":1,"nested":{"timestamp":2,"x":[{"timestamp":3,"y":1}]}}""")
    val b =
      post("""{ "nested": {"x": [{"y": 1, "timestamp": 99}], "timestamp": 98}, "timestamp": 97, "user": "foo" }""")
    val c = post("""{"user":"bar","timestamp":1}""")

    assertEquals(matcher.group(a), matcher.group(b))
    assertNotEquals(matcher.group(a), matcher.group(c))
  }

  test("methodUriAndJsonBody compares non-JSON bodies as is") {
    val matcher = JsonMatchers.methodUriAndJsonBody()

    assertEquals(matcher.group(post("not json")), matcher.group(post("not json")))
    assertNotEquals(matcher.group(post("not json")), matcher.group(post("not json!")))
  }

  test("JsonRedaction redacts fields at any depth") {
    val redacted = JsonRedaction.request("password")(post("""{"user":"foo","password":"x","n":[{"password":1}]}"""))

    assertEquals(redacted.body, """{"user":"foo","password":"REDACTED","n":[{"password":"REDACTED"}]}""")
  }

  test("JsonRedaction leaves bodies without matching fields or non-JSON bodies untouched") {
    val pretty = "{\n  \"user\": \"foo\"\n}"

    assertEquals(JsonRedaction.request("password")(post(pretty)).body, pretty)
    assertEquals(JsonRedaction.request("password")(post("password=x")).body, "password=x")
  }

  test("JsonRedaction skips binary bodies") {
    val binary = VcrResponse.fromBytes(200, "OK", Map.empty, Array[Byte](0, -1), None, None)

    assertEquals(JsonRedaction.response("password")(binary), binary)
  }

  test("JsonRedaction works with VcrMatcher.mapResponse") {
    val matcher = bekindrewind.VcrMatcher.default.mapResponse(JsonRedaction.response("token"))

    assertEquals(
      matcher.transform(entry(response = response("""{"token":"secret"}"""))).response.body,
      """{"token":"REDACTED"}"""
    )
  }
}
