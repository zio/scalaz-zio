---
id: index
title: "Introduction to Test Aspects"
sidebar_label: "Test Aspects"
---

A `TestAspect` is an aspect that can be weaved into specs. We can think of an aspect as a polymorphic function, capable of transforming one test into another, possibly enlarging the environment or error type. We use them to change existing tests or even entire suites or specs that we have already created.

We can think of a test aspect as a Spec transformer. It takes one spec, transforms it, and produces another spec (`Spec => Spec`). Test aspects are applied to a test or suite using the `@@` operator:

```scala mdoc:invisible
val testAspect = zio.test.TestAspect.identity
```

```scala mdoc:compile-only
import zio.test.{test, _}

test("a single test") {
  ???
} @@ testAspect

suite("suite of multiple tests") {
  ???
} @@ testAspect
```

Test aspects encapsulate cross-cutting concerns and increase the modularity of our tests. So we can focus on the primary concerns of our tests and at the end of the day, we can apply required aspects to our tests.

The great thing about test aspects is that they are very composable. So we can chain them one after another. We can even have test aspects that modify other test aspects.

Let's say we have the following test:

```scala mdoc:compile-only
import zio.test._

test("test") {
  assertTrue(true)
}
```

We can pass this test to whatever test aspect we want. For example, to run this test only on the JVM and repeat it five times, we can write the test as below:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

repeat(Schedule.recurs(5))(
  jvmOnly(
    test("test") {
      assertTrue(true)
    }
  )
)
```

To compose the aspects, we have a very nice `@@` syntax, which helps us to write tests concisely. So the previous example can be written as follows:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

test("test") {
  assertTrue(true)
} @@ jvmOnly @@ repeat(Schedule.recurs(5))
```

When composing test aspects, **the order of test aspects is important**. So if we change the order, their behavior may change. For example, the following test will repeat the test 2 times:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

suite("suite")(
  test("A") {
    ZIO.debug("executing test")
      .map(_ => assertTrue(true))
  },
) @@ nonFlaky @@ repeats(2)
```

The output:

```
executing test
executing test
executing test
+ suite - repeated: 2
  + A - repeated: 2
Ran 1 test in 343 ms: 1 succeeded, 0 ignored, 0 failed
```

But the following test aspect repeats the test 100 times:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

suite("suite")(
  test("A") {
    ZIO.debug("executing test")
      .map(_ => assertTrue(true))
  },
) @@ repeats(2) @@ nonFlaky
```

The output:

```
executing test
executing test
executing test
executing test
executing test
...
executing test
+ suite - repeated: 100
  + A - repeated: 100
Ran 1 test in 478 ms: 1 succeeded, 0 ignored, 0 failed
```

## Examples

So let's say we have a challenge that we need to run a test, and we want to make sure there is no flaky on the JVM, and then we want to make sure it doesn't take more than 60 seconds:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

test("a test with two aspects composed together") {
  ???
} @@ jvm(nonFlaky) @@ timeout(60.seconds)
```

This is another example of a test suite showing the use of aspects to modify test behavior:

```scala mdoc:compile-only
import zio.test._
import zio.{test => _, _}
import zio.test.TestAspect._

object MySpec extends ZIOSpecDefault {
  def spec = suite("A Suite")(
    test("A passing test") {
      assertTrue(true)
    },
    test("A passing test run for JVM only") {
      assertTrue(true)
    } @@ jvmOnly, // @@ jvmOnly only runs tests on the JVM
    test("A passing test run for JS only") {
      assertTrue(true)
    } @@ jsOnly, // @@ jsOnly only runs tests on Scala.js
    test("A passing test with a timeout") {
      assertTrue(true)
    } @@ timeout(10.nanos), // @@ timeout will fail a test that doesn't pass within the specified time
    test("A failing test... that passes") {
      assertTrue(true)
    } @@ failing, //@@ failing turns a failing test into a passing test
    test("A ignored test") {
      assertTrue(false)
    } @@ ignore, //@@ ignore marks test as ignored
    test("A test using a live service instead of the test service") {
      for {
        _ <- TestClock.timeZone
      } yield assertCompletes
    } @@ withLiveClock, //@@ withLiveClock uses the live Clock service from the ZIO runtime in the test
    test("A flaky test that only works on the JVM and sometimes fails; let's compose some aspects!") {
      assertTrue(false)
    } @@ jvmOnly           // only run on the JVM
      @@ eventually        // @@ eventually retries a test indefinitely until it succeeds
      @@ timeout(20.nanos) // it's a good idea to compose `eventually` with `timeout`, or the test may never end
  ) @@ timeout(60.seconds) // apply a timeout to the whole suite
}
```
