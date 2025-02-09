---
id: producing-consuming-data-from-kafka-topics
title: "Tutorial: How to Produce/Consume Data To/From Kafka Topics?"
sidebar_label: "Producing/Consuming Data To/From Kafka Topics"
---

## Introduction

Kafka is a distributed, fault-tolerant, message-oriented event-store platform. It is used as a message broker for distributed applications. Zio-kafka is a library that provides a way to consume and produce data from Kafka topics, and it also supports the ability to have streaming consumers and producers.

In this tutorial, we will learn how to use zio-streams and zio-kafka to produce and consume data from Kafka topics.

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
  "dev.zio" %% "zio"         % "2.1.15",
  "dev.zio" %% "zio-streams" % "2.1.15",
  "dev.zio" %% "zio-kafka"   % "2.10.0",
  "dev.zio" %% "zio-json"    % "0.7.16"
)
```

1. **Zio-kafka** is a ZIO native client for Apache Kafka. It provides a high-level streaming API on top of the Java client. So we can produce and consume events using the declarative concurrency model of zio-streams.

2. **Zio-streams** introduces a high-level API for working with streams of values. It is designated to work in a highly concurrent environment. It has seamless integration with ZIO, so we have the ability to use all the features of the ZIO along with the streams, e.g. `Scope`, `Schedule`, `ZLayer`, `Queue`, `Hub` etc. To learn more about zio-stream, we have a comprehensive section in on that [here](../../reference/stream/index.md).

3. **Zio-json** is a library to serialize and deserialize data from/to JSON data type. We will be using this library to serialize and deserialize data when reading and writing JSON data from/to Kafka topics.

## Setting Up The Kafka Cluster

Before we start, we need to set up a Kafka cluster. To start a kafka cluster for testing purposes we can use the following `docker-compose.yml` file:

```docker-compose
services:
  broker:
    image: apache/kafka:3.9.0
    container_name: broker
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_BROKER_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://broker:29092,CONTROLLER://broker:29093,PLAINTEXT_HOST://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://broker:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@broker:29093
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
```

Now we can run the `docker compose up -d` command to start the Kafka cluster:

```bash
$ docker compose up -d
```

## Writing a Simple Producer and Consumer Using ZIO Workflows

To write producers and consumers, using the zio-kafka library, we have two choices:

1. Using ZIO workflows
2. Using zio-streams workflows

In this section, we will try the first option.

### 1. Serializing and Deserializing Data

Before we can write a producer and consumer, let's talk about how data is stored in Kafka. Kafka is an event-store platform that stores records, records are key-value pairs as raw bytes. So a Kafka broker knows nothing about its records, it just appends the records to its internal log file.

To produce and consume data from Kafka, we need a way to serialize our data to a byte array and deserialize byte arrays to our data types. This is where the `Serde` data type comes in handy. A `Serde[R, A]` is a `Serializer` and `Deserializer` for values of type `A`, which can use the environment `R` to serialize and deserialize values.

Here is the simplified definition of the `Serde` data type:

```scala
trait Serde[-R, A] {
  def deserialize(data: Array[Byte]): RIO[R, A]
  def serialize(value: A)           : RIO[R, Array[Byte]]
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

Zio-kafka has several producers that can be used to produce data on Kafka topics. In this example, we will be using the `Producer.produce` method:

```scala
trait Producer {
  def produce[R, K, V](
    topic: String,
    key: K,
    value: V,
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  ): RIO[R, RecordMetadata]
}
```

Notice how the type parameters of `produce` method ensure that the value's type, corresponds to the type produced by the value's `Serializer` (and the same for the key and the `Serializer` for the key).

Here is a helper function that shows how we could use the `produce` method:

```scala
def produceRecord(producer: Producer, topic: String, key: Long, value: String): RIO[Any, RecordMetadata] =
  producer.produce[Any, Long, String](
    topic = topic,
    key = key,
    value = value,
    keySerializer = Serde.long,
    valueSerializer = Serde.string
  )
```

### 3. Creating a Producer

Now we can create a `Producer`:

```scala mdoc:compile-only
import zio._
import zio.kafka._
import zio.kafka.producer._

val producer: ZIO[Scope, Throwable, Producer] =
  Producer.make(
    ProducerSettings(List("localhost:9092"))
  )
```

The `ProducerSettings` as constructed in this code fragment is already sufficient for many applications. However, more configuration options are available on `ProducerSettings`.

Notice that the created producer requires a `Scope` in the environment. When this scope closes, the producer closes its
connection with the Kafka cluster. An explicit scope can be created with the `ZIO.scoped` method.

### 4. Creating a Consumer

Zio-kafka provides several ways to consume data from Kafka topics. In this example, we will use the `Consumer.consumeWith` function.

The following helper function creates a ZIO workflow that if we run it, runs forever and consumes records from the given topic and prints them to the console, and then commits the offsets of the consumed records:

```scala
def consumeAndPrintEvents(consumer: Consumer, groupId: String, topic: String, topics: String*): RIO[Any, Unit] =
  consumer.consumeWith(
    settings = ConsumerSettings(BOOSTRAP_SERVERS).withGroupId(groupId),
    subscription = Subscription.topics(topic, topics: _*),
    keyDeserializer = Serde.long,
    valueDeserializer = Serde.string,
  ) { (k, v) =>
    Console.printLine(s"Consumed key: $k, value: $v").orDie
  }
```

For performance reasons, records are always consumed in batches. The `consumeWith` method commits the offsets of consumed records, as soon all records of a batch have been processed.

For more options see [consumer tuning](https://zio.dev/zio-kafka/consumer-tuning).

### 5. The Complete Example

Now it's time to combine all the above steps to create a ZIO workflow that will produce and consume data from the Kafka cluster:

```scala mdoc:compile-only
import zio._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._

/** A simple app that produces and consumes messages from a kafka cluster
 * without using ZIO Streams.
 */
object SimpleKafkaApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:9092")
  private val KAFKA_TOPIC      = "hello"

  def run: ZIO[Scope, Throwable, Unit] = {
    for {
      c <- Consumer
        .consumeWith(
          settings =
            ConsumerSettings(BOOSTRAP_SERVERS).withGroupId("simple-kafka-app"),
          subscription = Subscription.topics(KAFKA_TOPIC),
          keyDeserializer = Serde.long,
          valueDeserializer = Serde.string
        ) { record =>
          Console.printLine(s"Consumed ${record.key()}, ${record.value()}").orDie
        }
        .fork

      producer <- Producer.make(ProducerSettings(BOOSTRAP_SERVERS))
      p <- Clock.currentDateTime
        .flatMap { time =>
          producer.produce[Any, Long, String](
            topic = KAFKA_TOPIC,
            key = time.getHour.toLong,
            value = s"$time -- Hello, World!",
            keySerializer = Serde.long,
            valueSerializer = Serde.string
          )
        }
        .schedule(Schedule.spaced(1.second))
        .fork

      _ <- (c <*> p).join
    } yield ()
  }

}
```

## Zio-kafka With Zio-streams

As we said before, to write producers and consumers using the zio-kafka library, we have two choices:

1. Using ZIO workflows
2. Using zio-streams workflows

In this section we show zio-kafka's seamless integration with zio-streams.

### 1. Streaming Producer API

With zio-kafka's `Producer.produceAll` we get a `ZPipeline`. It takes streams of `ProducerRecord[K, V]`, produces these records to a Kafka topic, and then returns a stream of `RecordMetadata`:

```scala
trait Producer {
  def produceAll[R, K, V](
    keySerializer: Serializer[R, K],
    valueSerializer: Serializer[R, V]
  ): ZPipeline[R, Throwable, ProducerRecord[K, V], RecordMetadata]
}
```

Here is an example that uses `Producer.produceAll`:

```scala
ZStream
  .fromIterator(Iterator.from(0), maxChunkSize = 50) // ZStream[Any, Throwable, Int]
  .mapChunksZIO { chunk =>
    chunk.map { i =>
      new ProducerRecord(topic, key = i, value = s"record $i") 
    }
  }                                                  // ZStream[Any, Throwable, ProducerRecord]
  .via(producer.produceAll(Serde.int, Serde.string)) // ZStream[Any, Throwable, RecordMetadata]
  .runDrain
```

For performance reasons, method `produceAll` produces records in batches, every chunk of the input stream results in a batch.

### 2. Creating a Consumer

When we use the streaming API we need to construct a Consumer:

```scala mdoc:compile-only
import zio._
import zio.kafka._
import zio.kafka.consumer._

val consumer: ZIO[Scope, Throwable, Consumer] =
  Consumer.make(
    ConsumerSettings(List("localhost:9092"))
      .withGroupId("streaming-kafka-app")
  )
```

Notice that the consumer requires a `Scope` in the environment. When this scope closes, the consumer closes its
connection with the Kafka cluster. An explicit scope can be created with the `ZIO.scoped` method.

For more options see [creating a consumer](https://zio.dev/zio-kafka/creating-a-consumer) and [consumer tuning](https://zio.dev/zio-kafka/consumer-tuning).

### 3. Streaming Consumer API

The `Consumer.plainStream` method gives a `ZStream` that, when run, consumes records from a Kafka topic and gives a stream of `CommittableRecord[K, V]`:

```scala
trait Consumer {
  def plainStream[R, K, V](
    subscription: Subscription,
    keyDeserializer: Deserializer[R, K],
    valueDeserializer: Deserializer[R, V]
  ): ZStream[R, Throwable, CommittableRecord[K, V]]
}
```

Parameter `subscription` indicates which topics and partitions should be consumed from. For example `Subscription.topics("events")` to consume from the `events` topic.

Parameters `keyDeserializer` and `valueDeserializer` are the `Serde` that will be used to deserialize the raw record bytes to whatever type you want them to be in.

To indicate that a record has been consumed successfully, and make sure that no other consumer in the same group will consume this record again (even when the application crashes and restarts), we need to commit the offset of the record. This is how it works: first get the `Offset` of the `CommittableRecord` with the `offset` method, then call `commit` on the returned `Offset`.

Here is an example:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka._
import zio.kafka.consumer._
import zio.kafka.serde._

val KAFKA_TOPIC = "my-topic"
val consumer: Consumer = ???

val c: ZIO[Any, Throwable, Unit] =
  consumer
    .plainStream(Subscription.topics(KAFKA_TOPIC), Serde.int, Serde.string)
    .tap(e => Console.printLine(e.value))
    .map(_.offset)      // Get the offset of the record
    .mapZIO(_.commit)   // Commit the offset
    .runDrain
```

While this works, it is not very performant. The problem with this approach is that we are committing offsets for each record separately. This causes a lot of overhead and slows down the consumption of records. To avoid this, we can aggregate offsets into batches and commit them all at once. This can be done by using the `ZStream#aggregateAsync` along with the `Consumer.offsetBatches` sink:

```scala mdoc:compile-only
import zio._
import zio.stream._
import zio.kafka._
import zio.kafka.consumer._
import zio.kafka.serde._

val KAFKA_TOPIC = "my-topic"
val consumer: Consumer = ???

val c: ZIO[Any, Throwable, Unit] =
  consumer
    .plainStream(Subscription.topics(KAFKA_TOPIC), Serde.int, Serde.string)
    .tap(e => Console.printLine(e.value))
    .map(_.offset)                           // Get the offset of the record
    .aggregateAsync(Consumer.offsetBatches)  // Group offsets in an OffsetBatch
    .mapZIO(_.commit)                        // Commit the batch of offsets
    .runDrain
```

Notice that because we are using `aggregateAsync`, the commits run asynchronously with the upstream of `aggregateAsync`. Every time a commit finishes, the next `OffsetBatch` is taken and committed.

:::caution
Keeping the chunking structure intact is important.

In the example so far we have used `tap` to print the records as they are consumed. Unfortunately, methods like `tap` and `mapZIO` destroy the chunking structure and lead to much lower throughput. Please read [a warning about mapZIO](https://zio.dev/zio-kafka/serialization-and-deserialization#a-warning-about-mapzio) for more details and alternatives.
:::

For more details see [consuming Kafka topics using ZIO Streams](https://zio.dev/zio-kafka/consuming-kafka-topics-using-zio-streams).

### 4. The Complete Streaming Example

It's time to create a full working example of zio-kafka with zio-streams:

```scala mdoc:compile-only
import org.apache.kafka.clients.producer.ProducerRecord
import zio._
import zio.kafka.consumer._
import zio.kafka.producer.{Producer, ProducerSettings}
import zio.kafka.serde._
import zio.stream.ZStream

object StreamingKafkaApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:9092")
  private val KAFKA_TOPIC      = "streaming-hello"

  private val producerSettings = ProducerSettings(BOOSTRAP_SERVERS)
  private val consumerSettings =
    ConsumerSettings(BOOSTRAP_SERVERS).withGroupId("streaming-kafka-app")

  def run: ZIO[Any, Throwable, Unit] = {
    val p: ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        for {
          producer <- Producer.make(producerSettings)
          _ <- ZStream
            .repeatZIO(Clock.currentDateTime)
            .schedule(Schedule.spaced(1.second))
            .map { time =>
              new ProducerRecord(
                KAFKA_TOPIC,
                time.getMinute,
                s"$time -- Hello, World!"
              )
            }
            .via(producer.produceAll(Serde.int, Serde.string))
            .runDrain
        } yield ()
      }

    val c: ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        for {
          consumer <- Consumer.make(consumerSettings)
          _ <- consumer
            .plainStream(
              Subscription.topics(KAFKA_TOPIC),
              Serde.int,
              Serde.string
            )
            // do not use `tap` in prod because it destroys the chunking structure and leads to lower performance
            // See https://zio.dev/zio-kafka/serialization-and-deserialization#a-warning-about-mapzio
            .tap(r => Console.printLine("Consumed: " + r.value))
            .map(_.offset)
            .aggregateAsync(Consumer.offsetBatches)
            .mapZIO(_.commit)
            .runDrain
        } yield ()
      }

    p <&> c
  }

}
```

## Producing and Consuming JSON Data

Until now, we learned how to work with simple types like `Int`, `String`, and how to use their `Serde` instances to serialize and deserialize the data.

In this section, we are going to learn how to serialize/deserialize user-defined data types (like case classes), and in particular how to use the JSON format. We also will learn how to use the `Serde` built-in instances to create more complex `Serde` instances.

### 1. Writing Custom Serializer and Deserializer

In zio-kafka all the built-in serializers/deserializers are instances of the `Serde` trait. All `Serde`s offers several combinators with which we can create new `Serde`s. We take a closer look at 2 of them:

- Method `inmap` transforms the `Serde` with pure transformations from the source type to the target type and back.
- Method `inmapZIO` transforms the `Serde` with effectful transformations from the source type to the target type and back. As it accepts effectful transformations, we can encode any parsing failure with a `ZIO` workflow.

In this example, we use `inmap` to transform the build-in `Serde.long` to a `Serde[Any, Instant]`. The `inmap` method gets 2 pure functions, the first converts from `Long` to `Instant`, the second from `Instant` to `Long`:

```scala
import java.time.Instant

val instantSerde: Serde[Any, Instant] =
  Serde.long.inmap[Instant](Instant.ofEpochMilli)(_.toEpochMilli)
```

In the following example, we will use `inmapZIO` to transform the built-in `Serde.string` to a `Serde[Any, Event]` where `Event` is a case class that is serialized to/deserialized from a String containing JSON.

Let's say we have the `Event` case class with the following fields:

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

Then we need to create a `Serde` for the `Event` type. To convert `Event` to JSON and back, we will use the zio-json library. To define a `Serde` for the `Event` type, we will use the `Serde.string.inmapZIO` combinator:

```scala mdoc:silent
import zio._
import zio.kafka.serde._

object EventKafkaSerde {
  val event: Serde[Any, Event] =
    Serde.string.inmapZIO[Any, Event](s =>
      ZIO
        .fromEither(s.fromJson[Event])
        .mapError(e => new RuntimeException(e))
    )(r => ZIO.succeed(r.toJson))
}
```

As we can see, we use the `String#fromJson` to convert the string to an `Event` object, and we also encode any parsing failure with a `RuntimeException` in the `ZIO` workflow.

See [](https://zio.dev/zio-kafka/serialization-and-deserialization)

### 2. The Complete JSON Streaming Example

Here is a full working example of producing and consuming JSON data with zio-kafka, zio-streams and zio-json:

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

/** This is the data we will be sending to Kafka in JSON format. */
case class Event(uuid: UUID, timestamp: OffsetDateTime, message: String)

/** A zio-json encoder/decoder for [[Event]]. */
object Event {
  implicit val encoder: JsonEncoder[Event] =
    DeriveJsonEncoder.gen[Event]

  implicit val decoder: JsonDecoder[Event] =
    DeriveJsonDecoder.gen[Event]
}

/** A zio-kafka serializer/deserializer for [[Event]]. */
object EventKafkaSerde {
  val event: Serde[Any, Event] =
    Serde.string.inmapZIO[Any, Event](s =>
      ZIO
        .fromEither(s.fromJson[Event])
        .mapError(e => new RuntimeException(e))
    )(r => ZIO.succeed(r.toJson))
}

object JsonStreamingKafkaApp extends ZIOAppDefault {
  private val BOOSTRAP_SERVERS = List("localhost:9092")
  private val KAFKA_TOPIC      = "json-streaming-hello"

  def run: ZIO[Any, Throwable, Unit] = {
    val p: ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        for {
          producer <- Producer.make(ProducerSettings(BOOSTRAP_SERVERS))
          _ <- ZStream
            .repeatZIO(Random.nextUUID <*> Clock.currentDateTime)
            .schedule(Schedule.spaced(1.second))
            .map { case (uuid, time) =>
              new ProducerRecord(
                KAFKA_TOPIC,
                time.getMinute,
                Event(uuid, time, "Hello, World!")
              )
            }
            .via(producer.produceAll(Serde.int, EventKafkaSerde.event))
            .runDrain
        } yield ()
      }

    val c: ZIO[Any, Throwable, Unit] =
      ZIO.scoped {
        for {
          consumer <- Consumer.make(
            ConsumerSettings(BOOSTRAP_SERVERS).withGroupId("streaming-kafka-app")
          )
          _ <- consumer
            .plainStream(
              Subscription.topics(KAFKA_TOPIC),
              Serde.int,
              EventKafkaSerde.event
            )
            .tap { r =>
              val event: Event = r.value
              Console.printLine(
                s"Event ${event.uuid} was sent at ${event.timestamp} with message ${event.message}"
              )
            }
            .map(_.offset)
            .aggregateAsync(Consumer.offsetBatches)
            .mapZIO(_.commit)
            .runDrain
        } yield ()
      }

    p <&> c
  }

}
```

## Conclusion

In this tutorial we first learned how to create a producer and consumer for Kafka using the ZIO workflow with zio-kafka. Then we learned how to do the same with zio-streams. We also learned how to create a custom serializer and deserializer for the Kafka records and how to produce and consume JSON data using the zio-json library.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) project on GitHub.
