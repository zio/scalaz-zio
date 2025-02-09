---
id: built-in-assertions
title: "Built-in Assertions"
---

To create `Assertion[A]` object one can use functions defined under `zio.test.Assertion`. There are already a number of useful assertions predefined like `equalTo`, `isFalse`, `isTrue`, `contains`, `throws` and more.

Using the `Assertion` type effectively often involves finding the best fitting function for the type of assumptions you would like to verify.

This list is intended to break up the available functions into groups based on the _Result type_. The types of the functions are included as well, to guide intuition.

For instance, if we wanted to assert that the fourth element of a `Vector[Int]` was a value equal to the number `5`, we would first look at assertions that operate on `Seq[A]`, with the type `Assertion[Seq[A]]`. For this example, I would select `hasAt`, as it accepts both the position into a sequence, as well as an `Assertion[A]` to apply at that position:

```scala
Assertion.hasAt[A](pos: Int)(assertion: Assertion[A]): Assertion[Seq[A]]
```

I could start by writing:

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion._

val xs = Vector(0, 1, 2, 3)

test("Fourth value is equal to 5") {
  assert(xs)(hasAt(3)(???))
}
```

The second parameter to `hasAt` is an `Assertion[A]` that applies to the third element of that sequence, so I would look for functions that operate on `A`, of the return type `Assertion[A]`.

I could select `equalTo`, as it accepts an `A` as a parameter, allowing me to supply `5`:

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion._

val xs = Vector(0, 1, 2, 3)

test("Fourth value is equal to 5") {
  assert(xs)(hasAt(3)(equalTo(5)))
}
```

Let's say this is too restrictive, and I would prefer to assert that a value is _near_ the number five, with a tolerance of two. This requires a little more knowledge of the type `A`, so I'll look for an assertion in the `Numeric` section. `approximatelyEquals` looks like what we want, as it permits the starting value `reference`, as well as a `tolerance`, for any `A` that is `Numeric`:

```scala
Assertion.approximatelyEquals[A: Numeric](reference: A, tolerance: A): Assertion[A]
```

Changing out `equalTo` with `approximatelyEquals` leaves us with:

```scala mdoc:compile-only
import zio.test._
import zio.test.Assertion._

val xs = Vector(0, 1, 2, 3)

test("Fourth value is approximately equal to 5") {
  assert(xs)(hasAt(3)(approximatelyEquals(5, 2)))
}
```

## Any

Assertions that apply to `Any` value.

| Function                                                         | Result type      | Description                                                          |
| --------                                                         | -----------      | -----------                                                          |
| `anything`                                                       | `Assertion[Any]` | Makes a new assertion that always succeeds.                          |
| `isNull`                                                         | `Assertion[Any]` | Makes a new assertion that requires a null value.                    |
| `isSubtype[A](assertion: Assertion[A])(implicit C: ClassTag[A])` | `Assertion[Any]` | Makes a new assertion that requires a value have the specified type. |
| `nothing`                                                        | `Assertion[Any]` | Makes a new assertion that always fails.                             |
| `throwsA[E: ClassTag]`                                           | `Assertion[Any]` | Makes a new assertion that requires the expression to throw.         |

## A

Assertions that apply to specific values.

| Function                                                              | Result type    | Description                                                                             |
| --------                                                              | -----------    | -----------                                                                             |
| `equalTo[A](expected: A)`                                             | `Assertion[A]` | Makes a new assertion that requires a value equal the specified value.                  |
| `hasField[A, B](name: String, proj: A => B, assertion: Assertion[B])` | `Assertion[A]` | Makes a new assertion that focuses in on a field in a case class.                       |
| `isOneOf[A](values: Iterable[A])`                                     | `Assertion[A]` | Makes a new assertion that requires a value to be equal to one of the specified values. |
| `not[A](assertion: Assertion[A])`                                     | `Assertion[A]` | Makes a new assertion that negates the specified assertion.                             |
| `throws[A](assertion: Assertion[Throwable])`                          | `Assertion[A]` | Makes a new assertion that requires the expression to throw.                            |

## Numeric

Assertions on `Numeric` types

