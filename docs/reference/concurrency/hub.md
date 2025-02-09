---
id: hub
title:  "Hub"
---

A `Hub` is an asynchronous message hub. Publishers can publish messages to the hub and subscribers can subscribe to receive messagesfrom the hub.

Unlike a `Queue`, where each value offered to the queue can be taken by _one_ taker, each value published to a hub can be received by _all_ subscribers. Whereas a `Queue` represents the optimal solution to the problem of how to _distribute_ values, a `Hub` represents the optimal solution to the problem of how to _broadcast_ them.

The fundamental operators on a `Hub` are `publish` and `subscribe`:

```scala mdoc
import zio._

trait Hub[A] {
  def publish(a: A): UIO[Boolean]
  def subscribe: ZIO[Scope, Nothing, Dequeue[A]]
}
```

The `publish` operator returns a `ZIO` effect that publishes a message of type `A` to the hub and succeeds with a value describing whether the message was successfully published to the hub.

The `subscribe` operator returns a scoped `ZIO` effect that subscribes to the hub and unsubscribes from the hub when the scope is closed. Within the scope we have access to a `Dequeue`, which is a `Queue` that can only be dequeued from, that allows us to take messages published to the hub.

For example, we can use a hub to broadcast a message to multiple subscribers like this:

```scala mdoc:silent
Hub.bounded[String](2).flatMap { hub =>
  ZIO.scoped {
    hub.subscribe.zip(hub.subscribe).flatMap { case (left, right) =>
      for {
        _ <- hub.publish("Hello from a hub!")
        _ <- left.take.flatMap(Console.printLine(_))
        _ <- right.take.flatMap(Console.printLine(_))
      } yield ()
    }
  }
}
```

A subscriber will only receive messages that are published to the hub while it is subscribed. So if we want to make sure that a particular message is received by a subscriber we must take care that the subscription has completed before publishing the message to the hub.

We can do this by publishing a message to the hub within the scope of the subscription as in the example above or by using other coordination mechanisms such as completing a `Promise`  when scope has been opened.

Of course, in many cases such as subscribing to receive real time data we may not care about this because we are happy to just pick up with the most recent messages after we have subscribed. But for testing and simple applications this can be an important point to keep in mind.

## Constructing Hubs

The most common way to create a hub is with the `bounded` constructor, which returns an effect that creates a new hub with the specified requested capacity.

```scala mdoc
def bounded[A](requestedCapacity: Int): UIO[Hub[A]] =
  ???
```

For maximum efficiency you should create hubs with capacities that are powers of two.

Just like a bounded queue, a bounded hub applies back pressure to publishers when it is at capacity, so publishers will semantically block on calls to `publish` if the hub is full.

The advantage of the back pressure strategy is that it guarantees that all subscribers will receive all messages published to the hub while they are subscribed. However, it does create the risk that a slow subscriber will slow down the rate at which messages are published and received by other subscribers.

If you do not want this you can create a hub with the `dropping` constructor.

```scala mdoc
def dropping[A](requestedCapacity: Int): UIO[Hub[A]] =
  ???
```

A dropping hub will simply drop values published to it if the hub is at capacity, returning `false` on calls to `publish` if the hub is full to signal that the value was not successfully published.

The advantage of the dropping strategy is that publishers can continue to publish new values so when there is space in the hub the newest values can be published to the hub. However, subscribers are no longer guaranteed to receive all values published to the hub and a slow subscriber can still prevent messages from being published to the hub and received by other subscribers.

You can also create a hub with the `sliding` constructor.

```scala mdoc
def sliding[A](requestedCapacity: Int): UIO[Hub[A]] =
  ???
```

A sliding hub will drop the oldest value if a new value is published to it and the hub is at capacity, so publishing will always succeed immediately.

The advantage of the sliding strategy is that a slow subscriber cannot slow down that rate at which messages are published to the hub or received by other subscribers. However, it creates the risk that slow subscribers may not receive all messages published to the hub.

Finally, you can create a hub with the `unbounded` constructor.

```scala mdoc
def unbounded[A]: UIO[Hub[A]] =
  ???
```

An unbounded hub is never at capacity so publishing to an unbounded hub always immediately succeeds.

The advantage of an unbounded hub is that it combines the guarantees that all subscribers will receive all messages published to the hub and that a slow subscriber will not slow down the rate at which messages are published and received by other subscribers. However, it does this at the cost of potentially growing without bound if messages are published to the hub more quickly than they are taken by the slowest subscriber.

