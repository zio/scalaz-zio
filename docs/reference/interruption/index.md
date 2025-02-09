---
id: index
title: "Introduction to ZIO's Interruption Model"
sidebar_label: Interruption Model
---

While developing concurrent applications, there are several cases that we need to _interrupt_ the execution of other fibers, for example:

1. A parent fiber might start some child fibers to perform a task, and later the parent might decide that it doesn't need the result of some or all of the child fibers.
2. Two or more fibers start a race with each other. The fiber whose result is computed first wins and all other fibers are no longer needed so they should be interrupted.
3. In interactive applications, a user may want to stop some already running tasks, such as clicking on the "stop" button to prevent downloading more files.
4. Computations that run longer than expected should be aborted by using timeout operations.
5. When we have an application that perform compute-intensive tasks based on the user inputs, if the user changes the input we should cancel the current task and perform another one.

## Polling vs. Asynchronous Interruption

A simple and naive way to implement fiber interruption is to provide a mechanism for one fiber to _kill/terminate_ another fiber. This is not a correct solution because if the target fiber is in the middle of changing a shared state it leads to an inconsistent state. So this solution doesn't guarantee to leave the shared mutable state internally consistent.

Other than the very simple kill solution, there are two popular valid solutions to this problem:

  1. **Semi-asynchronous Interruption (Polling for Interruption)**— Imperative languages such as Java often use polling to implement a semi-asynchronous signaling mechanism. In this model, a fiber sends a request for interruption of other fiber. The target fiber keeps polling the interrupt status, and based on the interrupt status will find out that whether there is an interruption request from other fibers. If so, it should terminate itself as soon as possible.

  Using this solution, the fiber itself takes care of critical sections. So while a fiber is in the middle of a critical section, if it receives an interruption request it should ignore the interruption and postpone the delivery of interruption during the critical section.

  The drawback of this solution is that, if the programmer forgets to poll regularly enough, then the target fiber becomes unresponsive and causes deadlocks. Another problem is that polling a global flag is not a functional operation and doesn't fit with ZIO's paradigm.

  2. **Asynchronous Interruption**— In asynchronous interruption, a fiber is allowed to terminate another fiber. So the target fiber is not responsible for polling the status, instead in critical sections the target fiber disables the interruptibility of these regions. This is a purely-functional solution and doesn't require polling a global state. ZIO uses this solution for its interruption model. It is a fully asynchronous signalling mechanism.

  This mechanism doesn't have the drawback of forgetting to poll regularly and also it's fully compatible with the functional paradigm because in a purely-functional computation, we can abort the computation at any point, except for critical sections.

## When Does a Fiber Get Interrupted?

There are several ways and situations that fibers can be interrupted. In this section we will introduce each one with an example of how to reproduce these situations:

### Calling `Fiber#interrupt` Operator

A fiber can be interrupted by calling `Fiber#interrupt` on that fiber.

Let's try to make a fiber and then interrupt it:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def task = {
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _ <- ZIO.debug(s"$fn starts a long running task")
      _ <- ZIO.sleep(1.minute)
      _ <- ZIO.debug("done!")
    } yield ()
  }

  def run =
    for {
      f <-
        task.onInterrupt(
          ZIO.debug(s"Task interrupted while running")
        ).fork
      _ <- f.interrupt
    } yield ()
}
```

Here is the output of running this piece of code, which denotes that the task was interrupted:

```
Task interrupted while running
```

### Interruption of Parallel Effects

When composing multiple parallel effects, when one of them is interrupted the other fibers will be interrupted also. So if we have two parallel tasks, if one of them fails or gets interrupted, the other will be interrupted:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  def debugInterruption(taskName: String) = (fibers: Set[FiberId]) =>
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _ <- ZIO.debug(
        s"The $fn fiber which is the underlying fiber of the $taskName task " +
          s"interrupted by ${fibers.map(_.threadName).mkString(", ")}"
      )
    } yield ()

  def task[R, E, A](name: String)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    zio.onInterrupt(debugInterruption(name))

  def debugMainFiber =
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _ <- ZIO.debug(s"Main fiber ($fn) starts executing the whole application.")
    } yield ()

  def run = {
    // self interrupting fiber 
    val first = task("first")(ZIO.interrupt)

    // never ending fiber
    val second = task("second")(ZIO.never)

    debugMainFiber *> {
      // uncomment each line and run the code to see the result

      // first fiber will be interrupted 
      first *> second

      // never ending application
      // second *> first

      // first fiber will be interrupted
      // first <*> second

      // never ending application
      // second <*> first

      // first and second will be interrupted
      // first <&> second

      // first and second will be interrupted 
      // second <&> first
    }
  }

}
```

