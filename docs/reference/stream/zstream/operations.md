---
id: operations
title: "Operations"
---

## Tapping

Tapping is an operation of running an effect on each emission of the ZIO Stream. We can think of `ZStream#tap` as an operation that allows us to observe each element of the stream, do some effectful operation and discard the result of this observation. The `tap` operation does not change elements of the stream, it does not affect the return type of the stream.

For example, we can print each element of a stream by using the `tap` operation:

```scala mdoc:invisible
import zio._
import zio.stream._
import zio.Cause.Die
import zio.Console._
import scala.io.Source
import zio.stm.{STM, TQueue}
import java.io.{BufferedReader, FileReader, FileInputStream, IOException}
import java.nio.file.{Files, Path, Paths}
import java.nio.file.Path._
import java.net.URL
import java.lang.IllegalArgumentException
import scala.concurrent.TimeoutException
```

```scala mdoc:silent:nest
val stream: ZStream[Any, IOException, Int] =
  ZStream(1, 2, 3)
    .tap(x => printLine(s"before mapping: $x"))
    .map(_ * 2)
    .tap(x => printLine(s"after mapping: $x"))
```

## Taking Elements

We can take a certain number of elements from a stream:

```scala mdoc:silent:nest
val stream = ZStream.iterate(0)(_ + 1)
val s1 = stream.take(5)
// Output: 0, 1, 2, 3, 4

val s2 = stream.takeWhile(_ < 5)
// Output: 0, 1, 2, 3, 4

val s3 = stream.takeUntil(_ == 5)
// Output: 0, 1, 2, 3, 4, 5

val s4 = s3.takeRight(3)
// Output: 3, 4, 5
```

## Mapping

**map** — Applies a given function to all element of this stream to produce another stream:
```scala mdoc:silent
import zio.stream._

val intStream: UStream[Int] = ZStream.fromIterable(0 to 100)
val stringStream: UStream[String] = intStream.map(_.toString)
```

If our transformation is effectful, we can use `ZStream#mapZIO` instead.

**mapZIOPar** —  It is similar to `mapZIO`, but will evaluate effects in parallel. It will emit the results downstream in the original order. The `n` argument specifies the number of concurrent running effects.

Let's write a simple page downloader, which download URLs concurrently:

```scala mdoc:silent:nest
def fetchUrl(url: URL): Task[String] = ZIO.succeed(???)
def getUrls: Task[List[URL]] = ZIO.succeed(???)

val pages = ZStream.fromIterableZIO(getUrls).mapZIOPar(8)(fetchUrl)  
```

**mapChunk** — Each stream is backed by some `Chunk`s. By using `mapChunk` we can batch the underlying stream and map every `Chunk` at once:

```scala mdoc:silent
val chunked = 
  ZStream
    .fromChunks(Chunk(1, 2, 3), Chunk(4, 5), Chunk(6, 7, 8, 9))

val stream = chunked.mapChunks(x => x.tail)

// Input:  1, 2, 3, 4, 5, 6, 7, 8, 9
// Output:    2, 3,    5,    7, 8, 9
```

If our transformation is effectful we can use `mapChunksZIO` combinator.

**mapAccum** — It is similar to a `map`, but it **transforms elements statefully**. `mapAccum` allows us to _map_ and _accumulate_ in the same operation.

```scala
abstract class ZStream[-R, +E, +O] {
  def mapAccum[S, O1](s: S)(f: (S, O) => (S, O1)): ZStream[R, E, O1]
}
```

Let's write a transformation, which calculate _running total_ of input stream:

```scala mdoc:silent:nest
def runningTotal(stream: UStream[Int]): UStream[Int] =
  stream.mapAccum(0)((acc, next) => (acc + next, acc + next))

// input:  0, 1, 2, 3,  4,  5
// output: 0, 1, 3, 6, 10, 15
```

**mapConcat** — It is similar to `map`, but maps each element to zero or more elements with the type of `Iterable` and then flattens the whole stream:

```scala mdoc:silent:nest
val numbers: UStream[Int] = 
  ZStream("1-2-3", "4-5", "6")
    .mapConcat(_.split("-"))
    .map(_.toInt)

// Input:  "1-2-3", "4-5", "6"
// Output: 1, 2, 3, 4, 5, 6
```

The effectful version of `mapConcat` is `mapConcatZIO`.

`ZStream` also has chunked versions of that which are `mapConcatChunk` and `mapConcatChunkZIO`.

**as** — The `ZStream#as` method maps the success values of this stream to the specified constant value.

For example, we can map all element to the unit value:

```scala mdoc:silent:nest
val unitStream: ZStream[Any, Nothing, Unit] = 
  ZStream.range(1, 5).as(())
```

## Filtering

The `ZStream#filter` allows us to filter emitted elements:

```scala mdoc:silent:nest
val s1 = ZStream.range(1, 11).filter(_ % 2 == 0)
// Output: 2, 4, 6, 8, 10

// The `ZStream#withFilter` operator enables us to write filter in for-comprehension style
val s2 = for {
  i <- ZStream.range(1, 11).take(10)
  if i % 2 == 0
} yield i
// Output: 2, 4, 6, 8, 10

val s3 = ZStream.range(1, 11).filterNot(_ % 2 == 0)
// Output: 1, 3, 5, 7, 9
```

## Scanning

Scans are like folds, but with a history. Like folds, they take a binary operator with an initial value. A scan combines elements of a stream and emits every intermediary result as an output of the stream:

```scala mdoc:silent:nest
val scan = ZStream(1, 2, 3, 4, 5).scan(0)(_ + _)
// Output: 0, 1, 3, 6, 10, 15
// Iterations:
//        =>  0 (initial value)
//  0 + 1 =>  1
//  1 + 2 =>  3
//  3 + 3 =>  6
//  6 + 4 => 10
// 10 + 5 => 15

val fold = ZStream(1, 2, 3, 4, 5).runFold(0)(_ + _)
// Output: 15 (ZIO effect containing 15)
```

## Draining

Assume we have an effectful stream, which contains a sequence of effects; sometimes we might want to execute its effect without emitting any element, in these situations to discard the results we should use the `ZStream#drain` method. It removes all output values from the stream:

```scala mdoc:silent:nest
val s1: ZStream[Any, Nothing, Nothing] = ZStream(1, 2, 3, 4, 5).drain
// Emitted Elements: <empty stream, it doesn't emit any element>

val s2: ZStream[Any, IOException, Int] =
  ZStream
    .repeatZIO {
      for {
        nextInt <- Random.nextInt
        number = Math.abs(nextInt % 10)
        _ <- Console.printLine(s"random number: $number")
      } yield (number)
    }
    .take(3)
// Emitted Elements: 1, 4, 7
// Result of Stream Effect on the Console:
// random number: 1
// random number: 4
// random number: 7

val s3: ZStream[Any, IOException, Nothing] = s2.drain
// Emitted Elements: <empty stream, it doesn't emit any element>
// Result of Stream Effect on the Console:
// random number: 4
// random number: 8
// random number: 2
```

The `ZStream#drain` often used with `ZStream#merge` to run one side of the merge for its effect while getting outputs from the opposite side of the merge:

```scala mdoc:silent:nest
val logging = ZStream.fromZIO(
  printLine("Starting to merge with the next stream")
)
val stream = ZStream(1, 2, 3) ++ logging.drain ++ ZStream(4, 5, 6)

// Emitted Elements: 1, 2, 3, 4, 5, 6
// Result of Stream Effect on the Console:
// Starting to merge with the next stream
```

Note that if we do not drain the `logging` stream, the emitted elements would be contained unit value:

```scala mdoc:silent:nest
val stream = ZStream(1, 2, 3) ++ logging ++ ZStream(4, 5, 6)

// Emitted Elements: 1, 2, 3, (), 4, 5, 6
// Result of Stream Effect on the Console:
// Starting to merge with the next stream
```

## Changes

The `ZStream#changes` emits elements that are not equal to the previous element:

```scala mdoc:silent:nest
val changes = ZStream(1, 1, 1, 2, 2, 3, 4).changes
// Output: 1, 2, 3, 4
```

The `ZStream#changes` operator, uses natural equality to determine whether two elements are equal. If we prefer the specialized equality checking, we can provide a function of type `(O, O) => Boolean` to the `ZStream#changesWith` operator.

Assume we have a stream of events with a composite key of _partition_ and _offset_ attributes, and we know that the offset is monotonic in each partition. So, we can use the `changesWith` operator to create a stream of unique elements:

```scala mdoc:silent:nest
case class Event(partition: Long, offset: Long, metadata: String) 
val events: ZStream[Any, Nothing, Event] = ZStream.fromIterable(???)

val uniques = events.changesWith((e1, e2) => (e1.partition == e2.partition && e1.offset == e2.offset))
```

## Collecting

We can perform `filter` and `map` operations in a single step using the `ZStream#collect` operation:

```scala mdoc:silent:nest
val source1 = ZStream(1, 2, 3, 4, 0, 5, 6, 7, 8)
  
val s1 = source1.collect { case x if x < 6 => x * 2 }
// Output: 2, 4, 6, 8, 0, 10

val s2 = source1.collectWhile { case x if x != 0 => x * 2 }
// Output: 2, 4, 6, 8

val source2 = ZStream(Left(1), Right(2), Right(3), Left(4), Right(5))

val s3 = source2.collectLeft
// Output: 1, 4

val s4 = source2.collectWhileLeft
// Output: 1

val s5 = source2.collectRight
// Output: 2, 3, 5

val s6 = source2.drop(1).collectWhileRight
// Output: 2, 3

val s7 = source2.map(_.toOption).collectSome
// Output: 2, 3, 5

val s8 = source2.map(_.toOption).collectWhileSome
// Output: empty stream
```

