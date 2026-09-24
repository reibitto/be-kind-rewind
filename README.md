# Be Kind Rewind ⏪

![Scala CI](https://github.com/reibitto/be-kind-rewind/actions/workflows/scala.yml/badge.svg)

*A VCR testing library for Scala*

## What is it?

Be Kind Rewind is a VCR testing library for Scala, similar to the popular [vcr](https://github.com/vcr/vcr) Ruby library. This library records HTTP interactions and plays them back in subsequent runs.

The goal is to make integration tests fast, deterministic, and potentially eliminate the need for mocks and fakes completely for HTTP requests.

Be Kind Rewind supports multiple HTTP clients ([sttp](https://sttp.softwaremill.com) 3 and 4, Java's [HttpClient](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html), [play-ws](https://github.com/playframework/play-ws), [pekko-http](https://pekko.apache.org/docs/pekko-http/current/client-side/index.html)) and integrates with [munit](https://scalameta.org/munit), [ScalaTest](https://www.scalatest.org) and [zio-test](https://zio.dev/reference/test).

Requires Scala 2.13 or 3.3+, and Java 11+.

## Modules

All modules are published under `"com.github.reibitto" %% "<module>" % "0.2.0"`.

| Module | Description |
| --- | --- |
| `be-kind-rewind-sttp` | [sttp client3](https://sttp.softwaremill.com/en/stable/) backend |
| `be-kind-rewind-sttp4` | [sttp client4](https://sttp.softwaremill.com/en/latest/) backend |
| `be-kind-rewind-java-http` | `java.net.http.HttpClient` |
| `be-kind-rewind-play` | play-ws (`WSClient`) |
| `be-kind-rewind-play-standalone` | play-ws-standalone (`StandaloneWSClient`) |
| `be-kind-rewind-pekko-http` | pekko-http |
| `be-kind-rewind-munit` | munit integration |
| `be-kind-rewind-scalatest` | ScalaTest integration |
| `be-kind-rewind-zio-test` | zio-test integration |
| `be-kind-rewind-codec-circe-json` | JSON recordings (included by the test framework modules) |
| `be-kind-rewind-codec-circe-yaml` | YAML recordings |
| `be-kind-rewind-xml` | Matching on XML request bodies |

## Quick start

The easiest way to use Be Kind Rewind is through one of the test framework integrations. Each test gets its own VCR
client and its own recording file.

Add the dependencies for your HTTP client and test framework (sttp and munit in this example):

```scala
"com.github.reibitto" %% "be-kind-rewind-sttp"  % "0.2.0" % Test
"com.github.reibitto" %% "be-kind-rewind-munit" % "0.2.0" % Test
```

Then:

```scala
import bekindrewind.munit.VcrSuite
import bekindrewind.sttpclient.VcrBackend
import sttp.client3._

class UserApiSpec extends VcrSuite {
  val backend = HttpClientSyncBackend()

  val vcrBackend = vcr(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions))

  // The first time you run this a real HTTP request will be sent and recorded to
  // src/test/resources/vcr/UserApiSpec/gets-a-user.json. The next time you run it, the
  // recorded response will be played back instead.
  vcrBackend.test("gets a user") { backend =>
    val response = basicRequest.get(uri"https://postman-echo.com/get?user=1").send(backend)
    assert(response.is200)
  }
}
```

The VCR client is saved when the test ends. It isn't closed, so one underlying HTTP client can be shared by the whole
suite.

## Test framework integration

All integrations have the same settings, which can be overridden in your suite:

| Setting | Default |
| --- | --- |
| `vcrDirectory` | `src/test/resources/vcr` in the suite's module (see below) |
| `vcrLayout` | `VcrLayout.PerTest` (see [Recording layout](#recording-layout)) |
| `vcrCodec` | `JsonCodec` (use `YamlCodec` from `be-kind-rewind-codec-circe-yaml` for YAML) |
| `vcrSuiteName` | The suite's class name |
| `vcrDefaultRecordMode` | `RecordMode.NewEpisodes`. `VCR_RECORD_MODE` overrides it if set |
| `vcrRecordOptions` | `RecordOptions.default` with the record mode above |

The module is found from where the suite's compiled classes are (`<module>/target/...` for sbt and Maven,
`<module>/build/classes/...` for Gradle), so recordings end up in the right place even when tests run from another
directory. For other layouts (such as sbt 2, which keeps every module's output under the root `target`), set the
module directory with a system property, e.g. in sbt:
`Test / javaOptions += s"-Dvcr.base.directory=${baseDirectory.value}"`.

To make CI never send real requests, have suites only replay unless asked to record:

```scala
override def vcrDefaultRecordMode = RecordMode.ReplayOnly
```

The context passed to the client factory (`ctx` in the examples) contains the test's `storage`, `recordOptions`,
`testName`, `suiteName` and recording `path`.

### munit

```scala
"com.github.reibitto" %% "be-kind-rewind-munit" % "0.2.0" % Test
```

```scala
class UserApiSpec extends VcrSuite {
  val vcrBackend = vcr(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions))

  vcrBackend.test("gets a user") { backend => ... }
}
```

`withVcr(testName)(create)(use)` (and `withVcrAsync` for code returning a `Future`) is also available for creating a
client inside any test.

### ScalaTest

```scala
"com.github.reibitto" %% "be-kind-rewind-scalatest" % "0.2.0" % Test
```

Mix in `VcrTestSuite` (or `AsyncVcrTestSuite` for async suites) and wrap the test body with `withVcr`:

```scala
import bekindrewind.scalatest.VcrTestSuite
import org.scalatest.funsuite.AnyFunSuite

class UserApiSpec extends AnyFunSuite with VcrTestSuite {
  test("gets a user") {
    withVcr(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)) { backend =>
      ...
    }
  }
}
```

Put the test's assertions inside `withVcr`, since whether the block succeeds determines how
[unused entries](#unused-entries) are handled. In async suites, call `withVcr` at the start of the test rather than
inside a callback.

### zio-test

```scala
"com.github.reibitto" %% "be-kind-rewind-zio-test" % "0.2.0" % Test
```

```scala
import bekindrewind.ziotest.VcrSpec
import zio._
import zio.test._

object UserApiSpec extends ZIOSpecDefault with VcrSpec {
  def spec = suite("UserApi")(
    vcrTest("gets a user")(ctx => VcrBackend(backend, ctx.storage, ctx.recordOptions)) { backend =>
      ZIO.attempt(basicRequest.get(uri"https://postman-echo.com/get").send(backend)).map { response =>
        assertTrue(response.is200)
      }
    }
  )
}
```

`withVcr(testName)(create)(use)` is also available.

### Recording layout

By default each test is recorded to its own file: `<vcrDirectory>/<suite>/<test>.json`. To keep all of a suite's tests
in one file instead (`<vcrDirectory>/<suite>.json`), override the layout:

```scala
override def vcrLayout = VcrLayout.PerSuite
```

Every entry is labelled with the test that recorded it, and in either layout a test only sees and replaces its own
entries (even if two test names map to the same file name).

### File names

Per-test files are named after the test by default. Override `vcrFileNaming` to change that:

```scala
override def vcrFileNaming = VcrFileNaming.ByTestName()                  // gets-a-user.json (default)
override def vcrFileNaming = VcrFileNaming.ByHash()                      // 3f1c9a0b7d2e4f68.json
override def vcrFileNaming = VcrFileNaming.Custom(_.testName.stripPrefix("The API should "))
```

Whichever you choose, names are made safe for every OS: only letters, digits, `.`, `_` and `-` are kept, Windows device
names such as `CON` are avoided, and names (including the extension) never exceed `maxLength` (100 characters by
default, which leaves room for the checkout and suite directories within Windows' 260 character path limit). Longer
names are truncated and get a short hash of the full name, so they stay unique.

To rename existing recordings after changing the naming (they're matched to tests by the test names stored in them):

```
sbt "Test/runMain bekindrewind.codec.VcrTool rename src/test/resources/vcr json --naming by-hash --dry-run"
``` So running a subset of tests (e.g. with `testOnly`) or running tests in parallel works the same either way.
One file per test is easier to navigate and less prone to merge conflicts. One file per suite means fewer files.

### Clients created outside of tests (dependency injection)

If your HTTP clients are created once and shared by every test (for example by a dependency injection framework),
create them with the suite's `vcrSession.storage` and mix in the session integration. `vcrSession.storage` always refers
to the running test, no matter which thread or fiber makes the request:

```scala
// ScalaTest (also AsyncVcrSessionSuite)
class PaymentsSpec extends AnyFunSuite with VcrSessionSuite {
  val client = VcrBackend(backend, vcrSession.storage) // or pass vcrSession.storage to your DI module

  test("creates a payment") { ... }
}

// munit
class PaymentsSpec extends VcrSessionSuite { ... }

// zio-test
object PaymentsSpec extends ZIOSpecDefault with VcrSessionSpec { ... }
```

Every test in the suite then gets its own recording, and the record options come from the suite. Since there's only one
running test per suite, its tests run one at a time. zio-test names tests after their labels below the root suite (e.g.
`payments / creates a payment`) and numbers duplicate names.

Requests made while no test is running fail, except those the matcher doesn't record (see
[`shouldRecord`](#only-record-a-subset-of-http-requests)), which are sent as usual.

For HTTP libraries that aren't supported out of the box, `vcrSession.client(matcher)` returns a `VcrClient` to build an
adapter with (see `VcrClient.resolve`). For play-ws request types of your own, `PlayVcr` has the helpers the built-in
Play clients use.

### Changing the record mode without code changes

The `VCR_RECORD_MODE` environment variable (or the `vcr.record.mode` system property) sets the
[record mode](#record-modes) for all suites. For example, to make sure CI never sends real requests:

```
VCR_RECORD_MODE=replay-only sbt test
```

Or to re-record everything:

```
VCR_RECORD_MODE=record-all sbt test
```

## Using an HTTP client directly

The VCR clients can also be used without a test framework. The recording is written when the client is closed (or when
`save()` is called).

### sttp (client3)

```scala
"com.github.reibitto" %% "be-kind-rewind-sttp" % "0.2.0"
"com.github.reibitto" %% "be-kind-rewind-codec-circe-json" % "0.2.0"
```

```scala
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient.VcrBackend
import sttp.client3._
import java.nio.file.Paths

val vcrBackend = VcrBackend(
  underlyingClient = HttpClientSyncBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

basicRequest.get(uri"https://postman-echo.com/get?a=1").send(vcrBackend)

vcrBackend.close()
```

<details>
  <summary>sttp (client4)</summary>

```scala
"com.github.reibitto" %% "be-kind-rewind-sttp4" % "0.2.0"
```

```scala
import bekindrewind.sttpclient4.{ VcrBackend, VcrSettings }
import sttp.client4._

val vcrBackend = VcrBackend(
  DefaultSyncBackend(),
  VcrSettings(storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec))
)

basicRequest.get(uri"https://postman-echo.com/get?a=1").send(vcrBackend)

vcrBackend.close()
```

Sync, async, streaming and WebSocket backends are supported. WebSocket requests are passed through without being
recorded.
</details>

<details>
  <summary>Java HttpClient</summary>

```scala
"com.github.reibitto" %% "be-kind-rewind-java-http" % "0.2.0"
```

`VcrHttpClient` extends `java.net.http.HttpClient`, so it can be passed to any code that expects one.

```scala
import bekindrewind.javahttp.VcrHttpClient
import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }

val client = VcrHttpClient(HttpClient.newHttpClient(), FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec))

client.send(HttpRequest.newBuilder(URI.create("https://postman-echo.com/get?a=1")).build(), HttpResponse.BodyHandlers.ofString())

client.close()
```

`send` and `sendAsync` work with any `BodyHandler`, including streaming ones such as `ofInputStream`.
</details>

<details>
  <summary>play-ws-standalone</summary>

```scala
"com.github.reibitto" %% "be-kind-rewind-play-standalone" % "0.2.0"
```

```scala
import bekindrewind.playwsstandalone.VcrStandaloneWSClient
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.{ Materializer, SystemMaterializer }
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import scala.concurrent.Await
import scala.concurrent.duration._

implicit val system: ActorSystem           = ActorSystem()
implicit val materializer: Materializer = SystemMaterializer(system).materializer

val client = VcrStandaloneWSClient(
  underlyingClient = StandaloneAhcWSClient(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

Await.result(client.url("https://postman-echo.com/get?a=1").get(), 30.seconds)

client.close()
system.terminate()
```
</details>

<details>
  <summary>play-ws</summary>

```scala
"com.github.reibitto" %% "be-kind-rewind-play" % "0.2.0"
```

```scala
import bekindrewind.playws.VcrWSClient
import play.api.libs.ws.WSClient

// Use the WSClient injected by Play
val wsClient: WSClient = ???

val client = VcrWSClient(
  underlyingClient = wsClient,
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

Await.result(client.url("https://postman-echo.com/get?a=1").get(), 30.seconds)

client.close()
```
</details>

<details>
  <summary>pekko-http</summary>

```scala
"com.github.reibitto" %% "be-kind-rewind-pekko-http" % "0.2.0"
```

```scala
import bekindrewind.pekkohttp.VcrHttp
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.HttpRequest

implicit val system: ActorSystem = ActorSystem()

val client = VcrHttp.useClassicActorSystem(FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec))

Await.result(client.send(HttpRequest(uri = "https://postman-echo.com/get?a=1")), 30.seconds)

client.close()
system.terminate()
```

Pekko HTTP has no client object, so `VcrHttp.create` can also wrap any `HttpRequest => Future[HttpResponse]` function.
</details>

## Record modes

The record mode determines when requests are replayed and when they are sent and recorded:

| Mode | Behavior |
| --- | --- |
| `RecordMode.NewEpisodes` (default) | Replay recorded requests and record new ones |
| `RecordMode.Once` | Record everything if nothing was recorded yet. Otherwise only replay, and fail on unrecorded requests |
| `RecordMode.ReplayOnly` | Only replay. Unrecorded requests fail instead of being sent (useful for CI) |
| `RecordMode.RecordAll` | Ignore previous recordings and re-record everything |

```scala
VcrBackend(backend, storage, recordOptions = RecordOptions.default.mode(RecordMode.ReplayOnly))
```

Requests that fail because of the record mode fail with a `VcrException` without being sent.

## Matching requests

By default, Be Kind Rewind matches requests by `method + uri` only. When the same request is made several times, the
recorded responses are replayed in the order they were recorded.

Prebuilt matchers:

| Matcher | Matches on |
| --- | --- |
| `VcrMatcher.default` | method + URI |
| `VcrMatcher.methodUriAndBody` | method + URI + body |
| `VcrMatcher.ignoringQueryParamOrder` | method + URI, where the order of query parameters doesn't matter |
| `VcrMatcher.methodUriAndHeaders("Accept", ...)` | method + URI + the specified headers (names are case-insensitive) |
| `VcrMatcher.identity` | the entire request |
| `JsonMatchers.methodUriAndJsonBody("timestamp", ...)` | method + URI + JSON body, ignoring the specified fields at any depth as well as formatting and field order (in `be-kind-rewind-codec-circe-json`) |
| `XmlMatchers.methodUriAndXmlBody(ignoredElements, ignoredAttributes)` | method + URI + XML body, ignoring the specified elements and attributes at any depth as well as whitespace and comments (in `be-kind-rewind-xml`) |

`JsonMatchers.methodUriAndJsonBody` is useful for request bodies with values that change every run, such as timestamps
or nonces. Without it, a real HTTP request would be sent every run because every request would look new.

`JsonMatchers.methodUriAndJsonBody(ignoredFields, parseNestedJson = true)` also parses JSON inside string fields, for
APIs that wrap one JSON request in another.

### Ignoring parts of requests

These can be added to any matcher. They only affect matching, so the recordings keep the original values:

```scala
JsonMatchers
  .methodUriAndJsonBody("timestamp")
  .ignoreQueryParams("nonce", "sign")
  .mapUri(uri => URI.create(uri.toString.replaceAll("/orders/[0-9]+", "/orders/ID")))
  .normalizeForMatching(req => req.copy(headers = req.headers - "X-Request-Id"))
```

To remove values from the recordings as well, see [Filtering sensitive data](#filtering-sensitive-data).

### Custom matchers

Use `VcrMatcher.groupBy` to match on anything else. Requests with equal keys are considered the same request:

```scala
import bekindrewind.{ RequestKeys, VcrMatcher }

val matcher = VcrMatcher.groupBy { req =>
  (req.method, RequestKeys.sortedQueryParams(req.uri), RequestKeys.headers(req, "X-Api-Version"))
}
```

### Only record a subset of HTTP requests

Requests rejected by `shouldRecord` are sent as usual but not recorded (or fail, if
`RecordOptions.notRecordedThrowsErrors` is set):

```scala
VcrMatcher.default.withShouldRecord(_.uri.getHost == "postman-echo.com")
```

### Using multiple matchers

If you're using the same HTTP client for multiple APIs, you may want a different matcher (or redaction) for each one.
Combine matchers with `append` (or the `:+` alias). Each request uses the first matcher whose `shouldRecord` accepts it:

```scala
val ipifyMatcher = VcrMatcher.default.withShouldRecord(_.uri.getHost == "api.ipify.org")

val postmanEchoMatcher = JsonMatchers
  .methodUriAndJsonBody("timestamp")
  .withShouldRecord(_.uri.getHost == "postman-echo.com")

val matcher = ipifyMatcher :+ postmanEchoMatcher
```

## Filtering sensitive data

You likely don't want to commit API keys and other secrets to source control, especially in a public repository.
Redaction replaces values with `REDACTED` before recordings are saved:

```scala
val matcher = VcrMatcher.default
  .redactHeaders("Authorization", "X-API-Key")           // request and response headers, case-insensitive
  .redactQueryParams("api_key")                          // query parameters
  .redactValues(apiKey)                                  // known secrets, wherever they appear
  .mapRequest(JsonRedaction.request("password"))         // JSON request body fields (be-kind-rewind-codec-circe-json)
  .mapResponse(JsonRedaction.response("accessToken"))    // JSON response body fields
```

The values of redacted headers and query parameters are also replaced wherever else they appear in the entry: in
bodies, URIs and other headers, of both the request and the response. That covers servers that echo requests back, or
error messages that include an API key. Only values of at least 8 characters are replaced elsewhere, since shorter ones
are unlikely to be secrets. Binary (base64) bodies are left as is.

Request redaction is also applied to live requests before they are matched, so a redacted query parameter still
matches on replay.

For anything else, `mapRequest`, `mapResponse` and `withTransformer` (or `andThenTransform` to add to the existing
transformer) take arbitrary functions that are applied before saving:

```scala
VcrMatcher.default.andThenTransform { entry =>
  entry.copy(response = entry.response.copy(headers = entry.response.headers - "Set-Cookie"))
}
```

## Expiring recordings

Set `expiresAfter` to re-record once recordings reach a certain age:

```scala
RecordOptions.default.expiresAfter(Duration.ofDays(90))
```

A test's entries expire together once the oldest one is older than `expiresAfter`, so that a test never mixes old and
newly recorded responses.

## Unused entries

Recorded entries that were never replayed, such as requests the test no longer makes, are reported when the recording
is saved. This is controlled by `RecordOptions.unusedEntries`:

| Setting | Behavior |
| --- | --- |
| `UnusedEntries.Warn` (default) | Print the unused entries to stderr |
| `UnusedEntries.Prune` | Remove the unused entries from the recording |
| `UnusedEntries.Fail` | Fail the test (useful on CI to catch stale recordings) |
| `UnusedEntries.Ignore` | Do nothing |

Unused entries are only handled when the test succeeded, since a failed test may not have made all of its requests.
`Prune` only applies in `NewEpisodes` mode and when the recording belongs to a single test (which is always the case with
the test framework integrations). Otherwise it behaves like `Warn`.

## Recordings

Recordings are human-readable. Bodies are stored as text when possible and as base64 for binary content, so responses
are replayed byte for byte. Text in a charset other than UTF-8 (such as Shift_JIS, from the `Content-Type` header) is
stored as text too, with the charset as its `bodyEncoding`. In YAML, multi-line bodies are written as literal blocks unless that would change them
(for example CRLF line endings or trailing spaces, which YAML parsers and editors don't preserve):

```json
{
  "entries": [
    {
      "testName": "gets a user",
      "request": {
        "method": "GET",
        "uri": "https://postman-echo.com/get?user=1",
        "body": "",
        "httpVersion": "HTTP/1.1"
      },
      "response": {
        "statusCode": 200,
        "statusText": "OK",
        "headers": {
          "Content-Type": "application/json; charset=utf-8"
        },
        "body": "{\"args\":{\"user\":\"1\"}}",
        "contentType": "application/json; charset=utf-8"
      },
      "recordedAt": "2026-09-24T12:34:56.789Z"
    }
  ],
  "version": "0.2.0"
}
```

Replayed responses have an `X-VCR-Cache: true` header, in case you need to tell them apart from real responses.

### Codecs

Recordings are stored as JSON by default. To use YAML, add:

```scala
"com.github.reibitto" %% "be-kind-rewind-codec-circe-yaml" % "0.2.0"
```

And use `YamlCodec` (override `vcrCodec` in the test framework integrations, or pass it to `FileVcrStorage`):

```scala
FileVcrStorage(Paths.get("vcr/example.yml"), YamlCodec)
```

Other formats can be supported by implementing `Codec`, and other kinds of storage by implementing `VcrStorage`.

### Converting recordings

Recordings can be converted between formats without re-recording them, with `VcrConverter` (`convert`, `convertFile`
and `convertDirectory`) or from the command line (requires `be-kind-rewind-codec-circe-yaml`):

```
sbt "Test/runMain bekindrewind.codec.VcrTool convert src/test/resources/vcr yaml json --delete-sources"
```

Every recording is read before any is written, so nothing changes if one of them can't be read. Converting to the same
format (e.g. `yaml yaml`) rewrites recordings in the current output format.

## Migrating from 0.1.0

- The `akka-http` module was replaced by `be-kind-rewind-pekko-http` (package `bekindrewind.pekkohttp`), and the Play
  modules now use Play 3 (Pekko). Scala 3 is supported, and Scala 2.12 no longer is.
- `RecordOptions(overwriteAll = true)` is now `RecordOptions.default.mode(RecordMode.RecordAll)`.
- `VcrStorage.read()` returns `Either[Throwable, Option[VcrEntries]]`, and `Codec` has a new `fileExtension` member.
- `VcrRequest.httpVersion` is now optional, and `VcrRequest`/`VcrResponse` have a new `bodyEncoding` field.
- Expiration applies to each test's entries instead of the whole file.
- Recordings made with 0.1.0 can still be read.

## Overview

VCR testing isn't perfect and has some downsides as well. I know many people who have used this style of testing in the past, had a bad first experience, and wrote it off completely afterwards. So it's important to have proper expectations and avoid the common pitfalls.

Here I'll note some pros and cons, as well as some precautions and things to avoid to make your experience with this style of testing smoother.

### Pros

- Tests complete faster as no real HTTP requests are sent during playback
- Tests are deterministic and eliminate a whole class of flaky tests. You don't have to worry about 3rd party APIs having intermittent issues causing your tests to fail at unexpected times (such as being down for maintenance or running into API rate limiting, especially if multiple CI jobs are running at the same time).
- Can often be a full replacement for mocking HTTP requests. You can even use VCR tests to generate the request/response for you and then you can view and edit the recorded values yourself rather than writing absolutely everything by hand (which can be error prone).
- Some APIs have incomplete or outdated docs. Being able to generate real HTTP responses for the tests can expose a lot of these discrepancies early.

### Cons
- It might not always be immediately clear to the user of the library when an HTTP request will be recorded or not.
- If you're testing something like a list route, you may get different results if you try re-recording that same test in the future (since it's stateful). There may be strategies to get around this though, such as using query filters (date ranges, etc.) if the API you're using supports that. Or sandbox each test run if that's an option.
- Some APIs are trickier to test than others. One such example would be an API that has only 1 route for everything and all the parameters are defined in the request body. Be Kind Rewind matches on HTTP method + URL by default. You can change this to include the body as well (see [Matching requests](#matching-requests)).
- You need to be careful with unstable values in your request (such as timestamps, random IDs, etc). If the request matcher is looking at the body and there are random values in there, then the previously recorded entry won't be found. You need to write a custom matcher in such cases to strip out any random values that you have no control over.
- Committing recordings to source control may not be desirable to some. I find it helpful having recorded requests/responses that I can always refer to, but opinions vary.
- You need to be careful to not commit sensitive information such as API keys. Especially if your repository is public. Be extra careful if you don't have API keys that point to a sandbox environment.
- You need to re-record tests if the API that you're using introduces breaking changes. In such cases you would have to update your tests regardless of whether you use VCR testing or not, but you would catch this earlier if you were always making real HTTP requests.

### How to write good VCR tests

- Try not hardcoding IDs, keys, and so on. For example, if your test flow is like:
	- `POST /api/user/[hardcoded-username]` - Create user
	- `GET /api/user/[hardcoded-username]` - Get user and use it to assert that the response is correct

	This may not work a 2nd time if you re-record the test because the POST request will see that "hardcoded-username" already exists and fail, causing your test to also fail. This would force you update the source code and choose a new username this time, like "hardcoded-username2".

	This isn't sustainable though. Instead, prefer to write your VCR tests to hit APIs with generated values, like `POST /api/user/{generated-username}`. Appending timestamps, using UUIDs, and so on are recommended.
- Try not to make assumptions about what data already exists in the 3rd party system that you're using. For example, don't create a user with Postman and then only test the `GET /user/:id` route afterwards. If that user ever gets deleted or the test environment that you're using gets cleared, the test will fail when you go to re-generate it. Always prefer to include the create calls in your tests as well.

## Contributing

You can check for issues marked as `good first issue` [here](https://github.com/reibitto/be-kind-rewind/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22). Or even ones not marked as `good first issue`. Feel free to take whatever interests you.
