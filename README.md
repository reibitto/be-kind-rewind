# Be Kind Rewind ⏪

![Scala CI](https://github.com/reibitto/be-kind-rewind/workflows/Scala%20CI/badge.svg)

*A VCR testing library for Scala*

## What is it?

Be Kind Rewind is a VCR testing library for Scala, similar to the popular [vcr](https://github.com/vcr/vcr) Ruby library. This library records HTTP interactions and plays them back in subsequent runs.

The goal is to make integration tests fast, deterministic, and potentially eliminate the need for mocks and fakes completely for HTTP requests.

Be Kind Rewind supports multiple HTTP clients ([sttp](https://sttp.softwaremill.com), [play-ws](https://github.com/playframework/play-ws)) and eventually direct integrations with popular testing frameworks (scalatest, MUnit, specs2, zio-test, etc.).

(***Note:*** *Be Kind Rewind hasn't been officially released yet. The first release will be coming very soon.*)

## Example usage

### Minimal example (using sttp)

```scala
import sttp.client3._

val request = basicRequest.get(uri"https://postman-echo.com/get?a=1")
val vcrBackend = VcrBackend(HttpURLConnectionBackend(), Paths.get("vcr/example.json"))

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
request.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

### Using a custom matcher

By default, Be Kind Rewind matches requests by `method + uri` only. Let's say you want to instead match on `method + uri + body`, except that you want to exclude one field in the JSON body (timestamp in this case). You can write use a custom matcher to accomplish this:

```scala
import sttp.client3._
import io.circe.syntax._

val request = basicRequest
  .post(uri"https://postman-echo.com/post")
  .body(
    Json.obj(
      "user" -> "foo".asJson,
      "timestamp" -> System.currentTimeMillis().asJson
    ).noSpaces
  )

val vcrBackend = VcrBackend(
  HttpURLConnectionBackend(),
  Paths.get("vcr/example2.json"),
  matcher = VcrMatcher.groupBy { req =>
    // This is just an example. Handle errors properly in real code.
    val jsonBody = io.circe.parser.parse(req.body).toOption.flatMap(_.asObject)

    // We want to key off `method + uri + body (excluding the timestamp)`
    (req.method, req.uri, jsonBody.remove("timestamp"))
  }
)

// The first time you run this a real HTTP request will be sent. The next time you run it, the
// recorded response will be played back instead.
request.send(vcrBackend)

// The recorded VCR file gets written on close
vcrBackend.close()
```

Note that if you didn't exclude `timestamp`, a real HTTP request would be sent every run because the matcher would see each request as a unique one.

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

  There's also an issue to [automate this](https://github.com/reibitto/be-kind-rewind/issues/5) in case you would like to specify something like "re-record the tests every week"

### How to write good VCR tests

- Try not hardcoding IDs, keys, and so on. For example, if your test flow is like:
	- `POST /api/user/[hardcoded-username]` - Create user
	- `GET /api/user/[hardcoded-username]` - Get user and use it to assert that the response is correct

	This may not work a 2nd time if you re-record the test because the POST request will see that "hardcoded-username" already exists and fail, causing your test to also fail. This would force you update the source code and choose a new username this time, like "hardcoded-username2".

	This isn't sustainable though. Instead, prefer to write your VCR tests to hit APIs with generated values, like `POST /api/user/{generated-username}`. Appending timestamps, using UUIDs, and so on are recommended.
- Try not to make assumptions about what data already exists in the 3rd party system that you're using. For example, don't create a user with Postman and then only test the `GET /user/:id` route afterwards. If that user ever gets deleted or the test environment that you're using gets cleared, the test will fail when you go to re-generate it. Always prefer to include the create calls in your tests as well.

## Contributing

There are a lot of issues marked as "good first issue" [here](https://github.com/reibitto/be-kind-rewind/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22). Feel free to take any that interest you.