We can also do effectful collect using `ZStream#collectZIO` and `ZStream#collectWhileZIO`.

ZIO stream has `ZStream#collectSuccess` which helps us to perform effectful operations and just collect the success values:

```scala mdoc:silent
val urls = ZStream(
  "dotty.epfl.ch",
  "zio.dev",
  "zio.github.io/zio-json",
  "zio.github.io/zio-nio/"
)

def fetch(url: String): ZIO[Any, Throwable, String] = 
  ZIO.attemptBlocking(???)

val pages = urls
  .mapZIO(url => fetch(url).exit)
  .collectSuccess
```

## Zipping

We can zip two stream by using `ZStream.zip` or `ZStream#zipWith` operator:

```scala mdoc:silent:nest
val s1: UStream[(Int, String)] =
  ZStream(1, 2, 3, 4, 5, 6).zipWith(ZStream("a", "b", "c"))((a, b) => (a, b))

val s2: UStream[(Int, String)] = 
  ZStream(1, 2, 3, 4, 5, 6).zip(ZStream("a", "b", "c"))
  
// Output: (1, "a"), (2, "b"), (3, "c")
``` 

The new stream will end when one of the streams ends.

In case of ending one stream before another, we might need to zip with default values; the `ZStream#zipAll` or `ZStream#zipAllWith` takes default values of both sides to perform such mechanism for us:

```scala mdoc:silent:nest
val s1 = ZStream(1, 2, 3)
  .zipAll(ZStream("a", "b", "c", "d", "e"))(0, "x")
val s2 = ZStream(1, 2, 3).zipAllWith(
  ZStream("a", "b", "c", "d", "e")
)(_ => 0, _ => "x")((a, b) => (a, b))

// Output: (1, a), (2, b), (3, c), (0, d), (0, e)
```

Sometimes we want to zip streams, but do not want to zip two elements one by one. For example, we may have two streams producing elements at different speeds, and do not want to wait for the slower one when zipping elements. When we need to zip elements with the latest element of the slower stream, `ZStream#zipLatest` or `ZStream#zipLatestWith` will do this for us. It zips two streams so that when a value is emitted by either of the two streams, it is combined with the latest value from the other stream to produce a result:

```scala mdoc:silent:nest
val s1 = ZStream(1, 2, 3)
  .schedule(Schedule.spaced(1.second))

val s2 = ZStream("a", "b", "c", "d")
  .schedule(Schedule.spaced(500.milliseconds))
  .rechunk(3)

s1.zipLatest(s2)

// Output: (1, a), (1, b), (1, c), (1, d), (2, d), (3, d)
```

ZIO Stream also has three useful operators for zipping element of a stream with their previous/next elements and also both of them:

```scala mdoc:silent:nest
val stream: UStream[Int] = ZStream.fromIterable(1 to 5)

val s1: UStream[(Option[Int], Int)]              = stream.zipWithPrevious
val s2: UStream[(Int, Option[Int])]              = stream.zipWithNext
val s3: UStream[(Option[Int], Int, Option[Int])] = stream.zipWithPreviousAndNext
```

By using `ZStream#zipWithIndex` we can index elements of a stream:

```scala mdoc:silent:nest
val indexedStream: ZStream[Any, Nothing, (String, Long)] = 
  ZStream("Mary", "James", "Robert", "Patricia").zipWithIndex

// Output: ("Mary", 0L), ("James", 1L), ("Robert", 2L), ("Patricia", 3L)
```

## Cross Product

ZIO stream has `ZStream#cross` and its variants to compute _Cartesian Product_ of two streams:

```scala mdoc:silent:nest
val first = ZStream(1, 2, 3)
val second = ZStream("a", "b")

val s1 = first cross second
val s2 = first <*> second
val s3 = first.crossWith(second)((a, b) => (a, b))
// Output: (1,a), (1,b), (2,a), (2,b), (3,a), (3,b)

val s4 = first crossLeft second 
val s5 = first <* second
// Keep only elements from the left stream
// Output: 1, 1, 2, 2, 3, 3 

val s6 = first crossRight second
val s7 = first *> second
// Keep only elements from the right stream
// Output: a, b, a, b, a, b
```

Note that the right-hand side stream would be run multiple times, for every element in the left stream.

ZIO stream also has `ZStream.crossN` which takes streams up to four one.

## Partitioning

### partition
`ZStream#partition` function splits the stream into tuple of streams based on the predicate. The first stream contains all element evaluated to true, and the second one contains all element evaluated to false.

The faster stream may advance by up to `buffer` elements further than the slower one. Two streams are wrapped by `Scope` type.

In the example below, left stream consists of even numbers only:

```scala mdoc:silent:nest
val partitionResult: ZIO[Scope, Nothing, (ZStream[Any, Nothing, Int], ZStream[Any, Nothing, Int])] =
  ZStream
    .fromIterable(0 to 100)
    .partition(_ % 2 == 0, buffer = 50)
```

