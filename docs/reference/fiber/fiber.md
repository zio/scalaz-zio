---
id: fiber
slug: fiber.md
title: "Fiber"
---

To perform an effect without blocking the current process, we can use fibers, which are a lightweight concurrency mechanism.

We can `fork` any `ZIO[R, E, A]` to immediately yield an `URIO[R, Fiber[E, A]]`. The provided `Fiber` can be used to `join` the fiber, which will resume on production of the fiber's value, or to `interrupt` the fiber, which immediately terminates the fiber and safely releases all resources acquired by the fiber.

```scala mdoc:invisible
import zio._
```
```scala mdoc:invisible
sealed trait Analysis
case object Analyzed extends Analysis

val data: String = "tut"

def analyzeData[A](data: A): UIO[Analysis] = ZIO.succeed(Analyzed)
def validateData[A](data: A): UIO[Boolean] = ZIO.succeed(true)
```

```scala mdoc:silent
val analyzed =
  for {
    fiber1   <- analyzeData(data).fork  // IO[E, Analysis]
    fiber2   <- validateData(data).fork // IO[E, Boolean]
    // Do other stuff
    valid    <- fiber2.join
    _        <- if (!valid) fiber1.interrupt
                else ZIO.unit
    analyzed <- fiber1.join
  } yield analyzed
```

## Operations

### fork and join
Whenever we need to start a fiber, we have to `fork` an effect to get a new fiber. This is similar to the `start` method on Java thread or submitting a new thread to the thread pool in Java, it is the same idea. Also, joining is a way of waiting for that fiber to compute its value. We are going to wait until it's done and receive its result.

In the following example, we create a separate fiber to output a delayed print message and then wait for that fiber to succeed with a value:

```scala mdoc:silent
import zio._
import zio.Console._
for {
  fiber <- (ZIO.sleep(3.seconds) *>
    printLine("Hello, after 3 second") *>
    ZIO.succeed(10)).fork
  _ <- printLine(s"Hello, World!")
  res <- fiber.join
  _ <- printLine(s"Our fiber succeeded with $res")
} yield ()
```

### forkDaemon

The `ZIO#forkDaemon` forks the effect into a new fiber **attached to the global scope**. Because the new fiber is attached to the global scope, when the fiber executing the returned effect terminates, the forked fiber will continue running.

In the following example, we have three effects: `inner`, `outer`, and `mainApp`. The outer effect is forking the `inner` effect using `ZIO#forkDaemon`. The `mainApp` effect is forking the `outer` fiber using `ZIO#fork` method and interrupt it after 3 seconds. Since the `inner` effect is forked in global scope, it will not be interrupted and continue its job:

```scala mdoc:silent:nest
val inner = printLine("Inner job is running.")
  .delay(1.seconds)
  .forever
  .onInterrupt(printLine("Inner job interrupted.").orDie)

val outer = (
  for {
    f <- inner.forkDaemon
    _ <- printLine("Outer job is running.").delay(1.seconds).forever
    _ <- f.join
  } yield ()
).onInterrupt(printLine("Outer job interrupted.").orDie)

val mainApp = for {
  fiber <- outer.fork
  _     <- fiber.interrupt.delay(3.seconds)
  _     <- ZIO.never
} yield ()
```

### interrupt
Whenever we want to get rid of our fiber, we can simply call `interrupt` on that. The interrupt operation does not resume until the fiber has completed or has been interrupted and all its finalizers have been run. These precise semantics allow construction of programs that do not leak resources.

### await
To inspect whether our fiber succeeded or failed, we can call `await` on that fiber. This call will wait for that fiber to terminate, and it will give us back the fiber's value as an `Exit`. That exit value could be failure or success:

```scala mdoc:silent
import zio.Console._

for {
  b <- Random.nextBoolean
  fiber <- (if (b) ZIO.succeed(10) else ZIO.fail("The boolean was not true")).fork
  exitValue <- fiber.await
  _ <- exitValue match {
    case Exit.Success(value) => printLine(s"Fiber succeeded with $value")
    case Exit.Failure(cause) => printLine(s"Fiber failed")
  }
} yield ()
```

