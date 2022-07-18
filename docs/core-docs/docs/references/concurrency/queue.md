---
id: queue
title: "Queue"
---

`Queue` is a lightweight in-memory queue built on ZIO with composable and transparent back-pressure. It is fully asynchronous (no locks or blocking), purely-functional and type-safe.

A `Queue[A]` contains values of type `A` and has two basic operations: `offer`, which places an `A` in the `Queue`, and `take` which removes and returns the oldest value in the `Queue`.

```scala mdoc:silent
import zio._

val res: UIO[Int] = for {
  queue <- Queue.bounded[Int](100)
  _ <- queue.offer(1)
  v1 <- queue.take
} yield v1
```

## Creating a queue

A `Queue` can be bounded (with a limited capacity) or unbounded.

There are several strategies to process new values when the queue is full:

- The default `bounded` queue is back-pressured: when full, any offering fiber will be suspended until the queue is able to add the item
- A `dropping` queue will drop new items when the queue is full
- A `sliding` queue will drop old items when the queue is full

To create a back-pressured bounded queue:
```scala mdoc:silent
val boundedQueue: UIO[Queue[Int]] = Queue.bounded[Int](100)
```

To create a dropping queue:
```scala mdoc:silent
val droppingQueue: UIO[Queue[Int]] = Queue.dropping[Int](100)
```

To create a sliding queue:
```scala mdoc:silent
val slidingQueue: UIO[Queue[Int]] = Queue.sliding[Int](100)
```

To create an unbounded queue:
```scala mdoc:silent
val unboundedQueue: UIO[Queue[Int]] = Queue.unbounded[Int]
```

## Adding items to a queue

The simplest way to add a value to the queue is `offer`:

```scala mdoc:silent
val res1: UIO[Unit] = for {
  queue <- Queue.bounded[Int](100)
  _ <- queue.offer(1)
} yield ()
```

When using a back-pressured queue, offer might suspend if the queue is full: you can use `fork` to wait in a different fiber.

```scala mdoc:silent
val res2: UIO[Unit] = for {
  queue <- Queue.bounded[Int](1)
  _ <- queue.offer(1)
  f <- queue.offer(1).fork // will be suspended because the queue is full
  _ <- queue.take
  _ <- f.join
} yield ()
```

It is also possible to add multiple values at once with `offerAll`:

```scala mdoc:silent
val res3: UIO[Unit] = for {
  queue <- Queue.bounded[Int](100)
  items = Range.inclusive(1, 10).toList
  _ <- queue.offerAll(items)
} yield ()
```

## Consuming Items from a Queue

The `take` operation removes the oldest item from the queue and returns it. If the queue is empty, this will suspend, and resume only when an item has been added to the queue. As with `offer`, you can use `fork` to wait for the value in a different fiber.

```scala mdoc:silent
val oldestItem: UIO[String] = for {
  queue <- Queue.bounded[String](100)
  f <- queue.take.fork // will be suspended because the queue is empty
  _ <- queue.offer("something")
  v <- f.join
} yield v
```

You can consume the first item with `poll`. If the queue is empty you will get `None`, otherwise the top item will be returned wrapped in `Some`.

```scala mdoc:silent
val polled: UIO[Option[Int]] = for {
  queue <- Queue.bounded[Int](100)
  _ <- queue.offer(10)
  _ <- queue.offer(20)
  head <- queue.poll
} yield head
```

You can consume multiple items at once with `takeUpTo`. If the queue doesn't have enough items to return, it will return all the items without waiting for more offers.

```scala mdoc:silent
val taken: UIO[Chunk[Int]] = for {
  queue <- Queue.bounded[Int](100)
  _ <- queue.offer(10)
  _ <- queue.offer(20)
  chunk  <- queue.takeUpTo(5)
} yield chunk
```

Similarly, you can get all items at once with `takeAll`. It also returns without waiting (an empty collection if the queue is empty).

```scala mdoc:silent
val all: UIO[Chunk[Int]] = for {
  queue <- Queue.bounded[Int](100)
  _ <- queue.offer(10)
  _ <- queue.offer(20)
  chunk  <- queue.takeAll
} yield chunk
```

## Shutting Down a Queue

It is possible with `shutdown` to interrupt all the fibers that are suspended on `offer*` or `take*`. It will also empty the queue and make all future calls to `offer*` and `take*` terminate immediately.

```scala mdoc:silent
val takeFromShutdownQueue: UIO[Unit] = for {
  queue <- Queue.bounded[Int](3)
  f <- queue.take.fork
  _ <- queue.shutdown // will interrupt f
  _ <- f.join // Will terminate
} yield ()
```

You can use `awaitShutdown` to execute an effect when the queue is shut down. This will wait until the queue is shut down. If the queue is already shut down, it will resume right away.

```scala mdoc:silent
val awaitShutdown: UIO[Unit] = for {
  queue <- Queue.bounded[Int](3)
  p <- Promise.make[Nothing, Boolean]
  f <- queue.awaitShutdown.fork
  _ <- queue.shutdown
  _ <- f.join
} yield ()
```

## Additional Resources

- [ZIO Queue Talk by John De Goes @ ScalaWave 2018](https://www.slideshare.net/jdegoes/zio-queue)
- [ZIO Queue Talk by Wiem Zine El Abidine @ PSUG 2018](https://www.slideshare.net/wiemzin/psug-zio-queue)
- [Elevator Control System using ZIO](https://medium.com/@wiemzin/elevator-control-system-using-zio-c718ae423c58)
- [Scalaz 8 IO vs Akka (typed) actors vs Monix](https://blog.softwaremill.com/scalaz-8-io-vs-akka-typed-actors-vs-monix-part-1-5672657169e1)
