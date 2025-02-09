---
id: classic-assertions
title: "Classic Assertions"
---

:::note
In almost all cases we encourage developers using _[smart assertions](smart-assertions.md)_ instead of [classic assertions](classic-assertions.md). They are more expressive and easier to use. So you can skip reading this section.

Only use _classic assertions_ when you know what you are doing. There are some rare cases where the smart assertions are not enough.
:::

The `assert` and its effectful counterpart `assertZIO` are the old way of asserting ordinary values and ZIO effects.

## Asserting Ordinary Values

In order to test ordinary values, we should use `assert`, like the example below:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

test("sum") {
  assert(1 + 1)(Assertion.equalTo(2))
}
```

## Asserting ZIO Effects

If we are testing an effect, we should use the `assertZIO` function:

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

test("updating ref") {
  val value = for {
    r <- Ref.make(0)
    _ <- r.update(_ + 1)
    v <- r.get
  } yield v
  assertZIO(value)(Assertion.equalTo(1))
}
```

## The for-comprehension Style

Having this all in mind, probably the most common and also most readable way of structuring tests is to pass a for-comprehension to `test` function and yield a call to `assert` function.

```scala mdoc:compile-only
import zio._
import zio.test.{test, _}

test("updating ref") {
  for {
    r <- Ref.make(0)
    _ <- r.update(_ + 1)
    v <- r.get
  } yield assert(v)(Assertion.equalTo(v))
} 
```

## Understanding the `test` Function

:::note
In this section we are going to learn about the internals of the `Assertion` data type. So feel free to skip this section if you are not interested.
:::

In order to understand the `Assertion` data type, let's first look at the `test` function:

```scala
def test[In](label: String)(assertion: => In)(implicit testConstructor: TestConstructor[Nothing, In]): testConstructor.Out
```

Its signature is a bit complicated and uses _path-dependent types_, but it doesn't matter. We can think of a `test` as a function from `TestResult` (or its effectful versions such as `ZIO[R, E, TestResult]` or `ZSTM[R, E, TestResult]`) to the `Spec[R, E]` data type:

```scala
def test(label: String)(assertion: => TestResult): Spec[Any, Nothing]
def test(label: String)(assertion: => ZIO[R, E, TestResult]): Spec[R, E]
```

Therefore, the function `test` needs a `TestResult`. The most common way to produce a `TestResult` is to resort to `assert` or its effectful counterpart `assertZIO`. The former one is for creating ordinary `TestResult` values and the latter one is for producing effectful `TestResult` values. Both of them accept a value of type `A` (effectful version wrapped in a `ZIO`) and an `Assertion[A]`.

## Understanding the `assert` Function

Let's look at the `assert` function:

```scala
def assert[A](expr: => A)(assertion: Assertion[A]): TestResult
``` 

It takes an expression of type `A` and an `Assertion[A]` and returns the `TestResult` which is the boolean algebra of the `AssertionResult`. Furthermore, we have an `Assertion[A]` which is capable of producing _assertion results_ on any value of type `A`. So the `assert` function can apply the expression to the assertion and produce the `TestResult`.

## Type-checker Macro

To check if the code compiles, we can use the `typeCheck` macro. It is useful when we want to test if the code compiles without running it. Here is an example of how to use it:

```scala
import zio.test._
import zio.test.Assertion._

test("lazy list") {
  assertZIO(typeCheck(
    """
      |val lazyList: LazyList[Int] = LazyList(1, 2, 3, 4, 5)
      |lazyList.foreach(println)
      |""".stripMargin))(isRight)

} @@ TestAspect.exceptScala212
```

The `LazyCheck` introduced in Scala 2.13, so we excluded this test from Scala 2.12.

## Examples

### Example 1: Equality Assertion

Assume we have a function that concatenates two strings. One simple property of this function would be "the sum of the length of all inputs should be equal to the length of the output". Let's see an example of how we can make an assertion about this property:

```scala mdoc:compile-only
import zio.test._

test("The sum of the lengths of both inputs must equal the length of the output") {
  check(Gen.string, Gen.string) { (a, b) =>
    assert((a + b).length)(Assertion.equalTo(a.length + b.length))
  }
}
```

The syntax of assertion in the above code, is `assert(expression)(assertion)`. The first section is an expression of type `A` which is _result_ of our computation and the second one is the expected assertion of type `Assertion[A]`.

### Example 2: Field-level Assertion

There is also an easy way to test an object's data for certain assertions with `hasField` which accepts besides a name, a mapping function from object to its tested property, and `Assertion` object which will validate this property. Here our test checks if a person has at least 18 years and is not from the USA.

```scala mdoc:reset-object:silent
import zio.test._
import zio.test.Assertion.{isRight, isSome,equalTo, isGreaterThanEqualTo, not, hasField}

final case class Address(country:String, city:String)
final case class User(name:String, age:Int, address: Address)

test("Rich checking") {
  assert(
    User("Jonny", 26, Address("Denmark", "Copenhagen"))
  )(
    hasField("age", (u:User) => u.age, isGreaterThanEqualTo(18)) &&
    hasField("country", (u:User) => u.address.country, not(equalTo("USA")))
  )
}
```

What is nice about those tests is that test reporters will tell you exactly which assertion was broken. Let's say we would change `isGreaterThanEqualTo(18)` to `isGreaterThanEqualTo(40)` which will fail. Print out on the console will be a nice detailed text explaining what exactly went wrong:

```bash
[info]       User(Jonny,26,Address(Denmark,Copenhagen)) did not satisfy (hasField("age", _.age, isGreaterThanEqualTo(45)) && hasField("country", _.country, not(equalTo(USA))))
[info]       26 did not satisfy isGreaterThanEqualTo(45)
```

### Example 3: Test if a ZIO Effect Fails With a Particular Error Type

The following example shows how to test if a ZIO effect fails with a particular error type. To test if a ZIO effect fails with a particular error type, we can use the `ZIO#exit` to determine the exit type of that effect.

```scala mdoc:compile-only
import zio._
import zio.test.{ test, _ }
import zio.test.Assertion._

case class MyError(msg: String) extends Exception

val effect: ZIO[Any, MyError, Unit] = ZIO.fail(MyError("my error msg"))

test("test if a ZIO effect fails with a particular error type") {
  for {
    exit <- effect.exit
  } yield assertTrue(exit == Exit.fail(MyError("my error msg")))
}
```

The exit method on a ZIO effect returns an `Exit` value, which represents the outcome of the effect. The `Exit` value can be either `Exit.succeed` or `Exit.fail`. If the effect succeeded, the `Exit.succeed` value will contain the result of the effect. If the effect failed, the `Exit.fail` value will contain the error that caused the failure.

### Example 4: Test if a ZIO Effect Fails With a Subtype of a Particular Error Type

To test if a ZIO effect fails with a `subtype` of a particular error type, we can use the `assertZIO` function and the two `fails`, and `isSubtype` assertions from the zio-test library. The `assertZIO` function takes a ZIO effect and an assertion. The assertion is called with the result of the ZIO effect. If the assertion returns true, then the `assertZIO` will succeed, otherwise it will fail.

Assume we have these error types:

```scala mdoc:silent
sealed trait MyError extends Exception
case class E1(msg: String) extends MyError
case class E2(msg: String) extends MyError
```

To assert if an error type is a subtype of a particular error type, we need to combine the `fails` and `isSubtype` assertions together:


```scala mdoc:compile-only
import zio.test.Assertion._

Assertion.fails(isSubtype[MyError](anything))
```

Now let's look at an example:

```scala mdoc:compile-only
import zio._
import zio.test.{ test, _ }
import zio.test.Assertion._

val effect = ZIO.fail(E1("my error msg"))

test("Test if a ZIO effect fails with a MyError") {
  assertZIO(effect.exit)(fails(isSubtype[MyError](anything)))
}
```
