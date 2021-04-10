package bekindrewind.util

import bekindrewind.VcrRecordRequest
import org.scalacheck.Gen

import java.net.URI

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

  val vcrRecordRequest: Gen[VcrRecordRequest] = {
    for {
      method <- method
      uri    <- uri
      body   <- Gen.asciiStr
    } yield VcrRecordRequest(method, uri, body, Map.empty, "HTTP/1.1")
  }
}
