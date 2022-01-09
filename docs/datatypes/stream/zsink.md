---
id: zsink
title: "ZSink"
---
```scala mdoc:invisible
import zio._
import zio.Console._
import java.io.IOException
import java.nio.file.{Path, Paths}
```

## Introduction

A `ZSink[R, E, I, L, Z]` is used to consume elements produced by a `ZStream`. You can think of a sink as a function that will consume a variable amount of `I` elements (could be 0, 1, or many!), might fail with an error of type `E`, and will eventually yield a value of type `Z` together with a remainder of type `L` as leftover.

To consume a stream using `ZSink` we can pass `ZSink` to the `ZStream#run` function:

```scala mdoc:silent
import zio._
import zio.stream._

val stream = ZStream.fromIterable(1 to 1000)
val sink   = ZSink.sum[Int]
val sum    = stream.run(sink)
```

## Creating sinks

The `zio.stream` provides numerous kinds of sinks to use.

### Common Constructors

**ZSink.head** — It creates a sink containing the first element, returns `None` for empty streams:

```scala mdoc:silent:nest
val sink: ZSink[Any, Nothing, Int, Int, Option[Int]] = ZSink.head[Int]
val head: ZIO[Any, Nothing, Option[Int]]             = ZStream(1, 2, 3, 4).run(sink)
// Result: Some(1)
``` 

**ZSink.last** — It consumes all elements of a stream and returns the last element of the stream:

```scala mdoc:silent:nest
val sink: ZSink[Any, Nothing, Int, Int, Option[Int]] = ZSink.last[Int]
val last: ZIO[Any, Nothing, Option[Int]]                 = ZStream(1, 2, 3, 4).run(sink)
// Result: Some(4)
```

**ZSink.count** — A sink that consumes all elements of the stream and counts the number of elements fed to it:

```scala mdoc:silent:nest
val sink : ZSink[Any, Nothing, Int, Nothing, Int] = ZSink.sum[Int]
val count: ZIO[Any, Nothing, Int]                 = ZStream(1, 2, 3, 4, 5).run(sink)
// Result: 5
```

**ZSink.sum** — A sink that consumes all elements of the stream and sums incoming numeric values:

```scala mdoc:silent:nest
val sink : ZSink[Any, Nothing, Int, Nothing, Int] = ZSink.sum[Int]
val sum: ZIO[Any, Nothing, Int]                 = ZStream(1, 2, 3, 4, 5).run(sink)
// Result: 15
```

**ZSink.take** — A sink that takes the specified number of values and result in a `Chunk` data type:

```scala mdoc:silent:nest
val sink  : ZSink[Any, Nothing, Int, Int, Chunk[Int]] = ZSink.take[Int](3)
val stream: ZIO[Any, Nothing, Chunk[Int]]             = ZStream(1, 2, 3, 4, 5).run(sink)
// Result: Chunk(1, 2, 3)
```

**ZSink.drain** — A sink that ignores its inputs:

```scala mdoc:silent:nest
val drain: ZSink[Any, Nothing, Any, Nothing, Unit] = ZSink.drain
```

**ZSink.timed** — A sink that executes the stream and times its execution:

```scala mdoc:silent
val timed: ZSink[Clock, Nothing, Any, Nothing, Duration] = ZSink.timed
val stream: ZIO[Clock, Nothing, Long] =
  ZStream(1, 2, 3, 4, 5).fixed(2.seconds).run(timed).map(_.getSeconds)
// Result: 10
```

**ZSink.foreach** — A sink that executes the provided effectful function for every element fed to it:

```scala mdoc:silent:nest
val printer: ZSink[Console, IOException, Int, Int, Unit] =
  ZSink.foreach((i: Int) => printLine(i))
val stream : ZIO[Console, IOException, Unit]             =
  ZStream(1, 2, 3, 4, 5).run(printer)
```

### From Success and Failure

Similar to the `ZStream` data type, we can create a `ZSink` using `fail` and `succeed` methods.

A sink that doesn't consume any element from its upstream and successes with a value of `Int` type:

```scala mdoc:silent:nest
val succeed: ZSink[Any, Any, Any, Nothing, Int] = ZSink.succeed(5)
```

A sink that doesn't consume any element from its upstream and intentionally fails with a message of `String` type:

```scala mdoc:silent:nest
val failed : ZSink[Any, String, Any, Nothing, Nothing] = ZSink.fail("fail!")
```

### Collecting

To create a sink that collects all elements of a stream into a `Chunk[A]`, we can use `ZSink.collectAll`:

```scala mdoc:silent:nest
val stream    : UStream[Int]    = UStream(1, 2, 3, 4, 5)
val collection: UIO[Chunk[Int]] = stream.run(ZSink.collectAll[Int])
// Output: Chunk(1, 2, 3, 4, 5)
```

