---
id: runtime
title: "Runtime"
---
```scala mdoc:invisible
import zio.{FiberRefs, Runtime, RuntimeFlags, Task, UIO, Unsafe, URIO, ZEnvironment, ZIO}
```

A `Runtime[R]` is capable of executing tasks within an environment `R`.

To run an effect, we need a `Runtime`, which is capable of executing effects. Runtimes bundle a thread pool together with the environment that effects need.

## What is a Runtime System?

Whenever we write a ZIO program, we create a ZIO effect from ZIO constructors plus using its combinators. We are building a blueprint. A ZIO effect is just a data structure that describes the execution of a concurrent program. So we end up with a tree data structure that contains lots of different data structures combined together to describe what the ZIO effect should do. This data structure doesn't do anything, it is just a description of a concurrent program.

So the most important thing we should keep in mind when we are working with a functional effect system like ZIO is that when we are writing code, printing a string onto the console, reading a file, querying a database, and so forth, we are just writing a workflow or blueprint of an application. We are just building a data structure.

So how can ZIO run these workflows? This is where the ZIO Runtime System comes into play. Whenever we run an `unsafe.run` function, the Runtime System is responsible for stepping through all the instructions described by the ZIO effect and executing them.

To simplify everything, we can think of a Runtime System like a black box that takes both the ZIO effect (`ZIO[R, E, A]`) and its environment (`R`). It will run this effect and return its result as an `Either[E, A]` value.


![ZIO Runtime System](/img/zio-runtime-system.svg)

## Responsibilities of the Runtime System

Runtime Systems have a lot of responsibilities:

1. **Execute every step of the blueprint** — They have to execute every step of the blueprint in a while loop until it's done.

2. **Handle unexpected errors** — They have to handle unexpected errors, not just the expected ones but also the unexpected ones. 

3. **Spawn concurrent fibers** — They are actually responsible for the concurrency that effect systems have. They have to spawn a new fiber every time we call `fork` on an effect.

4. **Cooperatively yield to other fibers** — They have to cooperatively yield to other fibers so that fibers that are sort of hogging the spotlight, don't get to monopolize all the CPU resources. They have to make sure that the fibers split the CPU cores among all the fibers that are working.

5. **Capture execution and stack traces** — They have to keep track of where we are in the progress of our own user-land code, so detailed execution traces can be captured. 

6. **Ensure finalizers are run appropriately** — They have to ensure finalizers are run appropriately at the right point in all circumstances to make sure that resources are closed and clean-up logic is executed. This is the feature that powers `Scope` and all the other resource-safe constructs in ZIO.

7. **Handle asynchronous callbacks** — They have to handle this messy job of dealing with asynchronous callbacks. So we don't have to deal with async code. When we are using ZIO, everything is just async out of the box. 

## Running a ZIO Effect

There are two common ways to run a ZIO effect. Most of the time, we use the [`ZIOAppDefault`](zioapp.md) trait. There are, however, some advanced use cases for which we need to directly feed a ZIO effect into the runtime system's `unsafe.run` method:

```scala mdoc:compile-only
import zio._

object RunZIOEffectUsingUnsafeRun extends scala.App {
  val myAppLogic = for {
    _ <- Console.printLine("Hello! What is your name?")
    n <- Console.readLine
    _ <- Console.printLine("Hello, " + n + ", good to meet you!")
  } yield ()

  Unsafe.unsafe { implicit unsafe =>
      zio.Runtime.default.unsafe.run(
        myAppLogic
      ).getOrThrowFiberFailure()
  }
}
```

We don't usually use this method to run our effects. One of the use cases of this method is when we are integrating legacy (non-effectful) code with the ZIO effect. It helps us to refactor a large legacy code base into a ZIO effect gradually: assume we have decided to refactor a component in the middle of an application and rewrite that with ZIO. We can start rewriting that component with the ZIO effect and then integrate that component with the existing code base using the `unsafe.run` function.

