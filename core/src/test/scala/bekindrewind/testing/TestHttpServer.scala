package bekindrewind.testing

import com.sun.net.httpserver.{ HttpExchange, HttpServer }

import java.net.{ InetSocketAddress, URI }
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/** Minimal local HTTP server so client modules can be tested against real HTTP traffic. */
final class TestHttpServer private (server: HttpServer) extends AutoCloseable {
  private val hitCount = new AtomicInteger(0)

  /** Number of requests that actually reached the server (as opposed to being replayed). */
  def hits: Int = hitCount.get()

  def uri(path: String): URI = URI.create(s"http://localhost:${server.getAddress.getPort}$path")

  def url(path: String): String = uri(path).toString

  override def close(): Unit = server.stop(0)

  private def route(path: String)(handle: HttpExchange => Unit): Unit = {
    server.createContext(
      path,
      (exchange: HttpExchange) =>
        try {
          hitCount.incrementAndGet()
          handle(exchange)
        } finally exchange.close()
    )
    ()
  }

  private def respond(
    exchange: HttpExchange,
    status: Int,
    body: String,
    contentType: String = "text/plain; charset=UTF-8",
    chunked: Boolean = false
  ): Unit = respondBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8), contentType, chunked)

  private def respondBytes(
    exchange: HttpExchange,
    status: Int,
    bytes: Array[Byte],
    contentType: String,
    chunked: Boolean = false
  ): Unit = {
    exchange.getResponseHeaders.add("Content-Type", contentType)
    exchange.sendResponseHeaders(status, if (chunked) 0L else if (bytes.isEmpty) -1L else bytes.length.toLong)
    if (bytes.nonEmpty) exchange.getResponseBody.write(bytes)
  }

  // Returns a different response on every call so tests can tell recorded responses apart.
  private val counter = new AtomicInteger(0)
  route("/counter")(ex => respond(ex, 200, counter.incrementAndGet().toString))

  // Echoes the request body bytes back with the request's Content-Type.
  route("/echo") { ex =>
    val body        = ex.getRequestBody.readAllBytes()
    val contentType = Option(ex.getRequestHeaders.getFirst("Content-Type")).getOrElse("text/plain; charset=UTF-8")
    ex.getResponseHeaders.add("X-Request-Method", ex.getRequestMethod)
    respondBytes(ex, 200, body, contentType)
  }

  route("/binary")(ex => respondBytes(ex, 200, TestHttpServer.binaryBody, "image/png"))

  route("/json")(ex => respond(ex, 200, """{"hello":"world","unicode":"日本語"}""", "application/json"))

  route("/cookie") { ex =>
    ex.getResponseHeaders.add("Set-Cookie", "session=abc123; Path=/; HttpOnly")
    respond(ex, 200, "cookie set")
  }

  route("/missing")(ex => respond(ex, 404, "not found"))

  route("/large")(ex => respond(ex, 200, TestHttpServer.largeBody, chunked = true))
}

object TestHttpServer {
  val largeBody: String = "0123456789" * 50000

  /** Every byte value, so it contains invalid UTF-8 sequences and control characters. */
  val binaryBody: Array[Byte] = (0 until 256).map(_.toByte).toArray

  def start(): TestHttpServer = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    val result = new TestHttpServer(server)
    server.start()
    result
  }
}
