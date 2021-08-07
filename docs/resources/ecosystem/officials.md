---
id: officials 
title:  "Official ZIO Libraries"
---

Official ZIO libraries are hosted in the [ZIO organization](https://github.com/zio/) on Github, and are generally maintained by core contributors to ZIO.

Each project in the ZIO organization namespace has a _Stage Badge_ which indicates the current status of that project:

* **Production Ready** — The project is stable and already used in production. We can expect reliability for the implemented use cases.

* **Development** — The project already has RC or milestone releases, but is still under active development. We should not expect full stability yet.

* **Experimental** — The project is not yet released, but an important part of the work is already done.

* **Research** — The project is at the design stage, with some sketches of work but nothing usable yet.

* **Concept** — The project is just an idea, development hasn't started yet.

* **Deprecated** — The project is not maintained anymore, and we don't recommend its usage.

## ZIO Actors

[ZIO Actors](https://github.com/zio/zio-actors) is a high-performance, purely functional library for building, composing, and supervising typed actors based on ZIO.

### Introduction

ZIO Actors is based on the _Actor Model_ which is a conceptual model of concurrent computation. In the actor model, the _actor_ is the fundamental unit of computation, unlike the ZIO concurrency model, which is the fiber.

Each actor has a mailbox that stores and processes the incoming messages in FIFO order. An actor allowed to:
- create another actor.
- send a message to itself or other actors.
- handle the incoming message, and:
    - decide **what to do** based on the current state and the received message.
    - decide **what is the next state** based on the current state and the received message.

Some characteristics of an _Actor Model_:

- **Isolated State** — Each actor holds its private state. They only have access to their internal state. They are isolated from each other, and they do not share the memory. The only way to change the state of an actor is to send a message to that actor.

- **Process of One Message at a Time** — Each actor handles and processes one message at a time. They read messages from their inboxes and process them sequentially.

- **Actor Persistence** — A persistent actor records its state as events. The actor can recover its state from persisted events after a crash or restart.

- **Remote Messaging** — Actors can communicate with each other only through messages. They can run locally or remotely on another machine. Remote actors can communicate with each other transparently as if there are located locally.

- **Actor Supervision** — Parent actors can supervise their child actors. For example, if a child actor fails, the supervisor actor can restart that actor.

### Installation

To use this library, we need to add the following line to our library dependencies in `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-actors" % "0.0.9" 
```

### Example

Let's try to implement a simple Counter Actor which receives two `Increase` and `Get` commands:

```scala mdoc:silent:nest
import zio.actors.Actor.Stateful
import zio.actors._
import zio.clock.Clock
import zio.console.putStrLn
import zio.{ExitCode, UIO, URIO, ZIO}

sealed trait Message[+_]
case object Increase extends Message[Unit]
case object Get      extends Message[Int]

object CounterActorExample extends zio.App {

  // Definition of stateful actor
  val counterActor: Stateful[Any, Int, Message] =
    new Stateful[Any, Int, Message] {
      override def receive[A](
          state: Int,
          msg: Message[A],
          context: Context
      ): UIO[(Int, A)] =
        msg match {
          case Increase => UIO((state + 1, ()))
          case Get      => UIO((state, state))
        }
    }

  val myApp: ZIO[Clock, Throwable, Int] =
    for {
      system <- ActorSystem("MyActorSystem")
      actor  <- system.make("counter", Supervisor.none, 0, counterActor)
      _      <- actor ! Increase
      _      <- actor ! Increase
      s      <- actor ? Get
    } yield s

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp
      .flatMap(state => putStrLn(s"The final state of counter: $state"))
      .exitCode
}
```

Akka actors also has some other optional modules for persistence (which is useful for event sourcing) and integration with Akka toolkit:

```scala
libraryDependencies += "dev.zio" %% "zio-actors-persistence" % zioActorsVersion
libraryDependencies += "dev.zio" %% "zio-actors-persistence-jdbc" % zioActorVersion
libraryDependencies += "dev.zio" %% "zio-actors-akka-interop" % zioActorVersion
```

## ZIO Akka Cluster

The [ZIO Akka Cluster](https://github.com/zio/zio-akka-cluster) library is a ZIO wrapper on [Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html). We can use clustering features of the Akka toolkit without the need to use the actor model.

### Introduction

This library provides us following features:

- **Akka Cluster** — This feature contains two Akka Cluster Membership operations called `join` and `leave` and also it has some methods to retrieve _Cluster State_ and _Cluster Events_.

- **Akka Distributed PubSub** — Akka has a _Distributed Publish Subscribe_ facility in the cluster. It helps us to send a message to all actors in the cluster that have registered and subscribed for a specific topic name without knowing their physical address or without knowing which node they are running on.

- **Akka Cluster Sharding** — Cluster sharding is useful when we need to _distribute actors across several nodes in the cluster_ and want to be able to interact with them using their logical identifier without having to care about their physical location in the cluster, which might also change over time. When we have many stateful entities in our application that together they consume more resources (e.g. memory) than fit on one machine, it is useful to use _Akka Cluster Sharding_ to distribute our entities to multiple nodes.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-akka-cluster" % "0.2.0" // Check the repo for the latest version
```

### Example 

In the following example, we are using all these three features. We have a distributed counter application that lives in the Akka Cluster using _Akka Cluster Sharding_ feature. So the location of `LiveUsers` and `TotalRequests` entities in the cluster is transparent for us. We send the result of each entity to the _Distributed PubSub_. So every node in the cluster can subscribe and listen to those results. Also, we have created a fiber that is subscribed to the cluster events. All the new events will be logged to the console:

```scala mdoc:silent:nest
import akka.actor.ActorSystem
import com.typesafe.config.{Config, ConfigFactory}
import zio.akka.cluster.Cluster
import zio.akka.cluster.sharding.{Entity, Sharding}
import zio.console.putStrLn
import zio.{ExitCode, Has, Managed, Task, URIO, ZIO, ZLayer}

sealed trait Counter extends Product with Serializable
case object Inc extends Counter
case object Dec extends Counter

case class CounterApp(port: String) {
  val config: Config =
    ConfigFactory.parseString(
      s"""
         |akka {
         |  actor {
         |    provider = "cluster"
         |  }
         |  remote {
         |    netty.tcp {
         |      hostname = "127.0.0.1"
         |      port = $port
         |    }
         |  }
         |  cluster {
         |    seed-nodes = ["akka.tcp://CounterApp@127.0.0.1:2551"]
         |  }
         |}
         |""".stripMargin)

  val actorSystem: ZLayer[Any, Throwable, Has[ActorSystem]] =
    ZLayer.fromManaged(
      Managed.make(Task(ActorSystem("CounterApp", config)))(sys =>
        Task.fromFuture(_ => sys.terminate()).either
      )
    )

  val counterApp: ZIO[zio.ZEnv, Throwable, Unit] =
    actorSystem.build.use(sys =>
      for {
        queue <- Cluster
          .clusterEvents(true)
          .provideCustomLayer(ZLayer.succeedMany(sys))

        pubsub <- zio.akka.cluster.pubsub.PubSub
          .createPubSub[Int]
          .provideCustomLayer(ZLayer.succeedMany(sys))

        liveUsersLogger <- pubsub
          .listen("LiveUsers")
          .flatMap(
            _.take.tap(u => putStrLn(s"Number of live users: $u")).forever
          )
          .fork
        totalRequestLogger <- pubsub
          .listen("TotalRequests")
          .flatMap(
            _.take.tap(r => putStrLn(s"Total request until now: $r")).forever
          )
          .fork

        clusterEvents <- queue.take
          .tap(x => putStrLn("New event in cluster: " + x.toString))
          .forever
          .fork

        counterEntityLogic = (c: Counter) =>
          for {
            entity <- ZIO.environment[Entity[Int]]
            newState <- c match {
              case Inc =>
                entity.get.state.updateAndGet(s => Some(s.getOrElse(0) + 1))
              case Dec =>
                entity.get.state.updateAndGet(s => Some(s.getOrElse(0) - 1))
            }
            _ <- pubsub.publish(entity.get.id, newState.getOrElse(0)).orDie
          } yield ()
        cluster <- Sharding
          .start("CounterEntity", counterEntityLogic)
          .provideCustomLayer(ZLayer.succeedMany(sys))

        _ <- cluster.send("LiveUsers", Inc)
        _ <- cluster.send("TotalRequests", Inc)
        _ <- cluster.send("LiveUsers", Dec)
        _ <- cluster.send("LiveUsers", Inc)
        _ <- cluster.send("LiveUsers", Inc)
        _ <- cluster.send("TotalRequests", Inc)
        _ <- cluster.send("TotalRequests", Inc)

        _ <-
          clusterEvents.join zipPar liveUsersLogger.join zipPar totalRequestLogger.join
      } yield ()
    )
}
```

Now, let's create a cluster comprising two nodes:

```scala mdoc:silent:nest
object CounterApp1 extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = 
    CounterApp("2551").counterApp.exitCode
}

object CounterApp2 extends zio.App {
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = 
    CounterApp("2552").counterApp.exitCode
}
```

## ZIO Cache

ZIO Cache is a library that makes it easy to optimize the performance of our application by caching values.

Sometimes we may call or receive requests to do overlapping work. Assume we are writing a service that is going to handle all incoming requests. We don't want to handle duplicate requests. Using ZIO Cache we can make our application to be more **performant** by preventing duplicated works.

### Introduction

Some key features of ZIO Cache:

- **Compositionality** — If we want our applications to be **compositional**, different parts of our application may do overlapping work. ZIO Cache helps us to stay benefit from compositionality while using caching.

- **Unification of Synchronous and Asynchronous Caches** — Compositional definition of cache in terms of _lookup function_ unifies synchronous and asynchronous caches. So the lookup function can compute value either synchronously or asynchronously.

- **Deep ZIO Integration** — ZIO Cache is a ZIO native solution. So without losing the power of ZIO it includes support for _concurrent lookups_, _failure_, and _interruption_.

- **Caching Policy** — Using caching policy, the ZIO Cache can determine when values should/may be removed from the cache. So, if we want to build something more complex and custom we have a lot of flexibility. The caching policy has two parts and together they define a whole caching policy:

    - **Priority (Optional Removal)** — When we are running out of space, it defines the order that the existing values **might** be removed from the cache to make more space.

    - **Evict (Mandatory Removal)** — Regardless of space when we **must** remove existing values because they are no longer valid anymore. They might be invalid because they do not satisfy business requirements (e.g., maybe it's too old). This is a function that determines whether an entry is valid based on the entry and the current time.

- **Composition Caching Policy** — We can define much more complicated caching policies out of much simpler ones.

- **Cache/Entry Statistics** — ZIO Cache maintains some good statistic metrics, such as entries, memory size, hits, misses, loads, evictions, and total load time. So we can look at how our cache is doing and decide where we should change our caching policy to improve caching metrics.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-cache" % "0.1.0" // Check the repo for the latest version
```

### Example

In this example, we are calling `timeConsumingEffect` three times in parallel with the same key. The ZIO Cache runs this effect only once. So the concurrent lookups will suspend until the value being computed is available:

```scala mdoc:silent:nest
import zio.cache.{Cache, Lookup}
import zio.clock.Clock
import zio.console.{Console, putStrLn}
import zio.duration.{Duration, durationInt}
import zio.{ExitCode, URIO, ZIO}

import java.io.IOException

def timeConsumingEffect(key: String): ZIO[Clock, Nothing, Int] =
  ZIO.sleep(5.seconds) *> ZIO.succeed(key.hashCode)

val myApp: ZIO[Console with Clock, IOException, Unit] =
  for {
    cache <- Cache.make(
      capacity = 100,
      timeToLive = Duration.Infinity,
      lookup = Lookup(timeConsumingEffect)
    )
    result <- cache.get("key1")
                .zipPar(cache.get("key1"))
                .zipPar(cache.get("key1"))
    _ <- putStrLn(s"Result of parallel execution three effects with the same key: $result")

    hits <- cache.cacheStats.map(_.hits)
    misses <- cache.cacheStats.map(_.misses)
    _ <- putStrLn(s"Number of cache hits: $hits")
    _ <- putStrLn(s"Number of cache misses: $misses")
  } yield ()
```

The output of this program should be as follows: 

```
Result of parallel execution three effects with the same key: ((3288498,3288498),3288498)
Number of cache hits: 2
Number of cache misses: 1
```


## ZIO Config

[ZIO Config](https://zio.github.io/zio-config/) is a ZIO-based library for loading and parsing configuration sources.

### Introduction
In the real world, config retrieval is the first to develop applications. We mostly have some application config that should be loaded and parsed through our application. Doing such things manually is always boring and error-prone and also has lots of boilerplates.

The ZIO Config has a lot of features, and it is more than just a config parsing library. Let's enumerate some key features of this library:

- **Support for Various Sources** — It can read/write flat or nested configurations from/to various formats and sources.

- **Composable sources** — ZIO Config can compose sources of configuration, so we can have, e.g. environmental or command-line overrides.

- **Automatic Document Generation** — It can auto-generate documentation of configurations. So developers or DevOps engineers know how to configure the application.

- **Report generation** — It has a report generation that shows where each piece of configuration data came from.

- **Automatic Derivation** — It has built-in support for automatic derivation of readers and writers for case classes and sealed traits.

- **Type-level Constraints and Automatic Validation** — because it supports _Refined_ types, we can write type-level predicates which constrain the set of values described for data types.

- **Descriptive Errors** — It accumulates all errors and reports all of them to the user rather than failing fast.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-config" % <version>
```

There are also some optional dependencies:
- **zio-config-mangolia** — Auto Derivation 
- **zio-config-refined** — Integration with Refined Library
- **zio-config-typesafe** — HOCON/Json Support
- **zio-config-yaml** — Yaml Support
- **zio-config-gen** — Random Config Generation

### Example

Let's add these four lines to our `build.sbt` file as we are using these modules in our example:

```scala
libraryDependencies += "dev.zio" %% "zio-config"          % "1.0.6"
libraryDependencies += "dev.zio" %% "zio-config-magnolia" % "1.0.6"
libraryDependencies += "dev.zio" %% "zio-config-typesafe" % "1.0.6"
libraryDependencies += "dev.zio" %% "zio-config-refined"  % "1.0.6"
```

In this example we are reading from HOCON config format using type derivation:

```scala mdoc:silent:nest
import eu.timepit.refined.W
import eu.timepit.refined.api.Refined
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.numeric.GreaterEqual
import zio.config.magnolia.{describe, descriptor}
import zio.config.typesafe.TypesafeConfigSource
import zio.console.putStrLn
import zio.{ExitCode, URIO, ZIO}

sealed trait DataSource

final case class Database(
    @describe("Database Host Name")
    host: Refined[String, NonEmpty],
    @describe("Database Port")
    port: Refined[Int, GreaterEqual[W.`1024`.T]]
) extends DataSource

final case class Kafka(
    @describe("Kafka Topics")
    topicName: String,
    @describe("Kafka Brokers")
    brokers: List[String]
) extends DataSource

object ZIOConfigExample extends zio.App {
  import zio.config._
  import zio.config.refined._

  val json =
    s"""
       |"Database" : {
       |  "port" : "1024",
       |  "host" : "localhost"
       |}
       |""".stripMargin

  val myApp =
    for {
      source <- ZIO.fromEither(TypesafeConfigSource.fromHoconString(json))
      desc = descriptor[DataSource] from source
      dataSource <- ZIO.fromEither(read(desc))
      // Printing Auto Generated Documentation of Application Config
      _ <- putStrLn(generateDocs(desc).toTable.toGithubFlavouredMarkdown)
      _ <- dataSource match {
        case Database(host, port) =>
          putStrLn(s"Start connecting to the database: $host:$port")
        case Kafka(_, brokers) =>
          putStrLn(s"Start connecting to the kafka brokers: $brokers")
      }
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.exitCode
}
```

## ZIO JSON

[ZIO Json](https://github.com/zio/zio-json) is a fast and secure JSON library with tight ZIO integration.

### Introduction

The goal of this project is to create the best all-round JSON library for Scala:

- **Performance** to handle more requests per second than the incumbents, i.e. reduced operational costs.
- **Security** to mitigate against adversarial JSON payloads that threaten the capacity of the server.
- **Fast Compilation** no shapeless, no type astronautics.
- **Future-Proof**, prepared for Scala 3 and next-generation Java.
- **Simple** small codebase, concise documentation that covers everything.
- **Helpful errors** are readable by humans and machines.
- **ZIO Integration** so nothing more is required.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-json" % "0.1.5"
```

### Example

Let's try a simple example of encoding and decoding JSON using ZIO JSON:

```scala mdoc:nest
import zio.json._

sealed trait Fruit                   extends Product with Serializable
case class Banana(curvature: Double) extends Fruit
case class Apple(poison: Boolean)    extends Fruit

object Fruit {
  implicit val decoder: JsonDecoder[Fruit] =
    DeriveJsonDecoder.gen[Fruit]

  implicit val encoder: JsonEncoder[Fruit] =
    DeriveJsonEncoder.gen[Fruit]
}

val json1         = """{ "Banana":{ "curvature":0.5 }}"""
val json2         = """{ "Apple": { "poison": false }}"""
val malformedJson = """{ "Banana":{ "curvature": true }}"""

json1.fromJson[Fruit]
json2.fromJson[Fruit]
malformedJson.fromJson[Fruit]

List(Apple(false), Banana(0.4)).toJsonPretty
```

## ZIO Kafka

[ZIO Kafka](https://github.com/zio/zio-kafka) is a Kafka client for ZIO. It provides a purely functional, streams-based interface to the Kafka client and integrates effortlessly with ZIO and ZIO Streams.

### Introduction

Apache Kafka is a distributed event streaming platform that acts as a distributed publish-subscribe messaging system. It enables us to build distributed streaming data pipelines and event-driven applications.

Kafka has a mature Java client for producing and consuming events, but it has a low-level API. ZIO Kafka is a ZIO native client for Apache Kafka. It has a high-level streaming API on top of the Java client. So we can produce and consume events using the declarative concurrency model of ZIO Streams.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-kafka" % "0.15.0" 
```

### Example

Let's write a simple Kafka producer and consumer using ZIO Kafka with ZIO Streams. Before everything, we need a running instance of Kafka. We can do that by saving the following docker-compose script in the `docker-compose.yml` file and run `docker-compose up`:

```docker
version: '2'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - 22181:2181
  
  kafka:
    image: confluentinc/cp-kafka:latest
    depends_on:
      - zookeeper
    ports:
      - 29092:29092
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
```

Now, we can run our ZIO Kafka Streaming application:

```scala mdoc:silent:nest
import zio._
import zio.console.putStrLn
import zio.duration.durationInt
import zio.kafka.consumer.{Consumer, ConsumerSettings, _}
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._
import zio.stream.ZStream

object ZIOKafkaProducerConsumerExample extends zio.App {
  val producer =
    ZStream
      .repeatEffect(zio.random.nextIntBetween(0, Int.MaxValue))
      .schedule(Schedule.fixed(2.seconds))
      .mapM { random =>
        Producer.produce[Any, Long, String](
          topic = "random",
          key = random % 4,
          value = random.toString
        )
      }
      .drain

  val consumer =
    Consumer
      .subscribeAnd(Subscription.topics("random"))
      .plainStream(Serde.long, Serde.string)
      .tap(r => putStrLn(r.value))
      .map(_.offset)
      .aggregateAsync(Consumer.offsetBatches)
      .mapM(_.commit)
      .drain

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    producer
      .merge(consumer)
      .runDrain
      .provideCustomLayer(appLayer)
      .exitCode

  def producerLayer = ZLayer.fromManaged(
    Producer.make(
      settings = ProducerSettings(List("localhost:29092")),
      keySerializer = Serde.long,
      valueSerializer = Serde.string
    )
  )

  def consumerLayer = ZLayer.fromManaged(
    Consumer.make(
      ConsumerSettings(List("localhost:29092")).withGroupId("group")
    )
  )

  def appLayer = producerLayer ++ consumerLayer
}
```

## ZIO Logging

[ZIO Logging](https://github.com/zio/zio-logging) is simple logging for ZIO apps, with correlation, context, and pluggable backends out of the box.

### Introduction

When we are writing our applications using ZIO effects, to log easy way we need a ZIO native solution for logging. ZIO Logging is an environmental effect for adding logging into our ZIO applications.

Key features of ZIO Logging:

- **ZIO Native** — Other than it is a type-safe and purely functional solution, it leverages ZIO's features.
- **Multi-Platform** - It supports both JVM and JS platforms.
- **Composable** — Loggers are composable together via contraMap.
- **Pluggable Backends** — Support multiple backends like ZIO Console, SLF4j, JS Console, JS HTTP endpoint.
- **Logger Context** — It has a first citizen _Logger Context_ implemented on top of `FiberRef`. The Logger Context maintains information like logger name, filters, correlation id, and so forth across different fibers. It supports _Mapped Diagnostic Context (MDC)_ which manages contextual information across fibers in a concurrent environment.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-config" % "0.5.11" 
```

There are also some optional dependencies:
- **zio-logging-slf4j** — SLF4j integration
- **zio-logging-slf4j-bridge** — Using ZIO Logging for SLF4j loggers, usually third-party non-ZIO libraries
- **zio-logging-jsconsole** — Scala.js console integration
- **zio-logging-jshttp** — Scala.js HTTP Logger which sends logs to a backend via Ajax POST

### Example

Let's try an example of ZIO Logging which demonstrates a simple application of ZIO logging along with its _Logger Context_ feature:

```scala mdoc:silent:nest
import zio.clock.Clock
import zio.duration.durationInt
import zio.logging._
import zio.random.Random
import zio.{ExitCode, NonEmptyChunk, ZIO}

object ZIOLoggingExample extends zio.App {

  val myApp: ZIO[Logging with Clock with Random, Nothing, Unit] =
    for {
      _ <- log.info("Hello from ZIO logger")
      _ <-
        ZIO.foreachPar(NonEmptyChunk("UserA", "UserB", "UserC")) { user =>
          log.locally(UserId(Some(user))) {
            for {
              _ <- log.info("User validation")
              _ <- zio.random
                .nextIntBounded(1000)
                .flatMap(t => ZIO.sleep(t.millis))
              _ <- log.info("Connecting to the database")
              _ <- zio.random
                .nextIntBounded(100)
                .flatMap(t => ZIO.sleep(t.millis))
              _ <- log.info("Releasing resources.")
            } yield ()
          }

        }
    } yield ()

  type UserId = String
  def UserId: LogAnnotation[Option[UserId]] = LogAnnotation[Option[UserId]](
    name = "user-id",
    initialValue = None,
    combine = (_, r) => r,
    render = _.map(userId => s"[user-id: $userId]")
      .getOrElse("undefined-user-id")
  )

  val env =
    Logging.console(
      logLevel = LogLevel.Info,
      format =
        LogFormat.ColoredLogFormat((ctx, line) => s"${ctx(UserId)} $line")
    ) >>> Logging.withRootLoggerName("MyZIOApp")

  override def run(args: List[String]) =
    myApp.provideCustomLayer(env).as(ExitCode.success)
}
```

The output should be something like this:

```
2021-07-09 00:14:47.457+0000  info [MyZIOApp] undefined-user-id Hello from ZIO logger
2021-07-09 00:14:47.807+0000  info [MyZIOApp] [user-id: UserA] User validation
2021-07-09 00:14:47.808+0000  info [MyZIOApp] [user-id: UserC] User validation
2021-07-09 00:14:47.818+0000  info [MyZIOApp] [user-id: UserB] User validation
2021-07-09 00:14:48.290+0000  info [MyZIOApp] [user-id: UserC] Connecting to the database
2021-07-09 00:14:48.299+0000  info [MyZIOApp] [user-id: UserA] Connecting to the database
2021-07-09 00:14:48.321+0000  info [MyZIOApp] [user-id: UserA] Releasing resources.
2021-07-09 00:14:48.352+0000  info [MyZIOApp] [user-id: UserC] Releasing resources.
2021-07-09 00:14:48.820+0000  info [MyZIOApp] [user-id: UserB] Connecting to the database
2021-07-09 00:14:48.882+0000  info [MyZIOApp] [user-id: UserB] Releasing resources.
```

## ZIO NIO
[ZIO NIO](https://zio.github.io/zio-nio/) is a small, unopinionated ZIO interface to NIO.

### Introduction

In Java, there are two packages for I/O operations:

1. Java IO (`java.io`)
    - Standard Java IO API
    - Introduced since Java 1.0
    - Stream-based API
    - **Blocking I/O operation**
    
2. Java NIO (`java.nio`)
    - Introduced since Java 1.4
    - NIO means _New IO_, an alternative to the standard Java IO API
    - It can operate in a **non-blocking mode** if possible
    - Buffer-based API

The [Java NIO](https://docs.oracle.com/javase/8/docs/api/java/nio/package-summary.html) is an alternative to the Java IO API. Because it supports non-blocking IO, it can be more performant in concurrent environments like web services.

### Installation

ZIO NIO is a ZIO wrapper on Java NIO. It comes in two flavors:

- **`zio.nio.core`** — a small and unopionanted ZIO interface to NIO that just wraps NIO API in ZIO effects,
- **`zio.nio`** — an opinionated interface with deeper ZIO integration that provides more type and resource safety.

In order to use this library, we need to add one of the following lines in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-nio-core" % "1.0.0-RC11"
libraryDependencies += "dev.zio" %% "zio-nio"      % "1.0.0-RC11" 
```

### Example

Let's try writing a simple server using `zio-nio` module: 

```scala mdoc:silent:nest
import zio._
import zio.console._
import zio.nio.channels._
import zio.nio.core._
import zio.stream._

object ZIONIOServerExample extends zio.App {
  val myApp =
    AsynchronousServerSocketChannel()
      .use(socket =>
        for {
          addr <- InetSocketAddress.hostName("localhost", 8080)
          _ <- socket.bindTo(addr)
          _ <- putStrLn(s"Waiting for incoming connections on $addr endpoint").orDie
          _ <- ZStream
            .repeatEffect(socket.accept.preallocate)
            .map(_.withEarlyRelease)
            .mapMPar(16) {
              _.use { case (closeConn, channel) =>
                for {
                  _ <- putStrLn("Received connection").orDie
                  data <- ZStream
                    .repeatEffectOption(
                      channel.readChunk(64).eofCheck.orElseFail(None)
                    )
                    .flattenChunks
                    .transduce(ZTransducer.utf8Decode)
                    .run(Sink.foldLeft("")(_ + _))
                  _ <- closeConn
                  _ <- putStrLn(s"Request Received:\n${data.mkString}").orDie
                } yield ()
              }
            }.runDrain
        } yield ()
      ).orDie
   
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.exitCode
}
```

Now we can send our requests to the server using _curl_ command:

```
curl -X POST localhost:8080 -d "Hello, ZIO NIO!"
```

## ZIO Optics

[ZIO Optics](https://github.com/zio/zio-optics) is a library that makes it easy to modify parts of larger data structures based on a single representation of an optic as a combination of a getter and setter.

### Introduction

When we are working with immutable nested data structures, updating and reading operations could be tedious with lots of boilerplates. Optics is a functional programming construct that makes these operations more clear and readable.

Key features of ZIO Optics:

- **Unified Optic Data Type** — All the data types like `Lens`, `Prism`, `Optional`, and so forth are type aliases for the core `Optic` data type.
- **Composability** — We can compose optics to create more advanced ones.
- **Embracing the Tremendous Power of Concretion** — Using concretion instead of unnecessary abstractions, makes the API more ergonomic and easy to use.
- **Integration with ZIO Data Types** — It supports effectful and transactional optics that works with ZIO data structures like `Ref` and `TMap`.
- **Helpful Error Channel** — Like ZIO, the `Optics` data type has error channels to include failure details.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-optics" % "0.1.0"
```

### Example

In this example, we are going to update a nested data structure using ZIO Optics:

```scala mdoc:silent:nest
import zio.optics._

case class Developer(name: String, manager: Manager)
case class Manager(name: String, rating: Rating)
case class Rating(upvotes: Int, downvotes: Int)

val developerLens = Lens[Developer, Manager](
  get = developer => Right(developer.manager),
  set = manager => developer => Right(developer.copy(manager = manager))
)

val managerLens = Lens[Manager, Rating](
  get = manager => Right(manager.rating),
  set = rating => manager => Right(manager.copy(rating = rating))
)

val ratingLens = Lens[Rating, Int](
  get = rating => Right(rating.upvotes),
  set = upvotes => rating => Right(rating.copy(upvotes = upvotes))
)

// Composing lenses
val optic = developerLens >>> managerLens >>> ratingLens

val jane    = Developer("Jane", Manager("Steve", Rating(0, 0)))
val updated = optic.update(jane)(_ + 1)

println(updated)
```

## ZIO Prelude

[ZIO Prelude](https://github.com/zio/zio-prelude) is a lightweight, distinctly Scala take on **functional abstractions**, with tight ZIO integration.

### Introduction

ZIO Prelude is a small library that brings common, useful algebraic abstractions and data types to scala developers.

It is an alternative to libraries like _Scalaz_ and _Cats_ based on radical ideas that embrace **modularity** and **subtyping** in Scala and offer **new levels of power and ergonomics**. It throws out the classic functor hierarchy in favor of a modular algebraic approach that is smaller, easier to understand and teach, and more expressive.

Design principles behind ZIO Prelude:

1. **Radical** — So basically it ignores all dogma and it is completely written with a new mindset.
2. **Orthogonality** — The goal for ZIO Prelude is to have no overlap. Type classes should do one thing and fit it well. So there is not any duplication to describe type classes.
3. **Principled** — All type classes in ZIO Prelude include a set of laws that instances must obey.
4. **Pragmatic** — If we have data types that don't satisfy laws but that are still useful to use in most cases, we can go ahead and provide instances for them.
5. **Scala-First** - It embraces subtyping and benefit from object-oriented features of Scala.

ZIO Prelude gives us:
- **Data Types** that complements the Scala Standard Library:
    - `NonEmptyList`, `NonEmptySet`
    - `ZSet`, `ZNonEmptySet`
    - `Validation`
    - `ZPure`
- **Type Classes** to describe similarities across different types to eliminate duplications and boilerplates:
    - Business entities (`Person`, `ShoppingCart`, etc.)
    - Effect-like structures (`Try`, `Option`, `Future`, `Either`, etc.)
    - Collection-like structures (`List`, `Tree`, etc.)
- **New Types** that allow to _increase type safety_ in domain modeling. Wrapping existing type adding no runtime overhead.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-prelude" % "1.0.0-RC5"
```

### Example

In this example, we are going to create a simple voting application. We will use two features of ZIO Prelude:
1. To become more type safety we are going to use _New Types_ and introducing `Topic` and `Votes` data types.
2. Providing instance of `Associative` type class for `Votes` data type which helps us to combine `Votes` values.

```scala mdoc:silent:nest
import zio.prelude._

object VotingExample extends scala.App {

  object Votes extends Subtype[Int] {
    implicit val associativeVotes: Associative[Votes] =
      new Associative[Votes] {
        override def combine(l: => Votes, r: => Votes): Votes =
          Votes(l + r)
      }
  }
  type Votes = Votes.Type

  object Topic extends Subtype[String]
  type Topic = Topic.Type

  final case class VoteState(map: Map[Topic, Votes]) { self =>
    def combine(that: VoteState): VoteState =
      VoteState(self.map combine that.map)
  }

  val zioHttp    = Topic("zio-http")
  val uziHttp    = Topic("uzi-http")
  val zioTlsHttp = Topic("zio-tls-http")

  val leftVotes  = VoteState(Map(zioHttp -> Votes(4), uziHttp -> Votes(2)))
  val rightVotes = VoteState(Map(zioHttp -> Votes(2), zioTlsHttp -> Votes(2)))

  println(leftVotes combine rightVotes)
  // Output: VoteState(Map(zio-http -> 6, uzi-http -> 2, zio-tls-http -> 2))
}
```

## ZIO Process

[ZIO Process](https://github.com/zio/zio-process) is a simple ZIO library for interacting with external processes and command-line programs.

### Introduction

ZIO Process provides a principled way to call out to external programs from within a ZIO application while leveraging ZIO's capabilities like interruptions and offloading blocking operations to a separate thread pool. We don't need to worry about avoiding these common pitfalls as we would if we were to use Java's `ProcessBuilder` or the `scala.sys.process` API since it is already taken care of for you.

Key features of the ZIO Process:
- **Deep ZIO Integration** — Leverages ZIO to handle interruption and offload blocking operations.
- **ZIO Streams** — ZIO Process is backed by ZIO Streams, which enables us to obtain the command output as streams of bytes or lines. So we can work with processes that output gigabytes of data without worrying about exceeding memory constraints.
- **Descriptive Errors** — In case of command failure, it has a descriptive category of errors.
- **Piping** — It has a simple DSL for piping the output of one command as the input of another.
- **Blocking Operations** —

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-process" % "0.5.0" 
```

### Example

Here is a simple example of using ZIO Process:

```scala mdoc:silent:nest
import zio.console.putStrLn
import zio.process.Command
import zio.{ExitCode, URIO}

import java.io.File

object ZIOProcessExample extends zio.App {

  val myApp = for {
    fiber <- Command("dmesg", "--follow").linesStream
      .foreach(putStrLn(_))
      .fork
    cpuModel <- (Command("cat", "/proc/cpuinfo") |
      Command("grep", "model name") |
      Command("head", "-n", "1") |
      Command("cut", "-d", ":", "-f", "2")).string
    _ <- putStrLn(s"CPU Model: $cpuModel")
    _ <- (Command("pg_dump", "my_database") > new File("dump.sql")).exitCode
    _ <- fiber.join
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.exitCode
}
```

## ZIO Redis

[ZIO Redis](https://github.com/zio/zio-redis) is a ZIO native Redis client.

### Introduction

ZIO Redis is in the experimental phase of development, but its goals are:

- **Type Safety**
- **Performance**
- **Minimum Dependency**
- **ZIO Native**

### Installation

Since the ZIO Redis is in the experimental phase, it is not released yet.

### Example

To execute our ZIO Redis effect, we should provide the `RedisExecutor` layer to that effect. To create this layer we should also provide the following layers:

- **Logging** — For simplicity, we ignored the logging functionality.
- **RedisConfig** — Using default one, will connect to the `localhost:6379` Redis instance.
- **Codec** — In this example, we are going to use the built-in `StringUtf8Codec` codec.

```scala
import zio.console.{Console, putStrLn}
import zio.duration._
import zio.logging.Logging
import zio.redis._
import zio.redis.codec.StringUtf8Codec
import zio.schema.codec.Codec
import zio.{ExitCode, URIO, ZIO, ZLayer}

object ZIORedisExample extends zio.App {

  val myApp: ZIO[Console with RedisExecutor, RedisError, Unit] = for {
    _ <- set("myKey", 8L, Some(1.minutes))
    v <- get[String, Long]("myKey")
    _ <- putStrLn(s"Value of myKey: $v").orDie
    _ <- hSet("myHash", ("k1", 6), ("k2", 2))
    _ <- rPush("myList", 1, 2, 3, 4)
    _ <- sAdd("mySet", "a", "b", "a", "c")
  } yield ()

  val layer: ZLayer[Any, RedisError.IOError, RedisExecutor] =
    Logging.ignore ++ ZLayer.succeed(RedisConfig.Default) ++ ZLayer.succeed(StringUtf8Codec) >>> RedisExecutor.live

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.provideCustomLayer(layer).exitCode
}
```

## ZIO S3

[ZIO S3](https://github.com/zio/zio-s3) is an S3 client for ZIO.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-s3" % "0.3.5" 
```

### Example

Let's try an example of creating a bucket and adding an object into it. To run this example, we need to run an instance of _Minio_ which is object storage compatible with S3:

```bash
docker run -p 9000:9000 -e MINIO_ACCESS_KEY=MyKey -e MINIO_SECRET_KEY=MySecret minio/minio  server --compat /data
```

In this example we create a bucket and then add a JSON object to it and then retrieve that:

```scala mdoc:silent:nest
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import zio.console.putStrLn
import zio.s3._
import zio.stream.{ZStream, ZTransducer}
import zio.{Chunk, ExitCode, URIO}

import java.net.URI

object ZIOS3Example extends zio.App {

  val myApp = for {
    _ <- createBucket("docs")
    json = Chunk.fromArray("""{  "id" : 1 , "name" : "A1" }""".getBytes)
    _ <- putObject(
      bucketName = "docs",
      key = "doc1",
      contentLength = json.length,
      content = ZStream.fromChunk(json),
      options = UploadOptions.fromContentType("application/json")
    )
    _ <- getObject("docs", "doc1")
      .transduce(ZTransducer.utf8Decode)
      .foreach(putStrLn(_))
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp
      .provideCustomLayer(
        live(
          Region.CA_CENTRAL_1,
          AwsBasicCredentials.create("MyKey", "MySecret"),
          Some(URI.create("http://localhost:9000"))
        )
      )
      .exitCode
}
```

## ZIO Schema

[ZIO Schema](https://github.com/zio/zio-schema) is a [ZIO](https://zio.dev)-based library for modeling the schema of data structures as first-class values.

### Introduction

Schema is a structure of a data type. ZIO Schema reifies the concept of structure for data types. It makes a high-level description of any data type and makes them as first-class values.

Creating a schema for a data type helps us to write codecs for that data type. So this library can be a host of functionalities useful for writing codecs and protocols like JSON, Protobuf, CSV, and so forth.

With schema descriptions that can be automatically derived for case classes and sealed traits, _ZIO Schema_ will be going to provide powerful features for free (Note that the project is in the development stage and all these features are not supported yet):

- Codecs for any supported protocol (JSON, protobuf, etc.), so data structures can be serialized and deserialized in a principled way
- Diffing, patching, merging, and other generic-data-based operations
- Migration of data structures from one schema to another compatible schema
- Derivation of arbitrary type classes (`Eq`, `Show`, `Ord`, etc.) from the structure of the data

When our data structures need to be serialized, deserialized, persisted, or transported across the wire, then _ZIO Schema_ lets us focus on data modeling and automatically tackle all the low-level, messy details for us.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-schema" % "0.0.6"
```

### Example

In this simple example first, we create a schema for `Person` and then run the _diff_ operation on two instances of the `Person` data type, and finally we encode a Person instance using _Protobuf_ protocol:

```scala mdoc:nest
import zio.console.putStrLn
import zio.schema.codec.ProtobufCodec._
import zio.schema.{DeriveSchema, Schema}
import zio.stream.ZStream
import zio.{Chunk, ExitCode, URIO}

final case class Person(name: String, age: Int, id: String)
object Person {
  implicit val schema: Schema[Person] = DeriveSchema.gen[Person]
}

Person.schema

import zio.schema.syntax._

Person("Alex", 31, "0123").diff(Person("Alex", 31, "124"))

def toHex(chunk: Chunk[Byte]): String =
  chunk.toArray.map("%02X".format(_)).mkString

zio.Runtime.default.unsafeRun(
  ZStream
    .succeed(Person("Thomas", 23, "2354"))
    .transduce(
      encoder(Person.schema)
    )
    .runCollect
    .flatMap(x => putStrLn(s"Encoded data with protobuf codec: ${toHex(x)}"))
)
```

## ZIO SQS

[ZIO SQS](https://github.com/zio/zio-sqs) is a ZIO-powered client for AWS SQS. It is built on top of the [AWS SDK for Java 2.0](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/basics.html) via the automatically generated wrappers from [zio-aws](https://gaithub.com/vigoo/zio-aws).

### Introduction

ZIO SQS enables us to produce and consume elements to/from the Amazon SQS service. It is integrated with ZIO Streams, so we can produce and consume elements in a streaming fashion, element by element or micro-batching.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-sqs" % "0.4.2"
```

### Example

In this example we produce a stream of events to the `MyQueue` and then consume them from that queue:

```scala mdoc:silent:nest
import io.github.vigoo.zioaws
import io.github.vigoo.zioaws.core.config.CommonAwsConfig
import io.github.vigoo.zioaws.sqs.Sqs
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import zio.clock.Clock
import zio.sqs.producer.{Producer, ProducerEvent}
import zio.sqs.serialization.Serializer
import zio.sqs.{SqsStream, SqsStreamSettings, Utils}
import zio.stream.ZStream
import zio.{ExitCode, RIO, URIO, ZLayer, _}

object ProducerConsumerExample extends zio.App {
  val queueName = "MyQueue"

  val client: ZLayer[Any, Throwable, Sqs] = zioaws.netty.default ++
    ZLayer.succeed(
      CommonAwsConfig(
        region = Some(Region.of("ap-northeast-2")),
        credentialsProvider = StaticCredentialsProvider.create(
          AwsBasicCredentials.create("key", "key")
        ),
        endpointOverride = None,
        commonClientConfig = None
      )
    ) >>>
    zioaws.core.config.configured() >>>
    zioaws.sqs.live

  val stream: ZStream[Any, Nothing, ProducerEvent[String]] =
    ZStream.iterate(0)(_ + 1).map(_.toString).map(ProducerEvent(_))

  val program: RIO[Sqs with Clock, Unit] = for {
    _        <- Utils.createQueue(queueName)
    queueUrl <- Utils.getQueueUrl(queueName)
    producer = Producer.make(queueUrl, Serializer.serializeString)
    _ <- producer.use { p =>
      p.sendStream(stream).runDrain
    }
    _ <- SqsStream(
      queueUrl,
      SqsStreamSettings(stopWhenQueueEmpty = true, waitTimeSeconds = Some(3))
    ).foreach(msg => UIO(println(msg.body)))
  } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    program.provideCustomLayer(client).exitCode
}
```


## ZIO Telemetry

[ZIO telemetry](https://github.com/zio/zio-telemetry) is purely-functional and type-safe. It provides clients for OpenTracing and OpenTelemetry.

### Introduction

In monolithic architecture, everything is in one place, and we know when a request starts and then how it goes through the components and when it finishes. We can obviously see what is happening with our request and where is it going. But, in distributed systems like microservice architecture, we cannot find out the story of a request through various services easily. This is where distributed tracing comes into play.

ZIO Telemetry is a purely functional client which helps up propagate context between services in a distributed environment.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file if we want to use [OpenTelemetry](https://opentelemetry.io/) client:

```scala
libraryDependencies += "dev.zio" %% "zio-telemetry" % "0.8.1"
```

And for using [OpenTracing](https://opentracing.io/) client we should add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-opentracing" % "0.8.1"
```

### Example

In this example, we create two services, `ProxyServer` and `BackendServer`. When we call ProxyServer, the BackendServer will be called.

Note that we are going to use _OpenTracing_ client for this example.

Here is a simplified diagram of our services:

```
                               ┌────────────────┐
                               │                │
                        ┌─────►│ Jaeger Backend │◄────┐
                        │      │                │     │
           Tracing Data │      └────────────────┘     │ Tracing Data
                        │                             │
               ┌────────┴─────────┐         ┌─────────┴────────┐
               │                  │         │                  │
User Request──►│   Proxy Server   ├────────►|  Backend Server  │
               │                  │         │                  │
               └──────────────────┘         └──────────────────┘
```

First of all we should add following dependencies to our `build.sbt` file:

```scala
object Versions {
  val http4s         = "0.21.24"
  val jaeger         = "1.6.0"
  val sttp           = "2.2.9"
  val opentracing    = "0.33.0"
  val opentelemetry  = "1.4.1"
  val opencensus     = "0.28.3"
  val zipkin         = "2.16.3"
  val zio            = "1.0.9"
  val zioInteropCats = "2.5.1.0"
}

lazy val openTracingExample = Seq(
  "org.typelevel"                %% "cats-core"                     % "2.6.1",
  "io.circe"                     %% "circe-generic"                 % "0.14.1",
  "org.http4s"                   %% "http4s-core"                   % Versions.http4s,
  "org.http4s"                   %% "http4s-blaze-server"           % Versions.http4s,
  "org.http4s"                   %% "http4s-dsl"                    % Versions.http4s,
  "org.http4s"                   %% "http4s-circe"                  % Versions.http4s,
  "io.jaegertracing"              % "jaeger-core"                   % Versions.jaeger,
  "io.jaegertracing"              % "jaeger-client"                 % Versions.jaeger,
  "io.jaegertracing"              % "jaeger-zipkin"                 % Versions.jaeger,
  "com.github.pureconfig"        %% "pureconfig"                    % "0.16.0",
  "com.softwaremill.sttp.client" %% "async-http-client-backend-zio" % Versions.sttp,
  "com.softwaremill.sttp.client" %% "circe"                         % Versions.sttp,
  "dev.zio"                      %% "zio-interop-cats"              % Versions.zioInteropCats,
  "io.zipkin.reporter2"           % "zipkin-reporter"               % Versions.zipkin,
  "io.zipkin.reporter2"           % "zipkin-sender-okhttp3"         % Versions.zipkin
)
```

Let's create a `ZLayer` for `OpenTracing` which provides us Jaeger tracer. Each microservice uses this layer to send its tracing data to the _Jaeger Backend_:

```scala mdoc:silent:nest
import io.jaegertracing.Configuration
import io.jaegertracing.internal.samplers.ConstSampler
import io.jaegertracing.zipkin.ZipkinV2Reporter
import org.apache.http.client.utils.URIBuilder
import zio.ZLayer
import zio.clock.Clock
import zio.telemetry.opentracing.OpenTracing
import zipkin2.reporter.AsyncReporter
import zipkin2.reporter.okhttp3.OkHttpSender

object JaegerTracer {
  def makeJaegerTracer(host: String, serviceName: String): ZLayer[Clock, Throwable, Clock with OpenTracing] =
    OpenTracing.live(new Configuration(serviceName)
      .getTracerBuilder
      .withSampler(new ConstSampler(true))
      .withReporter(
        new ZipkinV2Reporter(
          AsyncReporter.create(
            OkHttpSender.newBuilder
              .compressionEnabled(true)
              .endpoint(
                new URIBuilder()
                  .setScheme("http")
                  .setHost(host)
                  .setPath("/api/v2/spans")
                  .build.toString
              )
              .build
          )
        )
      )
      .build
    ) ++ Clock.live
}
```

The _BackendServer_:

```scala
import io.opentracing.propagation.Format.Builtin.{HTTP_HEADERS => HttpHeadersFormat}
import io.opentracing.propagation.TextMapAdapter
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing._
import JaegerTracer.makeJaegerTracer
import zio.{ExitCode, ZEnv, ZIO}

import scala.jdk.CollectionConverters._

object BackendServer extends CatsApp {
  type AppTask[A] = ZIO[Clock, Throwable, A]

  val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
  import dsl._

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    ZIO.runtime[Clock].flatMap { implicit runtime =>
      BlazeServerBuilder[AppTask](runtime.platform.executor.asEC)
        .bindHttp(port = 9000, host = "0.0.0.0")
        .withHttpApp(
          Router[AppTask](mappings = "/" ->
            HttpRoutes.of[AppTask] { case request@GET -> Root =>
              ZIO.unit
                .spanFrom(
                  format = HttpHeadersFormat,
                  carrier = new TextMapAdapter(request.headers.toList.map(h => h.name.value -> h.value).toMap.asJava),
                  operation = "GET /"
                )
                .provideLayer(makeJaegerTracer(host = "0.0.0.0:9411", serviceName = "backend-service")) *> Ok("Ok!")
            }
          ).orNotFound
        )
        .serve
        .compile
        .drain
    }.exitCode
}
```

And the _ProxyServer_ which calls the _BackendServer_:

```scala
import cats.effect.{ExitCode => catsExitCode}
import io.opentracing.propagation.Format.Builtin.{HTTP_HEADERS => HttpHeadersFormat}
import io.opentracing.propagation.TextMapAdapter
import io.opentracing.tag.Tags
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._
import sttp.client.asynchttpclient.zio.AsyncHttpClientZioBackend
import sttp.client.basicRequest
import sttp.model.Uri
import zio.clock.Clock
import zio.interop.catz._
import zio.telemetry.opentracing.OpenTracing
import JaegerTracer.makeJaegerTracer
import zio.{ExitCode, UIO, ZEnv, ZIO}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

object ProxyServer extends CatsApp {

  type AppTask[A] = ZIO[Clock, Throwable, A]

  private val backend = AsyncHttpClientZioBackend()

  override def run(args: List[String]): ZIO[ZEnv, Nothing, ExitCode] =
    ZIO.runtime[Clock].flatMap { implicit runtime =>
      implicit val ec = runtime.platform.executor.asEC
      BlazeServerBuilder[AppTask](ec)
        .bindHttp(port = 8080, host = "0.0.0.0")
        .withHttpApp(
          Router[AppTask](mappings = "/" -> {
            val dsl: Http4sDsl[AppTask] = Http4sDsl[AppTask]
            import dsl._

            HttpRoutes.of[AppTask] { case GET -> Root =>
              (for {
                _ <- OpenTracing.tag(Tags.SPAN_KIND.getKey, Tags.SPAN_KIND_CLIENT)
                _ <- OpenTracing.tag(Tags.HTTP_METHOD.getKey, GET.name)
                _ <- OpenTracing.setBaggageItem("proxy-baggage-item-key", "proxy-baggage-item-value")
                buffer = new TextMapAdapter(mutable.Map.empty[String, String].asJava)
                _ <- OpenTracing.inject(HttpHeadersFormat, buffer)
                headers <- extractHeaders(buffer)
                res <-
                  backend.flatMap { implicit backend =>
                    basicRequest.get(Uri("0.0.0.0", 9000).path("/")).headers(headers).send()
                  }.map(_.body)
                    .flatMap {
                      case Right(_) => Ok("Ok!")
                      case Left(_) => Ok("Oops!")
                    }
              } yield res)
                .root(operation = "GET /")
                .provideLayer(
                  makeJaegerTracer(host = "0.0.0.0:9411", serviceName = "proxy-server")
                )
            }
          }).orNotFound
        )
        .serve
        .compile[AppTask, AppTask, catsExitCode]
        .drain
        .as(ExitCode.success)
    }.exitCode

  private def extractHeaders(adapter: TextMapAdapter): UIO[Map[String, String]] = {
    val m = mutable.Map.empty[String, String]
    UIO(adapter.forEach { entry =>
      m.put(entry.getKey, entry.getValue)
      ()
    }).as(m.toMap)
  }

}
```

First, we run the following command to start Jaeger backend:

```bash
docker run -d --name jaeger \
  -e COLLECTOR_ZIPKIN_HTTP_PORT=9411 \
  -p 5775:5775/udp \
  -p 6831:6831/udp \
  -p 6832:6832/udp \
  -p 5778:5778 \
  -p 16686:16686 \
  -p 14268:14268 \
  -p 9411:9411 \
  jaegertracing/all-in-one:1.6
```

It's time to run Backend and Proxy servers. After starting these two, we can start calling `ProxyServer`:

```scala
curl -X GET http://0.0.0.0:8080/
```

Now we can check the Jaeger service (http://localhost:16686/) to see the result.

## ZIO ZMX

[ZIO ZMX](https://github.com/zio/zio-zmx) is a monitoring, metrics, and diagnostics toolkit for ZIO applications.

### Introduction

So ZIO ZMX is giving us a straightforward way to understand exactly what is going on in our ZIO application when we deploy that in production.

ZIO ZMX key features:

- **Easy Setup** — It seamlessly integrates with an existing application. We don't need to change any line of the existing ZIO application, except a few lines of code at the top level.
- **Diagnostics** —  To track the activity of fibers in a ZIP application including fiber lifetimes and reason for termination.
- **Metrics** — Tracking of user-defined metrics (Counter, Gauge, Histogram, etc.)
- **Integrations** — Support for major metrics collection services including _[Prometheus](https://github.com/prometheus/prometheus)_ and _[StatsD](https://github.com/statsd/statsd)_.
- **Zero Dependencies** - No dependencies other than ZIO itself.

### Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-zmx" % "0.0.6"
```

### Example

To run this example, we also should add the following dependency in our `build.sbt` file:

```scala
libraryDependencies += "org.polynote" %% "uzhttp" % "0.2.7"
```

In this example, we expose metric information using _Prometheus_ protocol:

```scala mdoc:silent:nest
import uzhttp._
import uzhttp.server.Server
import zio._
import zio.blocking.Blocking
import zio.clock.Clock
import zio.console._
import zio.duration.durationInt
import zio.zmx.metrics._
import zio.zmx.prometheus.PrometheusClient

import java.io.IOException
import java.lang
import java.net.InetSocketAddress

object ZmxSampleApp extends zio.App {

  val myApp: ZIO[Console with Clock with Has[PrometheusClient] with Blocking, IOException, Unit] =
    for {
      server <-
        Server
          .builder(new InetSocketAddress("localhost", 8080))
          .handleSome { case request if request.uri.getPath == "/" =>
            PrometheusClient.snapshot.map(p => Response.plain(p.value))
          }
          .serve
          .use(_.awaitShutdown).fork
      program <-
        (for {
          _ <- (ZIO.sleep(1.seconds) *> request @@ MetricAspect.count("request_counts")).forever.forkDaemon
          _ <- (ZIO.sleep(3.seconds) *>
            ZIO.succeed(
              lang.Runtime.getRuntime.totalMemory() - lang.Runtime.getRuntime.freeMemory()
            ).map(_ / (1024.0 * 1024.0)) @@ MetricAspect.setGauge("memory_usage")).forever.forkDaemon
        } yield ()).fork
      _ <- putStrLn("Press Any Key") *> getStrLn.catchAll(_ => ZIO.none) *> server.interrupt *> program.interrupt
    } yield ()

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    myApp.provideCustomLayer(PrometheusClient.live).exitCode

  private def request: UIO[Unit] = ZIO.unit
}
```

By calling the following API we can access metric information:

```bash
curl -X GET localhost:8080
```

Now we can config the Prometheus server to scrape metric information periodically.
