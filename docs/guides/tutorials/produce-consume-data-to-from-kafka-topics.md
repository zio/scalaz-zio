---
id: producing-consuming-data-from-kafka-topics
title: "Tutorial: How to Produce/Consume Data To/From Kafka Topics?"
sidebar_label: "Producing/Consuming Data To/From Kafka Topics"
---

## Introduction

Kafka is a distributed, fault-tolerant, message-oriented event-store platform. It is used as a message broker for distributed applications. ZIO Kafka is a library that provides a way to consume and produce data from Kafka topics and it also supports the ability to have streaming consumers and producers.

In this tutorial, we will learn how to use ZIO Streams and ZIO Kafka to produce and consume data from Kafka topics.

## Running Examples

To access the code examples, you can clone the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-kafka
```

And finally, run the application using sbt:

```bash
$ sbt run
```

Alternatively, to enable hot-reloading and prevent port binding issues, you can use:

```bash
sbt reStart
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Adding Dependencies to The Project

In this tutorial, we will be using the following dependencies. So, let's add them to the `build.sbt` file:

```scala
libraryDependencies += Seq(
  "dev.zio" %% "zio"         % "2.0.9",
  "dev.zio" %% "zio-streams" % "2.0.9",
  "dev.zio" %% "zio-kafka"   % "2.1.1",
  "dev.zio" %% "zio-json"    % "0.3.0-RC10"
)
```

1. **ZIO Kafka** is a ZIO native client for Apache Kafka. It has a high-level streaming API on top of the Java client. So we can produce and consume events using the declarative concurrency model of ZIO Streams.

2. **ZIO Stream** introduces a high-level API for working with streams of values. It is designated to work in a highly concurrent environment. It has seamless integration with ZIO, so we have the ability to use all the features of the ZIO along with the streams, e.g. `Scope`, `Schedule`, `ZLayer`, `Queue`, `Hub` etc. To learn more about ZIO Stream, we have a comprehensive section in on that [here](../../reference/stream/index.md).

3. **ZIO JSON** is a library to serialize and deserialize data from/to JSON data type. We will be using this library to serialize and deserialize data when reading and writing JSON data from/to Kafka topics.

## Setting Up The Kafka Cluster

Before we start, we need to set up a Kafka cluster. To set up the kafka cluster for testing purposes we can use the following `docker-compose.yml` file:

```docker-compose
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

Now we can run the `docker-compose up` command to start the Kafka cluster:

```bash
$ docker-compose up
```

This will create a Kafka cluster with one instance of Kafka broker and one instance of Zookeeper. Zookeeper is a distributed service that is used to coordinate broker instances inside the cluster.

## Writing a Simple Producer and Consumer Using ZIO Workflows

To write producers and consumers, using the ZIO Kafka library, we have two choices:

1. Using ZIO Workflows
2. Using ZIO Streams Workflows

In this section, we will try the first option.

### 1. Serializing and Deserializing Data

Before we can write a producer and consumer, let's talk about how data is stored in Kafka. Kafka is an event-store platform that stores key-value pairs as raw bytes. So a Kafka broker knows nothing about its records, it just appends the records to its internal log file.

So to produce and consume data from Kafka, we need a way to serialize our data to a byte array and deserialize byte arrays to our data types. This is where the `Serde` data type comes in handy. A `Serde[R, T]` is a serializer and deserializer for values of type `T`, which can use the environment `R` to serialize and deserialize values.

Here is the simplified definition of the `Serde` data type:

```scala
trait Serde[-R, T] {
  def deserialize(data: Array[Byte]): RIO[R, T]
  def serialize(value: T)           : RIO[R, Array[Byte]]
}
```

The companion object of `Serde` trait contains a set of built-in serializers and deserializers for primitive types:

- `Serde.long`
- `Serde.int`
- `Serde.short`
- `Serde.float`
- `Serde.double`
- `Serde.boolean`
- `Serde.string`
- `Serde.byteArray`
- `Serde.byteBuffer`
- `Serde.uuid`

In this example, the type of the `key` is `Int` and the type of the `value` is `String`. So we can use the `Serde.int` for the `key` and the `Serde.string` for the `value`.

### 2. Creating a Producer

ZIO Kafka has several producers that can be used to produce data on Kafka topics. In this example, we will be using the `Producer.produce` method:

```scala
object Producer {
  def produce[R, K, V](
    topic: String,
    key: K,
    value: V,
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  ): RIO[R with Producer, RecordMetadata]
}
```

So let's create a helper function that takes a topic name, key, and value and then returns a ZIO workflow that if we run it, will produce a record to the specified topic:

```scala
def produce(topic: String, key: Long, value: String): RIO[Any with Producer, RecordMetadata] =
  Producer.produce[Any, Long, String](
    topic = topic,
    key = key,
    value = value,
    keySerializer = Serde.long,
    valueSerializer = Serde.string
  )
