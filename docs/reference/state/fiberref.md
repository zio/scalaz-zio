---
id: fiberref
title: "FiberRef"
---

`FiberRef[A]` models a mutable reference to a value of type `A`. The two basic operations are `set`, which sets the reference to a new value, and `get`, which retrieves the current value of the reference.

We can think of `FiberRef` as Java's `ThreadLocal` on steroids. So, just like we have `ThreadLocal` in Java we have `FiberRef` in ZIO. So as different threads have different `ThreadLocal`s, we can say different fibers have different `FiberRef`s. They don't intersect or overlap in any way. `FiberRef` is the fiber version of `ThreadLocal` with significant improvements in its semantics. A `ThreadLocal` only has a mutable state in which each thread accesses its own copy, but threads don't propagate their state to their children's.

As opposed to `Ref[A]`, the value of a `FiberRef[A]` is bound to an executing fiber. Different fibers who hold the same `FiberRef[A]` can independently set and retrieve values of the reference, without collisions.

```scala mdoc:silent
import zio._

for {
  fiberRef <- FiberRef.make[Int](0)
  _        <- fiberRef.set(10)
  v        <- fiberRef.get
} yield v == 10
```

## Motivation

Whenever we have some kind of scoped information or context, and we don't want to use the ZIO environment to store it, we can use `FiberRef` to store it.

To illustrate this, let's try to find a solution to the _Structured Logging_ problem. In structured logging, we tend to attach contextual information to log messages, such as user id, correlation id, log level, and so on.

So assume we have written the following code:

```scala mdoc:invisible
import zio._

object Logging {
  def log(message: String) = ZIO.unit
  def logAnnotate[R, E, A](key: String, value: String)(zio: ZIO[R, E, A]) = ZIO.unit
}
```

```scala mdoc:compile-only
import zio._

for {
  _ <- Logging.log("Hello World!")
  _ <- ZIO.foreachParDiscard(List("Jane", "John")) { name =>
    Logging.logAnnotate("name", name) {
      for {
        _ <- Logging.log(s"Received request")
        fiberId <- ZIO.fiberId.map(_.ids.head)
        _ <- Logging.logAnnotate("fiber_id", s"$fiberId")(
          Logging.log("Processing request")
        )
        _ <- Logging.log("Finished processing request")
      } yield ()
    }
  }
  _ <- Logging.log("All requests processed")
} yield ()
```

```scala mdoc:invisible:reset

```

We would like to see the following log output:

```scala
Hello World!
[name=Jane] Received request
[name=John] Received request
[name=Jane] [fiber_id=7] Processing request
[name=John] [fiber_id=8] Processing request
[name=John] Finished processing request
[name=Jane] Finished processing request
All requests processed
```

In the above code, we have two users, `Jane` and `John`, and we want to handle some operations on each user, concurrently. When we perform concurrent operations, we would like to have a way to associate each concurrent operation with its corresponding user and fiber id. So when we log messages, we have all the information available for a specific event.

In order to do this, we need a context-aware logging service. This logging service needs to have a **state** that is a place to store annotations. This state can be accessed and modified **concurrently** by multiple fibers. And the important part is that each fiber should have its own isolated copy of the state, so when a fiber modifies the state, it doesn't clobber the state of other fibers.

Until now, we can categorize our requirements into two parts:
- We need a mechanism to carry some contextual information, without explicitly passing it around.
- We need a mechanism to update the state in an isolated fashion, where each fiber can update the state without affecting the state of other fibers.

## Solution

In this section, we will look at two solutions to the problem of structured logging we have mentioned above. The first solution has some limitations and drawbacks, so we will choose the second solution as the final solution.

### Solution 1: ZIO Environment

One solution is to use the ZIO environment to store the state. It addresses the first requirement, very well. ZIO environment is a nice place to store the contextual states. And to make the state isolated between fibers, we can reintroduce the new state to the environment instead of updating the environment globally:

```scala mdoc:compile-only
// Solution 1: Using the ZIO environment to store the state
import zio._

object Logging {
  type Annotation = Map[String, String]

  def logAnnotate[R, E, A](key: String, value: String)(
    zio: ZIO[R with Annotation, E, A]
  ): ZIO[R with Annotation, E, A] = {
    for {
      s <- ZIO.service[Annotation]
      r <- zio.provideSomeLayer[R](ZLayer.succeed(s.updated(key, value)))
    } yield (r)
  }

  def log(message: String): ZIO[Annotation, Nothing, Unit] = {
    ZIO.service[Annotation].flatMap {
      case annotation if annotation.isEmpty => 
        Console.printLine(message).orDie
      case annotation =>
        val line =
          s"${annotation.map { case (k, v) => s"[$k=$v]" }.mkString(" ")} $message"
        Console.printLine(line).orDie
    }
  }
}
```

