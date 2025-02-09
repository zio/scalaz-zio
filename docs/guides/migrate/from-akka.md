---
id: from-akka
title: "How to Migrate From Akka to ZIO?"
sidebar_label: "Migration From Akka"
---

## Overview

Here, we summarized alternative ZIO solutions for Akka Actor features. So before starting the migration, let's see an overview of corresponding features in ZIO:

| Topics                      | Akka                                        | ZIO                                        |
|-----------------------------|---------------------------------------------|--------------------------------------------|
| Parallelism                 | [Akka Actor][1]                             | [ZIO][2], [Concurrent Data Types][3]       |
| Concurrent State Management | [Akka Actor][1]                             | [Ref][75], [FiberRef][76], [ZState][77]    |
| Buffering Workloads         | [Akka Mailboxes][78]                        | [Queue][79]                                |
| Streaming                   | [Akka Streams][4]                           | [ZIO Streams][5]                           |
| HTTP Applications           | [Akka Http][55]                             | [ZIO HTTP][56]                             |
| Event Sourcing              | [Lagom Framework][6], [Akka Persistence][8] | [ZIO Entity][7], [Edomata][9]              |
| Entity Sharding             | [Akka Cluster Sharding][74]                 | [Shardcake][73]                            |
| Scheduling                  | [Akka Scheduler][10]                        | [Schedule data type][11]                   |
| Cron-like Scheduling        | [Akka Quartz Scheduler][12]                 | [Schedule data type][11]                   |
| Resiliency                  | [Akka CircuitBreaker][13]                   | [Schedule data type][11], [Rezilience][14] |
| Logging                     | [Built-in Support][15]                      | [Built-in Support][16], [ZIO Logging][92]  |
| Testing                     | [Akka Testkit][17]                          | [ZIO Test][18]                             |
| Testing Streams             | [Akka Stream Testkit][19]                   | [ZIO Test][18]                             |
| Metrics                     | [Cluster Metric Extension][20]              | [Metrics][21], [ZIO Metrics][93]           |
| Supervision                 | [Yes][22]                                   | Yes                                        |
| Monitoring                  | [Yes][22]                                   | Yes                                        |

There are also several integration libraries for Akka that cover a wide range of technologies. If you use any of these technologies, you have a chance to use the equivalent of them in the ZIO ecosystem:

| Tools                | Alpakka                            | ZIO Connect                            |
|----------------------|------------------------------------|----------------------------------------|
| gRPC                 | [Akka gRPC][23]                    | [ZIO gRPC][24]                         |
| GraphQL              | [Sangria][25]                      | [Caliban][26]                          |
| Apache Kafka         | [Alpakka Kafka][27]                | [ZIO Kafka][28]                        |
| AWS                  |                                    | [ZIO AWS][30]                          |
| AWS S3               | [Alpakka S3][29]                   | [ZIO S3][32]                           |
| AWS SNS              | [Alpakka SNS][31]                  | [ZIO AWS SNS][30]                      |
| AWS SQS              | [Alpakka SQS][33]                  | [ZIO SQS][34], [ZIO AWS SQS][30]       |
| AMQP                 | [Alpakka AMQP][35]                 | [ZIO AMQP][36]                         |
| AWS Kinesis          | [Alpakka Kinesis][37]              | [ZIO Kinesis][38]                      |
| AWS DynamoDB         | [Alpakka DynamoDB][39]             | [ZIO DynamoDB][40]                     |
| Pulsar               | [Pulsar4s][41]                     | [ZIO Pulsar][42]                       |
| AWS Lambda           | [Alpakka AWS Lambda][43]           | [ZIO Lambda][44], [ZIO AWS Lambda][30] |
|                      | [Alpakka Cassandra][45]            | [ZIO Cassandra][46]                    |
| Elasticsearch        | [Alpakka Elasticsearch][47]        | [ZIO Elasticsearch][48]                |
| FTP                  | [Alpakka FTP][49]                  | [ZIO FTP][50]                          |
|                      |                                    | [ZIO MongoDB][51]                      |
| Redis                |                                    | [ZIO Redis][52]                        |
| Data Codecs          | [Alpakka Avro Parquet][53]         | [ZIO Schema][54]                       |
|                      |                                    | [ZIO NIO][57]                          |
| Slick                | [Alpakka Slick][58]                | [ZIO Slick Interop][59]                |
| Google Cloud Pub/Sub | [Alpakka Google Cloud Pub/Sub][62] | [ZIO GCP Pub/Sub][63]                  |
| Google Cloud Storage | [Alpakka Google Cloud Storage][64] | [ZIO GCP Storage][63]                  |
| Json                 | [Alpakka JSON Streaming][65]       | [ZIO JSON][66]                         |
| OrientDB             | [Alpakka OrientDB][67]             | [ZIO Quill OrientDB][68]               |
| Logging              | [Akka SL4J][69]                    | [ZIO Logging SLF4J][70]                |
| Caching              | [Akka HTTP Caching][71]            | [ZIO Cache][72]                        |

We also have several other libraries that may not be covered by the Akka ecosystem, but you can still use them. So we encourage you to check the ecosystem section of the ZIO website; take a look at the libraries, and see if they are suitable for your requirements for the migration process.

## Akka From the Perspective of a ZIO Developer

### They Are Not Composable

Akka actors are modeled as a partial function from `Any` to `Unit`:

```scala
type Actor = PartialFunction[Any, Unit]
```

In other words, an actor accepts something and does something with it. Both its input and output are not well typed. It is just like a blackbox. We know that having these types of functions is not composable. So it is hard to write small pieces of actors and compose them together to build large applications.

While in ZIO everything is composable. We can write composable computational abstractions and compose them together to build large applications. Due to the support for referential transparency, we can reason about small pieces of code and then make sure the whole application is correct.

### When All We Have Is A Hammer

