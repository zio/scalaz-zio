---
id: spec
title: "Spec"
---

A `Spec[R, E, T]` is the backbone of ZIO Test. All specs require an environment of type `R` and may potentially fail with an error of type `E`.

We can think of a spec as just a collection of tests. It is essentially a recursive data structure where every spec is just one individual test or a suite that itself can have multiple specs inside that each could be tests or sub suites. We can go down as far as we want in a recursive tree-like data structure.

## Constructors

- **A Single Test** — The `test` constructor creates one single spec (test):

  ```scala mdoc:silent:nest
  import zio.test._
  
  val mySpec = test("true is true") {
    assertTrue(true)
  }
  ```
  
  Real tests that run some logic and return testing result are created mostly with `test` function. It expects two arguments, first one will be the label of test which will be used for visual reporting back to the user, and an assertion which contains some testable logic specified about a target under the test.

- **Collection of Multiple Tests** — The `suite` creates a suite which contains other specs (tests or suites):

```scala mdoc:compile-only
import zio.test._

val mySuite =
  suite("A suite containing multiple tests")(
    test("the first test") {
      assertTrue(1 + 1 == 2)
    },
    test("the second test") {
      assertTrue(2 * 2 == 4)
    }
  )
```

  Suites can contain other suites. We can have multiple suites and one big suite that will aggregate them all:

```scala mdoc:compile-only
import zio.test._

suite("int and string")(
  suite("int suite")(
    test("minus")(assertTrue(2 - 1 == 1)),
    test("plus")(assertTrue(1 + 1 == 2))
  ),
  suite("string suite")(
    test("concat")(assertTrue("a" + "b" == "ab")),
    test("length")(assertTrue("abc".length == 3))
  )
)
```
  
## Dependencies on Other Services

Just like the `ZIO` data type, the `Spec` requires an environment of type `R`. When we write tests, we might need to access a service through the environment. It can be a combination of the standard services such a `Clock`, `Console`, `Random` and `System` or test services like `TestClock`, `TestConsole`, `TestRandom`, and `TestSystem`, or any user-defined services.

### Using Standard Test Services

All standard test services are located at the `zio.test.environment` package. They are test implementation of standard ZIO services. The use of these test services enables us to test functionality that depends on printing to or reading from a console, randomness, timings, and, also the system properties.

Let's see how we can test the `sayHello` function, which uses the `Console` service:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.Assertion._

import java.io.IOException

def sayHello: ZIO[Console, IOException, Unit] =
  Console.printLine("Hello, World!")

suite("HelloWorldSpec")(
  test("sayHello correctly displays output") {
    for {
      _      <- sayHello
      output <- TestConsole.output
    } yield assertTrue(output == Vector("Hello, World!\n"))
  }
)
```

There is a separate section in the documentation pages that covers [all built-in test services](./environment/index.md).

### Providing Layers

By using `Spec#provideLayer`, `Spec#provideSomeLayer`, or `Spec#provideCustomLayer`, a test or suite of tests can be provided with any dependencies in a similar way to how a ZIO data type can. 

### Sharing Layers Within a Suite

The `Spec` has a very nice mechanism to share layers within all tests in a suite. So instead of acquiring and releasing dependencies for each test, we can share the layer within all tests. The test framework acquires that layer for once and shares that between all tests. When the execution of all tests is finished, that layer will be released.

Assume we have the following tests:

```scala mdoc:invisible
import zio.test.{test, _}
import zio.{Chunk, _}

case class Row(key: String, value: String)

trait Kafka {
  def consume(topic: String): Task[Chunk[Row]]

  def produce(topic: String, key: String, value: String): Task[Unit]
}

object Kafka {
  def consume(topic: String) =
    ZIO.serviceWith[Kafka](_.consume(topic))

  def produce(topic: String, key: String, value: String) =
    ZIO.serviceWith[Kafka](_.produce(topic, key, value))
}

case class EmbeddedKafka() extends Kafka {
  override def consume(topic: String): Task[Chunk[Row]] =
    ZIO.succeed(Chunk.empty)

  override def produce(topic: String, key: String, value: String): Task[Unit] =
    Task.unit
}

object EmbeddedKafka {
  val layer = (EmbeddedKafka.apply _).toLayer[Kafka]
}
```

```mdoc:compile-only
val testA =
  test("producing an element to the kafka service") {
    for {
      _ <- Kafka.produce(
        topic = "testTopic",
        key = "key1",
        value = "value1")
    } yield assertTrue(true)
  }

val testB =
  test("consuming elements from the kafka service") {
    for {
      _ <- Kafka.consume(topic = "testTopic")
    } yield assertTrue(true)
  }
```

We can provide kafka as a shared layer within all tests in a suite:

```scala
suite("a test suite with shared kafka layer")(
  testA,
  testB
).provideCustomShared(EmbeddedKafka.layer)
```

## Operations

In ZIO Test, specs are just values like other data types in ZIO. So we can filter, map or manipulate these data types. In this section, we are going to learn some of the most important operations on the `Spec` data type:

### Test Aspects

We can think of a test aspect as a polymorphic function from one test to another test. We use them to change existing tests or even entire suites or specs that we have already created.

Test aspects are applied to a test or suite using the `@@` operator:

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

The great thing about test aspects is that they are very composable. So we chain them one after another. We can even have test aspects that modify other test aspects.

So let's say we have a challenge that we need to run a test, and we want to make sure there is no flaky on the JVM, and then we want to make sure it doesn't take more than 60 seconds:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}
import zio.test.TestAspect._

test("a test with two aspects composed together") {
  ???
} @@ jvm(nonFlaky) @@ timeout(60.seconds)
```

This is an example of a test suite showing the use of aspects to modify test behavior:

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
    test("A flaky test that only works on the JVM and sometimes fails; let's compose some aspects!") {
      assertTrue(false)
    } @@ jvmOnly           // only run on the JVM
      @@ eventually        // @@ eventually retries a test indefinitely until it succeeds
      @@ timeout(20.nanos) // it's a good idea to compose `eventually` with `timeout`, or the test may never end
  ) @@ timeout(60.seconds) // apply a timeout to the whole suite
}
```