But this solution is not very flexible, because this brings type safety over the contextual data types. So any change to the type of contextual data needs a change to the whole program.

### Solution 2: FiberRef

The other solution is to use `FiberRef`. FiberRef is a nice way to store the contextual states and make them isolated. Any state maintained by a `FiberRef` will be isolated between fibers. Also, a nice thing about `FiberRef` is that we do not require to place the state in the environment.

Let's see how to use `FiberRef` to implement the logging service:

```scala mdoc:silent
import zio._

case class Logging private (ref: FiberRef[Map[String, String]]) {
  def logAnnotate[R, E, A](key: String, value: String)(
      zio: ZIO[R, E, A]
  ): ZIO[R, E, A] = ref.locallyWith(_ + (key -> value))(zio)

  def log(message: String): UIO[Unit] = {
    ref.get.flatMap {
      case annotation if annotation.isEmpty => Console.printLine(message).orDie
      case annotation =>
        val line =
          s"${annotation.map { case (k, v) => s"[$k=$v]" }.mkString(" ")} $message"
        Console.printLine(message).orDie
    }
  }
}

object Logging {
  def make() = FiberRef.make(Map.empty[String, String]).map(new Logging(_))
}
```

Now we can write a program that logs some information:

```scala mdoc:compile-only
import zio._

object FiberRefLoggingExample extends ZIOAppDefault {
  def run =
    for {
      logging <- Logging.make()
      _ <- logging.log("Hello World!")
      _ <- ZIO.foreachParDiscard(List("Jane", "John")) { name =>
        logging.logAnnotate("name", name) {
          for {
            _ <- logging.log(s"Received request")
            fiberId <- ZIO.fiberId.map(_.ids.head)
            _ <- logging.logAnnotate("fiber_id", s"$fiberId")(
              logging.log("Processing request")
            )
            _ <- logging.log("Finished processing request")
          } yield ()
        }
      }
      _ <- logging.log("All requests processed")
    } yield ()
}
```

> **Note:**
>
> In the above solution, if we replace the `FiberRef` with `Ref`, the program will not work properly, because the `Ref` is not isolated. The `Ref` will be shared between all fibers, so each fiber clobbers the other fibers' state.

## Use Cases

Whenever we have some kind of scoped information or context, we can think about `FiberRef` as a way to store that information. 

When developing applications, there are several use cases for `FiberRef`. Let's take a look at some of them:

1. **Distributed Tracing**— In an architecture, where we have highly concurrent workflows and distributed services, there is a need to trace requests as they propagate through the services. To be able to trace requests, we can use `FiberRef` to design the system to automatically propagate request-scoped information.

2. **Contextual Logging**— In lot of cases, logs are not independent piece of information, but they are part of a larger context. So other than just logging the message, we also need to log some additional information such as the request ID, the user ID, the session id, and so on. So whe we collect these logs, we can correlate them based on a common data point. Instead of explicitly passing these contextual information, we can use `FiberRef`.

3. **Execution Scoped Configuration**— When we write applications, we would like to make them configurable. So we configure the application once and used it throughout the whole components. Not all configurations are global. There are certain kinds of configurations that are not global, or at least we have a default value for them globally, but we need to change them dynamically for certain regions. `FiberRef` is a nice tool to model these kind of configurations.

In ZIO we have several use cases for `FiberRef`. Let's discuss some of them:

1. Whenever we use `ZIO.withParallelism`, we can specify the parallelism factor for a region of code. So this information will be stored inside a `FiberRef`, without any need to pass it around all effects explicitly. When we exit the region, the parallelism factor will be restored to the original value:

```scala mdoc:compile-only
import zio._
object MainApp extends ZIOAppDefault {
  def myJob(name: String) =
    ZIO.foreachParDiscard(1 to 3)(i =>
      ZIO.debug(s"The $name-$i job started") *> ZIO.sleep(2.second)
    )

  def run =
    ZIO.withParallelismUnbounded(
      for {
        _ <- myJob("foo")
        _ <- ZIO.debug("------------------")
        _ <- ZIO.withParallelism(1)(myJob("bar"))
        _ <- ZIO.debug("------------------")
        _ <- myJob("baz")
      } yield ()
    )
}
```