### partitionEither
If we need to partition a stream using an effectful predicate we can use `ZStream.partitionEither`.

```scala
abstract class ZStream[-R, +E, +O] {
  final def partitionEither[R1 <: R, E1 >: E, O2, O3](
    p: O => ZIO[R1, E1, Either[O2, O3]],
    buffer: Int = 16
  ): ZIO[R1 with Scope, E1, (ZStream[Any, E1, O2], ZStream[Any, E1, O3])]
}
```

Here is a simple example of using this function:

```scala mdoc:silent:nest
val partitioned: ZIO[Scope, Nothing, (ZStream[Any, Nothing, Int], ZStream[Any, Nothing, Int])] =
  ZStream
    .fromIterable(1 to 10)
    .partitionEither(x => ZIO.succeed(if (x < 5) Left(x) else Right(x)))
```

## GroupBy

### groupByKey

To partition the stream by function result we can use `groupBy` by providing a function of type `O => K` which determines by which keys the stream should be partitioned.

```scala
abstract class ZStream[-R, +E, +O] {
  final def groupByKey[K](
    f: O => K,
    buffer: Int = 16
  ): ZStream.GroupBy[R, E, K, O]
}
```

In the example below, exam results are grouped into buckets and counted:

```scala mdoc:silent
import zio._
import zio.stream._

  case class Exam(person: String, score: Int)

  val examResults = Seq(
    Exam("Alex", 64),
    Exam("Michael", 97),
    Exam("Bill", 77),
    Exam("John", 78),
    Exam("Bobby", 71)
  )

  val groupByKeyResult: ZStream[Any, Nothing, (Int, Int)] =
    ZStream
      .fromIterable(examResults)
      .groupByKey(exam => exam.score / 10 * 10) {
        case (k, s) => ZStream.fromZIO(s.runCollect.map(l => k -> l.size))
      }
```

:::note
`groupByKey` partition the stream by a simple function of type `O => K`; It is not an effectful function. In some cases we need to partition the stream by using an _effectful function_ of type `O => ZIO[R1, E1, (K, V)]`; So we can use `groupBy` which is the powerful version of `groupByKey` function.
:::

### groupBy
It takes an effectful function of type `O => ZIO[R1, E1, (K, V)]`; ZIO Stream uses this function to partition the stream and gives us a new data type called `ZStream.GroupBy` which represent a grouped stream. `GroupBy` has an `apply` method, that takes a function of type `(K, ZStream[Any, E, V]) => ZStream[R1, E1, A]`; ZIO Runtime runs this function across all groups and then merges them in a non-deterministic fashion as a result.

```scala
abstract class ZStream[-R, +E, +O] {
  final def groupBy[R1 <: R, E1 >: E, K, V](
    f: O => ZIO[R1, E1, (K, V)],
    buffer: Int = 16
  ): ZStream.GroupBy[R1, E1, K, V]
}
```

In the example below, we are going `groupBy` given names by their first character and then count the number of names in each group:

```scala mdoc:silent:nest
val counted: UStream[(Char, Long)] =
  ZStream("Mary", "James", "Robert", "Patricia", "John", "Jennifer", "Rebecca", "Peter")
    .groupBy(x => ZIO.succeed((x.head, x))) { case (char, stream) =>
      ZStream.fromZIO(stream.runCount.map(count => char -> count))
    }
// Input:  Mary, James, Robert, Patricia, John, Jennifer, Rebecca, Peter
// Output: (P, 2), (R, 2), (M, 1), (J, 3)
```

Let's change the above example a bit into an example of classifying students. The teacher assigns the student to a specific class based on the student's talent. Note that the partitioning operation is an effectful:

```scala mdoc:silent:nest
val classifyStudents: ZStream[Any, IOException, (String, Seq[String])] =
  ZStream.fromZIO(
    printLine("Please assign each student to one of the A, B, or C classrooms.")
  ) *> ZStream("Mary", "James", "Robert", "Patricia", "John", "Jennifer", "Rebecca", "Peter")
    .groupBy(student =>
      printLine(s"What is the classroom of $student? ") *>
        readLine.map(classroom => (classroom, student))
    ) { case (classroom, students) =>
      ZStream.fromZIO(
        students
          .runFold(Seq.empty[String])((s, e) => s :+ e)
          .map(students => classroom -> students)
      )
    }

// Input: 
// Please assign each student to one of the A, B, or C classrooms.
// What is the classroom of Mary? A
// What is the classroom of James? B
// What is the classroom of Robert? A
// What is the classroom of Patricia? C
// What is the classroom of John? B
// What is the classroom of Jennifer? A
// What is the classroom of Rebecca? C
// What is the classroom of Peter? A
//
// Output: 
// (B,List(James, John))
// (A,List(Mary, Robert, Jennifer, Peter))
// (C,List(Patricia, Rebecca))
```

## Grouping

### grouped
To partition the stream results with the specified chunk size, we can use the `grouped` function.