## Default Runtime

ZIO contains a default runtime called `Runtime.default` designed to work well for mainstream usage. It is implemented as below:

```scala
object Runtime {
  val default: Runtime[Any] =
    Runtime(ZEnvironment.empty, FiberRefs.empty, RuntimeFlags.default)
}
```

The default runtime provides the minimum capabilities to bootstrap execution of ZIO tasks.

We can easily access the default `Runtime` to run an effect:

```scala mdoc:compile-only
object MainApp extends scala.App {
  val myAppLogic = ZIO.succeed(???)

  val runtime = Runtime.default

  Unsafe.unsafe { implicit unsafe =>
    runtime.unsafe.run(myAppLogic).getOrThrowFiberFailure()
  }
}
```

## Top-level and Locally Scoped Runtimes

In ZIO, we have two types of runtimes:

- **Top-level runtime** is the one that is used to run the entire ZIO application from the very beginning. There is only one top-level runtime when running a ZIO application. Here are some use-cases:
  - Creating a top level runtime in a mixed application. For example, if we are using an HTTP library that does not have direct support for ZIO we may need to use `Runtime.unsafe.run` in the implementations of each of our routes.
  - Another use-case is when we want to install a custom monitoring or supervisor from the very beginning of the application.

- **Locally scoped runtimes** are used during the execution of the ZIO application. They are local to a specific region of the code. Suppose we want to change the runtime configurations in the middle of a ZIO application. In such cases, we use locally scoped runtimes, for example:
  - When we want to import an effectful or side-effecting application with a specific runtime.
  - In some performance-critical regions, we want to disable logging temporarily.
  - When we want to have a customized executor for running a portion of our code.

ZLayer provides a consistent way to customize and configure runtimes. Using layers to customize the runtime enables us to use ZIO workflows. So a configuration workflow can be pure, effectful, or resourceful. Let's say we want to customize the runtime based on configuration information from a file or database.

In most cases, it is sufficient to customize application runtime using the [`bootstrap` layer](#configuring-runtime-using-bootstrap-layer) or [providing a custom configuration](#configuring-runtime-by-providing-configuration-layers) directly to our application. If none of these solutions fit to our problem, we can use [top-level runtime configurations](#top-level-runtime-configuration).

Let's talk about each solution in detail.

## Locally Scoped Runtime Configuration

In ZIO all runtime configurations are inherited from their parent workflows. So whenever we access a runtime configuration, or obtain a runtime inside a workflow, we are accessing the runtime of the parent workflow. We can override the runtime configuration of the parent workflow by providing a new configuration to a region of the code. This is called locally scoped runtime configuration. When the execution of that region is finished, the runtime configuration will be restored to its original value.

We mainly use `ZIO#provideXYZ` operators to provide a new runtime configuration to a specific region of the code:

### Configuring Runtime by Providing Configuration Layers

By providing (`ZIO#provideXYZ`) runtime configuration layers to a ZIO workflow, we can change the runtime configs easily:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  val addSimpleLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.addLogger((_, _, _, message: () => Any, _, _, _, _) => println(message()))

  def run = {
    for {
      _ <- ZIO.log("Application started!")
      _ <- ZIO.log("Application is about to exit!")
    } yield ()
  }.provide(Runtime.removeDefaultLoggers ++ addSimpleLogger)
}
```

The output:

```scala
Application started!
Application is about to exit!
```

To provide runtime configuration to a specific region of a ZIO application, we should provide the configuration layer only to that specific region:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  val addSimpleLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.addLogger((_, _, _, message: () => Any, _, _, _, _) => println(message()))

  def run =
    for {
      _ <- ZIO.log("Application started!")
      _ <- {
        for {
          _ <- ZIO.log("I'm not going to be logged!")
          _ <- ZIO.log("I will be logged by the simple logger.").provide(addSimpleLogger)
          _ <- ZIO.log("Reset back to the previous configuration, so I won't be logged.")
        } yield ()
      }.provide(Runtime.removeDefaultLoggers)
      _ <- ZIO.log("Application is about to exit!")
    } yield ()
}
```