| Function                                                      | Result type    | Description                                                                                     |
| --------                                                      | -----------    | -----------                                                                                     |
| `approximatelyEquals[A: Numeric](reference: A, tolerance: A)` | `Assertion[A]` | Makes a new assertion that requires a given numeric value to match a value with some tolerance. |
| `isNegative[A](implicit num: Numeric[A])`                     | `Assertion[A]` | Makes a new assertion that requires a numeric value is negative.                                |
| `isPositive[A](implicit num: Numeric[A])`                     | `Assertion[A]` | Makes a new assertion that requires a numeric value is positive.                                |
| `isZero[A](implicit num: Numeric[A])`                         | `Assertion[A]` | Makes a new assertion that requires a numeric value is zero.                                    |
| `nonNegative[A](implicit num: Numeric[A])`                    | `Assertion[A]` | Makes a new assertion that requires a numeric value is non negative.                            |
| `nonPositive[A](implicit num: Numeric[A])`                    | `Assertion[A]` | Makes a new assertion that requires a numeric value is non positive.                            |

## Ordering

Assertions on types that support `Ordering`

| Function                                                           | Result type    | Description                                                                                              |
| --------                                                           | -----------    | -----------                                                                                              |
| `isGreaterThan[A](reference: A)(implicit ord: Ordering[A])`        | `Assertion[A]` | Makes a new assertion that requires the value be greater than the specified reference value.             |
| `isGreaterThanEqualTo[A](reference: A)(implicit ord: Ordering[A])` | `Assertion[A]` | Makes a new assertion that requires the value be greater than or equal to the specified reference value. |
| `isLessThan[A](reference: A)(implicit ord: Ordering[A])`           | `Assertion[A]` | Makes a new assertion that requires the value be less than the specified reference value.                |
| `isLessThanEqualTo[A](reference: A)(implicit ord: Ordering[A])`    | `Assertion[A]` | Makes a new assertion that requires the value be less than or equal to the specified reference value.    |
| `isWithin[A](min: A, max: A)(implicit ord: Ordering[A])`           | `Assertion[A]` | Makes a new assertion that requires a value to fall within a specified min and max (inclusive).          |

## Iterable

Assertions on types that extend `Iterable`, like `List`, `Seq`, `Set`, `Map`, and many others.

| Function                                                                    | Result type                | Description                                                                                                                                                                           |
| --------                                                                    | -----------                | -----------                                                                                                                                                                           |
| `contains[A](element: A)`                                                   | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain the specified element. See Assertion.exists if you want to require an Iterable to contain an element satisfying an assertion. |
| `exists[A](assertion: Assertion[A])`                                        | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain an element satisfying the given assertion.                                                                                    |
| `forall[A](assertion: Assertion[A])`                                        | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain only elements satisfying the given assertion.                                                                                 |
| `hasFirst[A](assertion: Assertion[A])`                                      | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable to contain the first element satisfying the given assertion.                                                                          |
| `hasIntersection[A](other: Iterable[A])(assertion: Assertion[Iterable[A]])` | `Assertion[Iterable[A]]`   | Makes a new assertion that requires the intersection of two Iterables satisfy the given assertion.                                                                                    |
| `hasLast[A](assertion: Assertion[A])`                                       | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable to contain the last element satisfying the given assertion.                                                                           |
| `hasSize[A](assertion: Assertion[Int])`                                     | `Assertion[Iterable[A]]`   | Makes a new assertion that requires the size of an Iterable be satisfied by the specified assertion.                                                                                  |
| `hasAtLeastOneOf[A](other: Iterable[A])`                                    | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain at least one of the specified elements.                                                                                       |
| `hasAtMostOneOf[A](other: Iterable[A])`                                     | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain at most one of the specified elements.                                                                                        |
| `hasNoneOf[A](other: Iterable[A])`                                          | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain none of the specified elements.                                                                                               |
| `hasOneOf[A](other: Iterable[A])`                                           | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable contain exactly one of the specified elements.                                                                                        |
| `hasSameElements[A](other: Iterable[A])`                                    | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable to have the same elements as the specified Iterable, though not necessarily in the same order.                                        |
| `hasSameElementsDistinct[A](other: Iterable[A])`                            | `Assertion[Iterable[A]]`   | Makes a new assertion that requires an Iterable to have the same distinct elements as the other Iterable, though not necessarily in the same order.                                   |
| `hasSubset[A](other: Iterable[A])`                                          | `Assertion[Iterable[A]]`   | Makes a new assertion that requires the specified Iterable to be a subset of the other Iterable.                                                                                      |
| `isDistinct`                                                                | `Assertion[Iterable[Any]]` | Makes a new assertion that requires an Iterable is distinct.                                                                                                                          |
| `isEmpty`                                                                   | `Assertion[Iterable[Any]]` | Makes a new assertion that requires an Iterable to be empty.                                                                                                                          |
| `isNonEmpty`                                                                | `Assertion[Iterable[Any]]` | Makes a new assertion that requires an Iterable to be non empty.                                                                                                                      |