In general you should prefer bounded, dropping, or sliding hubs for this reason. However, unbounded hubs can be useful in certain situations where you do not know exactly how many values will be published to the hub but are confident that it will not exceed a reasonable size or want to handle that concern at a higher level of your application.

## Operators On Hubs

In addition to `publish` and `subscribe`, many of the same operators that are available on queues are available on hubs.

We can publish multiple values to the hub using the `publishAll` operator.

```scala mdoc:nest
trait Hub[A] {
  def publishAll(as: Iterable[A]): UIO[Boolean]
}
```

We can check the capacity of the hub as well as the number of messages currently in the hub using the `size` and `capacity` operators.

```scala mdoc:nest
trait Hub[A] {
  def capacity: Int
  def size: UIO[Int]
}
```

Note that `capacity` returns an `Int` because the capacity is set at hub creation and never changes. In contrast, `size` returns a `ZIO` effect that determines the current size of the hub since the number of messages in the hub can change over time.

We can also shut down the hub, check whether it has been shut down, or await its shut down. Shutting down a hub will shut down all the queues associated with subscriptions to the hub, properly propagating the shut down signal.

```scala mdoc:nest
trait Hub[A] {
  def awaitShutdown: UIO[Unit]
  def isShutdown: UIO[Boolean]
  def shutdown: UIO[Unit]
}
```

As you can see, the operators on `Hub` are identical to the ones on `Queue` with the exception of `publish` and `subscribe` replacing `offer` and `take`. So if you know how to use a `Queue` you already know how to use a `Hub`.

In fact, a `Hub` can be viewed as a `Queue` that can only be written to.

```scala mdoc:nest
trait Hub[A] extends Enqueue[A]
```

Here the `Enqueue` type represents a queue that can only be enqueued. Enqueing to the queue publishes a value to the hub, shutting down the queue shuts down the hub, and so on.

This can be extremely useful because it allows us to use a `Hub` anywhere we are currently using a `Queue` that we only write to.

For example, say we are using the `into` operator on `ZStream` to send all elements of a stream of financial transactions to a `Queue` for processing by a downstream consumer.

```scala mdoc
import zio.stream._

trait ZStream[-R, +E, +O] {
  def into(
    queue: Enqueue[Take[E, O]]
  ): ZIO[R, E, Unit]
}
```

We would now like to have multiple downstream consumers process each of these transactions, for example to persist them and log them in addition to applying our business logic to them. With `Hub` this is easy because we can just use the `toQueue` operator to view any `Hub` as a `Queue` that can only be written to.

```scala mdoc:invisible
type ??? = Nothing
```

```scala mdoc:compile-only
type Transaction = ???

val transactionStream: ZStream[Any, Nothing, Transaction] =
  ???

val hub: Hub[Take[Nothing, Transaction]] =
  ???

transactionStream.into(hub)
```

All of the elements from the transaction stream will now be published to the hub. We can now have multiple downstream consumers process elements from the financial transactions stream with the guarantee that all downstream consumers will see all transactions in the stream, changing the topology of our data flow from one-to-one to one-to-many with a single line change.

## Hubs And Streams

Hubs play extremely well with streams.

We can create a `ZStream` from a subscription to a hub using the `fromHub` operator.

```scala mdoc
import zio.stream._

object ZStream {
  def fromHub[O](hub: Hub[O]): ZStream[Any, Nothing, O] =
    ???
}
```

This will return a stream that subscribes to receive values from a hub and then emits every value published to the hub while the subscription is active. When the stream ends the subscriber will automatically be unsubscribed from the hub.

There is also a `fromHubScoped` operator that returns the stream in the context of a scoped effect.

```scala mdoc:nest
object ZStream {
  def fromHubScoped[O](
    hub: Hub[O]
  ): ZIO[Scope, Nothing, ZStream[Any, Nothing, O]] =
    ???
}
```

The scoped effect here describes subscribing to receive messages from the hub while the stream describes taking messages from the hub. This can be useful when we need to ensure that a consumer has subscribed before a producer begins publishing values.

Here is an example of using it:

```scala mdoc:reset:invisible
import zio._
import zio.stream._
```

```scala mdoc:silent
for {
  promise <- Promise.make[Nothing, Unit]
  hub     <- Hub.bounded[String](2)
  scoped  = ZStream.fromHubScoped(hub).tap(_ => promise.succeed(()))
  stream   = ZStream.unwrapScoped(scoped)
  fiber   <- stream.take(2).runCollect.fork
  _       <- promise.await
  _       <- hub.publish("Hello")
  _       <- hub.publish("World")
  _       <- fiber.join
} yield ()
```

