---
id: index
title: "Introduction to the ZIO's Contextual Data Types"
sidebar_label: "Introduction"
---

ZIO provides a contextual abstraction that encodes the environment of the running effect. This means, every effect can work within a specific context, called an environment.

So when we have a `ZIO[R, E, A]` effect, we can say "given `R` as the environment of the effect, the effect may fail with an error type of `E`, or may succeed with a value of type `A`".

For example, when we have an effect of type `ZIO[DatabaseConnection, IOException, String]`, we can say that our effect works within the context of `DatabaseConnection`. In other words, we can say that our effect requires the `DatabaseConnection` service as a context to run.

We will see how layers can be used to eliminate the environment of an effect:

```scala mdoc:compile-only
import zio._

import java.io.IOException

trait DatabaseConnection

// An effect which requires DatabaseConnection to run
val effect: ZIO[DatabaseConnection, IOException, String] = ???

// A layer that produces DatabaseConnection service
val dbConnection: ZLayer[Any, IOException, DatabaseConnection] = ???

// After applying dbConnection to our environmental effect the reurned
// effect has no dependency on the DatabaseConnection
val eliminated: ZIO[Any, IOException, String] = 
  dbConnection { // Provides DatabaseConnection context
    effect       // An effect running within `DatabaseConnection` context
  }
```

