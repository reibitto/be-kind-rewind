package bekindrewind.util

import bekindrewind.{ VcrEntries, VcrEntry, VcrRequest, VcrResponse }
import org.scalacheck.Gen

import java.net.URI
import java.time.{ Instant, OffsetDateTime, ZoneOffset }

object Generators {
  val uri: Gen[URI] =
    for {
      schema <- Gen.oneOf("http", "https")
      host   <- Gen.alphaStr.suchThat(_.nonEmpty)
      path   <- Gen.alphaStr.map(s => s"/$s")
      port   <- Gen.choose(80, 65535)
    } yield new URI(schema, null, host, port, path, null, null)

  val method: Gen[String] = Gen.oneOf("GET", "POST", "PUT", "PATCH", "DELETE")

  val httpRequestHeaderName: Gen[String] =
    Gen.oneOf(
      "A-IM",
      "Accept",
      "Accept-Charset",
      "Accept-Encoding",
      "Accept-Language",
      "Accept-Datetime",
      "Access-Control-Request-Method",
      "Access-Control-Request-Headers",
      "Authorization",
      "Cache-Control",
      "Connection",
      "Content-Length",
      "Content-Type",
      "Cookie",
      "Date",
      "Expect",
      "Forwarded",
      "From",
      "Host",
      "If-Match",
      "If-Modified-Since",
      "If-None-Match",
      "If-Range",
      "If-Unmodified-Since",
      "Max-Forwards",
      "Origin",
      "Pragma",
      "Proxy-Authorization",
      "Range",
      "Referer",
      "TE",
      "User-Agent",
      "Upgrade",
      "Via",
      "Warning"
    )

  val body: Gen[Array[Byte]] =
    Gen.oneOf(
      Gen.asciiPrintableStr.map(_.getBytes("UTF-8")),
      Gen.const("""{"a":1,"b":[true,null]}""".getBytes("UTF-8")),
      Gen.const("日本語 ✓ ü".getBytes("UTF-8")),
      Gen.listOf(Gen.choose(Byte.MinValue, Byte.MaxValue)).map(_.toArray)
    )

  val headers: Gen[Map[String, Seq[String]]] =
    Gen.mapOf(Gen.zip(httpRequestHeaderName, Gen.nonEmptyListOf(Gen.alphaNumStr).map(_.toSeq)))

  val offsetDateTime: Gen[OffsetDateTime] =
    for {
      seconds <- Gen.choose(0L, 4102444800L)
      nanos   <- Gen.choose(0, 999999999)
      offset  <- Gen.choose(-12, 14)
    } yield OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos.toLong), ZoneOffset.ofHours(offset))

  val vcrRequest: Gen[VcrRequest] =
    for {
      method  <- method
      uri     <- uri
      body    <- body
      headers <- headers
      version <- Gen.option(Gen.oneOf("HTTP/1.1", "HTTP/2"))
    } yield VcrRequest.fromBytes(method, uri, body, headers, version)

  val vcrResponse: Gen[VcrResponse] =
    for {
      status      <- Gen.oneOf(200, 201, 204, 301, 400, 404, 500)
      statusText  <- Gen.alphaStr
      headers     <- headers
      body        <- body
      contentType <- Gen.option(Gen.oneOf("application/json", "text/plain; charset=UTF-8"))
      version     <- Gen.option(Gen.oneOf("HTTP/1.1", "HTTP/2"))
    } yield VcrResponse.fromBytes(status, statusText, headers, body, contentType, version)

  val vcrEntry: Gen[VcrEntry] =
    for {
      request    <- vcrRequest
      response   <- vcrResponse
      recordedAt <- offsetDateTime
      testName   <- Gen.option(Gen.alphaNumStr)
    } yield VcrEntry(request, response, recordedAt, testName)

  val vcrEntries: Gen[VcrEntries] =
    for {
      size    <- Gen.choose(0, 10)
      entries <- Gen.listOfN(size, vcrEntry)
      version <- Gen.oneOf("0.1.0", "0.2.0")
    } yield VcrEntries(entries.toVector, version)
}