`await` is similar to `join`, but they react differently to errors and interruption: `await` always succeeds with `Exit` information, even if the fiber fails or is interrupted. In contrast to that, `join` on a fiber that fails will itself fail with the same error as the fiber, and `join` on a fiber that is interrupted will itself become interrupted.

### Parallelism
To execute actions in parallel, the `zipPar` method can be used:

```scala mdoc:invisible
case class Matrix()
def computeInverse(m: Matrix): UIO[Matrix] = ZIO.succeed(m)
def applyMatrices(m1: Matrix, m2: Matrix, m3: Matrix): UIO[Matrix] = ZIO.succeed(m1)
```

```scala mdoc:silent
def bigCompute(m1: Matrix, m2: Matrix, v: Matrix): UIO[Matrix] =
  for {
    t <- computeInverse(m1).zipPar(computeInverse(m2))
    (i1, i2) = t
    r <- applyMatrices(i1, i2, v)
  } yield r
```

The `zipPar` combinator has resource-safe semantics. If one computation fails, the other computation will be interrupted, to prevent wasting resources.

### Racing

Two actions can be *raced*, which means they will be executed in parallel, and the value of the first action that completes successfully will be returned.

```scala
fib(100) race fib(200)
```

The `race` combinator is resource-safe, which means that if one of the two actions returns a value, the other one will be interrupted, to prevent wasting resources.

The `race` and even `zipPar` combinators are a specialization of a much-more powerful combinator called `raceWith`, which allows executing user-defined logic when the first of two actions succeeds.

On the JVM, fibers will use threads, but will not consume *unlimited* threads. Instead, fibers yield cooperatively during periods of high-contention.

```scala mdoc:silent
def fib(n: Int): UIO[Int] =
  if (n <= 1) {
    ZIO.succeed(1)
  } else {
    for {
      fiber1 <- fib(n - 2).fork
      fiber2 <- fib(n - 1).fork
      v2     <- fiber2.join
      v1     <- fiber1.join
    } yield v1 + v2
  }
```

## Error Model
The `ZIO` error model is simple, consistent, permits both typed errors and termination, and does not violate any laws in the `Functor` hierarchy.

A `ZIO[R, E, A]` value may only raise errors of type `E`. These errors are recoverable by using the `either` method.  The resulting effect cannot fail, because the failure case has been exposed as part of the `Either` success case.  

```scala mdoc:silent
val error: Task[String] = ZIO.fail(new RuntimeException("Some Error"))
val errorEither: ZIO[Any, Nothing, Either[Throwable, String]] = error.either
```

Separately from errors of type `E`, a fiber may be terminated for the following reasons:

* **The fiber self-terminated or was interrupted by another fiber**. The "main" fiber cannot be interrupted because it was not forked from any other fiber.

* **The fiber failed to handle some error of type `E`**. This can happen only when an `ZIO.fail` is not handled. For values of type `UIO[A]`, this type of failure is impossible.

* **The fiber has a defect that leads to a non-recoverable error**. There are only two ways this can happen:

     1. A partial function is passed to a higher-order function such as `map` or `flatMap`. For example, `io.map(_ => throw e)`, or `io.flatMap(a => throw e)`. The solution to this problem is to not to pass impure functions to purely functional libraries like ZIO, because doing so leads to violations of laws and destruction of equational reasoning.
     2. Error-throwing code was embedded into some value via `ZIO.succeed`, etc. For importing partial effects into `ZIO`, the proper solution is to use a method such as `ZIO.attempt`, which safely translates exceptions into values.

When a fiber is terminated, the reason for the termination, expressed as a `Throwable`, is passed to the fiber's supervisor, which may choose to log, print the stack trace, restart the fiber, or perform some other action appropriate to the context.

A fiber cannot stop its own interruption. However, all finalizers will be run during termination, even when some finalizers throw non-recoverable errors. Errors thrown by finalizers are passed to the fiber's supervisor.