```scala mdoc:silent:nest
val groupedResult: ZStream[Any, Nothing, Chunk[Int]] =
  ZStream.fromIterable(0 to 8).grouped(3)

// Input:  0, 1, 2, 3, 4, 5, 6, 7, 8
// Output: Chunk(0, 1, 2), Chunk(3, 4, 5), Chunk(6, 7, 8)
```

### groupedWithin
It allows grouping events by time or chunk size, whichever is satisfied first. In the example below every chunk consists of 30 elements and is produced every 3 seconds.

```scala mdoc:silent
import zio._
import zio.Duration._
import zio.stream._

val groupedWithinResult: ZStream[Any, Nothing, Chunk[Int]] =
  ZStream.fromIterable(0 to 10)
    .repeat(Schedule.spaced(1.seconds))
    .groupedWithin(30, 10.seconds)
```

## Concatenation

We can concatenate two streams by using `ZStream#++` or `ZStream#concat` operator which returns a stream that emits the elements from the left-hand stream and then emits the elements from the right stream:

```scala silent:nest
val a = ZStream(1, 2, 3)
val b = ZStream(4, 5)
val c1 = a ++ b
val c2 = a concat b
```

Also, we can use `ZStream.concatAll` constructor to concatenate given streams together:

```scala mdoc:invisible
val a = ZStream(1, 2, 3)
val b = ZStream(4, 5)
```

```scala mdoc:silent:nest
val c3 = ZStream.concatAll(Chunk(a, b))
```

There is also the `ZStream#flatMap` combinator which create a stream which elements are generated by applying a function of type `O => ZStream[R1, E1, O2]` to each output of the source stream and concatenated all of the results:

```scala mdoc:silent:nest
val stream = ZStream(1, 2, 3).flatMap(x => ZStream.repeat(x).take(4))
// Input:  1, 2, 3
// Output: 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3
```

Assume we have an API that takes an author name and returns all its book:

```scala mdoc:invisible
case class Book()
```

```scala mdoc:silent
def getAuthorBooks(author: String): ZStream[Any, Throwable, Book] = ZStream(???)
```

If we have a stream of author's names, we can use `ZStream#flatMap` to concatenate the results of all API calls:

```scala mdoc:silent
val authors: ZStream[Any, Throwable, String] = 
  ZStream("Mary", "James", "Robert", "Patricia", "John")
val allBooks: ZStream[Any, Throwable, Book]  = 
  authors.flatMap(getAuthorBooks _)
```

If we need to do the `flatMap` concurrently, we can use `ZStream#flatMapPar`, and also if the order of concatenation is not important for us, we can use the `ZStream#flatMapParSwitch` operator.

## Merging

Sometimes we need to interleave the emission of two streams and create another stream. In these cases, we can't use the `ZStream.concat` operation because the `concat` operation waits for the first stream to finish and then consumes the second stream. So we need a non-deterministic way of picking elements from different sources. ZIO Stream's `merge` operations does this for us. Let's discuss some variants of this operation:

### merge

The `ZSstream#merge` picks elements randomly from specified streams:

```scala mdoc:silent:nest
val s1 = ZStream(1, 2, 3).rechunk(1)
val s2 = ZStream(4, 5, 6).rechunk(1)

val merged = s1 merge s2
// As the merge operation is not deterministic, it may output the following stream of numbers:
// Output: 4, 1, 2, 5, 6, 3
```

Merge operation always try to pull one chunk from each stream, if we chunk our streams equal or over 3 elements in the last example, we encounter a new stream containing one of the `1, 2, 3, 4, 5, 6` or `4, 5, 6, 1, 2, 3` elements.

### Termination Strategy

When we merge two streams, we should think about the _termination strategy_ of this operation. Each stream has a specific lifetime. One stream may emit all its elements and finish its job, another stream may end after one hour of emission, one another may have a long-running lifetime and never end. So when we merge two streams with different lifetimes, what is the termination strategy of the resulting stream?

By default, when we merge two streams using `ZStream#merge` operation, the newly produced stream will terminate when both specified streams terminate. We can also define the _termination strategy_ corresponding to our requirement. ZIO Stream supports four different termination strategies:

- **Left** — The resulting stream will terminate when the left-hand side stream terminates.
- **Right** — The resulting stream will terminate when the right-hand side stream finishes.
- **Both** — The resulting stream will terminate when both streams finish.
- **Either** — The resulting stream will terminate when one of the streams finishes.

Here is an example of specifying termination strategy when merging two streams:

```scala mdoc:silent:nest
import zio.stream.ZStream.HaltStrategy
val s1 = ZStream.iterate(1)(_+1).take(5).rechunk(1)
val s2 = ZStream.repeat(0).rechunk(1)

val merged = s1.merge(s2, HaltStrategy.Left)
```

We can also use `ZStream#mergeTerminateLeft`, `ZStream#mergeTerminateRight` or `ZStream#mergeTerminateEither` operations instead of specifying manually the termination strategy.

