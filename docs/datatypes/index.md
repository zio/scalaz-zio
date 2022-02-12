---
id: index
title: "Introduction"
---

ZIO contains a few data types that can help you solve complex problems in asynchronous and concurrent programming. ZIO data types categorize into these sections:

1. [Core Data Types](#core-data-types)
2. [Contextual Data Types](#contextual-data-types)
3. [Concurrency](#concurrency)
   - [Fiber Primitives](#fiber-primitives)
   - [Concurrency Primitives](#concurrency-primitives)
   - [STM](#stm)
3. [Resource Management](#resource-management)
6. [Streaming](#streaming)
7. [Miscellaneous](#miscellaneous)

## Core Data Types
 - **[ZIO](core/zio/zio.md)** — `ZIO` is a value that models an effectful program, which might fail or succeed.
   + **[UIO](core/zio/uio.md)** — `UIO[A]` is a type alias for `ZIO[Any, Nothing, A]`.
   + **[URIO](core/zio/urio.md)** — `URIO[R, A]` is a type alias for `ZIO[R, Nothing, A]`.
   + **[Task](core/zio/task.md)** — `Task[A]` is a type alias for `ZIO[Any, Throwable, A]`.
   + **[RIO](core/zio/rio.md)** — `RIO[R, A]` is a type alias for `ZIO[R, Throwable, A]`.
   + **[IO](core/zio/io.md)** — `IO[E, A]` is a type alias for `ZIO[Any, E, A]`.
 - **[ZIOApp](core/zioapp.md)** — `ZIOApp` and the `ZIOAppDefault` are entry points for ZIO applications.
 - **[Runtime](core/runtime.md)** — `Runtime[R]` is capable of executing tasks within an environment `R`.
 - **[Exit](core/exit.md)** — `Exit[E, A]` describes the result of executing an `IO` value.
 - **[Cause](core/cause.md)** - `Cause[E]` is a description of a full story of a fiber failure. 

## Contextual Data Types
- **[ZEnvironment](contextual/zenvironment.md)** — `ZEnvironment[R]` is a built-in type-level map for the `ZIO` data type which is responsible for maintaining the environment of a `ZIO` effect.
- **[ZLayer](contextual/zlayer.md)** — `ZIO[-R, +E, +A]` data type describes an effect that requires an input type of `R`, as an environment, may fail with an error of type `E` or succeed and produces a value of type `A`.
    + **[RLayer](contextual/rlayer.md)** — `RLayer[-RIn, +ROut]` is a type alias for `ZLayer[RIn, Throwable, ROut]`, which represents a layer that requires `RIn` as its input, may fail with `Throwable` value, or returns `ROut` as its output.
    + **[ULayer](contextual/ulayer.md)** — `ULayer[+ROut]` is a type alias for `ZLayer[Any, Nothing, ROut]`, which represents a layer that doesn't require any services as its input, can't fail, and returns ROut as its output.
    + **[Layer](contextual/layer.md)** — `Layer[+E, +ROut]` is a type alias for `ZLayer[Any, E, ROut]`, which represents a layer that doesn't require any services, may fail with an error type of E, and returns ROut as its output.
    + **[URLayer](contextual/urlayer.md)** — `URLayer[-RIn, +ROut]` is a type alias for `ZLayer[RIn, Nothing, ROut]`, which represents a set of services that requires RIn as its input, can't fail, and returns ROut as its output.
    + **[TaskLayer](contextual/task-layer.md)** — `TaskLayer[+ROut]` is a type alias for `ZLayer[Any, Throwable, ROut]`, which represents a set of services that doesn't require any services as its input, may fail with Throwable value, and returns ROut as its output.

## Concurrency

### Fiber Primitives
 - **[Fiber](fiber/fiber.md)** — A fiber value models an `IO` value that has started running, and is the moral equivalent of a green thread.
 - **[FiberRef](fiber/fiberref.md)** — `FiberRef[A]` models a mutable reference to a value of type `A`. As opposed to `Ref[A]`, a value is bound to an executing `Fiber` only.  You can think of it as Java's `ThreadLocal` on steroids.
 - **[Fiber.Status](fiber/fiberstatus.md)** — `Fiber.Status` describe the current status of a Fiber.
 - **[FiberId](fiber/fiberid.md)** — `FiberId` describe the unique identity of a Fiber.
 
### Concurrency Primitives
- **[ZRef](concurrency/zref.md)** — `ZRef[EA, EB, A, B]` is a polymorphic, purely functional description of a mutable reference. The fundamental operations of a `ZRef` are `set` and `get`.
    + **[Ref](concurrency/ref.md)** — `Ref[A]` models a mutable reference to a value of type `A`. The two basic operations are `set`, which fills the `Ref` with a new value, and `get`, which retrieves its current content. All operations on a `Ref` are atomic and thread-safe, providing a reliable foundation for synchronizing concurrent programs.
- **[ZRef.Synchronized](concurrency/zrefsynchronized.md)** — `ZRef.Synchronized[RA, RB, EA, EB, A, B]` is a polymorphic, purely functional description of a mutable reference.
    + **[Ref.Synchronized](concurrency/refsynchronized.md)** — `Ref.Synchronized[A]` models a **mutable reference** to a value of type `A` in which we can store **immutable** data, and update it atomically **and** effectfully.
- **[Promise](concurrency/promise.md)** — `Promise` is a model of a variable that may be set a single time, and awaited on by many fibers.
- **[Queue](concurrency/queue.md)** — `Queue` is an asynchronous queue that never blocks, which is safe for multiple concurrent producers and consumers.
 - **[Hub](concurrency/hub.md)** - `Hub` is an asynchronous message hub that allows publishers to efficiently broadcast values to many subscribers.
- **[Semaphore](concurrency/semaphore.md)** — `Semaphore` is an asynchronous (non-blocking) semaphore that plays well with ZIO's interruption.

### STM
 - **[STM](stm/stm.md)** - `STM` represents an effect that can be performed transactionally resulting in a failure or success.
 - **[TArray](stm/tarray.md)** - `TArray` is an array of mutable references that can participate in transactions.
 - **[TSet](stm/tset.md)** - `TSet` is a mutable set that can participate in transactions.
 - **[TMap](stm/tmap.md)** - `TMap` is a mutable map that can participate in transactions.
 - **[TRef](stm/tref.md)** - `TRef` is a mutable reference to an immutable value that can participate in transactions.
 - **[TPriorityQueue](stm/tpriorityqueue.md)** - `TPriorityQueue` is a mutable priority queue that can participate in transactions.
 - **[TPromise](stm/tpromise.md)** - `TPromise` is a mutable reference that can be set exactly once and can participate in transactions.
 - **[TQueue](stm/tqueue.md)** - `TQueue` is a mutable queue that can participate in transactions.
 - **[TReentrantLock](stm/treentrantlock.md)** - `TReentrantLock` is a reentrant read / write lock that can be composed.
 - **[TSemaphore](stm/tsemaphore.md)** - `TSemaphore` is a semaphore that can participate in transactions.
 
## Resource Management
- **[ZManaged](resource/zmanaged.md)** — `ZManaged` is a value that describes a perishable resource that may be consumed only once inside a given scope.
    - **[Managed](resource/managed.md)** — `Managed[E, A]` is a type alias for `ZManaged[Any, E, A]`.
    - **[TaskManaged](resource/task-managed.md)** — `TaskManaged[A]` is a type alias for `ZManaged[Any, Throwable, A]`.
    - **[RManaged](resource/rmanaged.md)** — `RManaged[R, A]` is a type alias for `ZManaged[R, Throwable, A]`.
    - **[UManaged](resource/umanaged.md)** — `UManaged[A]` is a type alias for `ZManaged[Any, Nothing, A]`.
    - **[URManaged](resource/urmanaged.md)** — `URManaged[R, A]` is a type alias for `ZManaged[R, Nothing, A]`.
- **[ZPool](resource/zpool.md)** — An asynchronous and concurrent generalized pool of reusable managed resources.

## Streaming
- **[ZStream](stream/zstream.md)** — `ZStream` is a lazy, concurrent, asynchronous source of values.
   + **[Stream](stream/stream.md)** — `Stream[E, A]` is a type alias for `ZStream[Any, E, A]`, which represents a ZIO stream that does not require any services, and may fail with an `E`, or produce elements with an `A`. 
- **[ZSink](stream/zsink.md)** — `ZSink` is a consumer of values from a `ZStream`, which may produces a value when it has consumed enough.
   + **[Sink](stream/sink.md)** — `Sink[InErr, A, OutErr, L, B]` is a type alias for `ZSink[Any, InErr, A, OutErr, L, B]`.
- **[ZPipeline](stream/zpipeline.md)** - `ZPipeline` is a polymorphic stream transformer.
- **[SubscriptionRef](stream/subscriptionref.md)** — `SubscriptionRef[A]` contains a current value of type `A` and a stream that can be consumed to observe all changes to that value.
 
## Miscellaneous
- **[Chunk](misc/chunk.md)** — `Chunk` is a fast, pure alternative to Arrays.
- **[Schedule](misc/schedule.md)** — `Schedule` is a model of a recurring schedule, which can be used for repeating successful `IO` values, or retrying failed `IO` values.
- **[Supervisor](misc/supervisor.md)** — `Supervisor[A]` is allowed to supervise the launching and termination of fibers, producing some visible value of type `A` from the supervision.

To learn more about these data types, please explore the pages above, or check out the Scaladoc documentation.