The output:

```scala
timestamp=2022-08-31T14:28:34.711461Z level=INFO thread=#zio-fiber-6 message="Application started!" location=<empty>.MainApp.run file=ZIOApp.scala line=9
I will be logged by the simple logger.
timestamp=2022-08-31T14:28:34.832035Z level=INFO thread=#zio-fiber-6 message="Application is about to exit!" location=<empty>.MainApp.run file=ZIOApp.scala line=17
```

### Configuring Runtime Using `bootstrap` Layer

The `bootstrap` layer is a special layer that is mainly used to acquire and release services that are necessary for the application to run. However, it can also be applied to runtime customization as well. This solution requires us to override the `bootstrap` layer from the `ZIOApp` trait.

By using this technique, after initialization of the top-level runtime, it will provide the `bootstrap` layer to the ZIO application given through the `run` method.

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  val addSimpleLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.addLogger((_, _, _, message: () => Any, _, _, _, _) => println(message()))

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ addSimpleLogger

  def run =
    for {
      _ <- ZIO.log("Application started!")
      _ <- ZIO.log("Application is about to exit!")
    } yield ()
}
```

The output:

```scala
Application started!
Application is about to exit!
```

Although using this method will apply the configuration layer to the whole ZIO application, it is categorized as local runtime configuration because the `bootstrap` layer is evaluated and applied after the top-level runtime is initialized. So it will only be applied to the ZIO application given through the `run` method.

To elaborate more on this, let's look at the following example:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  val addSimpleLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.addLogger((_, _, _, message: () => Any, _, _, _, _) => println(message()))
  
  val effectfulConfiguration: ZLayer[Any, Nothing, Unit] =
    ZLayer.fromZIO(ZIO.log("Started effectful workflow to customize runtime configuration"))

  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ addSimpleLogger ++ effectfulConfiguration

  def run =
    for {
      _ <- ZIO.log("Application started!")
      _ <- ZIO.log("Application is about to exit!")
    } yield ()
}
```

What do we expect to see as the output? We have `Runtime.removeDefaultLoggers` which removes the default logger from the runtime. So we expect to see log messages only from the simple logger. But that is not the case. We have an effectful configuration layer that is evaluated after the top-level runtime is initialized. So we can see the log message related to the initialization of `effectfulConfiguration` layer from the default logger:

```scala
timestamp=2022-09-01T08:07:47.870219Z level=INFO thread=#zio-fiber-6 message="Started effectful workflow to customize runtime configuration" location=<empty>.MainApp.effectfulConfiguration file=ZIOApp.scala line=8
Application started!
Application is about to exit!
```

## Top-level Runtime Configuration

When we write a ZIO application using the `ZIOAppDefault` trait, a default top-level runtime is created and used to run the application automatically under the hood. Further, we can customize the rest of the ZIO application by providing locally scoped configuration layers using [`provideXYZ` operations](#configuring-runtime-by-providing-configuration-layers) or [`bootstrap` layer](#configuring-runtime-using-bootstrap-layer).

This is usually sufficient for lots of ZIO applications, but it is not always the case. There are cases where we want to customize the runtime of the entire ZIO application from the top level.

In such cases, we need to create a top-level runtime by unsafely running the configuration layer to convert that configuration to the `Runtime` by using the `Runtime.unsafe.fromLayer` operator:

```scala mdoc:invisible
import zio._
val layer = ZLayer.empty
```

```scala mdoc:compile-only
val runtime: Runtime[Any] =
  Unsafe.unsafe { implicit unsafe =>
    Runtime.unsafe.fromLayer(layer)
  }
```

Let's try a fully working example:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  // In a real-world application we might need to implement a `sl4jlogger` layer
  val addSimpleLogger: ZLayer[Any, Nothing, Unit] =
    Runtime.addLogger((_, _, _, message: () => Any, _, _, _, _) => println(message()))

  val layer: ZLayer[Any, Nothing, Unit] =
    Runtime.removeDefaultLoggers ++ addSimpleLogger

  override val runtime: Runtime[Any] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe.fromLayer(layer)
    }

  def run = ZIO.log("Application started!")
}
```

:::caution
Keep in mind that only the "bootstrap" layer of applications will be combined when we compose two ZIO applications. Therefore, when we compose two ZIO programs, top-level runtime configurations won't be integrated.
:::

Another use-case of top-level runtimes is when we want to integrate our ZIO application inside a legacy application:

```scala mdoc:compile-only
import zio._

