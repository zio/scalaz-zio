---
id: test-hierarchies-and-organization
title: "Test Hierarchies and Organization"
---

A `Spec[R, E]` is the backbone of ZIO Test. All specs require an environment of type `R` and may potentially fail with an error of type `E`.

We can think of a spec as just a collection of tests. It is essentially a recursive data structure where every spec is just one individual test or a suite that itself can have multiple specs inside that each could be tests or sub suites. We can go down as far as we want in a recursive tree-like data structure.

## A Single Spec

The `test` constructor creates one single spec:

```scala mdoc:silent:nest
import zio.test._

val mySpec = test("true is true") {
  assertTrue(true)
}
```

Real tests that run some logic and return testing result are created mostly with `test` function. It expects two arguments, first one will be the label of test which will be used for visual reporting back to the user, and an assertion which contains some testable logic specified about a target under the test.

## Collection of Multiple Specs

The `suite` creates a suite which contains other specs (tests or suites):

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

## Collection of Multiple **Smart** Specs

The `suite` method creates a spec from a collection of specs. So what we can do is to provide it with a collection of specs:

```scala mdoc:compile-only
import zio.test._

object ExampleSpec extends ZIOSpecDefault {

  def spec =
    suite("some suite")(
      test("test 1") {
        val stuff = 1
        assertTrue(stuff == 1)
      },
      test("test 2") {
        val stuff = Some(1)
        assertTrue(stuff == Some(1))
      }
    )

}
```

But what if we wanted to have a suite of tests that work on a common value, e.g. the same `stuff`? ZIO provides the `suiteAll` method that helps us to share the same `stuff` between all tests:

```scala mdoc:compile-only
import zio.test._

object ExampleSpec extends ZIOSpecDefault {

  def spec =
    suiteAll("some suite") {

      val stuff = "hello"

      test("test 1") {
        assertTrue(stuff.startsWith("h"))
      }

      val stuff2 = 5

      test("test 2") {
        assertTrue(stuff.length == stuff2)
      }
    }

}
```

## Sharing the result of an effectful value between tests

As we saw in the previous section, the `suiteAll` method allows tests to work with common values. But what if the value is a result of some effect and we want to use it in several tests, without running the effect every time? Suites in `zio-test` can be effectual, so we can use this feature to share effectual values:

```scala mdoc:compile-only
import zio._
import zio.test._

object ExampleSpec extends ZIOSpecDefault {
  val spec =
    suite("some suite") {
      for {
        stuff <- ZIO.succeed("hello")
        stuff2 = 5
      } yield Chunk(

        test("test 1") {
          assertTrue(stuff.startsWith("h"))
        },

        test("test 2") {
          assertTrue(stuff.length == stuff2)
        }

      )
    }
}
```
