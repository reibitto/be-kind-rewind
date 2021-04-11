# Be Kind Rewind ⏪

![Scala CI](https://github.com/reibitto/be-kind-rewind/workflows/Scala%20CI/badge.svg)

*A VCR testing library for Scala*

## What is it?

Be Kind Rewind is a VCR testing library for Scala, similar to the popular [vcr](https://github.com/vcr/vcr) Ruby library. This library records HTTP interactions and plays them back in subsequent runs.

The goal is to make integration tests fast, deterministic, and potentially eliminate the need for mocks and fakes completely for HTTP requests.

Be Kind Rewind supports multiple HTTP clients ([sttp](https://sttp.softwaremill.com), [play-ws](https://github.com/playframework/play-ws), [akka-http](https://doc.akka.io/docs/akka-http/current/client-side/index.html)) with plans to support more (see [this issue](https://github.com/reibitto/be-kind-rewind/issues/4)).

(***Note:*** *Be Kind Rewind hasn't been officially released yet. The first release will be coming very soon.*)

## Example usage

### Minimal example (using `sttp`)

Add the following dependencies:

```scala
"com.github.reibitto" %% "be-kind-rewind-sttp" % "0.1.0"
"com.github.reibitto" %% "be-kind-rewind-codec-circe-json" % "0.1.0" // Optional
```

_(See the [Codecs](#codecs) section if you want to use a different codec, such as for YAML)_

Then:

```scala
import sttp.client3._
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient._
import java.nio.file.Paths

val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

val request = basicRequest.get(uri"https://postman-echo.com/get?a=1")

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
request.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

### Minimal example (using `play-ws-standalone`)

<details>
  <summary>Click to expand</summary>

Add the following dependencies:

```scala
"com.github.reibitto" %% "be-kind-rewind-play-standalone" % "0.1.0"
"com.github.reibitto" %% "be-kind-rewind-codec-circe-json" % "0.1.0" // Optional
```

Then:

```scala
import akka.actor.ActorSystem
import akka.stream.SystemMaterializer
import bekindrewind.playwsstandalone.VcrStandaloneWSClient
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration._

// Create Akka system for thread and streaming management
implicit val system = ActorSystem()
system.registerOnTermination {
  System.exit(0)
}

implicit val materializer = SystemMaterializer(system).materializer

val client = VcrStandaloneWSClient(
  underlyingClient = StandaloneAhcWSClient(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
Await.result(client.url("https://postman-echo.com/get?a=1").get(), 30.seconds)

// The recorded VCR file gets written on close
client.close()
system.terminate()
```
</details>

### Minimal example (using `play-ws`)

<details>
  <summary>Click to expand</summary>

Add the following dependencies:

```scala
"com.github.reibitto" %% "be-kind-rewind-play" % "0.1.0"
"com.github.reibitto" %% "be-kind-rewind-codec-circe-json" % "0.1.0" // Optional
```

Then:

```scala
import akka.actor.ActorSystem
import akka.stream.SystemMaterializer
import bekindrewind.playws._
import play.api.libs.ws.WSClient
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration._

// Create Akka system for thread and streaming management
implicit val system = ActorSystem()
system.registerOnTermination {
  System.exit(0)
}

implicit val materializer = SystemMaterializer(system).materializer

// Use the WSClient injected by Play
val wsClient: WSClient = ???
    
val client = VcrWSClient(
  underlyingClient = wsClient,
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec)
)

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
Await.result(client.url("https://postman-echo.com/get?a=1").get(), 30.seconds)

// The recorded VCR file gets written on close
client.close()
system.terminate()
```
</details>

### Minimal example (using `akka-http`)

<details>
  <summary>Click to expand</summary>

Add the following dependencies:

```scala
"com.github.reibitto" %% "be-kind-rewind-akka-http" % "0.1.0"
"com.github.reibitto" %% "be-kind-rewind-codec-circe-json" % "0.1.0" // Optional
```

Then:

```scala
import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import bekindrewind.akkahttp.VcrHttp
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import java.nio.file.Paths
import scala.concurrent.Await
import scala.concurrent.duration._

implicit val system = ActorSystem.create()

val client  = VcrHttp.useClassicActorSystem(FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec))
val request = HttpRequest(uri = "http://localhost:7777")

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
Await.result(client.send(request), 30.seconds)

// The recorded VCR file gets written on close
client.close()
system.terminate()
```
</details>

### Using a custom matcher

By default, Be Kind Rewind matches requests by `method + uri` only. Let's say you want to instead match on
`method + uri + body`, except that you want to exclude one field in the JSON body (timestamp in this case). You can
write a custom matcher to accomplish this:

```scala
import bekindrewind._
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient._
import io.circe.Json
import io.circe.syntax._
import sttp.client3._
import java.nio.file.Paths

val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec),
  matcher = VcrMatcher.groupBy { req =>
    // This is just an example. Handle errors properly in real code.
    val jsonBody = io.circe.parser.parse(req.body).toOption.flatMap(_.asObject).get

    // We want to key off `method + uri + body (excluding the timestamp)`
    (req.method, req.uri, jsonBody.remove("timestamp"))
  }
)

val request = basicRequest
  .post(uri"https://postman-echo.com/post")
  .body(
    Json.obj(
      "user" -> "foo".asJson,
      "timestamp" -> System.currentTimeMillis().asJson
    ).noSpaces
  )

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
request.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

Note that if you didn't exclude `timestamp`, a real HTTP request would be sent every run because the matcher would see each request as a unique one.

### Only record a subset of HTTP requests

You can use the `shouldRecord` filter to choose which requests to record and which to leave alone.

```scala
import bekindrewind._
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient._
import sttp.client3._
import java.nio.file.Paths

val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec),
  matcher = VcrMatcher.default.withShouldRecord { req =>
    req.uri.getHost == "postman-echo.com"
  }
)

// Will record this request since the host matches our filter.
val request1 = basicRequest.get(uri"https://postman-echo.com/get?a=1")
request1.send(vcrBackend)

// The HTTP request will go through as normal, but it won't be recorded as it doesn't match the filter.
val request2 = basicRequest.get(uri"https://api.ipify.org")
request2.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

### Using VCR transformers (e.g. filter sensitive data)

A transformer is basically just a mapping function `VcrEntry => VcrEntry` that is applied before saving to disk. This is
particularly useful if you want filter sensitive data such as API keys. You likely don't want to commit those to source
control, especially in a public repository.

```scala
import bekindrewind._
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient._
import sttp.client3._
import java.nio.file.Paths

val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec),
  matcher = VcrMatcher.default.withTransformer { entry =>
    entry.copy(
      request = entry.request.copy(
        // Remove the sensitive HTTP request header
        headers = entry.request.headers - "X-API-Key"
      )
    )
  }
)

val request = basicRequest.get(uri"https://postman-echo.com/get?a=1").header("X-API-Key", "very-secret-key")

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
request.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

### Making recorded VCR entries expire after some time

You can set the `expiresAfter` Duration field in `RecordOptions` to make recorded VCR entries expire after a specified
amount of time.

```scala
val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec),
  recordOptions = RecordOptions.default.copy(
    expiresAfter = Some(Duration.ofDays(90))
  )
)
```

### Using multiple matchers

If you're using the same HTTP client instance for multiple websites/APIs/etc., you may find yourself wanting to
customize the VCR matcher for each one. For example, 2 separate APIs having different fields that you want to not match
on. Or if you need different transformers for each API.

You can combine multiple VcrMatchers with `append` (or the `:+` alias) like this:

```scala
import bekindrewind.VcrMatcher
import bekindrewind.codec.JsonCodec
import bekindrewind.storage.FileVcrStorage
import bekindrewind.sttpclient._
import io.circe.Json
import io.circe.syntax._
import sttp.client3._
import java.nio.file.Paths

val ipifyMatcher = VcrMatcher.default.withShouldRecord(_.uri.getHost == "api.ipify.org")

val postmanEchoMatcher = VcrMatcher.groupBy { req =>
  // This is just an example. Handle errors properly in real code.
  val jsonBody = io.circe.parser.parse(req.body).toOption.flatMap(_.asObject).get

  // We want to key off `method + uri + body (excluding the timestamp)`
  (req.method, req.uri, jsonBody.remove("timestamp"))
}.withShouldRecord(_.uri.getHost == "postman-echo.com")

val vcrBackend = VcrBackend(
  underlyingClient = HttpURLConnectionBackend(),
  storage = FileVcrStorage(Paths.get("vcr/example.json"), JsonCodec),
  matcher = ipifyMatcher :+ postmanEchoMatcher // Combines both matchers into one.
)

val request1 = basicRequest.get(uri"https://api.ipify.org")

// This will match `ipifyMatcher` because of its `shouldRecord` predicate which checks if the host is `api.ipify.org`
request1.send(vcrBackend)

val request2 = basicRequest
  .post(uri"https://postman-echo.com/post")
  .body(
    Json
      .obj(
        "user"      -> "foo".asJson,
        "timestamp" -> System.currentTimeMillis().asJson
      )
      .noSpaces
  )

// This will first fail to match `ipifyMatcher`, and then the next matcher is attempted. `postmanEchoMatcher` matches
// so that is used instead.
request2.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

### Codecs

The examples above use JSON for storing the recorded VCR entries, but you can also use other formats (as well as custom ones)
such as YAML.

To use YAML, add the following dependency:

```scala
"com.github.reibitto" %% "be-kind-rewind-codec-circe-yaml" % "0.1.0" // Optional
```

And then pass `YamlCodec` to `FileVcrStorage`:

```scala
FileVcrStorage(Paths.get("vcr/example.yml"), YamlCodec)
```

### Other

If you want to know whether the HTTP response is a VCR replay, you can check the follow response header:

```
X-VCR-Cache: true
```

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
- Some APIs are trickier to test than others. One such example would be an API that has only 1 route for everything and all the parameters are defined in the request body. Be Kind Rewind matches on HTTP method + URL by default. You can change this to include the body as well.
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