We can collect all elements into a `Set`:

```scala mdoc:silent:nest
val collectAllToSet: ZSink[Any, Nothing, Int, Nothing, Set[Int]] = ZSink.collectAllToSet[Int]
val stream: ZIO[Any, Nothing, Set[Int]] = ZStream(1, 3, 2, 3, 1, 5, 1).run(collectAllToSet)
// Output: Set(1, 3, 2, 5)
```

Or we can collect and merge them into a `Map[K, A]` using a merge function. In the following example, we use `(_:Int) % 3` to determine map keys and, we provide `_ + _` function to merge multiple elements with the same key:

```scala mdoc:silent:nest
val collectAllToMap: ZSink[Any, Nothing, Int, Nothing, Map[Int, Int]] = ZSink.collectAllToMap((_: Int) % 3)(_ + _)
val stream: ZIO[Any, Nothing, Map[Int, Int]] = ZStream(1, 3, 2, 3, 1, 5, 1).run(collectAllToMap)
// Output: Map(1 -> 3, 0 -> 6, 2 -> 7)
```

**ZSink.collectAllN** — Collects incoming values into chunk of maximum size of `n`:

```scala mdoc:silent:nest
ZStream(1, 2, 3, 4, 5).run(
  ZSink.collectAllN(3)
)
// Output: Chunk(1,2,3), Chunk(4,5)
```

**ZSink.collectAllWhile** — Accumulates incoming elements into a chunk as long as they verify the given predicate:

```scala mdoc:silent:nest
ZStream(1, 2, 0, 4, 0, 6, 7).run(
  ZSink.collectAllWhile(_ != 0)
)
// Output: Chunk(1,2), Chunk(4), Chunk(6,7)
```

**ZSink.collectAllToMapN** — Creates a sink accumulating incoming values into maps of up to `n` keys. Elements are mapped to keys using the function `key`; elements mapped to the same key will be merged with the function `f`:

```scala
object ZSink {
  def collectAllToMapN[Err, In, K](
    n: Long
  )(key: In => K)(f: (In, In) => In): ZSink[Any, Err, In, Err, In, Map[K, In]]
}
```

Let's do an example:

```scala mdoc:silent:nest
ZStream(1, 2, 0, 4, 5).run(
  ZSink.collectAllToMapN[Nothing, Int, Int](10)(_ % 3)(_ + _)
)
// Output: Map(1 -> 5, 2 -> 7, 0 -> 0)
```

**ZSink.collectAllToSetN** — Creates a sink accumulating incoming values into sets of maximum size `n`:

```scala mdoc:silent:nest
ZStream(1, 2, 1, 2, 1, 3, 0, 5, 0, 2).run(
  ZSink.collectAllToSetN(3)
)
// Output: Set(1,2,3), Set(0,5,2), Set(1)
```

### Folding

Basic fold accumulation of received elements:

```scala mdoc:silent
ZSink.foldLeft[Int, Int](0)(_ + _)
```

A fold with short-circuiting has a termination predicate that determines the end of the folding process:

```scala mdoc:silent
ZStream.iterate(0)(_ + 1).run(
  ZSink.fold(0)(sum => sum <= 10)((acc, n: Int) => acc + n)
)
// Output: 15
```


**ZSink.foldWeighted** — Creates a sink that folds incoming elements until reaches the `max` worth of elements determined by the `costFn`, then the pipeline emits the computed value and restarts the folding process:

```scala
object ZSink {
  def foldWeighted[Err, In, S](z: S)(costFn: (S, In) => Long, max: Long)(
    f: (S, In) => S
  ): ZSink[Any, Err, In, Err, In, S] = ???
}
```

In the following example, each time we consume a new element we return one as the weight of that element using cost function. After three times, the sum of the weights reaches to the `max` number, and the folding process restarted. So we expect this pipeline to group each three elements in one `Chunk`:

```scala mdoc:silent:nest
ZStream(3, 2, 4, 1, 5, 6, 2, 1, 3, 5, 6)
  .transduce(
    ZSink
      .foldWeighted(Chunk[Int]())(
        (_, _: Int) => 1,
        3
      ) { (acc, el) =>
        acc ++ Chunk(el)
      }
  )
// Output: Chunk(3,2,4),Chunk(1,5,6),Chunk(2,1,3),Chunk(5,6)
```

Another example is when we want to group element which sum of them equal or less than a specific number:

```scala mdoc:silent:nest
ZStream(1, 2, 2, 4, 2, 1, 1, 1, 0, 2, 1, 2)
  .transduce(
    ZSink
      .foldWeighted(Chunk[Int]())(
        (_, i: Int) => i.toLong,
        5
      ) { (acc, el) =>
        acc ++ Chunk(el)
      }
  )
// Output: Chunk(1,2,2),Chunk(4),Chunk(2,1,1,1,0),Chunk(2,1,2)
```