In the above code the `first <&> second` is a parallel composition of the `first` and `second` tasks. When we run them together, the `zipWithPar`/`<&>` operator will run these two tasks in two parallel fibers. If either side of this operator fails or is interrupted the other side will be interrupted.

### Child Fibers Are Scoped to Their Parents

1. If a child fiber does not complete its job or does not join its parent before the parent has completed its job, the child fiber will be interrupted:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def debugInterruption(taskName: String) = (fibers: Set[FiberId]) =>
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _  <- ZIO.debug(
              s"the $fn fiber which is the underlying fiber of the $taskName task " +
              s"interrupted by ${fibers.map(_.threadName).mkString(", ")}"
            )
    } yield ()

  def run =
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _  <- ZIO.debug(s"$fn starts working.")
      child =
        for {
          cfn <- ZIO.fiberId.map(_.threadName)
          _   <- ZIO.debug(s"$cfn starts working by forking from its parent ($fn)")
          _   <- ZIO.never
        } yield ()
      _  <- child.onInterrupt(debugInterruption("child")).fork
      _  <- ZIO.sleep(1.second)
      _  <- ZIO.debug(s"$fn finishes its job and is going go exit.")
    } yield ()
    
}
```

Here is the result of one of the executions of this sample code:

```
zio-fiber-2 starts working.
zio-fiber-7 starts working by forking from its parent (zio-fiber-2)
zio-fiber-2 finishes its job and is going to exit.
the zio-fiber-7 fiber which is the underlying fiber of the child task interrupted by zio-fiber-2
```

2. If a parent fiber is interrupted, all its children will be interrupted:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  def debugInterruption(taskName: String) = (fibers: Set[FiberId]) =>
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _ <- ZIO.debug(
        s"The $fn fiber which is the underlying fiber of the $taskName task " +
          s"interrupted by ${fibers.map(_.threadName).mkString(", ")}"
      )
    } yield ()

  def task =
    for {
      fn <- ZIO.fiberId.map(_.threadName)
      _ <- ZIO.debug(s"$fn starts running that will print random numbers and booleans")
      f1 <- Random.nextIntBounded(100)
        .debug("random number ")
        .schedule(Schedule.spaced(1.second).forever)
        .onInterrupt(debugInterruption("random number"))
        .fork
      f2 <- Random.nextBoolean
        .debug("random boolean ")
        .schedule(Schedule.spaced(2.second).forever)
        .onInterrupt(debugInterruption("random boolean"))
        .fork
        _ <- f1.join
        _ <- f2.join
    } yield ()

  def run =
    for {
      f <- task.fork
      _ <- ZIO.sleep(5.second)
      _ <- f.interrupt
    } yield ()
}
```

Here is one sample output for this program:

```
zio-fiber-7 starts running that will print random numbers and booleans
random number : 65
random boolean : true
random number : 51
random number : 46
random boolean : true
random number : 30
The zio-fiber-9 fiber which is the underlying fiber of the random boolean task interrupted by zio-fiber-7
The zio-fiber-8 fiber which is the underlying fiber of the random number task interrupted by zio-fiber-7
```

## Blocking Operations

### Interruption of Blocking Operations

By default, when we convert a blocking operation into a ZIO effect using `attemptBlocking`, there is no guarantee that if that effect is interrupted the underlying effect will be interrupted.

Let's create a blocking effect from an endless loop:

```scala mdoc:compile-only
import zio._

for {
  _ <- Console.printLine("Starting a blocking operation")
  fiber <- ZIO.attemptBlocking {
    while (true) {
      Thread.sleep(1000)
      println("Doing some blocking operation")
    }
  }.ensuring(
    Console.printLine("End of a blocking operation").orDie
  ).fork
  _ <- fiber.interrupt.schedule(
    Schedule.delayed(
      Schedule.duration(1.seconds)
    )
  )
} yield ()
```