The primary tool in object-oriented programming is object. Objects bundle the data and behavior. In OOP evertything is an object. So we have one tool to solve all problems.

Akka actors are the same. Each actor is an object which has its own state and behavior. Except that Akka actors are not synchronous. We can send messages to them asynchronously. This is where the Akka actors are different from the traditional objects. But the philosophy is the same: everything is an actor. So we have one tool to solve all problems.

But in reality, we don't need to use actors for everything. Many computations do not require any state. We just need to transform the input and produce the output. In ZIO We can use functions for them. Composing functions allows us to build large applications. Although if we need a state, we can use recursive functions or `Ref` to model the state without losing referential transparency and composability.

Other than actors, there are many lightweight tools that can solve concurrency and parallelism problems:

- [Fiber][80]
- [Ref][82]
- [Promise][84]
- [Semaphore][86]
- [Queue][79]
- [Hub][85]
- [Synchronization Primitives][81]

So it doesn't make sense to use actors for everything. It is better to choose the right tool for the right problem.

### Eager Evaluation

The primary evaluation strategy in ZIO is lazy evaluation. In other words, our code isn't evaluated until it's actually needed. So when we write ZIO applications, we are defining a description of the program. This enables us to model the program as a data structure. We can then transform the data structure to build a new program. So this makes us more productive by having reusable and composable abstractions.

So a ZIO application, is composed of series of ZIO values that are description of the whole workflow, finally when we provide the entire application to the `run` method, the ZIO runtime will execute the application.

In contrast, in Akka, the primary evaluation strategy is eager evaluation, except for some part (e.g. Akka Typed). So when we writing Akka application, we are writing a sequence of instructions. This makes it hard to reuse and compose the pieces of code. We can't transform the instructions to build a new program.

### Akka Actors And Futures

Akka actors are modeled on top of Scala `Future`. Both `Future` and `Actor` are asynchronous. Futures are mainly used in Akka by the `Ask` and `PipeTo` methods.

The `Future` is a data structure that represents a value that may not yet be available. It models the asynchronous computation, but the most problem with `Future` is that it is not referentially transparent. So even though we can compose `Future`s together, we can't reason about the whole program. As a result, we cannot ensure that the whole program is correct.

Furthermore, Futures are already running asynchronous constructs, so we cannot control their execution. For example, assume we have two `Future`s; we want to run them concurrently and return the first one that finishes. It is possible to do it with `Future`, but we cannot cancel the already running loser `Future`.

The `ZIO` data type is an alternative construct for `Future`. It is referentially transparent. We can compose `ZIO` values together and reason about the whole program. So we can make sure the entire program is correct.

ZIO support a nice interruption mechanism. We can easily cancel any running `ZIO` workflow using `Fiber#interrupt`. Also, there are high-level APIs on top of interruption. For example, we have `race` operators, where we can run multiple ZIO workflows and get the first successful result: `ZIO.succeed("Hello!").delay(1.second).race(ZIO.succeed("Hi!").delay(2.second))`. ZIO automatically cancels the loser computation.

## Akka Use-cases

Before starting the migration, we need to understand what types of use-cases developers use Akka for.

Akka is a toolkit for building highly concurrent, distributed, and resilient message-driven applications. Here are the most common use-cases for Akka among developers:

1. [Parallelism](#1-parallelism)
2. [Concurrent State Management](#2-concurrent-state-management)
3. [Buffering Workloads](#3-buffering-workloads)
4. [Streaming](#4-streaming)
5. [HTTP Applications](#5-http-applications)
6. [Event Sourcing](#6-event-sourcing)
7. [Entity Sharding](#7-entity-sharding)
8. [Distributed Computing](#8-distributed-computing)

Let's see an example of each use-case in a simple application using Akka.

## 1. Parallelism

### Parallelism in Akka

We can achieve parallelism in Akka by creating multiple instances of an actor and sending messages to them. Akka takes care of how to route messages to these actors. In the following example, we have a simple `JobRunner` actor which accepts `Job` messages and runs them one by one:

```scala mdoc:silent
import akka.actor._

case class Job(n: Int)

class JobRunner extends Actor {
  override def receive = { case Job(n) =>
    println(s"job$n — started")
    Thread.sleep(1000)
    println(s"job$n — finished")
  }
}
```

If we have plenty of jobs to run, we can create a pool of `JobRunner` actors and send them jobs to make them run in parallel:

```scala mdoc:compile-only
import akka.actor._
import akka.routing.RoundRobinPool

object MainApp extends scala.App {
  val actorSystem = ActorSystem("parallel-app")
  val jobRunner = actorSystem.actorOf(
    Props[JobRunner].withRouter(RoundRobinPool(4)),
    "job-runner"
  )

  for (job <- (1 to 10).map(Job)) {
    jobRunner ! job
  }
}
```

### Parallelism in ZIO

In ZIO we can achieve the same functionality easily by using `ZIO.foreachPar` operators:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  def jobRunner(n: Int) =
    for {
      _ <- Console.printLine(s"job$n - started")
      _ <- ZIO.sleep(1.second)
      _ <- Console.printLine(s"job$n - finished")
    } yield ()

  val jobs = (1 to 10)
  
  def run = ZIO.foreachParDiscard(jobs)(jobRunner)
}
```

We use `ZIO.withParallelism` operator to change the default parallelism factor:

```scala
ZIO.withParallelism(4) {
  ZIO.foreachParDiscard(jobs)(jobRunner)
} 
```

## 2. Concurrent State Management

### State Management in Akka

The main purpose of Akka actors is to write concurrent stateful applications. Using Akka actors, we can have stateful actors without worrying about concurrent access to the shared state. In the following example, we have a simple `Counter` actor which accepts `inc` and `dec` messages and increments or decrements its internal state:

```scala mdoc:silent
import akka.actor.Actor

class Counter extends Actor {
  private var state = 0

  override def receive: Receive = {
    case "inc" =>
      state += 1
    case "dec" =>
      state -= 1
    case "get" =>
      sender() ! state
  }
}
```

Now we can create an instance of the `Counter` actor and send it `inc` and `dec` messages to increment and decrement its internal state:

```scala mdoc:compile-only
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success}

object MainApp extends App {
  val system = ActorSystem("counter-app")
  val counterActor = system.actorOf(Props[Counter], "counter")

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = Timeout(1.second)

  counterActor ! "inc"
  counterActor ! "inc"
  counterActor ! "inc"
  counterActor ! "dec"

  (counterActor ? "get").onComplete {
    case Success(v) =>
      println(s"The current value of counter: $v")
    case Failure(e) =>
      println(s"Failed to receive the result from the counter: ${e.getMessage}")
  }

}
```

Outside the actor, we haven't access to its internal states, so we can't modify it directly. We can only send messages to the actor and let it handle the state management on its own. Using this approach, we can have safe concurrent state management. If multiple actors send messages to this actor concurrently, they can't make the state inconsistent.

### State Management in ZIO

State management is very easy in ZIO in presence of the `Ref` data type. `Ref` models a mutable state which is safe for concurrent access:

```scala mdoc:silent:nest
import zio._

case class Counter(state: Ref[Int]) {
  def inc = state.update(_ + 1)
  def dec = state.update(_ - 1)
  def get = state.get
}

object Counter {
  def make = Ref.make(0).map(Counter(_))
}
```

That's it! Very simple! We now we can use the counter in a program:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run =
    for {
      c <- Counter.make
      _ <- c.inc <&> c.inc <&> c.inc <&> c.dec
      _ <- c.get.debug("The current value of the counter")
    } yield ()
}
```

## 3. Buffering Workloads

Sometimes we have to deal with a high volume of incoming requests. In spite of parallelism and concurrency, we may not be able to handle all incoming requests within a short period of time. In such cases, we can use a buffer to store incoming requests temporarily and process them later.

### Buffering Workloads in Akka

Each actor in Akka has a mailbox that is used to store incoming messages. The mailbox is a FIFO queue. Actors only process one message at a time. If an actor receives more messages than it can process, the messages will be pending in the mailbox:

```scala
import akka.actor.{Actor, ActorSystem, Props}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object MainApp extends scala.App {
  val actorSystem = ActorSystem("parallel-app")
  val worker = actorSystem.actorOf(Props[JobRunner], "worker")

  val jobs = (1 to 1000).map(Job)

  for (job <- jobs) {
    worker ! job
  }

  println("All messages were sent to the actor!")

  Await.result(actorSystem.whenTerminated, Duration.Inf)
}
```

If we run the above example, we can see that all messages are sent to the actor before, and the actor still processing messages from the mailbox one by one. By default, the mailbox is an unbounded FIFO queue. But we can also use a bounded mailbox or a custom priority mailbox.

### Buffering Workloads in ZIO

ZIO has a data type called `Queue` which is useful for buffering workloads:

```scala mdoc:silent
import zio._
import zio.stream._

trait Actor[-In] {
  def tell(i: In): UIO[Boolean]
}

object Actor {
  def make[In](receive: In => UIO[Unit]): ZIO[Scope, Nothing, Actor[In]] =
    for {
      queue <- Queue.unbounded[In]
      _     <- queue.take.flatMap(receive).forever.forkScoped
    } yield new Actor[In] {
      override def tell(i: In): UIO[Boolean] =
        queue.offer(i)
    }
}
```

Now we can send a high load of messages to the actor and the actor will process them one by one:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = ZIO.scoped {
    for {
      actor <- Actor.make[Int](n => ZIO.debug(s"processing job-$n").delay(1.second))
      _     <- ZIO.foreachParDiscard(1 to 1000)(actor.tell)
      _     <- ZIO.debug("All messages were sent to the actor!")
      _     <- ZIO.never
    } yield ()
  }
}
```

## 4. Streaming

### Streaming in Akka

Akka stream is developed on top of Akka actors with backpressure support. There are three main components in Akka streams:

1. Source
2. Sink
3. Flow

Here is a simple example of how to have a streaming app in Akka:

```scala mdoc:invisible:reset

```

```scala mdoc:compile-only
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.util.ByteString

import java.nio.file.Paths
import scala.concurrent._

object AkkaStreamApp extends App {
  implicit val system: ActorSystem = ActorSystem("stream")
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  val source = Source(1 to 100)
  val factorial = Flow[Int].scan(BigInt(1))((acc, next) => acc * next)
  val serialize = Flow[BigInt].map(num => ByteString(s"$num\n"))
  val sink = FileIO.toPath(Paths.get("factorials.txt"))

  source
    .via(factorial)
    .via(serialize)
    .runWith(sink)
    .onComplete(_ => system.terminate())
}
```

### Streaming in ZIO

ZIO Streams is a purely functional, composable, effectful, and resourceful streaming library. It provides a way to model streaming data processing as a pure function. It is built on top of ZIO and supports backpressure using a pull-based model.

Like the Akka terminology, ZIO streams have three main components:

1. [ZStream](../../reference/stream/zstream/index.md)
2. [ZPipeline](../../reference/stream/zpipeline.md)
3. [ZSink](../../reference/stream/zsink/index.md)

Let's see how to implement the same example in ZIO:

```scala mdoc:compile-only
import zio._
import zio.stream._

object ZIOStreamApp extends ZIOAppDefault {
  val source    = ZStream.fromIterable(1 to 100)
  val factorial = ZPipeline.scan(BigInt(1))((acc, next: Int) => acc * next)
  val serialize = ZPipeline.map((num: BigInt) => Chunk.fromArray(s"$num".getBytes))
  val sink      = ZSink.fromFileName("factorials.txt")

  def run = 
    source
      .via(factorial)
      .via(serialize).flattenChunks
      .run(sink)
}
```

## 5. HTTP Applications

### HTTP Applications in Akka

Akka HTTP is a library for building HTTP applications on top of Akka actors and streams. It supports both server-side and client-side HTTP applications:

```scala mdoc:invisible:reset

```

```scala mdoc:compile-only
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._

object AkkHttpServer extends App {
  implicit val system = ActorSystem(Behaviors.empty, "system")
  implicit val executionContext = system.executionContext

  Http().newServerAt("localhost", 8080).bind {
    path("hello") {
      get {
        complete(
          HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            "<h1>Say hello to akka-http</h1>"
          )
        )
      }
    }
  }
}
```

### HTTP Applications in ZIO

On the other hand, ZIO has a library called [ZIO HTTP][56] which is a pure functional library for building HTTP applications. It is on top of ZIO, ZIO Streams.

Let's see how the above Akka HTTP service can be written in ZIO:

```scala mdoc:compile-only
import zio.http._
import zio.http.template.Html
import zio.ZIOAppDefault

object ZIOHttpServer extends ZIOAppDefault {
  val routes =
    Routes(
      Method.GET / "hello" -> handler(Response.html(Html.fromString("<h1>Say hello to zio-http</h1>")))
    )

  def run = Server.serve(routes).provide(Server.defaultWithPort(8080))
}
```

## 6. Event Sourcing

### Event Sourcing in Akka

In event sourcing, we store the events that happened in the past and use them to reconstruct the current state of the application. Akka has a built-in solution called Akka Persistence.

In the following example, we have a simple `PersistentCounter` actor which accepts `inc` and `dec` messages and increments or decrements in its internal state and also sores incoming events in persistent storage. When the actor is restarted, it will recover its state from the persistent storage:

```scala mdoc:invisible:reset

```

```scala mdoc:compile-only
import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.persistence._
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success}

class PersistentCounter extends PersistentActor {
  private var state: Int = 0

  override def receive = {
    case "inc" => persist("inc")(_ => state += 1)
    case "dec" => persist("dec")(_ => state -= 1)
    case "get" => sender() ! state
  }

  override def receiveRecover = {
    case "inc" => state += 1
    case "dec" => state -= 1
  }

  override def receiveCommand: Receive = _ => ()

  override def persistenceId: String = "my-persistence-id"
}

object MainApp extends App {
  val system = ActorSystem("counter-app")
  val counter = system.actorOf(Props[PersistentCounter], "counter")

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global
  implicit val timeout: Timeout = Timeout(1.second)

  counter ! "inc"
  counter ! "inc"
  counter ! "inc"
  counter ! "dec"

  (counter ? "get").onComplete {
    case Success(v) =>
      println(s"Current value of the counter: $v")
    case Failure(e) =>
      println(s"Failed to receive the result from the counter: ${e.getMessage}")
  }

}
```

### Event Sourcing in ZIO

In the ZIO ecosystem, we have a community library called [ZIO Entity](https://github.com/thehonesttech/zio-entity) which aims to provide a distributed event-sourcing solution. Although it is not production-ready yet, there is nothing to worry about. We can write our toolbox or use other libraries from other functional effect ecosystems using ZIO Interop.

Note that based on your use case, you may don't need any event-sourcing framework or library. So before using any library, you should consider if you really need it or not. In many cases, this is very dependent on our domain and business requirements, and using a library may mislead you in the wrong direction.

Anyway, there is a purely functional library called [Edomata](https://edomata.ir/) which provides a simple solution for event sourcing. It is written in a tagless final style using [Cats Effect](https://typelevel.org/cats-effect/) and [Fs2](https://fs2.io/). We can use ZIO Interop to use the ZIO effect to run an Edomaton.

In the following example, we are going to use Edomata to implement a simple counter which is event sourced. To do so, we need to define Events, Commands, State, Rejections, and Transitions:

After finding out domain events, we can define them using enum or sealed traits. In this example, we have two `Increased` and `Decreased` events:

```scala
enum Event {
  case Increased, Decreased
}
```

Then we should define the commands our domain can handle:

```scala
enum Command {
  case Inc, Dec
}
```

To notify some information data to the outside world, we can use `Notification`s, let's define a simple notification data type:

```scala
case class Notification(message: String)
```

Now it's time to define the domain model and the state of the counter:

```scala
import cats.data.ValidatedNec
import edomata.core.{Decision, DomainModel}
import cats.implicits.*
import edomata.syntax.all.*

case class Counter(state: Int) {
  def inc = this.perform { Decision.accept(Event.Increased) }
  def dec = this.perform {
    if (state > 0) Decision.accept(Event.Decreased) else "decision rejected".reject
  }
}

object Counter extends DomainModel[Counter, Event, String] {
  override def initial: Counter = Counter(0)

  override def transition = {
    case Event.Increased => state => state.copy(state = state.state + 1).validNec
    case Event.Decreased => state => state.copy(state = state.state - 1).validNec
  }
}
```

We are ready to define the `CounterService`:

```scala
object CounterService extends Counter.Service[Command, Notification] {
  import App._
  def apply(): PureApp[Unit] = router {
    case Command.Inc =>
      for {
        counter <- state.decide(_.inc)
        _ <- publish(Notification(s"state is going to become ${counter.state}"))
      } yield ()
    case Command.Dec => state.decide(_.dec).void
  }
}
```

So far, we have defined an edomaton called `CounterService`. To run it, we need a backend:

```scala
import scala.concurrent.duration.*
import cats.effect.std.Console
import cats.effect.{Async, Concurrent, Resource}
import cats.data.EitherNec
import edomata.core.{CommandMessage, DomainService}
import edomata.skunk.{BackendCodec, CirceCodec, SkunkBackend}
import edomata.backend.Backend
import edomata.skunk.SkunkBackend.PartialBuilder
import edomata.syntax.all.liftTo
import fs2.io.net.Network
import skunk.Session
import io.circe.generic.auto.*
import natchez.Trace
import natchez.Trace.Implicits.noop
import zio.*
import zio.interop.catz.*

object BackendService {
  given BackendCodec[Event] = CirceCodec.jsonb // or .json
  given BackendCodec[Notification] = CirceCodec.jsonb
  given BackendCodec[Counter] = CirceCodec.jsonb
  given Network[Task] = Network.forAsync[Task]
  given Trace[Task] = Trace.Implicits.noop
  given Console[Task] = Console.make[Task]

  def backend =
    SkunkBackend(
      Session
        .single("localhost", 5432, "postgres", "postgres", Some("postgres"))
    )

  def buildBackend =
    backend
      .builder(CounterService, "counter")
      .withRetryConfig(retryInitialDelay = 2.seconds)
      .persistedSnapshot(200)
      .build
      .toScopedZIO

  type Service = CommandMessage[Command] => RIO[Scope, EitherNec[String, Unit]]

  def service: ZIO[Scope, Throwable, Service] =
    buildBackend
      .map(_.compile(CounterService().liftTo[Task]))
}
```

To demonstrate how the backend works, we can write a simple web service that accepts `Inc` and `Dec` commands:

```scala
import zio.*
import zio.http.*
import edomata.core.CommandMessage
import BackendService.Service
import java.time.Instant

object ZIOCounterHttpApp {

  def apply(service: Service) =
    Routes(
      // command: inc or dec
      // GET /{edomaton address}/{command}/{command id}
      Method.GET / string("address") / string("command") / string("commandId") ->
        handler { (address: String, command: String, commandId: String, _: Request) =>
          val cmd = command match {
            case "inc" => Command.Inc
            case "dec" => Command.Dec
          }

          service(CommandMessage(commandId, Instant.now, address, cmd))
            .map(r => Response.text(r.toString))
            .orDie
        })
}
```

Now we can wire everything and run the application:

```scala
import zio.*
import zio.interop.catz.*
import zio.http.*
import cats.effect.std.Console

object MainApp extends ZIOAppDefault {
  given Console[Task] = Console.make[Task]

  def run =
    ZIO.scoped {
      for {
        backendService <- BackendService.service
        _ <- ZIOCounterHttpApp(backendService).serve.provide(Server.defaultWithPort(8090))
      } yield ()
    }
}
```

To test the application, we can send the following requests:

```http
GET /FooCounter/inc/cf2209c9-6b41-44da-8c52-7e0dce109dc3
GET /FooCounter/inc/11aa920d-254e-4aa7-86f2-8002df80533b
GET /FooCounter/dec/b2e8a02c-77db-463d-8213-d462fc5a9439
GET /FooCounter/inc/9ac51e44-36d0-4daa-ac23-28e624bec174
```

If multiple commands with the same command id are sent, the backend only processes the first one and ignores the rest. If the command is rejected, the backend will not accept any subsequent commands.

To see all the events or the current state associated with the `FooCounter` adomaton, we can use the `Backend#repository` to query the database:

```scala
import zio.*
import zio.interop.catz.*
import zio.stream.interop.fs2z._
import cats.effect.std.Console

object ZIOStateAndHistory extends ZIOAppDefault {
  given Console[Task] = Console.make[Task]

  def run =
    ZIO.scoped {
      for {
        backendService <- BackendService.buildBackend.orDie
        history <- backendService.repository
          .history("FooCounter")
          .toZStream()
          .runCollect
        state <- backendService.repository.get("FooCounter")
        _ <- ZIO.debug(s"FooCounter History: $history")
        _ <- ZIO.debug(s"FooCounter State: $state")
      } yield ()
    }
}

// Output:
// FooCounter History: Chunk(Valid(Counter(0),0),Valid(Counter(1),1),Valid(Counter(2),2),Valid(Counter(1),3),Valid(Counter(2),4))
// FooCounter State: Valid(Counter(2),4)
```

That's it! By using functional programming instead of Akka actors, we implemented a simple event sourced counter.

## 7. Entity Sharding

Entity sharding is a technique for distributing a large number of entities across a cluster of nodes. It reduces resource contention by sharding the entities across the nodes. It also provides a way to scale out the system by adding more nodes to the system.

### Entity Sharding in Akka

Akka has a module called Akka Cluster Sharding that provides a way to distribute entities. Without further ado, in the following example, we are going to shard instances of the `Counter` entity type and then create a web service that can be used to increment or decrement each entity.

```scala mdoc:invisible:reset

```

```scala mdoc:silent
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object Counter {
  sealed trait Message
  case class Increase(replyTo: ActorRef[Int]) extends Message
  case class Decrease(replyTo: ActorRef[Int]) extends Message

  def apply(entityId: String): Behavior[Message] = {
    def updated(value: Int): Behavior[Message] = {
      Behaviors.receive { (context, command) =>
        val log = context.log
        val address = context.system.address
        command match {
          case Increase(replyTo) =>
            log.info(s"executing inc msg for $entityId entity inside $address")
            val state = value + 1
            replyTo ! state
            updated(state)
          case Decrease(replyTo) =>
            log.info(s"executing dec msg for $entityId entity inside $address")
            val state = value - 1
            replyTo ! state
            updated(state)
        }
      }
    }
    updated(0)
  }
}
```

Now, it's time to create a simple web service that can be used to receive the `inc` and `dec` commands from clients:

```scala mdoc:silent
import akka.actor.typed.ActorSystem

import scala.concurrent.duration.DurationInt
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.util.Timeout

object CounterHttpApp {
  implicit val timeout: Timeout = 1.seconds

  def routes(implicit
      system: ActorSystem[ShardingEnvelope[Counter.Message]]
  ): Route = {
    path(Segment / Segment) { case (entityId, command) =>
      get {
        val response = system.ask[Int](askReplyTo =>
          ShardingEnvelope(
            entityId,
            command match {
              case "inc" => Counter.Increase(askReplyTo)
              case "dec" => Counter.Decrease(askReplyTo)
            }
          )
        )
        onComplete(response) { value =>
          complete(value.toString)
        }
      }
    }
  }
}
```

To be able to shard instances of the `Counter` entity, let's define the guardian behavior:

```scala mdoc:silent
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl._

object Guardian {
  def apply(): Behavior[ShardingEnvelope[Counter.Message]] =
    Behaviors.setup { context =>
      val TypeKey: EntityTypeKey[Counter.Message] =
        EntityTypeKey[Counter.Message]("counter")
      val clusterSharding = ClusterSharding(context.system)
      val shardRegion =
        clusterSharding.init(Entity(TypeKey)(c => Counter(c.entityId)))
      Behaviors.receiveMessage { msg =>
        shardRegion ! msg
        Behaviors.same
      }
    }
}
```

To be able to run multiple instances of the application, we need to define a seed node and also let the application read the port number from the environment. So, let's create the `application.conf` file in the `src/main/resources` directory:

```hocon
akka {
  actor {
    allow-java-serial ization =true 
    provider = "cluster"
  }
  remote.artery.canonical {
    hostname = "127.0.0.1" 
    port = 2551
    port=${?PORT}
  }
  cluster.seed-nodes= ["akka://system@127.0.0.1:2551"]
}

webservice {
  host = "127.0.0.1"
  port = 8082
  port = ${?HTTP_PORT}
}
```

The final step is to wire everything together to create the application:

```scala mdoc:compile-only
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.http.scaladsl.Http
import com.typesafe.config.ConfigFactory

object AkkaClusterShardingExample extends App {
  val config = ConfigFactory.load("application.conf")

  implicit val system: ActorSystem[ShardingEnvelope[Counter.Message]] =
    ActorSystem(Guardian(), "system", config)

  Http()
    .newServerAt(
      config.getString("webservice.host"),
      config.getInt("webservice.port")
    )
    .bind(CounterHttpApp.routes)
}
```

To run the application, we need to start the seed node first:

```bash
sbt -DHTTP_PORT=8081 -DPORT=2551 "runMain AkkaClusterShardingExample"
```

Then, we can start some more nodes:

```bash
sbt -DHTTP_PORT=8082 -DPORT=2552 "runMain AkkaClusterShardingExample"
sbt -DHTTP_PORT=8083 -DPORT=2553 "runMain AkkaClusterShardingExample"
```

Now, we can send some requests to any of theses nodes:

```bash
GET http://localhost:8081/foo/inc
GET http://localhost:8082/foo/inc
GET http://localhost:8083/foo/inc

GET http://localhost:8081/bar/inc
GET http://localhost:8082/bar/inc
GET http://localhost:8083/bar/inc

// ...
```

We can see that the each of `foo` and `bar` entities will be executed in a single node at a time, even if we are sending requests to different nodes.

### Entity Sharding in ZIO

In the ZIO community, there is a library called [Shardcake][73] that provides a purely functional API for entity sharding. It is highly configurable and customizable. Let's try to implement the same example as in the previous section using Shardcake.

First, we are going to define the `Counter` entity:

```scala mdoc:silent:reset
import com.devsisters.shardcake._
import zio._

sealed trait CounterMessage
object CounterMessage {
  case class Increase(replier: Replier[Int]) extends CounterMessage
  case class Decrease(replier: Replier[Int]) extends CounterMessage
}

object Counter extends EntityType[CounterMessage]("counter") {

  def handleMessage(
    entityId: String,
    state: Ref[Int],
    message: CounterMessage
  ): ZIO[Sharding, Nothing, Unit] =
    podPort.flatMap { port =>
      message match {
        case CounterMessage.Increase(replier) =>
          state
            .updateAndGet(_ + 1)
            .debug(s"The $entityId counter increased inside localhost:$port pod")
            .flatMap(replier.reply)
        case CounterMessage.Decrease(replier) =>
          state
            .updateAndGet(_ - 1)
            .debug(s"The $entityId counter decreased inside localhost:$port pod")
            .flatMap(replier.reply)
      }
    }

  def behavior(
    entityId: String,
    messages: Dequeue[CounterMessage]
  ): ZIO[Sharding, Nothing, Nothing] =
    Ref.make(0).flatMap { state =>
      messages.take.flatMap(handleMessage(entityId, state, _)).forever
    }

  def podPort: UIO[String] =
    System
      .env("PORT")
      .some
      .orDieWith(_ => new Exception("Application started without any specified port!"))
}
```

To be able to receive messages from the clients, let's define a web service:

```scala mdoc:silent
import com.devsisters.shardcake.{Messenger, Sharding}
import zio.http._
import zio.Scope

object WebService {
  def apply(counter: Messenger[CounterMessage]): Routes[Sharding with Scope, Nothing] =

    Routes(
      Method.GET / string("entityId") / "inc" -> handler { (entityId: String, _: Request) =>
        counter
          .send(entityId)(CounterMessage.Increase)
          .map(r => Response.text(r.toString))
      },
      Method.GET / string("entityId") / "dec" -> handler { (entityId: String, _: Request) =>
        counter
          .send(entityId)(CounterMessage.Decrease)
          .map(r => Response.text(r.toString))
      }
    ).sandbox
}
```

In this example, we are going to use Redis as the storage backend for the sharding. So, let's define a live layer for the sharding:

```scala mdoc:silent
import com.devsisters.shardcake.StorageRedis.Redis
import dev.profunktor.redis4cats.Redis
import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.RedisCodec
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import zio.interop.catz._
import zio._

object RedisLive {
  val layer: ZLayer[Any, Throwable, Redis] =
    ZLayer.scopedEnvironment {
      implicit val runtime: zio.Runtime[Any] = zio.Runtime.default
      
      implicit val logger: Log[Task] = new Log[Task] {
        override def debug(msg: => String): Task[Unit] = ZIO.logDebug(msg)
        override def error(msg: => String): Task[Unit] = ZIO.logError(msg)
        override def info(msg: => String): Task[Unit]  = ZIO.logInfo(msg)
      }

      (for {
        client   <- RedisClient[Task].from("redis://localhost")
        commands <- Redis[Task].fromClient(client, RedisCodec.Utf8)
        pubSub   <- PubSub.mkPubSubConnection[Task, String, String](client, RedisCodec.Utf8)
      } yield ZEnvironment(commands, pubSub)).toScopedZIO
    }
}
```

We also need a configuration layer for the sharding:

```scala mdoc:silent
import zio._
import com.devsisters.shardcake.Config

object ShardConfig {
  val layer: ZLayer[Any, SecurityException, Config] =
    ZLayer(
      System
        .env("PORT")
        .map(
          _.flatMap(_.toIntOption)
            .fold(Config.default)(port => Config.default.copy(shardingPort = port))
        )
    )
}
```

Now we are ready to create our application:

```scala mdoc:silent
import com.devsisters.shardcake._
import zio._
import zio.http.Server

object HttpApp extends ZIOAppDefault {

  def run: Task[Unit] =
    ZIO.scoped {
      for {
        port    <- System.env("HTTP_PORT").map(_.flatMap(_.toIntOption).getOrElse(8080))
        _       <- Sharding.registerEntity(Counter, Counter.behavior)
        _       <- Sharding.registerScoped
        counter <- Sharding.messenger(Counter)
        _       <- Server.serve(WebService(counter)).provideSome[Sharding & Scope](Server.defaultWithPort(port))
      } yield ()
    }.provide(
      ShardConfig.layer,
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(RedisConfig.default),
      RedisLive.layer,
      StorageRedis.live,
      KryoSerialization.live,
      ShardManagerClient.liveWithSttp,
      GrpcPods.live,
      Sharding.live,
      GrpcShardingService.live
    )
}
```

To manage sharding, we should run a separate application which is called `ShardManager`:

```scala mdoc:silent
import zio._
import com.devsisters.shardcake.interfaces._

object ShardManagerApp extends ZIOAppDefault {
  def run: Task[Nothing] =
     com.devsisters.shardcake.Server.run.provide(
      ZLayer.succeed(ManagerConfig.default),
      ZLayer.succeed(GrpcConfig.default),
      ZLayer.succeed(RedisConfig.default),
      RedisLive.layer,
      StorageRedis.live, // store data in Redis
      PodsHealth.local,  // just ping a pod to see if it's alive
      GrpcPods.live,     // use gRPC protocol
      ShardManager.live  // Shard Manager logic
    )
}
```

That's it! Now it's time to run the application. First, let's run an instance of Redis using docker:

```bash
docker run -d -p 6379:6379 --name sampleredis redis
```

Then, we can run the `ShardManager` application:

```bash
sbt "runMain ShardManagerApp"
```

Now, we can run multiple instances of `HttpApp`:

```
sbt -DHTTP_PORT=8081 -DPORT=8091 "runMain HttpApp"
sbt -DHTTP_PORT=8082 -DPORT=8092 "runMain HttpApp"
sbt -DHTTP_PORT=8083 -DPORT=8093 "runMain HttpApp"
```

Finally, we can send requests to the `HttpApp` instances:

```bash
curl http://localhost:8081/foo/inc
curl http://localhost:8082/foo/inc
curl http://localhost:8083/foo/inc

curl http://localhost:8081/bar/inc
curl http://localhost:8082/bar/inc
curl http://localhost:8083/bar/inc
```

At the same time, each entity is running only in one instance of `HttpApp`. So if we send a request for an entity to one of the instances of `HttpApp` where that entity doesn't belong, that request will be routed to the correct instance.

## 8. Distributed Computing

The most important feature of Akka is its distributed computing capabilities. It provides a set of well-established tools for building distributed systems, like Akka Cluster, Akka Distributed Data, Akka Remoting, Akka gRPC, etc.

ZIO has started a couple of projects to support distributed computing and there are also some community projects. However, some of them are still in the early stages of development. Hence, if you are heavily relying on distributed Akka technologies, you may need to make a decision with more caution.

In this section, we are going to iterate over the available options and what is the current progress:

First of all, we have a production-ready project for gRPC called [ZIO gRPC][24]. It is a ZIO wrapper around [ScalaPB][87]. It also supports streaming RPC calls using ZIO Streams.

The next fantastic project is [ZIO Schema][88]. Using ZIO Schema, you can define your data types as schemas and then generate codecs for them. It also supports distributed computing by providing a way to serialize and deserialize computations. So we can both move data and computations over the network and execute them remotely.

Again, as we [mentioned](#entity-sharding-in-zio) in this article, if you need to scale out your application using Entity Sharding, you can use [ShardCake][73]. It provides location transparency for your entities, and you can run them in a distributed manner.

ZIO has another project in development called [ZIO Flow][89]. It is a distributed workflow executor. We can think of `ZFlow` as a distributed version of `ZIO`. Using `ZFlow` we can describe a distributed workflow without worrying about the underlying concerns like transactional guarantees, fault tolerance, manual retries, etc. It is still in the early stages of development and it is not ready for production use.

[ZIO Keeper][90] is another project in development. It aims to provide solutions for the following distributed computing problems:

- Transport: A transport layer for sending and receiving messages between nodes. Currently, it supports unicast and broadcast messages.
- Membership: A membership layer for discovering nodes that are part of the same distributed system and also providing algorithms for joining and leaving the cluster. Currently, it uses SWIM and HyParView algorithms.
- Consensus: A consensus layer provides a solution for this problem: "What if the leader becomes unavailable?". Which is not developed yet.

There is also a work-in-progress implementation of the Raft protocol called [ZIO Raft][91] which is worth mentioning.

[1]: https://doc.akka.io/docs/akka/current/index.html
[2]: https://zio.dev/reference/core/zio/
[3]: https://zio.dev/reference/concurrency/
[4]: https://doc.akka.io/docs/akka/current/stream/index.html
[5]: ../../reference/stream
[6]: https://www.lagomframework.com/
[7]: https://github.com/thehonesttech/zio-entity
[8]: https://doc.akka.io/docs/akka/current/persistence.html
[9]: https://edomata.ir/
[10]: https://doc.akka.io/docs/akka/2.5.32/scheduler.html
[11]: ../../reference/schedule
[12]: https://github.com/enragedginger/akka-quartz-scheduler
[13]: https://doc.akka.io/docs/akka/current/common/circuitbreaker.html
[14]: https://www.vroste.nl/rezilience/
[15]: https://doc.akka.io/docs/akka/current/logging.html
[16]: ../../guides/tutorials/enable-logging-in-a-zio-application.md
[17]: https://doc.akka.io/docs/akka/current/typed/testing.html
[18]: ../../reference/test
[19]: https://doc.akka.io/docs/akka/current/stream/stream-testkit.html
[20]: https://doc.akka.io/docs/akka/current/cluster-metrics.html
[21]: ../../reference/observability/metrics
[22]: https://doc.akka.io/docs/akka/current/general/supervision.html
[23]: https://doc.akka.io/docs/akka-grpc/current/index.html
[24]: ../../ecosystem/community/zio-grpc.md
[25]: https://github.com/sangria-graphql/sangria-akka-http-example
[26]: ../../ecosystem/community/caliban.md
[27]: https://doc.akka.io/docs/alpakka-kafka/current/home.html
[28]: ../../zio-kafka/index.md
[29]: https://doc.akka.io/docs/alpakka/current/s3.html
[30]: ../../zio-aws/index.md
[31]: https://doc.akka.io/docs/alpakka/current/sns.html
[32]: https://github.com/zio/zio-s3
[33]: https://doc.akka.io/docs/alpakka/current/sqs.html
[34]: ../../zio-sqs/index.md
[35]: https://doc.akka.io/docs/alpakka/current/amqp.html
[36]: ../../ecosystem/community/zio-amqp.md
[37]: https://doc.akka.io/docs/alpakka/current/kinesis.html
[38]: ../../ecosystem/community/zio-kinesis.md
[39]: https://doc.akka.io/docs/alpakka/current/dynamodb.html
[40]: https://github.com/zio/zio-dynamodb
[41]: https://doc.akka.io/docs/alpakka/current/external/pulsar.html
[42]: ../../ecosystem/community/zio-pulsar.md
[43]: https://doc.akka.io/docs/alpakka/current/awslambda.html
[44]: https://github.com/zio/zio-lambda
[45]: https://doc.akka.io/docs/alpakka/current/cassandra.html
[46]: https://github.com/palanga/zio-cassandra
[47]: https://doc.akka.io/docs/alpakka/current/elasticsearch.html 
[48]: https://github.com/aparo/zio-elasticsearch
[49]: https://doc.akka.io/docs/alpakka/current/ftp.html
[50]: ../../zio-ftp/index.md
[51]: https://github.com/mbannour/zio-mongodb
[52]: ../../zio-redis/index.md
[53]: https://doc.akka.io/docs/alpakka/current/avroparquet.html
[54]: ../../zio-schema/index.md
[55]: https://doc.akka.io/docs/akka-http/current/index.html
[56]: https://github.com/zio/zio-http
[57]: ../../zio-nio/index.md
[58]: https://doc.akka.io/docs/alpakka/current/slick.html
[59]: ../../ecosystem/community/zio-slick-interop.md
[60]: https://doc.akka.io/docs/alpakka/current/external/tcp.html
[61]: https://github.com/searler/zio-tcp
[62]: https://doc.akka.io/docs/alpakka/current/google-cloud-pub-sub.html
[63]: https://github.com/zio/zio-gcp 
[64]: https://doc.akka.io/docs/alpakka/current/google-cloud-storage.html
[65]: https://doc.akka.io/docs/alpakka/current/data-transformations/json.html
[66]: ../../zio-json/index.md
[67]: https://doc.akka.io/docs/alpakka/current/orientdb.html
[68]: ../../zio-quill/index.md
[69]: https://doc.akka.io/docs/akka/current/typed/logging.html
[70]: ../../zio-logging/index.md
[71]: https://doc.akka.io/docs/akka-http/current/common/caching.html
[72]: ../../zio-cache/index.md
[73]: https://devsisters.github.io/shardcake/ 
[74]: https://doc.akka.io/docs/akka/current/typed/cluster-sharding.html
[75]: ../../reference/state-management/global-shared-state.md
[76]: ../../reference/state-management/fiberref.md
[77]: ../../reference/state-management/zstate.md
[78]: https://doc.akka.io/docs/akka/current/typed/mailboxes.html
[79]: ../../reference/concurrency/queue.md
[80]: ../../reference/fiber/fiber.md
[81]: ../../reference/sync/index.md
[82]: ../../reference/concurrency/ref.md
[83]: ../../reference/concurrency/refsynchronized.md
[84]: ../../reference/concurrency/promise.md
[85]: ../../reference/concurrency/hub.md
[86]: ../../reference/concurrency/semaphore.md
[87]: https://scalapb.github.io/ 
[88]: ../../zio-schema/index.md
[89]: https://github.com/zio/zio-flow 
[90]: https://zio.github.io/zio-keeper/
[91]: https://github.com/ariskk/zio-raft
[92]: ../../zio-logging/index.md
[93]: ../../zio-metrics/index.md