object MainApp {
  val sl4jlogger: ZLogger[String, Any] = ???

  def legacyApplication(input: Int): Unit = ???

  val zioWorkflow: ZIO[Any, Nothing, Int] = ???

  val runtime: Runtime[Unit] =
    Unsafe.unsafe { implicit unsafe =>
      Runtime.unsafe
        .fromLayer(
          Runtime.removeDefaultLoggers ++ Runtime.addLogger(sl4jlogger)
        )
    }

  def zioApplication(): Int =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(zioWorkflow)
        .getOrThrowFiberFailure()
    }

  def main(args: Array[String]): Unit = {
    val result = zioApplication()
    legacyApplication(result)
  }

}
```

## Providing Environment to Runtime System

The custom runtime can be used to run many different effects that all require the same environment, so we don't have to call `ZIO#provide` on all of them before we run them.

For example, assume we want to create a `Runtime` for services that are for testing purposes, and they don't interact with real external APIs. So we can create a runtime especially for testing.

Let's say we have defined two `LoggingService` and `EmailService` services:

```scala mdoc:silent:nest
trait LoggingService {
  def log(line: String): UIO[Unit]
}

object LoggingService {
  def log(line: String): URIO[LoggingService, Unit] =
    ZIO.serviceWithZIO[LoggingService](_.log(line))
}

trait EmailService {
  def send(user: String, content: String): Task[Unit]
}

object EmailService {
  def send(user: String, content: String): ZIO[EmailService, Throwable, Unit] =
    ZIO.serviceWithZIO[EmailService](_.send(user, content))
}
```

We are going to implement a live version of `LoggingService` and also a fake version of `EmailService` for testing:

```scala mdoc:silent:nest
case class LoggingServiceLive() extends LoggingService {
  override def log(line: String): UIO[Unit] =
    ZIO.succeed(print(line))
}

case class EmailServiceFake() extends EmailService {
  override def send(user: String, content: String): Task[Unit] =
    ZIO.attempt(println(s"sending email to $user"))
}
```

Let's create a custom runtime that contains these two service implementations in its environment:

```scala mdoc:silent:nest
val testableRuntime = Runtime(
  ZEnvironment[LoggingService, EmailService](LoggingServiceLive(), EmailServiceFake()),
  FiberRefs.empty,
  RuntimeFlags.default
)
```

Also, we can replace the environment of the default runtime with our own custom environment, which allows us to add new services to the ZIO environment:

```scala mdoc:silent:nest
val testableRuntime: Runtime[LoggingService with EmailService] =
  Runtime.default.withEnvironment {
    ZEnvironment[LoggingService, EmailService](LoggingServiceLive(), EmailServiceFake())
  }
```

Now we can run our effects using this custom `Runtime`:

```scala mdoc:silent:nest
Unsafe.unsafe { implicit unsafe =>
    testableRuntime.unsafe.run(
      for {
        _ <- LoggingService.log("sending newsletter")
        _ <- EmailService.send("David", "Hi! Here is today's newsletter.")
      } yield ()
    ).getOrThrowFiberFailure()
}
```
