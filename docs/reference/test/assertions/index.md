---
id: index
title: "Introduction to ZIO Test Assertions"
---

Assertions are used to make sure that the assumptions on computations are exactly what we expect them to be. They are _executable checks_ for a property that must be true in our code. Also, they can be seen as a _specification of a program_ and facilitate understanding of programs.

An `Assertion[A]` is a statement that can be used to assert the predicate of type `A => Boolean`. It is a piece of code that checks whether a value of type `A` satisfies some condition. If the condition is satisfied, the assertion passes; otherwise, it fails. We can think of the `Assertion[A]` as a function from `A` to `Boolean`:

```scala
case class Assertion[-A](arrow: TestArrow[A, Boolean]) {
  def test(value: A): Boolean = ???
  def run(value: => A): TestResult = ???
}
```

`Assertion` has a companion object with lots of predefined assertions that can be used to test values of different types. For example, the `Assertion.equalTo` takes a value of type `A` and returns an assertion that checks whether the value is equal to the given value:

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion

def sut = 40 + 2
val assertion: Assertion[Int] = Assertion.equalTo[Int, Int](42)
assertion.test(sut) // true
```

:::note
Behind the scenes, the `Assertion` type uses a `TestArrow` type to represent the function from `A` to `Boolean`. For example, instead of using a predefined `equalTo` assertion, we can create our assertion directly from a `TestArrow`:

```scala mdoc:compile-only
import zio.test._

def sut = 40 + 2
val assertion: Assertion[Int] = Assertion(TestArrow.fromFunction(_ == 42))
assertion.test(sut) // true
```

Please note that the `TestArrow` is the fundamental building block of assertions specially the complex ones. Usually, as the end user, we do not require interacting with `TestArrow` directly. But it is good to know that it is there and how it works. We will see more about `TestArrow` in the next sections.
:::


## Built-in Assertions

The companion object of `Assertion` provides a comprehensive set of predefined assertions that can be used to test values of different types. We have a separate page for introducing the [built-in assertions](built-in-assertions.md) in ZIO Test.

## Logical Operations

As a proposition, assertions compose using logical conjunction and disjunction and can be negated:

```scala mdoc:silent
import zio.test._

val greaterThanZero: Assertion[Int] = Assertion.isPositive
val lessThanFive   : Assertion[Int] = Assertion.isLessThan(5)
val equalTo10      : Assertion[Int] = Assertion.equalTo(10)

val assertion: Assertion[Int] = greaterThanZero && lessThanFive || !equalTo10
```

After composing them, we can run it on any expression:

```scala mdoc:compile-only
import zio._

val result: TestResult = assertion.run(10)
```

## Composable Nested Assertions

Besides the logical operators, we can also combine assertions like the following to have assertions on more complex types like `Option[Int]`:

```scala mdoc:compile-only
val assertion: Assertion[Option[Int]] = Assertion.isSome(Assertion.equalTo(5))

test("optional value is some(5)") {
  assert(Some(1 + 4))(assertion)
}
```

This nested assertion will pass only if the given value is `Some(5)`.

We can also combine assertions on more complex types like `Either[Int, Option[Int]]`:

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion.{isRight, isSome, equalTo, hasField}

test("either value is right(Some(5))") {
  assert(Right(Some(1 + 4)))(isRight(isSome(equalTo(5))))
}
```

Here we're checking deeply nested values inside an `Either` and `Option`. Because `Assertion`s compose this is not a problem. All layers are being peeled off tested for the condition until the final value is reached.

Here the expression `Right(Some(1 + 4))` is of type `Either[Any, Option[Int]]` and our assertion `isRight(isSome(equalTo(5)))` is of type `Assertion[Either[Any, Option[Int]]]`

:::note
Under the hood, the above assertion uses the `>>>` operator of `TestArrow` to make the composition of two assertions sequentially:

```scala mdoc:invisible:reset

```

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion

def isRight[A]: TestArrow[Either[Any, A], A] =
  TestArrow.fromFunction(_.toOption.get)

def isSome[A]: TestArrow[Option[A], A] =
  TestArrow.fromFunction(_.get)

def equalTo[A, B](expected: B): TestArrow[B, Boolean] =
  TestArrow.fromFunction((actual: B) => actual == expected)

val assertion: Assertion[Either[Any, Option[Int]]] = {
  val arrow: TestArrow[Either[Any, Option[Int]], Boolean] =
    isRight >>>        // Either[Any, Option[Int]] => Option[Int]
      isSome[Int] >>>  // Option[Int] => Int
      equalTo(5)       // Int => Boolean
  Assertion(arrow)
}
```

By composing an arrow of `TestArrow[Either[Any, Option[Int]], Option[Int]]` and `TestArrow[Option[Int], Boolean]` and `TestArrow[Int, Boolean]` we can create an arrow of `TestArrow[Either[Any, Option[Int]], Boolean]`. Using this technique, we can compose more arrows to create more and more complex assertions.

We can see that `TestArrow` has the same analogy as [`ZLayer`](../../contextual/zlayer.md). We are dealing with generalization of functions and composition of functions in a pure and declarative fashion, which is called "arrow" in functional programming. In other words, with `TestArrow`, we have reified the concept of a function and its composition, which allows us to manipulate functions as first-class values.

One of the benefits of reification of assertions into "arrows" is that we can write macros to generate assertions from pure Scala code. This is how the smart assertions work in ZIO Test.
:::

## Testing using Assertions

We have two types of methods for writing test assertions:
1. **[Classic Assertions](classic-assertions.md)**— This one is the classic way of asserting ordinary values (`assert`) and ZIO effects (`assertZIO`) without using macros.
2. **[Smart Assertions](smart-assertions.md)**— This is a unified syntax for asserting both ordinary values and ZIO effects using the `assertTrue` macro.