### mergeAll

Usually, micro-services or long-running applications are composed of multiple components that need to run infinitely in the background and if something happens to them, or they terminate abruptly we should crash the entire application.

So our main fiber should perform these three things:

* **Launch and wait** — It should launch all of those background components and wait infinitely. It should not exit prematurely, because then our application won't be running.
* **Interrupt everything** — It should interrupt all those components whenever we receive a termination signal from the operating system.
* **Watch all fibers** — It should watch all those fibers (background components), and quickly exit if something goes wrong.

So how should we do that with our main fiber? Let's try to create a long-running application:

```scala 
val main = 
  kafkaConsumer.runDrain.fork *>
  httpServer.fork *>
  scheduledJobRunner.fork *>
  ZIO.never
```

We can launch the Kafka consumer, the HTTP server, and our job runner and fork them, and then wait using `ZIO.never`. This will indeed wait, but if something happens to any of them and if they crash, nothing happens. So our application just hangs and remains up without anything working in the background. So this approach does not work properly.

So another idea is to watch background components. By using `ZIO.raceFirst` as soon as one of those fibers terminates with either success or failure, it will interrupt all the rest of the components:

```scala mdoc:invisible
val kafkaConsumer     : ZStream[Any, Nothing, Int] = ZStream.fromZIO(ZIO.succeed(???))
val httpServer        : ZIO[Any, Nothing, Nothing] = ZIO.never
val scheduledJobRunner: ZIO[Any, Nothing, Nothing] = ZIO.never
```

```scala mdoc:silent:nest
val main =
  ZIO.raceFirst(kafkaConsumer.runDrain, List(httpServer, scheduledJobRunner))
```

We can also do this with streams:

```scala mdoc:silent:nest
val main =
  for {
  //_ <- other resources
    _ <- ZStream
      .mergeAllUnbounded(16)(
        kafkaConsumer.drain,
        ZStream.fromZIO(httpServer),
        ZStream.fromZIO(scheduledJobRunner)
      )
      .runDrain
  } yield ()
```

Using `ZStream.mergeAll` we can combine all these streaming components concurrently into one application.

### mergeWith

Sometimes we need to merge two streams and after that, unify them and convert them to new element types. We can do this by using the `ZStream#mergeWith` operation:

```scala mdoc:silent:nest
val s1 = ZStream("1", "2", "3")
val s2 = ZStream(4.1, 5.3, 6.2)

val merged = s1.mergeWith(s2)(_.toInt, _.toInt)
```

## Interleaving

When we `merge` two streams, the ZIO Stream picks elements from two streams randomly. But how to merge two streams deterministically? The answer is the `ZStream#interleave` operation.

The `ZStream#interleave` operator pulls an element from each stream, one by one, and then returns an interleaved stream. When one stream is exhausted, all remaining values in the other stream will be pulled:

```scala mdoc:silent:nest
val s1 = ZStream(1, 2, 3)
val s2 = ZStream(4, 5, 6, 7, 8)

val interleaved = s1 interleave s2

// Output: 1, 4, 2, 5, 3, 6, 7, 8
```

ZIO Stream also has the `interleaveWith` operator, which is a more powerful version of `interleave`. By using `ZStream#interleaveWith`, we can specify the logic of interleaving:

```scala mdoc:silent:nest
val s1 = ZStream(1, 3, 5, 7, 9)
val s2 = ZStream(2, 4, 6, 8, 10)

val interleaved = s1.interleaveWith(s2)(ZStream(true, false, false).forever)
// Output: 1, 2, 4, 3, 6, 8, 5, 10, 7, 9
```

`ZStream#interleaveWith` uses a stream of boolean to decide which stream to choose. If it reaches a true value, it will pick a value from the left-hand side stream, otherwise, it will pick from the right-hand side.

## Interspersing

We can intersperse any stream by using `ZStream#intersperse` operator:

```scala mdoc:silent:nest
val s1 = ZStream(1, 2, 3, 4, 5).intersperse(0)
// Output: 1, 0, 2, 0, 3, 0, 4, 0, 5

val s2 = ZStream("a", "b", "c", "d").intersperse("[", "-", "]")
// Output: [, -, a, -, b, -, c, -, d]
```

## Broadcasting

We can broadcast a stream by using `ZStream#broadcast`, it returns a scoped list of streams that have the same elements as the source stream. The `broadcast` operation emits each element to the inputs of returning streams. The upstream stream can emit events as much as `maximumLag`, then it decreases its speed by the slowest downstream stream.

In the following example, we are broadcasting stream of random numbers to the two downstream streams. One of them is responsible to compute the maximum number, and the other one does some logging job with additional delay. The upstream stream decreases its speed by the logging stream:

```scala mdoc:silent:nest
val stream: ZIO[Any, IOException, Unit] =
  ZIO.scoped {
    ZStream
      .fromIterable(1 to 20)
      .mapZIO(_ => Random.nextInt)
      .map(Math.abs)
      .map(_ % 100)
      .tap(e => printLine(s"Emit $e element before broadcasting"))
      .broadcast(2, 5)
      .flatMap { streams =>
          for {
            out1 <- streams(0).runFold(0)((acc, e) => Math.max(acc, e))
                      .flatMap(x => printLine(s"Maximum: $x"))
                      .fork
            out2 <- streams(1).schedule(Schedule.spaced(1.second))
                      .foreach(x => printLine(s"Logging to the Console: $x"))
                      .fork
            _    <- out1.join.zipPar(out2.join)
          } yield ()
      }
  }
```

## Distribution

The `ZStream#distributedWith` operator is a more powerful version of `ZStream#broadcast`. It takes a `decide` function, and based on that decide how to distribute incoming elements into the downstream streams:

```scala
abstract class ZStream[-R, +E, +O] {
  final def distributedWith[E1 >: E](
    n: Int,
    maximumLag: Int,
    decide: O => UIO[Int => Boolean]
  ): ZIO[R with Scope, Nothing, List[Dequeue[Exit[Option[E1], O]]]] = ???
}
```

In the example below, we are partitioning incoming elements into three streams using `ZStream#distributedWith` operator:

```scala mdoc:silent:nest
val partitioned: ZIO[Scope, Nothing, (UStream[Int], UStream[Int], UStream[Int])] =
  ZStream
    .iterate(1)(_ + 1)
    .schedule(Schedule.fixed(1.seconds))
    .distributedWith(3, 10, x => ZIO.succeed(q => x % 3 == q))
    .flatMap { 
      case q1 :: q2 :: q3 :: Nil =>
        ZIO.succeed(
          ZStream.fromQueue(q1).flattenExitOption,
          ZStream.fromQueue(q2).flattenExitOption,
          ZStream.fromQueue(q3).flattenExitOption
        )
      case _ => ZIO.dieMessage("Impossible!")
    }
```

## Buffering

Since the ZIO streams are pull-based, it means the consumers do not need to message the upstream to slow down. Whenever a downstream stream pulls a new element, the upstream produces a new element. So, the upstream stream is as fast as the slowest downstream stream. Sometimes we need to run producer and consumer independently, in such a situation we can use an asynchronous non-blocking queue for communication between faster producer and slower consumer; the queue can buffer elements between two streams. ZIO stream also has a built-in `ZStream#buffer` operator which does the same thing for us.

The `ZStream#buffer` allows a faster producer to progress independently of a slower consumer by buffering up to `capacity` chunks in a queue.

In the following example, we are going to buffer a stream. We print each element to the console as they are emitting before and after the buffering:

```scala mdoc:silent:nest
ZStream
  .fromIterable(1 to 10)
  .rechunk(1)
  .tap(x => Console.printLine(s"before buffering: $x"))
  .buffer(4)
  .tap(x => Console.printLine(s"after buffering: $x"))
  .schedule(Schedule.spaced(5.second))  
```

We spaced 5 seconds between each emission to show the lag between producing and consuming messages.

Based on the type of underlying queue we can use one the buffering operators:
- **Bounded Queue** — `ZStream#buffer(capacity: Int)`
- **Unbounded Queue** — `ZStream#bufferUnbounded`
- **Sliding Queue** — `ZStream#bufferSliding(capacity: Int)`
- **Dropping Queue** `ZStream#bufferDropping(capacity: Int)`

## Debouncing

The `ZStream#debounce` method debounces the stream with a minimum period of `d` between each element:

```scala mdoc:silent:nest
val stream = (
  ZStream(1, 2, 3) ++
    ZStream.fromZIO(ZIO.sleep(500.millis)) ++ ZStream(4, 5) ++
    ZStream.fromZIO(ZIO.sleep(10.millis)) ++
    ZStream(6)
).debounce(100.millis) // emit only after a pause of at least 100 ms
// Output: 3, 6
```

## Aggregation

Aggregation is the process of converting one or more elements of type `A` into elements of type `B`. This operation takes a transducer as an aggregation unit and returns another stream that is aggregated. We have two types of aggregation:

### Synchronous Aggregation

They are synchronous because the upstream emits an element when the _transducer_ emits one. To apply a synchronous aggregation to the stream we can use `ZStream#aggregate` or `ZStream#transduce` operations.

Let's see an example of synchronous aggregation:

```scala mdoc:silent:nest
val stream = ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
val s1 = stream.transduce(ZSink.collectAllN[Int](3))
// Output Chunk(1,2,3), Chunk(4,5,6), Chunk(7,8,9), Chunk(10)
```

Sometimes stream processing element by element is not efficient, specially when we are working with files or doing I/O works; so we might need to aggregate them and process them in a batch way:

```scala mdoc:silent:nest
val source =
  ZStream
    .iterate(1)(_ + 1)
    .take(200)
    .tap(x =>
      printLine(s"Producing Element $x")
        .schedule(Schedule.duration(1.second).jittered)
    )

val sink = 
  ZSink.foreach((e: Chunk[Int]) =>
    printLine(s"Processing batch of events: $e")
      .schedule(Schedule.duration(3.seconds).jittered)
  )
  
val myApp = 
  source.transduce(ZSink.collectAllN[Int](5)).run(sink)
```

Let's see one output of running this program:

```
Producing element 1
Producing element 2
Producing element 3
Producing element 4
Producing element 5
Processing batch of events: Chunk(1,2,3,4,5)
Producing element 6
Producing element 7
Producing element 8
Producing element 9
Producing element 10
Processing batch of events: Chunk(6,7,8,9,10)
Producing element 11
Producing element 12
Processing batch of events: Chunk(11,12)
```

Elements are grouped into Chunks of 5 elements and then processed in a batch way.

### Asynchronous Aggregation

Asynchronous aggregations, aggregate elements of upstream as long as the downstream operators are busy. To apply an asynchronous aggregation to the stream, we can use `ZStream#aggregateAsync`, `ZStream#aggregateAsyncWithin`, and `ZStream#aggregateAsyncWithinEither` operations.


For example, consider `source.aggregateAsync(ZSink.collectAllN[Nothing, Int](5)).mapZIO(processChunks)`. Whenever the downstream (`mapZIO(processChunks)`) is ready for consumption and pulls the upstream, the transducer `(ZTransducer.collectAllN(5))` will flush out its buffer, regardless of whether the `collectAllN` buffered all its 5 elements or not. So the `ZStream#aggregateAsync` will emit when downstream pulls:

```scala mdoc:silent:nest
val myApp = 
  source.aggregateAsync(ZSink.collectAllN[Int](5)).run(sink)
```

Let's see one output of running this program:

```
Producing element 1
Producing element 2
Producing element 3
Producing element 4
Processing batch of events: Chunk(1,2)
Processing batch of events: Chunk(3,4)
Producing element 5
Processing batch of events: Chunk(5)
Producing element 6
Processing batch of events: Chunk(6)
Producing element 7
Producing element 8
Producing element 9
Processing batch of events: Chunk(7)
Producing element 10
Producing element 11
Processing batch of events: Chunk(8,9)
Producing element 12
Processing batch of events: Chunk(10,11)
Processing batch of events: Chunk(12)
```

The `ZStream#aggregateAsyncWithin` is another aggregator which takes a scheduler. This scheduler will consume all events produced by the given transducer. So the `aggregateAsyncWithin` will emit when the transducer emits or when the scheduler expires:

```scala
abstract class ZStream[-R, +E, +O] {
  final def aggregateAsyncWithin[R1 <: R, E1 >: E, E2, A1 >: A, B](
    sink: ZSink[R1, E1, A1, E2, A1, B],
    schedule: Schedule[R1, Option[B], Any]
  )(implicit trace: Trace): ZStream[R1, E2, B] = ???
}
```

When we are doing I/O, batching is very important. With ZIO streams, we can create user-defined batches. It is pretty easy to do that with the `ZStream#aggregateAsyncWithin` operator. Let's see the below snippet code:

```scala mdoc:invisible
case class Record()
val dataStream: ZStream[Any, Nothing, Record] = ZStream.repeat(Record())
```

```scala mdoc:silent:nest
dataStream.aggregateAsyncWithin(
   ZSink.collectAllN[Record](2000),
   Schedule.fixed(30.seconds)
 )
```

So it will collect elements into a chunk up to 2000 elements and if we have got less than 2000 elements and 30 seconds have passed, it will pass currently collected elements down the stream whether it has collected zero, one, or 2000 elements. So this is a sort of timeout for aggregation operation. This approach aggressively favors **throughput** over **latency**. It will introduce a fixed amount of latency into a stream. We will always wait for up to 30 seconds if we haven't reached this sort of boundary value.

Instead, thanks to `Schedule` we can create a much smarter **adaptive batching algorithm** that can balance between **throughput** and **latency*. So what we are doing here is that we are creating a schedule that operates on chunks of records. What the `Schedule` does is that it starts off with 30-second timeouts for as long as its input has a size that is lower than 1000, now once we see an input that has a size look higher than 1000, we will switch to a second schedule with some jittery, and we will remain with this schedule for as long as the batch size is over 1000:

```scala mdoc:silent:nest
val schedule: Schedule[Any, Option[Chunk[Record]], Long] =
// Start off with 30-second timeouts as long as the batch size is < 1000
  Schedule.fixed(30.seconds).whileInput[Option[Chunk[Record]]](_.getOrElse(Chunk.empty).length < 100) andThen
    // and then, switch to a shorter jittered schedule for as long as batches remain over 1000
    Schedule.fixed(5.seconds).jittered.whileInput[Option[Chunk[Record]]](_.getOrElse(Chunk.empty).length >= 1000)
    
dataStream
  .aggregateAsyncWithin(ZSink.collectAllN[Record](2000), schedule)
```
