---
id: tset
title: "TSet"
---

A `TSet[A]` is a mutable set that can participate in transactions in STM.

## Create a TSet

Creating an empty `TSet`:

```scala mdoc:silent
import zio._
import zio.stm._

val emptyTSet: STM[Nothing, TSet[Int]] = TSet.empty[Int]
```

Or creating a `TSet` with specified values:

```scala mdoc:silent
import zio._
import zio.stm._

val specifiedValuesTSet: STM[Nothing, TSet[Int]] = TSet.make(1, 2, 3)
```

Alternatively, you can create a `TSet` by providing a collection of values:

```scala mdoc:silent
import zio._
import zio.stm._

val iterableTSet: STM[Nothing, TSet[Int]] = TSet.fromIterable(List(1, 2, 3))
```

In case there are duplicates provided, the last one is taken.

## Put an element to a TSet

The new element can be added to the set in the following way:

```scala mdoc:silent
import zio._
import zio.stm._

val putElem: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2)
  _    <- tSet.put(3)
} yield tSet).commit
```

In case the set already contains the element, no modification will happen.

## Remove an element from a TSet

The simplest way to remove an element from `TSet` is using `delete` method:

```scala mdoc:silent
import zio._
import zio.stm._

val deleteElem: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3)
  _    <- tSet.delete(1)
} yield tSet).commit
```

Also, it is possible to remove every element that satisfies provided predicate:

```scala mdoc:silent
import zio._
import zio.stm._

val removedEvenElems: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  _    <- tSet.removeIf(_ % 2 == 0)
} yield tSet).commit
```

Or you can keep all the elements that match predicate function:

```scala mdoc:silent
import zio._
import zio.stm._

val retainedEvenElems: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  _    <- tSet.retainIf(_ % 2 == 0)
} yield tSet).commit
```

Note that `retainIf` and `removeIf` serve the same purpose as `filter` and `filterNot`. The reason for naming them differently was to emphasize a distinction in their nature. Namely, both `retainIf` and `removeIf` are destructive - calling them can modify the collection.

## Union of a TSet

Union of the sets A and B represents the set of elements belonging to set A or set B, or both.
Using `A union B` method modifies set `A`.

```scala mdoc:silent
import zio._
import zio.stm._

// unionTSet = {1, 2, 3, 4, 5, 6}
val unionTSet: UIO[TSet[Int]] = (for {
  tSetA <- TSet.make(1, 2, 3, 4)
  tSetB <- TSet.make(3, 4, 5, 6)
  _     <- tSetA.union(tSetB)
} yield tSetA).commit
```

## Intersection of a TSet

The intersection of the sets A and B is the set of elements belonging to both A and B.
Using `A intersect B` method modifies set `A`.

```scala mdoc:silent
import zio._
import zio.stm._

// intersectionTSet = {3, 4}
val intersectionTSet: UIO[TSet[Int]] = (for {
  tSetA <- TSet.make(1, 2, 3, 4)
  tSetB <- TSet.make(3, 4, 5, 6)
  _     <- tSetA.intersect(tSetB)
} yield tSetA).commit
```

## Difference of a TSet

The difference between sets A and B is the set containing elements of set A but not in B.
Using `A diff B` method modifies set `A`.

```scala mdoc:silent
import zio._
import zio.stm._

// diffTSet = {1, 2}
val diffTSet: UIO[TSet[Int]] = (for {
  tSetA <- TSet.make(1, 2, 3, 4)
  tSetB <- TSet.make(3, 4, 5, 6)
  _     <- tSetA.diff(tSetB)
} yield tSetA).commit
```

## Transform elements of a TSet

The transform function `A => A` allows computing a new value for every element in the set: 

```scala mdoc:silent
import zio._
import zio.stm._

val transformTSet: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  _    <- tSet.transform(a => a * a)
} yield tSet).commit
```

Note that it is possible to shrink a `TSet`:

```scala mdoc:silent
import zio._
import zio.stm._

val shrinkTSet: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  _    <- tSet.transform(_ => 1)
} yield tSet).commit
```
Resulting set in example above has only one element.

Note that `transform` serves the same purpose as `map`. The reason for naming it differently was to emphasize a distinction in its nature. Namely, `transform` is destructive - calling it can modify the collection.

The elements can be mapped effectfully via `transformSTM`:

```scala mdoc:silent
import zio._
import zio.stm._

val transformSTMTSet: UIO[TSet[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  _    <- tSet.transformSTM(a => STM.succeed(a * a))
} yield tSet).commit
```

Folds the elements of a `TSet` using the specified associative binary operator:

```scala mdoc:silent
import zio._
import zio.stm._

val foldTSet: UIO[Int] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  sum  <- tSet.fold(0)(_ + _)
} yield sum).commit
```

The elements can be folded effectfully via `foldSTM`:

```scala mdoc:silent
import zio._
import zio.stm._

val foldSTMTSet: UIO[Int] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  sum  <- tSet.foldSTM(0)((acc, el) => STM.succeed(acc + el))
} yield sum).commit
```

## Perform effects for TSet elements

`foreach` is used for performing an STM effect for each element in set:

```scala mdoc:silent
import zio._
import zio.stm._

val foreachTSet = (for {
  tSet   <- TSet.make(1, 2, 3, 4)
  tQueue <- TQueue.unbounded[Int]
  _      <- tSet.foreach(a => tQueue.offer(a).unit)
} yield tSet).commit
```

## Check TSet membership 

Checking whether the element is present in a `TSet`:

```scala mdoc:silent
import zio._
import zio.stm._

val tSetContainsElem: UIO[Boolean] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  res  <- tSet.contains(3)
} yield res).commit
```

## Convert TSet to a List

List of set elements can be obtained as follows:

```scala mdoc:silent
import zio._
import zio.stm._

val tSetToList: UIO[List[Int]] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  list <- tSet.toList
} yield list).commit
```

## Size of a TSet

Set's size can be obtained as follows:

```scala mdoc:silent
import zio._
import zio.stm._

val tSetSize: UIO[Int] = (for {
  tSet <- TSet.make(1, 2, 3, 4)
  size <- tSet.size
} yield size).commit
```