2. Using `ZIOAspect.annotated` we can annotate the effect with some contextual information, e.g. the `correlation_id`. This information will be stored inside a `FiberRef`, which will be propagated to all fibers that are created from the same parent fiber. Each fiber will have its own set of annotations. When we log inside a fiber, the logging service will use the fiber's specific annotations to create the log message:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  def handleRequest(request: String) =
    for {
      _ <- ZIO.log(s"Received request.")
      _ <- ZIO.unit // do something with the request
      _ <- ZIO.log(s"Finished processing request")
    } yield ()

  def run =
    for {
      _ <- ZIO.log("Hello World!")
      _ <- ZIO.foreachParDiscard(List(("req1", "1"), ("req2", "2"), ("req3", "3"))){ case (req, id) =>
        handleRequest(req) @@ ZIOAspect.annotated("correlation_id", id)
      }
      _ <- ZIO.log("Goodbye!")
    } yield ()

}
```

Here is the output (extra columns were removed for better readability):

```
message="Hello World!"
message="Received request." correlation_id=2
message="Received request." correlation_id=1
message="Received request." correlation_id=3
message="Finished processing request." correlation_id=3
message="Finished processing request." correlation_id=1
message="Finished processing request." correlation_id=2
message="Goodbye!"
```

3. Log levels are also maintained by using `FiberRef`. They are stored inside a `FiberRef`, and whenever we want, we can change the log level using the `ZIO.logLevel` operator:

```scala mdoc:compile-only
import zio._

for {
  _ <- ZIO.log("Application started!")
  _ <- ZIO.logLevel(LogLevel.Trace) {
    for {
      _ <- ZIO.log("Entering trace log level region")
      _ <- ZIO.log("Doing something")
      _ <- ZIO.log("Leaving trace log level region")
    } yield ()
  }
  _ <- ZIO.log("Application ended!")
} yield ()
```

4. The same goes for when we access the environment (e.g. `ZIO.service`), or when we provide a layer to a ZIO effect (e.g. `ZIO#provide`). ZIO uses `FiberRef` under the hood to store the environment:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  private val fooLayer = ZLayer.succeed("foo")
  private val barLayer = ZLayer.succeed("bar") 
  
  def run =
    (for {
      _ <- ZIO.service[String].debug("context")
      _ <- ZIO.service[String].debug("context").provide(barLayer)
      _ <- ZIO.service[String].debug("context")
    } yield ()).provide(fooLayer)
}
// Output:
// context: foo
// context: bar
// context: foo
```

There are several other use cases for `FiberRef` in ZIO itself. We just covered some of them to get you some ideas on how they are used in the real world.

## Operations

`FiberRef[A]` has an API almost identical to `Ref[A]`. It includes well-known methods such as:

- `FiberRef#get`. Returns the current value of the reference.
- `FiberRef#set`. Sets the current value of the reference.
- `FiberRef#update` / `FiberRef#updateSome` updates the value with the specified function.
- `FiberRef#modify`/ `FiberRef#modifySome` modifies the value with the specified function, computing a return value for the operation.

You can also use `locally` to scope `FiberRef` value only for a given effect:

```scala mdoc:compile-only
import zio._

for {
  correlationId <- FiberRef.make[String]("")
  v1            <- correlationId.locally("my-correlation-id")(correlationId.get)
  v2            <- correlationId.get
} yield v1 == "my-correlation-id" && v2 == ""
```

## Propagation

Let's go back to the `FiberRef`s analog called `ThreadLocal` and see how it works. If we have thread `A` with its `ThreadLocal` and thread `A` creates a new thread, let's call it thread `B`. When thread `A` sends thread `B` the same `ThreadLocal` then what value does thread `B` see inside the `ThreadLocal`? Well, it sees the default value of the `ThreadLocal`. It does not see `A`s value of the `ThreadLocal`. So in other words, `ThreadLocal`s do not propagate their values across the sort of graph of threads so when one thread creates another, the `ThreadLocal` value is not propagated from parent to child.

`FiberRef`s improve on that model quite dramatically. Basically, whenever a child's fiber is created from its parent, the `FiberRef` value of parent fiber propagated to its child fiber.

### Copy-on-Fork 
`FiberRef[A]` has *copy-on-fork* semantics for `ZIO#fork`. This essentially means that a child `Fiber` starts with `FiberRef` values of its parent. When the child sets a new value of `FiberRef`, the change is visible only to the child itself. The parent fiber still has its own value.