## Ordering

Assertions that apply to ordered `Iterable`s

| Function                                        | Result type              | Description                                                                 |
| --------                                        | -----------              | -----------                                                                 |
| `isSorted[A](implicit ord: Ordering[A])`        | `Assertion[Iterable[A]]` | Makes a new assertion that requires an Iterable is sorted.                  |
| `isSortedReverse[A](implicit ord: Ordering[A])` | `Assertion[Iterable[A]]` | Makes a new assertion that requires an Iterable is sorted in reverse order. |

## Seq

Assertions that operate on sequences (`List`, `Vector`, `Map`, and many others)

| Function                                      | Result type         | Description                                                                                                                |
| --------                                      | -----------         | -----------                                                                                                                |
| `endsWith[A](suffix: Seq[A])`                 | `Assertion[Seq[A]]` | Makes a new assertion that requires a given string to end with the specified suffix.                                       |
| `hasAt[A](pos: Int)(assertion: Assertion[A])` | `Assertion[Seq[A]]` | Makes a new assertion that requires a sequence to contain an element satisfying the given assertion on the given position. |
| `startsWith[A](prefix: Seq[A])`               | `Assertion[Seq[A]]` | Makes a new assertion that requires a given sequence to start with the specified prefix.                                   |

## Either

Assertions for `Either` values.

| Function                              | Result type                   | Description                                                                         |
| --------                              | -----------                   | -----------                                                                         |
| `isLeft[A](assertion: Assertion[A])`  | `Assertion[Either[A, Any]]`   | Makes a new assertion that requires a Left value satisfying a specified assertion.  |
| `isLeft`                              | `Assertion[Either[Any, Any]]` | Makes a new assertion that requires an Either is Left.                              |
| `isRight[A](assertion: Assertion[A])` | `Assertion[Either[Any, A]]`   | Makes a new assertion that requires a Right value satisfying a specified assertion. |
| `isRight`                             | `Assertion[Either[Any, Any]]` | Makes a new assertion that requires an Either is Right.                             |

## Exit/Cause/Throwable

Assertions for `Exit` or `Cause` results.

| Function                                         | Result type                 | Description                                                                                                |
| --------                                         | -----------                 | -----------                                                                                                |
| `containsCause[E](cause: Cause[E])`              | `Assertion[Cause[E]]`       | Makes a new assertion that requires a Cause contain the specified cause.                                   |
| `dies(assertion: Assertion[Throwable])`          | `Assertion[Exit[Any, Any]]` | Makes a new assertion that requires an exit value to die.                                                  |
| `failsCause[E](assertion: Assertion[Cause[E]])`  | `Assertion[Exit[E, Any]]`   | Makes a new assertion that requires an exit value to fail with a cause that meets the specified assertion. |
| `fails[E](assertion: Assertion[E])`              | `Assertion[Exit[E, Any]]`   | Makes a new assertion that requires an exit value to fail.                                                 |
| `isInterrupted`                                  | `Assertion[Exit[Any, Any]]` | Makes a new assertion that requires an exit value to be interrupted.                                       |
| `succeeds[A](assertion: Assertion[A])`           | `Assertion[Exit[Any, A]]`   | Makes a new assertion that requires an exit value to succeed.                                              |
| `hasMessage(message: Assertion[String])`         | `Assertion[Throwable]`      | Makes a new assertion that requires an exception to have a certain message.                                |
| `hasThrowableCause(cause: Assertion[Throwable])` | `Assertion[Throwable]`      | Makes a new assertion that requires an exception to have a certain cause.                                  |

## Try

