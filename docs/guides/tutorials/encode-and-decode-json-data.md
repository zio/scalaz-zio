---
id: encode-and-decode-json-data
title: "Tutorial: How to Encode and Decode JSON Data?"
sidebar_label: "Encoding and Decoding JSON Data"
---

## Introduction

In this article, we will cover how to encode and decode JSON data.

## Running Examples

To access the code examples, you can clone the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash 
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-encode-decode-json-data
```

To run all tests, execute the following command:

```bash
$ sbt Test/runMain dev.zio.quickstart.JsonSpec
```

## What is ZIO JSON?

ZIO JSON is a library that provides facilities for writing efficient JSON encoders and decoders. In this article, we will use this library to work with JSON data. To learn more about that, please refer to the [ZIO JSON](https://zio.github.io/zio-json/) documentation.

## Adding Dependencies

To use ZIO JSON, we need to add the following dependency to our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-json" % "0.3.0-RC10"
```

## JsonEncoder and JsonDecoder

The `JsonEncoder` and `JsonDecoder` are the two main types in ZIO JSON. They are used to encode and decode JSON data. Let's see how they are defined (with simplified syntax):


```scala
trait JsonDecoder[A] {
  def decodeJson(str: CharSequence): Either[String, A]
}

trait JsonEncoder[A] {
  def encodeJson(a: A): CharSequence
}
```

We can say for a type `A`:
- A **decoder** is a function that takes a `CharSequence` and returns a `Right` with the decoded value or a `Left` with an error message.
- An **encoder** is a function that takes a value of type `A` and returns a `CharSequence` that represents the encoded value (JSON string).

If we provide an instance of `JsonDecoder` and `JsonEncoder` for a type `A`, we can encode and decode JSON data of that type.

## Built-in Decoders and Encoders


### Simple Values

The ZIO JSON library provides a default implementation for most of the primitive types like `Int`, `String`, `Boolean`, etc.

Let's start test some simple examples:

```scala mdoc:compile-only
import zio.test._
import zio.json._

test("decode from string") {
  val json    = "\"John Doe\""
  val decoded = JsonDecoder[String].decodeJson(json)

  assertTrue(decoded == Right("John Doe"))
}

test("decode from int") {
  val json    = "123"
  val decoded = JsonDecoder[Int].decodeJson(json)

  assertTrue(decoded == Right(123))
}
```

### Higher-kinded Types

It also supports higher-kinded types like `List` and `Option`:

```scala mdoc:compile-only
import zio.json._
import zio.test._
import zio.test.Assertion._

test("decode from optional value") {
  val json = "null"
  val decoded = JsonDecoder[Option[Int]].decodeJson(json)
  assertTrue(decoded == Right(None))
} +
test("decode from array of ints") {
  val json    = "[1, 2, 3]"
  val decoded = json.fromJson[Array[Int]]

  assert(decoded)(isRight(equalTo(Array(1, 2, 3))))
}
```

## How to Define Custom Decoder/Encoder?

### Writing From Scratch

To have a new instance we implement the `JsonEncoder` and `JsonDecoder` interfaces for a type `A`.

For example, if we have a type `Person` we can create instances of `JsonEncoder` and `JsonDecoder` for this type as below:

```scala mdoc:compile-only
import zio.json._
import zio.json.internal.{Write, RetractReader}

case class Person(name: String, age: Int)

object Person {
  implicit val encoder: JsonEncoder[Person] =
    new JsonEncoder[Person] {
      override def unsafeEncode(a: Person, indent: Option[Int], out: Write): Unit = ???
    }
  implicit val decoder: JsonDecoder[Person] =
    new JsonDecoder[Person] {
      override def unsafeDecode(trace: List[JsonError], in: RetractReader): Person = ???
    }
}
```

Writing encoders and decoders from scratch is a complicated task and is not recommended for regular usage. So we don't deep into it furthermore.

### Automatic Derivation of Codecs (macros)

By using macro utilities, we can derive the instances of `JsonEncoder` and `JsonDecoder` for a case class using `DeriveJsonDecoder.gen[A]` and `DeriveJsonEncoder.gen[A]` macros:

```scala mdoc:compile-only
import zio.test._
import zio.json._

test("automatic derivation for case classes") {
  case class Person(name: String, age: Int)
  object Person {
    implicit val decoder: JsonDecoder[Person] = DeriveJsonDecoder.gen[Person]
    implicit val encoder: JsonEncoder[Person] = DeriveJsonEncoder.gen[Person]
  }

  assertTrue((Person("John", 42).toJson == "{\"name\":\"John\",\"age\":42}")
    && ("{\"name\":\"John\",\"age\":42}".fromJson[Person] == Right(Person("John", 42)))
  )
}
```

Let's try a more complex example. Assume we have a data type `Fruit` that is written as follows:

```scala mdoc:silent
sealed trait Fruit extends Product with Serializable
case class Banana(curvature: Double) extends Fruit
case class Apple (poison: Boolean)   extends Fruit
```

We can generate encoder and decoder for this ADT using the macros:

```scala mdoc:silent
import zio.json._

object Fruit {
  implicit val decoder: JsonDecoder[Fruit] =
    DeriveJsonDecoder.gen[Fruit]

  implicit val encoder: JsonEncoder[Fruit] =
    DeriveJsonEncoder.gen[Fruit]
}
```

So then we can have the following tests:

```scala mdoc:compile-only
import zio.test._
import zio.json._

test("decode from custom adt") {
  val json =
    """
      |[
      |  {
      |    "Apple": {
      |      "poison": false
      |    }
      |  },
      |  {
      |    "Banana": {
      |      "curvature": 0.5
      |    }
      |  }
      |]
      |""".stripMargin

  val decoded = json.fromJson[List[Fruit]]
  assertTrue(decoded == Right(List(Apple(false), Banana(0.5))))
} +
test("roundtrip custom adt") {
  val fruits = List(Apple(false), Banana(0.5))
  val json = fruits.toJson
  val roundTrip = json.fromJson[List[Fruit]]
  assertTrue(roundTrip == Right(fruits))
}
```

### Mapping Existing Codecs to Complex Types

If we have `JsonDecoder[A]` we can map its **output** to `JsonDecoder[B]` by providing a function `A => B` as an argument to `map` operation:

```scala
trait JsonDecoder[A] {
  def map[B](f: A => B): JsonDecoder[B]
} 
```

Example:

```scala mdoc:compile-only
import zio.test._
import zio.json._

test("mapping decoders") {
  case class Person(name: String, age: Int)
  object Person {
    implicit val decoder = JsonDecoder[(String, Int)].map { case (name, age) => Person(name, age) }
  }

  val person = "[\"John\",42]".fromJson[Person]

  assertTrue(person == Right(Person("John", 42)))
}
```

If we have `JsonEncoder[A]` we can map its **input** by providing a function  of typ `B => A` to `contramap` operator to create a new `JsonEncoder[B]`:

```scala
trait JsonEncoder[A] {
  def contramap[B](f: B => A): JsonEncoder[B]
}
```

Example:

```scala mdoc:compile-only
import zio.test._
import zio.json._

test("mapping encoders (contramap)") {
  case class Person(name: String, age: Int)
  object Person {
    implicit val encoder: JsonEncoder[Person] =
      JsonEncoder[(String, Int)].contramap((p: Person) => (p.name, p.age))
  }

  val json = Person("John", 42).toJson

  assertTrue(json == "[\"John\",42]")
}
```

## Conclusion

In this section we have covered the basics of JSON encoding and decoding. We have also seen how to create custom codecs for complex types. 

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) on Github.
