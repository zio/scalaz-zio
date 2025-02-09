---
id: tmap
title: "TMap"
---

A `TMap[A]` is a mutable map that can participate in transactions in STM.

## Create a TMap

Creating an empty `TMap`:

```scala mdoc:silent
import zio._
import zio.stm._

val emptyTMap: STM[Nothing, TMap[String, Int]] = TMap.empty[String, Int]
```

Or creating a `TMap` with specified values:

```scala mdoc:silent
import zio._
import zio.stm._

val specifiedValuesTMap: STM[Nothing, TMap[String, Int]] = TMap.make(("a", 1), ("b", 2), ("c", 3))
```

Alternatively, you can create a `TMap` by providing a collection of tuple values:

```scala mdoc:silent
import zio._
import zio.stm._

val iterableTMap: STM[Nothing, TMap[String, Int]] = TMap.fromIterable(List(("a", 1), ("b", 2), ("c", 3)))
```

## Put a key-value pair to a TMap

New key-value pair can be added to the map in the following way:

```scala mdoc:silent
import zio._
import zio.stm._

val putElem: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2))
  _    <- tMap.put("c", 3)
} yield tMap).commit
```

Another way of adding an entry in the map is by using `merge`:

```scala mdoc:silent
import zio._
import zio.stm._

val mergeElem: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.merge("c", 4)((x, y) => x * y)
} yield tMap).commit
```

If the key is not present in the map it behaves as a simple `put` method. It merges the existing value with the new one using the provided function otherwise.

## Remove an element from a TMap

The simplest way to remove a key-value pair from `TMap` is using `delete` method that takes key:

```scala mdoc:silent
import zio._
import zio.stm._

val deleteElem: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.delete("b")
} yield tMap).commit
```

Also, it is possible to remove every key-value pairs that satisfy provided predicate:

```scala mdoc:silent
import zio._
import zio.stm._

val removedEvenValues: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3), ("d", 4))
  _    <- tMap.removeIf((_, v) => v % 2 == 0)
} yield tMap).commit
```

Or you can keep all key-value pairs that match predicate function:

```scala mdoc:silent
import zio._
import zio.stm._

val retainedEvenValues: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3), ("d", 4))
  _    <- tMap.retainIf((_, v) => v % 2 == 0)
} yield tMap).commit
```

Note that `retainIf` and `removeIf` serve the same purpose as `filter` and `filterNot`. The reason for naming them differently was to emphasize a distinction in their nature. Namely, both `retainIf` and `removeIf` are destructive - calling them can modify the collection.

## Retrieve the value from a TMap

Value associated with the key can be obtained as follows: 

```scala mdoc:silent
import zio._
import zio.stm._

val elemGet: UIO[Option[Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  elem <- tMap.get("c")
} yield elem).commit
```

Alternatively, you can provide a default value if entry by key is not present in the map:

```scala mdoc:silent
import zio._
import zio.stm._

val elemGetOrElse: UIO[Int] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  elem <- tMap.getOrElse("d", 4)
} yield elem).commit
```

## Transform entries of a TMap

The transform function `(K, V) => (K, V)` allows computing a new value for every entry in the map: 

```scala mdoc:silent
import zio._
import zio.stm._

val transformTMap: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.transform((k, v) => k -> v * v)
} yield tMap).commit
```

Note that it is possible to shrink a `TMap`:

```scala mdoc:silent
import zio._
import zio.stm._

val shrinkTMap: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.transform((_, v) => "d" -> v)
} yield tMap).commit
```

The entries can be mapped effectfully via `transformSTM`:

```scala mdoc:silent
import zio._
import zio.stm._

val transformSTMTMap: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.transformSTM((k, v) => STM.succeed(k -> v * v))
} yield tMap).commit
```

The `transformValues` function `V => V` allows computing a new value for every value in the map: 

```scala mdoc:silent
import zio._
import zio.stm._

val transformValuesTMap: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.transformValues(v => v * v)
} yield tMap).commit
```

The values can be mapped effectfully via `transformValuesSTM`:

```scala mdoc:silent
import zio._
import zio.stm._

val transformValuesMTMap: UIO[TMap[String, Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  _    <- tMap.transformValuesSTM(v => STM.succeed(v * v))
} yield tMap).commit
```

Note that both `transform` and `transformValues` serve the same purpose as `map` and `mapValues`. The reason for naming them differently was to emphasize a distinction in their nature. Namely, both `transform` and `transformValues` are destructive - calling them can modify the collection.

Folds the elements of a `TMap` using the specified associative binary operator:

```scala mdoc:silent
import zio._
import zio.stm._

val foldTMap: UIO[Int] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  sum  <- tMap.fold(0) { case (acc, (_, v)) => acc + v }
} yield sum).commit
```

The elements can be folded effectfully via `foldSTM`:

```scala mdoc:silent
import zio._
import zio.stm._

val foldSTMTMap: UIO[Int] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  sum  <- tMap.foldSTM(0) { case (acc, (_, v)) => STM.succeed(acc + v) }
} yield sum).commit
```

## Perform effects for TMap key-value pairs

`foreach` is used for performing an STM effect for each key-value pair in the map:

```scala mdoc:silent
import zio._
import zio.stm._

val foreachTMap = (for {
  tMap   <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  tQueue <- TQueue.unbounded[String]
  _      <- tMap.foreach((k, v) => tQueue.offer(s"$k -> $v").unit)
} yield tMap).commit
```

## Check TMap membership

Checking whether key-value pair is present in a `TMap`:

```scala mdoc:silent
import zio._
import zio.stm._

val tMapContainsValue: UIO[Boolean] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  res  <- tMap.contains("a")
} yield res).commit
```

## Convert TMap to a List

List of tuples can be obtained as follows:

```scala mdoc:silent
import zio._
import zio.stm._

val tMapTuplesList: UIO[List[(String, Int)]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  list <- tMap.toList
} yield list).commit
```

List of keys can be obtained as follows:

```scala mdoc:silent
import zio._
import zio.stm._

val tMapKeysList: UIO[List[String]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  list <- tMap.keys
} yield list).commit
```

List of values can be obtained as follows:

```scala mdoc:silent
import zio._
import zio.stm._

val tMapValuesList: UIO[List[Int]] = (for {
  tMap <- TMap.make(("a", 1), ("b", 2), ("c", 3))
  list <- tMap.values
} yield list).commit
```