Notice that in this case we used a `Promise` to ensure that the subscription had completed before publishing to the hub. The scoped `ZIO` in the return type of `fromHubScoped` made it easy for us to signal when the subscription had occurred by using `tap` and completing the `Promise`.

Of course in many real applications we don't need this kind of sequencing and just want to subscribe to receive new messages. In this case we can use the `fromHub` operator to return a `ZStream` that will automatically handle subscribing and unsubscribing for us.

There is also a `fromHubWithShutdown` variant that shuts down the hub itself when the stream ends. This is useful when the stream represents your main application logic and you want to shut down other subscriptions to the hub when the stream ends.

Each of these constructors also has `Chunk` variants, `fromChunkHub` and `fromChunkHubWithShutdown`, that allow you to preserve the chunked structure of data when working with hubs and streams.

In addition to being able to create streams from subscriptions to hubs, there are a variety of ways to send values emitted by streams to hubs to build more complex data flow graphs.

The simplest of these is the `toHub` operator, which constructs a new hub and publishes each element emitted by the stream to that hub.

```scala mdoc
trait ZStream[-R, +E, +O] {
  def toHub[E1 >: E, O1 >: O](
    capacity: Int
  ): ZIO[R with Scope, Nothing, Hub[Take[E1, O1]]]
}
```

The hub will be constructed with the `bounded` constructor using the specified capacity.

If you want to send values emitted by a stream to an existing hub or a hub created using one of the other hub constructors you can use the `runIntoHub` operator.

```scala mdoc:nest
trait ZStream[-R, +E, +O] {
  def runIntoHub[E1 >: E, O1 >: O](
    hub: => Hub[Take[E1, O1]]
  ): ZIO[R, E1, Unit]
}
```

There is an `runIntoHubScoped` variant of this if you want to send values to the hub in the context of a `Scope`.

Here is the example above adapted to publish values from a stream to the hub:

```scala mdoc:silent
for {
  promise <- Promise.make[Nothing, Unit]
  hub     <- Hub.bounded[Take[Nothing, String]](2)
  scoped  = ZStream.fromHubScoped(hub).tap(_ => promise.succeed(()))
  stream   = ZStream.unwrapScoped(scoped).flattenTake
  fiber   <- stream.take(2).runCollect.fork
  _       <- promise.await
  _       <- ZStream("Hello", "World").runIntoHub(hub)
  _       <- fiber.join
} yield ()
```

Notice that we created a `Hub` of `Take` values this time. `Take` is an algebraic data type that represents the different potential results of pulling from a stream, including the stream emitting a chunk of values, failing with an error, or being done.

Here we automatically unwrapped the `Take` values using the `flattenTake` operator on `ZStream`. In other cases where the subscriber was not a `ZStream` the `Take` value would allow the subscriber to observe whether the stream had emitted a value, failed with an error, or ended, and handle it appropriately.

You can also create a sink that sends values to a hub.

```scala mdoc
object ZSink {
  def fromHub[I](
    hub: Hub[I]
  ): ZSink[Any, Nothing, I, Nothing, Unit] =
    ???
}
```

The sink will publish each value sent to the sink to the specified hub. Again there is a `fromHubWithShutdown` variant that will shut down the hub when the stream ends.

Finally, `Hub` is used internally to provide a highly efficient implementation of the `broadcast` family of operators, including `broadcast` and `broadcastDynamic`.

```scala mdoc:nest
trait ZStream[-R, +E, +O] {
  def broadcast(
    n: Int,
    maximumLag: Int
  ): ZIO[R with Scope, Nothing, List[ZStream[Any, E, O]]]
  def broadcastDynamic(
    maximumLag: Int
  ): ZIO[R with Scope, Nothing, ZIO[Scope, Nothing, ZStream[Any, E, O]]]
}
```

The `broadcast` operator generates the specified number of new streams and broadcasts each value from the original stream to each of the new streams. The `broadcastDynamic` operator returns a new `ZIO` value that you can use to dynamically subscribe and unsubscribe to receive values broadcast from the original stream.

You don't have to do anything with `Hub` to take advantage of these operators other than enjoy their optimized implementation in terms of `Hub`.

With `broadcast` and other `ZStream` operators that model distributing values to different streams and combining values from different streams it is straightforward to build complex data flow graphs, all while being as performant as possible.
