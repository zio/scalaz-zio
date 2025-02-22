---
id: annotating-tests
title: "Annotating Tests"
---

## Measuring Execution Time

We can annotate the execution time of each test using the `timed` test aspect:


```scala mdoc:compile-only
import zio._
import zio.test.{ test, _ }

suite("a timed suite")(
  test("A")(Live.live(ZIO.sleep(100.millis)).map(_ => assertTrue(true))),
  test("B")(assertTrue(true)),
  test("C")(assertTrue(true))
) @@ TestAspect.timed 
```

After running the test suite, the output should be something like this:

```
+ a timed suite - 178 ms (100.00%)
  + A - 108 ms (60.95%)
  + B - 34 ms (19.39%)
  + C - 35 ms (19.66%)
Ran 3 tests in 346 ms: 3 succeeded, 0 ignored, 0 failed
```

## Tagging

ZIO Test allows us to define some arbitrary tags. By labeling tests with one or more tags, we can categorize them, and then, when running tests, we can filter tests according to their tags.

Let's tag all slow tests and run them separately:

```scala mdoc:invisible
import zio.test.{test, _}

val longRunningAssertion        = assertTrue(true)
val anotherLongRunningAssertion = assertTrue(true)
```

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

object TaggedSpecsExample extends ZIOSpecDefault {
  def spec =
    suite("a suite containing tagged tests")(
      test("a slow test") {
        longRunningAssertion
      } @@ TestAspect.tag("slow", "math"),
      test("a simple test") {
        assertTrue(1 + 1 == 2)
      } @@ TestAspect.tag("math"),
      test("another slow test") {
        anotherLongRunningAssertion
      } @@ TestAspect.tag("slow")
    )
}
```

By adding the `-tags slow` argument to the command line, we will only run the slow tests:

```
sbt> testOnly TaggedSpecsExample -tags slow
```

The output would be:

```
[info] running (fork) TaggedSpecsExample -tags slow
[info] + a suite containing tagged tests - tagged: "slow", "math"
[info]   + a slow test - tagged: "slow", "math"
[info]   + another slow test - tagged: "slow"
[info] Ran 2 tests in 162 ms: 2 succeeded, 0 ignored, 0 failed
[success] Total time: 1 s, completed Nov 2, 2021, 12:36:36 PM
```

Run tests tagged with `slow` for the entire project:

```
sbt> testOnly -- -tags slow
```