| Function                                     | Result type           | Description                                                                             |
| --------                                     | -----------           | -----------                                                                             |
| `isFailure(assertion: Assertion[Throwable])` | `Assertion[Try[Any]]` | Makes a new assertion that requires a Failure value satisfying the specified assertion. |
| `isFailure`                                  | `Assertion[Try[Any]]` | Makes a new assertion that requires a Try value is Failure.                             |
| `isSuccess[A](assertion: Assertion[A])`      | `Assertion[Try[A]]`   | Makes a new assertion that requires a Success value satisfying the specified assertion. |
| `isSuccess`                                  | `Assertion[Try[Any]]` | Makes a new assertion that requires a Try value is Success.                             |

## Sum type

An assertion that applies to some type, giving a method to transform the source
type into another type, then assert a property on that projected type.

| Function                                                                                      | Result type      | Description                                                           |
| --------                                                                                      | -----------      | -----------                                                           |
| `isCase[Sum, Proj]( termName: String, term: Sum => Option[Proj], assertion: Assertion[Proj])` | `Assertion[Sum]` | Makes a new assertion that requires the sum type be a specified term. |


## Map

Assertions for `Map[K, V]`

| Function                                             | Result type            | Description                                                                                                        |
| --------                                             | -----------            | -----------                                                                                                        |
| `hasKey[K, V](key: K)`                               | `Assertion[Map[K, V]]` | Makes a new assertion that requires a Map to have the specified key.                                               |
| `hasKey[K, V](key: K, assertion: Assertion[V])`      | `Assertion[Map[K, V]]` | Makes a new assertion that requires a Map to have the specified key with value satisfying the specified assertion. |
| `hasKeys[K, V](assertion: Assertion[Iterable[K]])`   | `Assertion[Map[K, V]]` | Makes a new assertion that requires a Map have keys satisfying the specified assertion.                            |
| `hasValues[K, V](assertion: Assertion[Iterable[V]])` | `Assertion[Map[K, V]]` | Makes a new assertion that requires a Map have values satisfying the specified assertion.                          |

## String

Assertions for Strings

| Function                                   | Result type         | Description                                                                                       |
| --------                                   | -----------         | -----------                                                                                       |
| `containsString(element: String)`          | `Assertion[String]` | Makes a new assertion that requires a substring to be present.                                    |
| `endsWithString(suffix: String)`           | `Assertion[String]` | Makes a new assertion that requires a given string to end with the specified suffix.              |
| `equalsIgnoreCase(other: String)`          | `Assertion[String]` | Makes a new assertion that requires a given string to equal another ignoring case.                |
| `hasSizeString(assertion: Assertion[Int])` | `Assertion[String]` | Makes a new assertion that requires the size of a string be satisfied by the specified assertion. |
| `isEmptyString`                            | `Assertion[String]` | Makes a new assertion that requires a given string to be empty.                                   |
| `isNonEmptyString`                         | `Assertion[String]` | Makes a new assertion that requires a given string to be non empty.                               |
| `matchesRegex(regex: String)`              | `Assertion[String]` | Makes a new assertion that requires a given string to match the specified regular expression.     |
| `startsWithString(prefix: String)`         | `Assertion[String]` | Makes a new assertion that requires a given string to start with a specified prefix.              |

## Boolean

Assertions for Booleans

| Function  | Result type          | Description                                           |
| --------  | -----------          | -----------                                           |
| `isFalse` | `Assertion[Boolean]` | Makes a new assertion that requires a value be false. |
| `isTrue`  | `Assertion[Boolean]` | Makes a new assertion that requires a value be true.  |

## Option

Assertions for Optional values

| Function                             | Result type              | Description                                                                          |
| --------                             | -----------              | -----------                                                                          |
| `isNone`                             | `Assertion[Option[Any]]` | Makes a new assertion that requires a None value.                                    |
| `isSome[A](assertion: Assertion[A])` | `Assertion[Option[A]]`   | Makes a new assertion that requires a Some value satisfying the specified assertion. |
| `isSome`                             | `Assertion[Option[Any]]` | Makes a new assertion that requires an Option is Some.                               |

## Unit

Assertion for Unit

| Function | Result type       | Description                                            |
| -------- | -----------       | -----------                                            |
| `isUnit` | `Assertion[Unit]` | Makes a new assertion that requires the value be unit. |