There are no circumstances in which any errors will be "lost", which makes the `ZIO` error model more diagnostic-friendly than the `try`/`catch`/`finally` construct that is baked into both Scala and Java, which can easily lose errors.

## Fiber Interruption

In Java, a thread can be interrupted via `Thread#interrupt` from another thread, but it may refuse the interruption request and continue processing. Unlike Java, in ZIO when a fiber interrupts another fiber, we know that the interruption occurs, and it always works.

When working with ZIO fibers, we should consider these notes about fiber interruptions:

### Interruptible/Uninterruptible Regions

All fibers are interruptible by default. To make an effect uninterruptible we can use `Fiber#uninterruptible`, `ZIO#uninterruptible` or `ZIO.uninterruptible`. ZIO provides also the reverse direction of these methods to make an uninterruptible effect interruptible.

```scala mdoc:silent:nest
for {
  fiber <- Clock.currentDateTime
    .flatMap(time => printLine(time))
    .schedule(Schedule.fixed(1.seconds))
    .uninterruptible
    .fork
  _     <- fiber.interrupt // Runtime stuck here and does not go further
} yield ()
```

Note that there is no way to stop interruption. We can only delay it, by making an effect uninterruptible.

### Fiber Finalization on Interruption

When a fiber has done its work or has been interrupted, the finalizer of that fiber is guaranteed to be executed:

```scala mdoc:silent:nest
for {
  fiber <- printLine("Working on the first job")
    .schedule(Schedule.fixed(1.seconds))
    .ensuring {
      (printLine(
        "Finalizing or releasing a resource that is time-consuming"
      ) *> ZIO.sleep(7.seconds)).orDie
    }
    .fork
  _     <- fiber.interrupt.delay(4.seconds)
  _     <- printLine(
          "Starting another task when the interruption of the previous task finished"
        )
} yield ()
```

In the above example, the release action takes some time for freeing up resources. So it slows down the call to `interrupt` the fiber.

### Fast Interruption

As we saw in the previous section, the ZIO runtime gets stuck on interruption until the fiber's finalizer finishes its job. We can prevent this behavior by using `ZIO#disconnect` or `Fiber#interruptFork` which perform fiber's interruption in the background or in separate daemon fiber:

Let's try the `Fiber#interruptFork`:

```scala mdoc:silent:nest
for {
  fiber <- printLine("Working on the first job")
    .schedule(Schedule.fixed(1.seconds))
    .ensuring {
      (printLine(
        "Finalizing or releasing a resource that is time-consuming"
      ) *> ZIO.sleep(7.seconds)).orDie
    }
    .fork
  _ <- fiber.interruptFork.delay(4.seconds) // fast interruption
  _ <- printLine(
    "Starting another task while interruption of the previous fiber happening in the background"
  )
} yield ()
```

### Interrupting Blocking Operations