```

The `produce` function is polymorphic in the type of key and value of the record. Based on what type of key and value we pass, we should provide the appropriate `Serde` for the key and value.

### 3. Creating a Producer Layer

The `produce` workflow requires the `Producer` from the ZIO environment. So we need to provide a `Producer` instance to it. So let's create a `producer` layer:

```scala mdoc:compile-only
import zio._
import zio.kafka._
import zio.kafka.producer._

val producer: ZLayer[Any, Throwable, Producer] =
  ZLayer.scoped(
    Producer.make(
      ProducerSettings(List("localhost:29092"))
    )
  )
```

It is sufficient for this example, although the following helper methods are available for more customization:

```scala
class ProducerSettings {
  def withBootstrapServers(servers: List[String]): ProducerSettings
  def withClientId(clientId: String)             : ProducerSettings
  def withCloseTimeout(duration: Duration)       : ProducerSettings
  def withProperty(key: String, value: AnyRef)   : ProducerSettings
  def withProperties(kvs: (String, AnyRef)*)     : ProducerSettings
  def withProperties(kvs: Map[String, AnyRef])   : ProducerSettings
}
```

### 4. Creating a Consumer

ZIO Kafka also has several consumers that can be used to consume data from Kafka topics including the support for ZIO Streams which we will discuss later. In this example, we will use the `Consumer.consumeWith` function.

The following helper function will create a ZIO workflow that if we run it, will run forever and consume records from the given topic and finally print them to the console:

```scala
def consumeAndPrintEvents(groupId: String, topic: String, topics: String*): RIO[Any, Unit] =
  Consumer.consumeWith(
    settings = ConsumerSettings(BOOSTRAP_SERVERS).withGroupId(groupId),
    subscription = Subscription.topics(topic, topics: _*),
    keyDeserializer = Serde.long,
    valueDeserializer = Serde.string,
  )((k, v) => Console.printLine((k, v)).orDie)
```

### 5. The Complete Example

Now it's time to combine all the above steps to create a ZIO workflow that will produce and consume data from the Kafka cluster:

```scala mdoc:compile-only
import org.apache.kafka.clients.producer.RecordMetadata
import zio._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._

object SimpleApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:29092")
  private val KAFKA_TOPIC = "hello"

  private def produce(
      topic: String,
      key: Long,
      value: String
  ): RIO[Any with Producer, RecordMetadata] =
    Producer.produce[Any, Long, String](
      topic = topic,
      key = key,
      value = value,
      keySerializer = Serde.long,
      valueSerializer = Serde.string
    )

  private def consumeAndPrintEvents(
      groupId: String,
      topic: String,
      topics: String*
  ): RIO[Any, Unit] =
    Consumer.consumeWith(
      settings = ConsumerSettings(BOOSTRAP_SERVERS)
        .withGroupId(groupId),
      subscription = Subscription.topics(topic, topics: _*),
      keyDeserializer = Serde.long,
      valueDeserializer = Serde.string
    )(record => Console.printLine((record.key(), record.value())).orDie)

  private val producer: ZLayer[Any, Throwable, Producer] =
    ZLayer.scoped(
      Producer.make(
        ProducerSettings(BOOSTRAP_SERVERS)
      )
    )

  def run =
    for {
      f <- consumeAndPrintEvents("my-consumer-group", KAFKA_TOPIC).fork
      _ <-
        Clock.currentDateTime
          .flatMap { time =>
            produce(KAFKA_TOPIC, time.getHour, s"$time -- Hello, World!")
          }
          .schedule(Schedule.spaced(1.second))
          .provide(producer)
      _ <- f.join
    } yield ()

}
```

## ZIO Kafka With ZIO Streams

As we said before, to write producers and consumers using the ZIO Kafka library, we have two choices:

1. Using ZIO Workflows
2. Using ZIO Streams Workflows

In the previous section, we used the ZIO Kafka with the ZIO Workflow. The ZIO Kafka also works with the ZIO Streams seamlessly. So instead of using the `Producer.produce` and `Consumer.consumeWith` functions, we can use the streaming APIs provided by ZIO Kafka.

### 1. Streaming Producer API

To produce data using ZIO Streams, ZIO Kafka has a `Producer.produceAll` API, which is a `ZPipeline`. It takes streams of `ProducerRecord[K, V]` as upstream and uses the `Producer` from the environment to produce streams to the Kafka topic and then returns a stream of `RecordMetadata` as downstream:

```scala
object Producer {
  def produceAll[R, K, V](
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  ): ZPipeline[R with Producer, Throwable, ProducerRecord[K, V], RecordMetadata] = ???
}
```

Note that the `ZStream` implicitly chunks the records into batches for the sake of performance. So the `produceAll` produces records in batches instead of one at a time.

### 2. Streaming Consumer API

Creating a streaming consumer is also simple. We can use the `Consumer.plainStream` API to create a `ZStream` that if we run it, will use the `Consumer` from the environment to consume records from a Kafka topic and then returns a stream of `CommittableRecord[K, V]` as downstream:

```scala
object Consumer {
  def plainStream[R, K, V](
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V],
    bufferSize: Int = 4
  ): ZStream[R with Consumer, Throwable, CommittableRecord[K, V]] = ???
}
```

The `CommittableRecord` is a record that can be committed to Kafka via `CommittableRecord#commit` to indicate that the record has been consumed successfully. After we commit the record that we have consumed, if our application crashes, when we restart, we can resume consuming from the last record that we have committed.

For example, if we want to consume records and then save them to a file system, we can run the `CommittableRecord#commit` function after we wrote the record to the file system. So we are sure that the record has been persisted in the file system:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka._
import zio.kafka.consumer._
import zio.kafka.serde._

val KAFKA_TOPIC = "my-topic"

val c: ZStream[Consumer, Throwable, Nothing] =
  Consumer
    .plainStream(Subscription.topics(KAFKA_TOPIC), Serde.int, Serde.string)
    .tap(e => Console.printLine(e.value))
    .map(_.offset)
    .mapZIO(_.commit)
    .drain
```

The problem with this approach is that we are committing offsets for each record that we consume. This will cause a lot of overhead and will slow down the consumption of the records. To avoid this, we can aggregate the offsets into batches and commit them all at once. This can be done by using the `ZStream#aggregateAsync` along with the `Consumer.offsetBatches` sink:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka._
import zio.kafka.consumer._
import zio.kafka.serde._

val KAFKA_TOPIC = "my-topic"

val c: ZStream[Consumer, Throwable, Nothing] =
  Consumer
    .plainStream(Subscription.topics(KAFKA_TOPIC), Serde.int, Serde.string)
    .tap(e => Console.printLine(e.value))
    .map(_.offset)
    .aggregateAsync(Consumer.offsetBatches)
    .mapZIO(_.commit)
    .drain
```

The `Consumer.offsetBatches` sink folds `Offset`s into `OffsetBatch` which contains the maximum offset we have seen so far. So instead of committing the offset for each record, we commit the maximum offset of all the records in the batch.

### 3. Creating a Consumer and Producer Layer

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka._
import zio.kafka.producer._
import zio.kafka.consumer._

val BOOSTRAP_SERVERS = List("localhost:29092")

private val producer: ZLayer[Any, Throwable, Producer] =
  ZLayer.scoped(
    Producer.make(
      ProducerSettings(BOOSTRAP_SERVERS)
    )
  )

private val consumer: ZLayer[Any, Throwable, Consumer] =
  ZLayer.scoped(
    Consumer.make(
      ConsumerSettings(BOOSTRAP_SERVERS)
        .withGroupId("streaming-kafka-app")
    )
  )
```

