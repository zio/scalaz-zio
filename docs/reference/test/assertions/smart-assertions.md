---
id: smart-assertions
title: "Smart Assertions"
---

The smart assertion is a simple way to assert both _ordinary values_ and _ZIO effects_. It uses the `assertTrue` function, which uses macro under the hood.

## Asserting Ordinary Values

In the following example, we assert simple ordinary values using the `assertTrue` method:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

test("sum"){
  assertTrue(1 + 1 == 2)
}
```

We can assert multiple assertions inside a single `assertTrue`:

```scala
test("multiple assertions"){
  assertTrue(
    true,
    1 + 1 == 2,
    Some(1 + 1) == Some(2)
  )
}
```

## Asserting ZIO effects

The `assertTrue` method can also be used to assert ZIO effects:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

test("updating ref") {
  for {
    r <- Ref.make(0)
    _ <- r.update(_ + 1)
    v <- r.get
  } yield assertTrue(v == 1)
}
```

Using `assertTrue` with for-comprehension style, we can think of testing as these three steps:

1. **Set up the test** — In this section we should setup the system under test (e.g. `Ref.make(0)`).
2. **Running the test** — Then we run the test scenario according to the test specification. (e.g `ref.update(_ + 1)`)
3. **Making assertions about the test** - Finally, we should assert the result with the right expectations (e.g. `assertTrue(v == 1)`)

## Assertion Operators

Each `assertTrue` returns a `AssertResult`, so they have the same operators as `AssertResult`. Here are some of the useful operators:

1. **`&&`** - This is the logical and operator to make sure that both assertions are true:

```scala mdoc:compile-only
import zio.test._

test("&&") {
  check(Gen.int <*> Gen.int) { case (x: Int, y: Int) =>
    assertTrue(x + y == y + x) && assertTrue(x * y == y * x)
  }
}
```

2. **||** - This is the logical or operator to make sure that at least one of the assertions is true:

```scala mdoc:compile-only
import zio.test._

suite("||")(
  test("false || true") {
    assertTrue(false) || assertTrue(true) // this will pass
  },
  test("true || false") {
    assertTrue(true) || assertTrue(false) // this will pass
  },
  test("true || true") {
    assertTrue(true) || assertTrue(true) // this will pass
  },
  test("false || false") {
    assertTrue(false) || assertTrue(false) // this will false
  },
)
```

3. **`!`** - This is the logical not operator to negate the assertion:

```scala mdoc:compile-only
import zio.test._

suite("unary !") (
    test("negate true") {
        !assertTrue(true) // this will fail
    },
    test("negate false") {
        !assertTrue(false) // this will pass
    }
)
```

4. **implies** - This is the logical implies operator to make sure that the first assertion implies the second assertion. It is equivalent to `!p || q` which is a conditional statement of the form "if p, then q" where p and q are propositions. The `==>` operator is an alias for `implies`.

```scala mdoc:compile-only
import zio.test._

suite("implies") (
  test("true implies true")(
    assertTrue(true) implies assertTrue(true) // this will pass
  ),
  test("true implies false")(
    assertTrue(true) implies assertTrue(false) // this will fail
  ),
  test("false implies true")(
    assertTrue(false) implies assertTrue(true) // this will pass
  ),
  test("false implies false")(
    assertTrue(false) implies assertTrue(false) // this will pass
  ),
)
```

The `implies` assertion is true if either the p is false or when both p and q are true:

| P     | Q     | P implies Q |
|-------|-------|-------------|
| true  | true  | true        |
| true  | false | false       |
| false | true  | true        |
| false | false | true        |

5. **iff** - This is the logical iff operator to make sure that the first assertion is true if and only if the second assertion is true. It is equivalent to `(p implies q) && (q implies p)`. The `<==>` operator is an alias for `iff`.

```scala mdoc:compile-only
import zio.test._

suite("iff") (
  test("true iff true")(
    assertTrue(true) iff assertTrue(true) // this will pass
  ),
  test("true iff false")(
    assertTrue(true) iff assertTrue(false) // this will fail
  ),
  test("false iff true")(
    assertTrue(false) iff assertTrue(true) // this will fail
  ),
  test("false iff false")(
    assertTrue(false) iff assertTrue(false) // this will pass
  )
)
```

Here is the truth table for the iff operator:

| P     | Q     | P iff Q |
|-------|-------|---------|
| true  | true  | true    |
| true  | false | false   |
| false | true  | false   |
| false | false | true    |

6. **??**- We can add a custom message to the assertion using the `??` operator. This will be useful when assertion fails, and we want to provide more information about the failure:

```scala mdoc:compile-only
import zio.test._

assertTrue(1 + 1 == 3) ?? "1 + 1 should be equal to 2"
```

## Asserting Nested Values

There are several operators designed specifically for use within the `assertTrue` macro, enhancing the ease and readability of assertions. These operators, intended exclusively for the `assertTrue` macro, leverage the `TestLens[A]` type-class to access the underlying value of the type `A`.

We use the `is` extension method inside the `assertTrue` macro to convert the given value to a `TestLens`. Now no matter how deeply nested the value is, we can access the underlying values using extension method defined for `TestLens` values:

### Testing Optional Values

There are two operators for testing optional values:

1. **`TestLens#some`** - This operator is used to peek into the `Some` value:

```scala mdoc:compile-only
import zio.test._

test("optional value is some(42)") {
  val sut: Option[Int] = Some(40 + 2)
  assertTrue(sut.is(_.some) == 42)
}
```

2. **`TestLens#anything`** - This operator is used to assert that the value is `Some`:

```scala mdoc:compile-only
import zio.test._

test("optional value is anything") {
  val sut: Option[Int] = Some(42)
  assertTrue(sut.is(_.anything))
}
```

### Testing Either Values

1. **`TestLens#right`** - This operator is used to peek into the `Right` value:

```scala mdoc:compile-only
import zio.test._

test("TestLens#right") {
  val sut: Either[Error, Int] = Right(40 + 2)
  assertTrue(sut.is(_.right) == 42)
}
```

2. **`TestLens#left`** - This operator is used to peek into the `Left` value:

```scala mdoc:compile-only
import zio.test._

case class Error(errorMessage: String)

test("TestLens#left") {
  val sut: Either[Error, Int] = Left(Error("Boom!"))
  assertTrue(sut.is(_.left).errorMessage == "Boom!")
}
```

3. **`TestLens#anything`** - This operator is used to assert that the value is `Right`:

```scala mdoc:compile-only
import zio.test._

test("TestLens#anything") {
  val sut: Either[Error, Int] = Right(42)
  assertTrue(sut.is(_.anything))
}
```

### Testing Exit Values

1. **`TestLens#success`** - This operator transforms the `Exit` value to its success type `A` if it is a `Exit.Success`, otherwise it will fail. So this can be used for asserting the success value of the `Exit`:

```scala mdoc:compile-only
import zio.Exit
import zio.test._

test("TestLens#success") {
  val sut: Exit[Error, Int] = Exit.succeed(42)
  assertTrue(sut.is(_.success) == 42)
}
```

2. **`TestLens#failure`** - This operator transforms the `Exit` value to its failure type `E` if it is a `Exit.Failure`, otherwise it will fail. So this can be used for asserting the failure value of the `Exit`:

```scala mdoc:compile-only
import zio.Exit
import zio.test._

case class Error(errorMessage: String)

test("TestLens#failure") {
  val sut: Exit[Error, Int] = Exit.fail(Error("Boom!"))
  assertTrue(sut.is(_.failure).errorMessage == "Boom!")
}
```

3. **`TestLens#die`** - This operator transforms the `Exit` value to its die type `E` if it is a `Exit.Die`, otherwise it will fail. So this can be used for asserting the die value of the `Exit`:

```scala mdoc:compile-only
import zio.Exit
import zio.test._

test("TestLens#die") {
  val sut: Exit[Error, Int] = Exit.die(new RuntimeException("Boom!"))
  assertTrue(sut.is(_.die).getMessage == "Boom!")
}
```

4. **`TestLens#cause`** - This operator transforms the `Exit` value to its underlying `Cause` value if it has one otherwise it will fail. So this can be used for asserting the cause of the `Exit`:

```scala mdoc:fail
import zio.{ZIO, Cause}
import zio.test._

test("TestLens#cause") {
  for {
    exit <- ZIO.failCause(Cause.fail("Boom!")).exit
  } yield assertTrue(exit.is(_.cause) == Cause.fail("Boom!"))
}
```

5. **`TestLens#interrupt`** - This operator transforms the `Exit` value to its interrupt value if it is a `Exit.Interrupt`, otherwise it will fail. So this can be used for asserting the interrupt value of the `Exit`:

```scala mdoc:compile-only
import zio.{durationInt, ZIO}
import zio.test._

test("TestLens#interrupt") {
  for {
    exit <- ZIO.sleep(5.seconds).fork.flatMap(_.interrupt)
  } yield assertTrue(exit.is(_.interrupted))
}
```

## Deeply Nested Values

Sometimes we need to test values with more than one level of nesting. There is no difference in the way we test nested values:

```scala mdoc:compile-only
import zio.test._

test("assertion of multiple nested values (TestLens#right.some)") {
  val sut: Either[Error, Option[Int]] = Right(Some(40 + 2))
  assertTrue(sut.is(_.right.some) == 42)
}
```

## Custom Assertions

Using `CustomAssertion` we can create our own custom assertions for use in `assertTrue`. We can define custom assertions using the `CustomAssertion.make` method. This method takes a partial function from the type `A` to `Either[String, B]`. If the partial function is defined for the given value, it returns `Right[B]`, otherwise it returns `Left[String]`. 

Here is an example of a custom assertion for a sealed trait and case classes:

```scala mdoc:compile-only
import zio.test._

// Define the sealed trait and case classes
sealed trait Book
case class Novel(pageCount: Int) extends Book
case class Comic(illustrations: Int) extends Book
case class Textbook(subject: String) extends Book

// Custom assertion for Book
val subject =
  CustomAssertion.make[Book] {
    case Textbook(subject) => Right(subject)
    case other => Left(s"Expected $$other to be Textbook")
  }

// Usage
suite("custom assertions")(
  test("subject assertion") {
    val book: Option[Book] = Some(Textbook("Mathematics"))
    assertTrue(book.is(_.some.custom(subject)) == "Mathematics")
  }
)
```

In the above example, we define a custom assertion for the `Book` sealed trait. The custom assertion `subject` is defined to extract the `subject` from the `Textbook` case class. So then we can assert the `subject` of the `Textbook` case class.

## More Examples

The `assertTrue` macro is designed to make it easy to write assertions in a more readable way. Most test cases can be written as when we're comparing ordinary values in Scala. However, we have a [`SmartAssertionSpec`](https://github.com/zio/zio/blob/series/2.x/test-tests/shared/src/test/scala/zio/test/SmartAssertionSpec.scala) which is a collection of examples to demonstrate the power of the `assertTrue` macro.