When we interrupt this loop after one second it will still not stop. It will only stop when the entire JVM stops. The `attemptBlocking` doesn't translate the ZIO interruption into thread interruption (`Thread.interrupt`).

Instead, we should use `attemptBlockingInterrupt` to create interruptible blocking effects:

```scala mdoc:compile-only
import zio._

for {
  _ <- Console.printLine("Starting a blocking operation")
  fiber <- ZIO.attemptBlockingInterrupt {
    while(true) {
      Thread.sleep(1000)
      println("Doing some blocking operation")
    }
  }.ensuring(
     Console.printLine("End of the blocking operation").orDie
   ).fork
  _ <- fiber.interrupt.schedule(
    Schedule.delayed(
      Schedule.duration(3.seconds)
    )
  )
} yield ()
```

Notes:

1. If we are converting a blocking I/O to a ZIO effect, it would be better to use `attemptBlockingIO` which refines the error type to `java.io.IOException`.

2. The `attemptBlockingInterrupt` method adds significant overhead. So for performance-sensitive applications, it is better to handle interruptions manually using `attemptBlockingCancelable`.

### Cancellation of Blocking Operation

Some blocking operations do not respect `Thread#interrupt` by swallowing `InterruptedException`. So they will not be interrupted via `attemptBlockingInterrupt`. Instead, they may provide us an API to signal them to _cancel_ their operation.

The following `BlockingService` will not be interrupted in case of a `Thread#interrupt` call, but it checks the `released` flag constantly. If this flag becomes true, the blocking service will finish its job:

```scala mdoc:silent
import zio._
import java.util.concurrent.atomic.AtomicReference

final case class BlockingService() {
  private val released = new AtomicReference(false)

  def start(): Unit = {
    while (!released.get()) {
      println("Doing some blocking operation")
      try Thread.sleep(1000)
      catch {
        case _: InterruptedException => () // Swallowing InterruptedException
      }
    }
    println("Blocking operation closed.")
  }

  def close(): Unit = {
    println("Releasing resources and ready to be closed.")
    released.getAndSet(true)
  }
}
```

So to translate ZIO interruption into cancellation of these types of blocking operations we should use `attemptBlockingCancelable`. This method takes a `cancel` effect which is responsible for signalling the blocking code to close itself when ZIO interruption occurs:

```scala mdoc:compile-only
import zio._

val myApp =
  for {
    service <- ZIO.attempt(BlockingService())
    fiber   <- ZIO.attemptBlockingCancelable(
      effect = service.start()
    )(
      cancel = ZIO.succeed(service.close())
    ).fork
    _       <- fiber.interrupt.schedule(
      Schedule.delayed(
        Schedule.duration(3.seconds)
      )
    )
  } yield ()
```

Here is another example of the cancellation of a blocking operation. When we `accept` a server socket, this blocking operation will never be interrupted until we close it using the `ServerSocket#close` method:

```scala mdoc:compile-only
import java.net.{Socket, ServerSocket}
import zio._

def accept(ss: ServerSocket): Task[Socket] =
  ZIO.attemptBlockingCancelable(ss.accept())(ZIO.succeed(ss.close()))
```

## Disabling Interruption of Fibers

As we discussed earlier, it is dangerous for fibers to interrupt others. The danger with such an interruption is that:

- If the interruption occurs during the execution of an operation that must be _finalized_, the finalization will not be executed.

- If this interruption occurs in the middle of a _critical section_, it will cause an application state to become inconsistent.

- It is also a threat to _resource safety_. If the fiber is in the middle of acquiring a resource and is interrupted, the application will leak resources.

ZIO introduces the `uninterruptible` and `uninterruptibleMask` operations for this purpose. The former creates a region of code uninterruptible and the latter has the same functionality but gives us a `restore` function that can be applied to any region of code to restore the interruptibility of that region.

These operators are advanced and very low-level so we don't use them in regularly in application development unless we know what we are doing as library designers. If you find yourself using these operators, think again about refactoring your code using high-level operators like `ZIO#onInterrupt`, `ZIO#onDone`, `ZIO#ensuring`, `ZIO.acquireRelease*` and many other concurrent operators like `race`, `foreachPar`, etc.