ZIO provides this facility through the following concepts and data types:
1. [ZIO Environment](#1-zio-environment) — The `R` type parameter of `ZIO[R, E, A]` data type.
2. [ZEnvironment](#2-zenvironment) — Built-in type-level map for maintaining the environment of a `ZIO` data type. 
3. [ZLayer](#3-zlayer) — Describes how to build one or more services in our application.

Next, we will discuss _ZIO Environment_ and _ZLayer_ and finally how to write ZIO services using the _Service Pattern_.

## 1. ZIO Environment

The `ZIO[-R, +E, +A]` data type describes an effect that requires an input of type `R`, as an environment, may fail with an error of type `E`, or succeed with a value of type `A`.

The input type is also known as _environment type_. This type-parameter indicates that to run an effect we need one or some services as an environment of that effect. In other word, `R` represents the _requirement_ for the effect to run, meaning we need to fulfill the requirement in order to make the effect _runnable_.

So we can think of `ZIO[R, E, A]` as a mental model of a function from a value of type `R` to the `Either[E, A]`:

```scala
type ZIO[R, E, A] = R => Either[E, A]
```

`R` represents dependencies; whatever services, config, or wiring a part of a ZIO program depends upon to work. We will explore what we can do with `R`, as it plays a crucial role in `ZIO`.

```scala mdoc:invisible:reset

```

### Motivation

One might ask "What is the motivation behind encoding the dependency in the type parameter of `ZIO` data type"? What is the benefit of doing so?

Let's see how writing an application which requires reading from or writing to the terminal. As part of making the application _modular_ and _testable_ we define a separate service called `Terminal` which is responsible for reading from and writing to the terminal. We do that simply by writing an interface:

```scala mdoc:silent
import zio._

trait Terminal {
  def print(line: Any): Task[Unit]

  def printLine(line: Any): Task[Unit]

  def readLine: Task[String]
}
```

Now we can write our application that accepts the `Terminal` interface as a parameter:

```scala mdoc:silent
import zio._

def myApp(c: Terminal): Task[Unit] =
  for {
    _    <- c.print("Please enter your name: ")
    name <- c.readLine
    _    <- c.printLine(s"Hello, $name!")
  } yield ()
```

Similar to the object-oriented paradigm we code to interface not implementation. In order to run the application, we need to implement a production version of the `Terminal`:

```scala mdoc:silent
import zio._

object TerminalLive extends Terminal {
  override def print(line: Any): Task[Unit] =
    ZIO.attemptBlocking(scala.Predef.print(line))

  override def printLine(line: Any): Task[Unit] =
    ZIO.attemptBlocking(scala.Predef.println(line))

  override def readLine: Task[String] =
    ZIO.attemptBlocking(scala.io.StdIn.readLine())
}
```

Finally, we can provide the `TerminalLive` to our application and run the whole:

```scala mdoc:fail:silent
import zio._

object MainApp extends ZIOAppDefault {
  def myApp(c: Terminal): Task[Unit] =
    for {
      _    <- c.print("Please enter your name: ")
      name <- c.readLine
      _    <- c.printLine(s"Hello, $name!")
    } yield ()

  def run = myApp(TerminalLive)
}
```

```scala mdoc:invisible:reset

```

In the above example, we discard the fact that we could use the ZIO environment and utilize the `R` parameter of the `ZIO` data type. So instead we tried to write the application with the `Task` data type, which ignores the ZIO environment. To create our application testable, we gathered all terminal functionalities into the same interface called `Terminal`, and implemented that in another object called `TerminalLive`. Finally, at the end of the day, we provide the implementation of the `Terminal` service, i.e. `TerminalLive`, to our application.

**While this technique works for small programs, it doesn't scale.** Assume we have multiple services, and we use them in our application logic like below:

```scala
def foo(
   s1: Service1,
   s2: Service2,
   s3: Service3
)(arg1: String, arg2: String, arg3: Int): Task[Int] = ???

def bar(
  s1: Service1,
  s12: Service12,
  s18: Service18, 
  sn: ServiceN
)(arg1: Int, arg2: String, arg3: Double, arg4: Int): Task[Unit]

def myApp(s1: Service1, s2: Service2, ..., sn: ServiceN): Task[Unit] = 
  for {
    a <- foo(s1, s2, s3)("arg1", "arg2", 4) 
    _ <- bar(s1, s12, s18, sn)(7, "arg2", 1.2, a)
      ...
  } yield ()
```

Writing real applications using this technique is tedious and cumbersome because all dependencies have to be passed across all methods. We can simplify the process of writing our application by using the ZIO environment and [Service Pattern](../service-pattern/service-pattern.md).

```scala
def foo(arg1: String, arg2: String, arg3: Int): ZIO[Service1 & Service2 & Service3, Throwable, Int] = 
  for {
    s1 <- ZIO.service[Service1]
    s2 <- ZIO.service[Service2] 
      ...
  } yield ()

def bar(arg1: Int, arg2: String, arg3: Double, arg4: Int): ZIO[Service1 & Service12 & Service18 & ServiceN, Throwable, Unit] =
  for {
    s1  <- ZIO.service[Service1] 
    s12 <- ZIO.service[Service12]
      ...
  } yield ()
```

### Advantage of Using ZIO Environment

ZIO environment facility enables us to:

1. **Code to Interface** — Like object-oriented paradigm, in ZIO we are encouraged to code to interface and defer the implementation. It is the best practice, but ZIO does not enforce us to do that.

2. **Write a Testable Code** — By coding to an interface, whenever we want to test our effects, we can easily mock any external services, by providing a _test_ version of those instead of the _live_ version.

3. **Compose Services with Strong Type Inference Facility** — We can compose multiple effects that require various services, so the final effect requires the intersection of all those services:

```scala mdoc:compile-only
import zio._

trait ServiceA
trait ServiceB
trait ServiceC

// Requires ServiceA and produces a value of type Int
def foo: ZIO[ServiceA, Nothing, Int] = ???

// Requires ServiceB and ServiceC and produces a value of type String
def bar: ZIO[ServiceB & ServiceC, Throwable, String] = ???

// Requires ServicB and produces a value of type Double
def baz(a: Int, b: String): ZIO[ServiceB, Nothing, Double] = ???

// Requires ServiceA and ServiceB and ServiceC and produces a value of type Double
val myApp: ZIO[ServiceA & ServiceB & ServiceC, Throwable, Double] =
  for {
    a <- foo
    b <- bar
    c <- baz(a, b)
  } yield c
```

Another important note about the ZIO environment is that the type inference works well on effect composition. After we composed all the application logic together, the compiler and also IDE can infer the proper type for the environment of the final effect.

In the example above, the compiler can infer the environment type of the `myApp` effect which is `ServiceA & ServiceB & ServiceC`.

### Accessing ZIO Environment

We have two types of accessors for the ZIO environment:
1. **Service Accessor (`ZIO.service`)** is used to access a specific service from the environment.
2. **Service Member Accessors (`ZIO.serviceWith` and `ZIO.serviceWithZIO`)** are used to access capabilities of a specific service from the environment.

:::note

To access the entire ZIO environment we can use `ZIO.environment*`, but we do not use these methods regularly to access ZIO services. Instead, we use service accessors and service member accessors.
:::

#### Service Accessor

To access a service from the ZIO environment, we can use the `ZIO.service` constructor. For example, in the following program we are going to access the `AppConfig` from the environment:

```scala mdoc:silent
import zio._

case class AppConfig(host: String, port: Int)

val myApp: ZIO[AppConfig, Nothing, Unit] =
  for {
    config <- ZIO.service[AppConfig]
    _      <- ZIO.logInfo(s"Application started with config: $config")
  } yield ()
```

To run the `myApp` effect, we should provide the `AppConfig` layer (we will talk about `ZLayer` on the next section):

```scala mdoc:compile-only
object MainApp extends ZIOAppDefault {
  def run = myApp.provide(ZLayer.succeed(AppConfig("localhost", 8080)))
}
```

```scala mdoc:invisible:reset

```

To access multiple services from the ZIO environment, we can do the same:

```scala mdoc:compile-only
import zio._

trait Foo
trait Bar
trait Baz

for {
  foo <- ZIO.service[Foo]  
  bar <- ZIO.service[Bar]
  bax <- ZIO.service[Baz]
} yield ()
```

When creating ZIO layers that have multiple dependencies, this can be helpful. We will discuss this pattern in the [Service Pattern](../service-pattern/service-pattern.md) section.

#### Service Member Accessors

Sometimes instead of accessing a service, we need to access the capabilities (members) of a service. Based on the return type of each capability, we can use one of these accessors:
- **ZIO.serviceWith**
- **ZIO.serviceWithZIO**

In [Service Pattern](../service-pattern/service-pattern.md), we use these accessors to write "accessor methods" for ZIO services.

Let's look at each one in more detail:

1. **ZIO.serviceWith** — When we are accessing service members whose return type is an ordinary value, we should use the `ZIO.serviceWith`.

In the following example, we need to use the `ZIO.serviceWith` to write accessor methods for all of the `AppConfig` members:

<!-- accessor methods are now deprecated, TODO: rewrite the following so it no longer needs accessor methods -->

```scala mdoc:compile-only
import zio._

case class AppConfig(host: String, port: Int, poolSize: Int)

object AppConfig {
  // Accessor Methods
  def host: ZIO[AppConfig, Nothing, String]  = ZIO.serviceWith(_.host) 
  def port: ZIO[AppConfig, Nothing, Int]     = ZIO.serviceWith(_.port)
  def poolSize: ZIO[AppConfig, Nothing, Int] = ZIO.serviceWith(_.poolSize)
}

val myApp: ZIO[AppConfig, Nothing, Unit] =
  for {
    host     <- AppConfig.host
    port     <- AppConfig.port
    _        <- ZIO.logInfo(s"The service will be service at $host:$port")
    poolSize <- AppConfig.poolSize
    _        <- ZIO.logInfo(s"Application started with $poolSize pool size")
  } yield ()
```

2. **ZIO.serviceWithZIO** — When we are accessing service members whose return type is a ZIO effect, we should use the `ZIO.serviceWithZIO`.

For example, in order to write the accessor method for the `foo` member of the `Foo` service, we need to use the `ZIO.serviceWithZIO` function:

<!-- accessor methods are now deprecated, TODO: rewrite the following so it no longer needs accessor methods
perhaps we can simply remove this?
-->

```scala mdoc:compile-only
import zio._

trait Foo {
  def foo(input: String): Task[Unit]
}

object Foo {
  // Accessor Method
  def foo(input: String): ZIO[Foo, Throwable, Unit] =
    ZIO.serviceWithZIO(_.foo(input))
}
```

## 2. ZEnvironment

`ZEnvironment` is a built-in type-level map for maintaining the environment of a `ZIO` data type. We don't typically use this data type directly. It's okay to skip learning it at the moment. We have a [separate article](zenvironment.md) about this data type.

## 3. ZLayer

`ZLayer[-RIn, +E, +ROut]` is a recipe to build an environment of type `ROut`, starting from a value `RIn`, and possibly producing an error `E` during creation.

