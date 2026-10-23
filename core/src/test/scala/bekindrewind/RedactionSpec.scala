package bekindrewind

import bekindrewind.testing.TestData._
import munit._

import java.nio.charset.Charset

class RedactionSpec extends FunSuite {
  private val secret = "sk_live_1234567890abcdef"

  private val echoed = entry(
    request(
      method = "POST",
      uri = s"https://api.example.com/charge?api_key=$secret&page=1",
      body = s"""{"key":"$secret"}"""
    )
      .copy(headers = Map("X-Api-Key" -> Seq(secret), "Authorization" -> Seq(s"Bearer token_abcdefghij"))),
    response(s"""{"echo":{"x-api-key":"$secret","authorization":"Bearer token_abcdefghij"}}""")
      .copy(headers = Map("X-Echo" -> Seq(s"key=$secret"), "Set-Cookie" -> Seq("session=cookie_1234567890")))
  )

  test("Values of redacted headers are also replaced in bodies, URIs and other headers") {
    val redacted = VcrMatcher.default.redactHeaders("x-api-key", "authorization").transform(echoed)

    assertEquals(redacted.request.headers, Map("X-Api-Key" -> Seq("REDACTED"), "Authorization" -> Seq("REDACTED")))
    assertEquals(redacted.request.body, """{"key":"REDACTED"}""")
    assertEquals(redacted.request.uri.toString, "https://api.example.com/charge?api_key=REDACTED&page=1")
    assertEquals(redacted.response.body, """{"echo":{"x-api-key":"REDACTED","authorization":"REDACTED"}}""")
    assertEquals(redacted.response.headers("X-Echo"), Seq("key=REDACTED"))
    assert(!redacted.toString.contains(secret) && !redacted.toString.contains("token_abcdefghij"))
  }

  test("Values of redacted query parameters are replaced everywhere, URL-encoded or not") {
    val key      = "abc/def+ghi=="
    val withKey  = entry(
      request(uri = s"https://a.com/rates?app_id=${java.net.URLEncoder.encode(key, "UTF-8")}"),
      response(s"""{"app_id":"$key"}""")
    )
    val redacted = VcrMatcher.default.redactQueryParams("app_id").transform(withKey)

    assertEquals(redacted.request.uri.toString, "https://a.com/rates?app_id=REDACTED")
    assertEquals(redacted.response.body, """{"app_id":"REDACTED"}""")
  }

  test("Values from response headers are replaced in the response too") {
    val redacted = VcrMatcher.default
      .redactHeaders("Set-Cookie")
      .transform(
        echoed.copy(response = echoed.response.copy(body = "your session is session=cookie_1234567890"))
      )

    assertEquals(redacted.response.body, "your session is REDACTED")
  }

  test("redactValues replaces known secrets anywhere") {
    val redacted = VcrMatcher.default.redactValues(secret).transform(echoed)

    assert(!redacted.toString.contains(secret), redacted.toString)
    assertEquals(redacted.request.headers("Authorization"), Seq("Bearer token_abcdefghij"))
  }

  test("Short values aren't replaced elsewhere, since they're unlikely to be secrets") {
    val short    = entry(request(uri = "https://a.com/?page=1&token=1").copy(body = "page 1 of 10"))
    val redacted = VcrMatcher.default.redactQueryParams("token").transform(short)

    assertEquals(redacted.request.uri.toString, "https://a.com/?page=1&token=REDACTED")
    assertEquals(redacted.request.body, "page 1 of 10")
  }

  test("Redacted requests still match live requests, including body-based matchers") {
    val matcher = VcrMatcher.methodUriAndBody.redactHeaders("x-api-key")

    assertEquals(matcher.group(matcher.transform(echoed).request), matcher.group(echoed.request))
  }

  test("Text bodies in other charsets are scrubbed, binary bodies are left alone") {
    val shiftJis = Charset.forName("Shift_JIS")
    val text     = VcrResponse.fromBytes(
      200,
      "OK",
      Map.empty,
      s"キー $secret".getBytes(shiftJis),
      Some("text/plain; charset=Shift_JIS"),
      None
    )
    val binary   = VcrResponse.fromBytes(200, "OK", Map.empty, Array[Byte](0, -1) ++ secret.getBytes("UTF-8"), None, None)
    val matcher  = VcrMatcher.default.redactValues(secret)

    val redactedText = matcher.transform(entry(response = text)).response
    assertEquals(new String(redactedText.bodyBytes, shiftJis), "キー REDACTED")
    assertEquals(matcher.transform(entry(response = binary)).response, binary)
  }

  test("Redaction survives withTransformer, and composes on combined matchers") {
    val matcher = (VcrMatcher.default.recordOnly(_.uri.getHost == "other.com") :+ VcrMatcher.default)
      .redactHeaders("x-api-key")
      .withTransformer(identity)

    assertEquals(matcher.transform(echoed).request.headers("X-Api-Key"), Seq("REDACTED"))
  }

  test("Errors about unrecorded requests don't show redacted values") {
    val recording =
      new VcrRecording(new storage.InMemoryVcrStorage(), RecordOptions.default.mode(RecordMode.ReplayOnly))
    val matcher   = VcrMatcher.default.redactQueryParams("api_key")

    recording.resolve(matcher, echoed.request) match {
      case VcrAction.Fail(error) =>
        assert(!error.getMessage.contains(secret), error.getMessage)
        assert(error.getMessage.contains("api_key=REDACTED"), error.getMessage)
      case other                 => fail(s"Expected a failure, got $other")
    }
  }
}