The `ZIO#attemptBlocking` is interruptible by default, but its interruption will not translate to JVM thread interruption. Instead, we can use `ZIO#attemptBlockingInterrupt` to translate the ZIO interruption of that effect into JVM thread interruption. For details and examples on interrupting blocking operations see [here](../core/zio/zio.md#blocking-operations).

### Automatic Interruption

If we never _cancel_ a running effect explicitly, ZIO performs **automatic interruption** for several reasons:

1. **Structured Concurrency** — If a parent fiber terminates, then by default, all child fibers are interrupted, and they cannot outlive their parent. We can prevent this behavior by using `ZIO#forkDaemon` or `ZIO#forkIn` instead of `ZIO#fork`.

2. **Parallelism** — If one effect fails during the execution of many effects in parallel, the others will be canceled. Examples include `foreachPar`, `zipPar`, and all other parallel operators.

3. **Timeouts** — If a running effect with a `timeout` has not been completed in the specified amount of time, then the execution is canceled.

4. **Racing** — The loser of a `race`, if still running, is canceled.

### Joining an Interrupted Fiber

We can join an interrupted fiber, which will cause our fiber to become interrupted. This process preserves the proper finalization of the caller. So, **ZIO's concurrency model respects brackets even when we _join_ an interrupted fiber**:

```scala mdoc:silent:nest
val myApp =
  (
    for {
      fiber <- printLine("Running a job").delay(1.seconds).forever.fork
      _     <- fiber.interrupt.delay(3.seconds)
      _     <- fiber.join // Joining an interrupted fiber
    } yield ()
  ).ensuring(
    printLine(
      "This finalizer will be executed without occurring any deadlock"
    ).orDie
  )
```

A fiber that is interrupted because of joining another interrupted fiber will properly finalize; this is a distinction between ZIO and the other effect systems, which _deadlock_ the joining fiber.

## Thread Shifting - JVM
By default, fibers give no guarantees as to which thread they execute on. They may shift between threads, especially as they execute for long periods of time.

Fibers only ever shift onto the thread pool of the runtime system, which means that by default, fibers running for a sufficiently long time will always return to the runtime system's thread pool, even when their (asynchronous) resumptions were initiated from other threads.

For performance reasons, fibers will attempt to execute on the same thread for a (configurable) minimum period, before yielding to other fibers. Fibers that resume from asynchronous callbacks will resume on the initiating thread, and continue for some time before yielding and resuming on the runtime thread pool.

These defaults help to guarantee stack safety and cooperative multitasking. They can be changed in `Runtime` if automatic thread shifting is not desired.

## Types of Workloads
Let's discuss the types of workloads that a fiber can handle. There are three types of them:
1. **CPU Work/Pure CPU Bound** is a workload that exploits the processing power of a CPU for pure computations of a huge chunk of work, e.g. complex numerical computations.
4. **Blocking I/O** is a workload that goes beyond pure computation by doing communication in a blocking fashion. For example, waiting for a certain amount of time to elapse or waiting for an external event to happen are blocking I/O operations.
5. **Asynchronous I/O** is a workload that goes beyond pure computation by doing communication asynchronously, e.g. registering a callback for a specific event.

### CPU Work
What we refer to as CPU Work is pure computational firepower without involving any interaction and communication with the outside world. It doesn't involve any I/O operation. It's a pure computation. By I/O, we mean anything that involves reading from and writing to an external resource such as a file or a socket or web API, or anything that would be characterized as I/O. 

Fibers are designed to be **cooperative** which means that **they will yield to each other as required to preserve some level of fairness**. If we have a fiber that is doing CPU Work which passes through one or more ZIO operations such as `flatMap` or `map`, as long as there exists a touchpoint where the ZIO runtime system can sort of keep a tab on that ongoing CPU Work then that fiber will yield to other fibers. These touchpoints allow many fibers who are doing CPU Work to end up sharing the same thread.

What if though, we have a CPU Work operation that takes a really long time to run? Let's say 30 seconds it does pure CPU Work very computationally intensive? What happens if we take that single gigantic function and put that into a `ZIO#attempt`? In that case there is no way for the ZIO Runtime to force that fiber to yield to other fibers. In this situation, the ZIO Runtime cannot preserve some level of fairness, and that single big CPU operation monopolizes the underlying thread. It is not a good practice to monopolize the underlying thread.

ZIO has a special thread pool that can be used to do these computations. That's the **blocking thread pool**. The `ZIO#blocking` operator and its variants (see [here](../core/zio/#blocking-operations)) can be used to run a big CPU Work on a dedicated thread. So, it doesn't interfere with all the other work that is going on simultaneously in the ZIO Runtime system.

If a CPU Work doesn't yield quickly, then that is going to monopolize a thread. So how can we determine that our CPU Work can yield quickly or not? 
- If that overall CPU Work composes many ZIO operations, then due to the composition of ZIO operations, it has a chance to yield quickly to other fibers and doesn't monopolize a thread.
- If that CPU work doesn't compose any ZIO operations, or we lift that from a legacy library, then the ZIO Runtime doesn't have any chance of yielding quickly to other fibers. So this fiber is going to monopolize the underlying thread.

The best practice is to run those huge CPU Work on a dedicated thread pool, by lifting them with `ZIO#blocking`.

> **Note**:
>
> So as a rule of thumb, when we have a huge CPU Work that is not chunked with built-in ZIO operations, and thus going to monopolize the underlying thread, we should run that on a dedicated thread pool that is designed to perform CPU-driven tasks.

### Blocking I/O
Inside Java, there are many methods that will put our thread to sleep. For example, if we call `read` on a socket and there is nothing to read right now because not enough bytes have been read from the other side over the TCP/IP protocol, then that will put our thread to sleep. 

Most of the I/O operations and certainly all the classic I/O operations like `InputStream` and `OutputStream` are utilizing a locking mechanism that will `park` a thread. When we `write` to `OutputStream`, this method will `park` the thread and `wait` until the data has actually been written to file. It is the same way for `read` and similar blocking operations. Anytime we use a `lock`, anything in `java.util.concurrent.locks`, all those locks use this mechanism. All these operations are called blocking because they `park` the thread that is doing the work, and the thread that's doing the work goes to sleep.

> _**Note:**_ 
>
> What we refer to as blocking I/O is not necessarily just an I/O operation. Remember every time we use a `lock` we are also `park`ing a thread. It goes to `sleep`, and it has to be woken up again. We refer to this entire class of operations as **blocking I/O**.

There are multiple types of overhead associated with parking a thread:

1. When we `park` a thread then that thread is still consuming resources, it's still obviously consuming stack resources, the heap, and all metadata associated with the underlying thread in the JVM.

2. Since every JVM thread corresponds to an operating system level thread, there's a large amount of overhead even inside the operating system. Every thread has a pre-allocated stack size so that memory is reserved even if that thread is not doing any work. That memory is sort of reserved for the thread, and we cannot touch it. 

3. Besides, the actual process of putting a thread to sleep and then later waking it up again is computationally intensive. It slows down our computations.

This is why it has become a sort of best practice and part of the architectural pattern of reactive applications to design what is called **non-blocking application**. Non-blocking is synonymous with asynchronous. Non-blocking and asynchronous and to some degree even reactive, they're all trying to get at something which is what we want: scalable applications.

Scalable applications cannot afford to have thousands of threads just sitting around doing nothing and just consuming work and taking a long time to wake up again. We cannot do blocking I/O in scalable applications. It is considered an anti-pattern because it is not efficient. That is not a way to build scalable applications, but nonetheless, we have to support that use case.

Today, we have lots of common Java libraries that use blocking constructs, like `InputStream` and `OutputStream`, and `Reader` and `Writer`. Also, the JDBC is entirely blocking. The only way of doing database I/O in Java is blocking. So obviously, we have to do blocking I/O. We can do blocking I/O from a fiber. So is it best practice? No, it should be avoided whenever possible, but the reality is we have to do blocking I/O. 

Whenever we lift a blocking I/O operation into ZIO, the ZIO Runtime is executing a fiber that is doing blocking I/O. The underlying thread will be parked, and it has to be woken up later. It doesn't have any ability to avoid this. It can't stop an underlying thread from being parked. That's just the way these APIs are designed. So, we have to block. There's no way around that. That fiber will be monopolizing the underneath thread and therefore that thread is not available for performing all the work of the other fibers in the system. So that can be a bottleneck point in our application. 

And again, the solution to this problem is the same as the solution to the first class of problems, the CPU Work. The solution is to run this action **using the blocking thread pool** in ZIO which will ensure that this blocking code executes on its dedicated thread pool. **So it doesn't interfere or compete with the threads that are used for doing the bulk of work in your application**. So basically ZIO's philosophy is that if we _have_ to do CPU Work or blocking synchronous work that's ok we can do that. Just we need to do it in the right place. So it doesn't interfere with our primary thread pool.

ZIO has one primary built-in fixed thread pool. This sort of workhorse thread pool is designed to be used for the majority of our application requirements. It has a certain number of threads in it and that stays constant over the lifetime of our application.

Why is that the case? Well because for the majority of workloads in our applications, it does not actually help things to create more threads than the number of CPU cores. If we have eight cores, it does not accelerate any sort of processing to create more than eight threads. Because at the end of the day our hardware is only capable of running eight things at the same time. 

If we create a thousand threads on a system that can only run eight of them in parallel at a time, then what does the operating system do? As we have not enough CPU cores, the operating system starts giving a little slice of the eight cores to all these threads by switching between them over and over again.

The overhead for context switching between threads is significant. The CPU has to load in new registers, refill all its caches, it has to go through all these crazy complex processes that interfere with its main job to get stuff done. There's significant overhead associated with that. As a result, it's not going to be very efficient. We are going to waste a lot of our time and resources just switching back and forth between all these threads, that would kill our application.

So for that reason, ZIO's default thread pool is fixed with a number of threads equal to the number of CPU cores. That is the best practice. That means that no matter how much work we create if we create a hundred thousand fibers, they will still run on a fixed number of threads.

Let's say we do blocking I/O on the main ZIO thread pool, so we have got eight threads all sitting and parked on a socket read. What happens to all the other 100000 fibers in our system? They line up in a queue waiting for their chance to run. That's not ideal. That's why we should take these effects that either do blocking I/O, or they do big CPU Work that's not chunked and run them using ZIO's blocking thread pool which will give us a dedicated thread. 

That dedicated thread is not efficient, but again, sometimes we have to interact with legacy code and legacy code is full of blocking code. We just need to be able to handle that gracefully and ZIO does that using the blocking thread pool.

### Asynchronous I/O
The third category is asynchronous I/O, and we refer to it as Async Work. Async Work is code that whenever it runs into something that it needs to wait on, instead of blocking and parking the thread, it registers a callback, and returns immediately.

 It allows us to register a callback and when that result is available then our callback will be invoked. Callbacks are the fundamental way by which all async code on the JVM works. There is no mechanism in the JVM right now to support async code natively, but once that would happen in the future, probably in the Loom project, it will simplify a lot of things.

But for now, in current days, sort of all published JVM versions have no such thing. The only way we can get a non-blocking async code is to have this callback registering mechanism.

Callbacks have the pro that they don't wait for CPU. Instead of waiting to read the next chunk from a socket or instead of waiting to acquire a lock, all we have to do is call it and give it a callback. It doesn't need to do anything else. It can return control to the thread pool and then later on when that data has been read from the socket or when that lock has been acquired or when that amount of time has elapsed if we're sleeping for a certain amount of time, then our callback can be invoked. 

It has the potential to be extraordinarily efficient. The drawback of callbacks is they are not so pretty and fun to work with. They don't compose well with `try` / `finally` constructs. Error handling is really terrible, we have to do error propagation on our own. So that is what gave rise to data types like `Future` which eliminates the need for callbacks. By using `Future`s we can wrap callback-based APIs and get the benefits of async but without the callbacks. Also, it supports for-comprehension, so we can structure our code as a nice linear sequence. 

Similarly, in ZIO we never see a callback with ZIO, but fundamentally everything boils down to asynchronous operations on the JVM in a callback fashion. Callback base code is obscenely efficient, but it is extraordinarily painful to deal with directly. Data types like `Future` and `ZIO` allow us to avoid even seeing a callback in our code. 

With ZIO, we do not have to think about callbacks, unless sometimes, when we need to integrate with legacy code. ZIO has an appropriate constructor to turn that ugly callback-based API into a ZIO effect. It is the `async` constructor.

Most of the ZIO operations that one would expect to be blocking do actually not block the underlying thread, but they offer blocking semantics managed by ZIO. For example, every time we see something like `ZIO.sleep` or when we take something from a queue (`queue.take`) or offer something to a queue (`queue.offer`) or if we acquire a permit from a semaphore (`semaphore.withPermit`) and so forth, we are just blocking semantically without actually blocking an underlying thread. If we use the corresponding methods in Java, like `Thread.sleep` or any of its `lock` machinery, then those methods are going to block a thread. So this is why we say that ZIO is 100% non-blocking, while Java threads are not.

All of the pieces of machinery that ZIO gives us are 100% asynchronous and non-blocking. As they don't block and monopolize the thread, all of the async work is executed on the primary thread pool in ZIO.