So if we create a `FiberRef` and set its value to `5`, and we pass this `FiberRef` to a child fiber, it sees the value `5`. If the child fiber modifies the value from `5` to `6`, the parent fiber can't see that change. So the child fiber gets its own copy of the `FiberRef`, and it can modify it locally. Those changes will not affect the parent fiber:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make(5)
  promise <- Promise.make[Nothing, Int]
  _ <- fiberRef
    .updateAndGet(_ => 6)
    .flatMap(promise.succeed).fork
  childValue <- promise.await
  parentValue <- fiberRef.get
} yield assert(parentValue == 5 && childValue == 6)
```

## Merging Back

ZIO does not only support to propagate `FiberRef` values from parents to childs, but also to fetch back these values into the current fiber. This section describes multiple variants for doing so.

### join
If we `join` a fiber then its `FiberRef` is merged back into the parent fiber:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make(5)
  child <- fiberRef.set(6).fork
  _ <- child.join
  parentValue <- fiberRef.get
} yield assert(parentValue == 6)
```

So if we `fork` a fiber and that child fiber modifies a bunch of `FiberRef`s and then later we `join` it, we get those modifications merged back into the parent fiber. So that's the semantic model of ZIO on `join`. 

Each fiber has its own `FiberRef` and can modify it locally. So when multiple child fibers `join` their parent, how do they get merged? By default, the last child fiber will win, the last fiber which is going to `join` will override the parent's `FiberRef` value.

As we can see, `child1` is the last fiber, so its value, which is `6`, gets merged back into its parent:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make(5)
  child1 <- fiberRef.set(6).fork
  child2 <- fiberRef.set(7).fork
  _ <- child2.join
  _ <- child1.join
  parentValue <- fiberRef.get
} yield assert(parentValue == 6)
```

### join with Custom Merge
Furthermore, we can customize how, if at all, the value will be initialized when a fiber is forked and how values will be combined when a fiber is merged. To do this you specify the desired behavior during `FiberRef#make`:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make(initial = 0, join = math.max)
  child    <- fiberRef.update(_ + 1).fork
  _        <- fiberRef.update(_ + 2)
  _        <- child.join
  value    <- fiberRef.get
} yield assert(value == 2)
```

### await

It is important to note that `await` has no such merge behavior. So `await` waits for the child fiber to finish and gives us its value as an `Exit`, without ever merging any `FiberRef` values back into the parent:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make(5)
  child <- fiberRef.set(6).fork
  _ <- child.await
  parentValue <- fiberRef.get
} yield assert(parentValue == 5)
```

`join` has higher-level semantics than `await` because it will fail if the child fiber failed, it will interrupt if the child is interrupted, and it will also merge back its value to its parent.

### inheritAll

We can inherit the values from all `FiberRef`s from an existing `Fiber` using the `Fiber#inheritAll` method:

```scala mdoc:compile-only
import zio._

for {
  fiberRef <- FiberRef.make[Int](0)
  latch    <- Promise.make[Nothing, Unit]
  fiber    <- (fiberRef.set(10) *> latch.succeed(())).fork
  _        <- latch.await
  _        <- fiber.inheritAll
  v        <- fiberRef.get
} yield v == 10
```

Note that `inheritAll` is automatically called on `join`. However, `join` will wait for merging the *final* values, while `inheritAll` will merge the *current* values and then continue:

```scala mdoc:compile-only
import zio._

val withJoin =
    for {
        fiberRef <- FiberRef.make[Int](0)
        fiber    <- (fiberRef.set(10) *> fiberRef.set(20).delay(2.seconds)).fork
        _        <- fiber.join  // wait for fiber's end and copy final result 20 into fiberRef
        v        <- fiberRef.get
    } yield assert(v == 20)
```

```scala mdoc:compile-only
import zio._

val withoutJoin =
    for {
        fiberRef <- FiberRef.make[Int](0)
        fiber    <- (fiberRef.set(10) *> fiberRef.set(20).delay(2.seconds)).fork
        _        <- fiber.inheritAll.delay(1.second) // copy intermediate result 10 into fiberRef and continue
        v        <- fiberRef.get
    } yield assert(v == 10)
```

## Memory Safety

The value of a `FiberRef` is automatically garbage collected once the `Fiber` owning it is finished. A `FiberRef` that is no longer reachable (has no reference to it in user-code) will cause all fiber-specific values of the reference to be garbage collected, even if they were once used in a `Fiber` that is currently executing.