> _**Note**_
>
> The `ZSink.foldWeighted` cannot decompose elements whose weight is more than the `max` number. So elements that have an individual cost larger than `max` will force the pipeline to cross the `max` cost. In the last example, if the source stream was `ZStream(1, 2, 2, 4, 2, 1, 6, 1, 0, 2, 1, 2)` the output would be `Chunk(1,2,2),Chunk(4),Chunk(2,1),Chunk(6),Chunk(1,0,2,1),Chunk(2)`. As we see, the `6` element crossed the `max` cost.
>
> To decompose these elements, we should use `ZSink.foldWeightedDecompose` function.

**ZSink.foldWeightedDecompose** — As we saw in the previous section, we need a way to decompose elements — whose cause the output aggregate cross the `max` — into smaller elements. This version of fold takes `decompose` function and enables us to do that:

```scala
object ZSink {
  def foldWeightedDecompose[Err, In, S](
     z: S
   )(costFn: (S, In) => Long, max: Long, decompose: In => Chunk[In])(
     f: (S, In) => S
   ): ZSink[Any, Err, In, Err, In, S] = ???
}
```

In the following example, we are break down elements that are bigger than 5, using `decompose` function:

```scala mdoc:silent:nest
ZStream(1, 2, 2, 2, 1, 6, 1, 7, 2, 1, 2)
  .transduce(
    ZSink
      .foldWeightedDecompose(Chunk[Int]())(
        (_, i: Int) => i.toLong,
        5,
        (i: Int) =>
          if (i > 5) Chunk(i - 1, 1) else Chunk(i)
      )((acc, el) => acc ++ Chunk.succeed(el))
  )
// Ouput: Chunk(1,2,2),Chunk(2,1),Chunk(5),Chunk(1,1),Chunk(5),Chunk(1,1,2,1),Chunk(2)
```

**ZSink.foldUntil** — Creates a sink that folds incoming element until specific `max` elements have been folded:

```scala mdoc:silent:nest
ZStream(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  .run(ZSink.foldUntil(0, 3)(_ + _))
// Output: 6, 15, 24, 10
```

**ZSink.foldLeft** — This sink will fold the inputs until the stream ends, resulting in one element:

```scala mdoc:silent:nest
val stream: ZIO[Any, Nothing, Int] = 
  ZStream(1, 2, 3, 4).run(ZSink.foldLeft[Int, Int](0)(_ + _))
// Output: 10
```

### From Effect

The `ZSink.fromEffect` creates a single-value sink produced from an effect:

```scala mdoc:silent:nest
val sink = ZSink.fromZIO(ZIO.succeed(1))
```

### From File

The `ZSink.fromPath` creates a file sink that consumes byte chunks and writes them to the specified file:

```scala mdoc:silent:nest
def fileSink(path: Path): ZSink[Any, Throwable, String, Byte, Long] =
  ZSink
    .fromPath(path)
    .contramapChunks[String](_.flatMap(_.getBytes))

val result = ZStream("Hello", "ZIO", "World!")
  .intersperse("\n")
  .run(fileSink(Paths.get("file.txt")))
```

### From OutputStream

The `ZSink.fromOutputStream` creates a sink that consumes byte chunks and write them to the `OutputStream`:

```scala mdoc:silent:nest
ZStream("Application", "Error", "Logs")
  .intersperse("\n")
  .run(
    ZSink
      .fromOutputStream(java.lang.System.err)
      .contramapChunks[String](_.flatMap(_.getBytes))
  )
```

### From Queue

A queue has a finite or infinite buffer size, so they are useful in situations where we need to consume streams as fast as we can, and then do some batching operations on consumed messages. By using `ZSink.fromQueue` we can create a sink that is backed by a queue; it enqueues each element into the specified queue:

```scala mdoc:silent:nest
val myApp: ZIO[Console with Clock, IOException, Unit] =
  for {
    queue    <- ZQueue.bounded[Int](32)
    producer <- ZStream
      .iterate(1)(_ + 1)
      .fixed(200.millis)
      .run(ZSink.fromQueue(queue))
      .fork
    consumer <- queue.take.flatMap(printLine(_)).forever
    _        <- producer.zip(consumer).join
  } yield ()
```

### From Hub

`Hub` is an asynchronous data type in which publisher can publish their messages to that and subscribers can subscribe to take messages from the `Hub`. The `ZSink.fromHub` takes a `ZHub` and returns a `ZSink` which publishes each element to that `ZHub`.

In the following example, the `sink` consumes elements of the `producer` stream and publishes them to the `hub`. We have two consumers that are subscribed to that hub and they are taking its elements forever:

```scala mdoc:silent:nest
val myApp: ZIO[Console with Clock, IOException, Unit] =
  for {
    promise <- Promise.make[Nothing, Unit]
    hub <- ZHub.bounded[Int](1)
    sink <- ZIO.succeed(ZSink.fromHub(hub))
    producer <- ZStream.iterate(0)(_ + 1).fixed(1.seconds).run(sink).fork
    consumers <- hub.subscribe.zip(hub.subscribe).use { case (left, right) =>
      for {
        _ <- promise.succeed(())
        f1 <- left.take.flatMap(e => printLine(s"Left Queue: $e")).forever.fork
        f2 <- right.take.flatMap(e => printLine(s"Right Queue: $e")).forever.fork
        _ <- f1.zip(f2).join
      } yield ()
    }.fork
    _ <- promise.await
    _ <- producer.zip(consumers).join
  } yield ()
```

## Operations

Having created the sink, we can transform it with provided operations.

### contramap

Contramap is a simple combinator to change the domain of an existing function. While _map_ changes the co-domain of a function, the _contramap_ changes the domain of a function. So the _contramap_ takes a function and maps over its input.

This is useful when we have a fixed output, and our existing function cannot consume those outputs. So we can use _contramap_ to create a new function that can consume that fixed output. Assume we have a `ZSink.sum` that sums incoming numeric values, but we have a `ZStream` of `String` values. We can convert the `ZSink.sum` to a sink that can consume `String` values;

```scala mdoc:silent:nest
val numericSum: ZSink[Any, Nothing, Int, Nothing, Int]    = 
  ZSink.sum[Int]
val stringSum : ZSink[Any, Nothing, String, Nothing, Int] = 
  numericSum.contramap((x: String) => x.toInt)

val sum: ZIO[Any, Nothing, Int] =
  ZStream("1", "2", "3", "4", "5").run(stringSum)
// Output: 15
```

### dimap

A `dimap` is an extended `contramap` that additionally transforms sink's output:

```scala mdoc:silent:nest
// Convert its input to integers, do the computation and then convert them back to a string
val sumSink: ZSink[Any, Nothing, String, Nothing, String] =
  numericSum.dimap[String, String](_.toInt, _.toString)
  
val sum: ZIO[Any, Nothing, String] =
  ZStream("1", "2", "3", "4", "5").run(sumSink)
// Output: 15
```


### Filtering

Sinks have `ZSink#filterInput` for filtering incoming elements:

```scala mdoc:silent:nest
ZStream(1, -2, 0, 1, 3, -3, 4, 2, 0, 1, -3, 1, 1, 6)
  .transduce(
    ZSink
      .collectAllN[Int](3)
      .filterInput[Int](_ > 0)
  )
// Output: Chunk(Chunk(1,1,3),Chunk(4,2,1),Chunk(1,1,6),Chunk())
```


## Concurrency and Parallelism

### Parallel Zipping

Like `ZStream`, two `ZSink` can be zipped together. Both of them will be run in parallel, and their results will be combined in a tuple:

```scala mdoc:invisible
case class Record()
```

```scala mdoc:silent:nest
val kafkaSink: ZSink[Any, Throwable, Record, Record, Unit] =
  ZSink.foreach[Any, Throwable, Record](record => ZIO.attempt(???))

val pulsarSink: ZSink[Any, Throwable, Record, Record, Unit] =
  ZSink.foreach[Any, Throwable, Record](record => ZIO.attempt(???))

val stream: ZSink[Any, Throwable, Record, Record, Unit] =
  kafkaSink zipPar pulsarSink 
```

### Racing

We are able to `race` multiple sinks, they will run in parallel, and the one that wins will provide the result of our program:

```scala mdoc:silent:nest
val stream: ZSink[Any, Throwable, Record, Record, Unit] =
  kafkaSink race pulsarSink 
```

To determine which one succeeded, we should use the `ZSink#raceBoth` combinator, it returns an `Either` result.

## Leftovers

### Exposing Leftovers

A sink consumes a variable amount of `I` elements (zero or more) from the upstream. If the upstream is finite, we can expose leftover values by calling `ZSink#exposeLeftOver`. It returns a tuple that contains the result of the previous sink and its leftovers:

```scala mdoc:silent:nest
val s1: ZIO[Any, Nothing, (Chunk[Int], Chunk[Int])] =
  ZStream(1, 2, 3, 4, 5).run(
    ZSink.take(3).exposeLeftover
  )
// Output: (Chunk(1, 2, 3), Chunk(4, 5))


val s2: ZIO[Any, Nothing, (Option[Int], Chunk[Int])] =
  ZStream(1, 2, 3, 4, 5).run(
    ZSink.head[Int].exposeLeftover
  )
// Output: (Some(1), Chunk(2, 3, 4, 5))
```

### Dropping Leftovers

If we don't need leftovers, we can drop them by using `ZSink#dropLeftover`:

```scala mdoc:silent:nest
ZSink.take[Int](3).dropLeftover
```