### 4. The Complete Streaming Example

It's time to create a full working example of ZIO Kafka with ZIO streams:

```scala mdoc:compile-only
import org.apache.kafka.clients.producer.ProducerRecord
import zio._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._
import zio.stream.ZStream

object StreamingKafkaApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:29092")
  private val KAFKA_TOPIC      = "streaming-hello"

  private val producer: ZLayer[Any, Throwable, Producer] =
    ZLayer.scoped(
      Producer.make(
        ProducerSettings(BOOSTRAP_SERVERS)
      )
    )

  private val consumer: ZLayer[Any, Throwable, Consumer] =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(BOOSTRAP_SERVERS)
          .withGroupId("streaming-kafka-app")
      )
    )

  def run = {
    val p: ZStream[Producer, Throwable, Nothing] =
      ZStream
        .repeatZIO(Clock.currentDateTime)
        .schedule(Schedule.spaced(1.second))
        .map(time => new ProducerRecord(KAFKA_TOPIC, time.getMinute, s"$time -- Hello, World!"))
        .via(Producer.produceAll(Serde.int, Serde.string))
        .drain

    val c: ZStream[Consumer, Throwable, Nothing] =
      Consumer
        .plainStream(Subscription.topics(KAFKA_TOPIC), Serde.int, Serde.string)
        .tap(e => Console.printLine(e.value))
        .map(_.offset)
        .aggregateAsync(Consumer.offsetBatches)
        .mapZIO(_.commit)
        .drain

    (p merge c).runDrain.provide(producer, consumer)
  }

}
```

## Producing and Consuming JSON Data

Until now, we learned how to work with simple primitive types like `Int`, `String`, etc and how to use their `Serde` instances to encode and decode the data.

In this section, we are going to learn how to work with user-defined data types (like case classes e.g. `Event`), and how to produce and consume the JSON data representing our user-defined data types. We also will learn how to use the `Serde` built-in instances to create more complex `Serde` instances.

### 1. Writing Custom Serializer and Deserializer

In ZIO Kafka all of the built-in serializers/deserializers are instances of the `Serde` trait, which has two useful methods:

```scala
trait Serde[-R, T] extends Deserializer[R, T] with Serializer[R, T] {
  def inmap[U](f: T => U)(g: U => T): Serde[R, U] =
    Serde(map(f))(contramap(g))

  def inmapM[R1 <: R, U](f: T => RIO[R1, U])(g: U => RIO[R1, T]): Serde[R1, U] =
    Serde(mapM(f))(contramapM(g))
}
```

Using the `inmap` and `inmapM` combinators, we can create our own serializers and deserializers on top of the built-in ones:

- The `inmap` is used to transform the `Serde` type `U` with pure transformations of `f` and `g`.
- The `inmapM` is used to transform the `Serde` type `U` with effectful transformations of `f` and `g`. As it accepts effectful transformations, we can encode any parsing failure with a `ZIO` workflow.

Let's say we have a case class `Event` with the following fields:

```scala mdoc:silent
import java.time.OffsetDateTime
import java.util.UUID

case class Event(
  uuid: UUID,
  timestamp: OffsetDateTime,
  message: String
)
```

First, we need to define a JSON decoder and encoder for it:

```scala mdoc:silent
import zio.json._

object Event {
  implicit val encoder: JsonEncoder[Event] =
    DeriveJsonEncoder.gen[Event]

  implicit val decoder: JsonDecoder[Event] =
    DeriveJsonDecoder.gen[Event]
}
```

Then we need to create a `Serde` for the `Event` type. To convert `Event` to JSON and back, we will use the ZIO JSON library, and to define `Serde` for the `Event` type, we will use the `Serde#inmapM` combinator:

```scala mdoc:silent
import zio._
import zio.kafka.serde._

object KafkaSerde {
  val key: Serde[Any, Int] =
    Serde.int

  val value: Serde[Any, Event] =
    Serde.string.inmapM[Any, Event](s =>
      ZIO.fromEither(s.fromJson[Event])
        .mapError(e => new RuntimeException(e))
    )(r => ZIO.succeed(r.toJson))
}
```

As we can see, we use the `String#fromJson` to convert the string to an `Event` object and we also encode any parsing failure with a `RuntimeException` in the `ZIO` workflow.

### 2. Using the Custom Serde

After we have defined our custom `Serde` for the `Event` type, we can use it in our Kafka producer and consumer streams:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka.serde._
import zio.kafka.producer._
import zio.kafka.consumer._
import org.apache.kafka.clients.producer.ProducerRecord

val KAFKA_TOPIC = "json-streaming-hello"

val events: UStream[ProducerRecord[Int, Event]] = ???

val producer =
  events.via(Producer.produceAll(KafkaSerde.key, KafkaSerde.value))

val consumer =
  Consumer
    .plainStream(Subscription.topics(KAFKA_TOPIC), KafkaSerde.key, KafkaSerde.value)
```

### 3. The Complete JSON Streaming Example

Here is a full working example of producing and consuming JSON data with ZIO Kafka, ZIO Streams and ZIO JSON:

```scala mdoc:compile-only
import org.apache.kafka.clients.producer.ProducerRecord
import zio._
import zio.json._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._
import zio.stream.ZStream

import java.time.OffsetDateTime
import java.util.UUID

case class Event(uuid: UUID, timestamp: OffsetDateTime, message: String)

object Event {
  implicit val encoder: JsonEncoder[Event] =
    DeriveJsonEncoder.gen[Event]

  implicit val decoder: JsonDecoder[Event] =
    DeriveJsonDecoder.gen[Event]
}

object KafkaSerde {
  val key: Serde[Any, Int] =
    Serde.int

  val value: Serde[Any, Event] =
    Serde.string.inmapM[Any, Event](s =>
      ZIO.fromEither(s.fromJson[Event])
        .mapError(e => new RuntimeException(e))
    )(r => ZIO.succeed(r.toJson))
}

object JsonStreamingKafkaApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:29092")
  private val KAFKA_TOPIC      = "json-streaming-hello"

  private val producer: ZLayer[Any, Throwable, Producer] =
    ZLayer.scoped(
      Producer.make(
        ProducerSettings(BOOSTRAP_SERVERS)
      )
    )

  private val consumer: ZLayer[Any, Throwable, Consumer] =
    ZLayer.scoped(
      Consumer.make(
        ConsumerSettings(BOOSTRAP_SERVERS)
          .withGroupId("streaming-kafka-app")
      )
    )

  def run = {
    val p: ZStream[Producer, Throwable, Nothing] =
      ZStream
        .repeatZIO(Random.nextUUID <*> Clock.currentDateTime)
        .schedule(Schedule.spaced(1.second))
        .map { case (uuid, time) =>
          new ProducerRecord(
            KAFKA_TOPIC,
            time.getMinute,
            Event(uuid, time, "Hello, World!")
          )
        }
        .via(Producer.produceAll(KafkaSerde.key, KafkaSerde.value))
        .drain

    val c: ZStream[Consumer, Throwable, Nothing] =
      Consumer
        .plainStream(Subscription.topics(KAFKA_TOPIC), KafkaSerde.key, KafkaSerde.value)
        .tap(e => Console.printLine(e.value))
        .map(_.offset)
        .aggregateAsync(Consumer.offsetBatches)
        .mapZIO(_.commit)
        .drain

    (p merge c).runDrain.provide(producer, consumer)
  }

}
```

## Conclusion

In this tutorial first, we learned how to create a producer and consumer for Kafka using the ZIO workflow with ZIO Kafka. Then we learned how to do the same with ZIO Streams. We also learned how to create a custom serializer and deserializer for the Kafka records and how to produce and consume JSON data using the ZIO JSON library.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) project on Github.
