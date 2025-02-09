---
id: scala-k8s
title: "Scala k8s"
---

[Scala k8s](https://github.com/hnaderi/scala-k8s) is a Kubernetes client, data models and typesafe manifest generation for scala, scalajs, and scala native.

## Introduction
This library provides a full blown extensible client that you can use to interact directly with kubernetes API server, to create operators or accomplish other automation tasks, also you can use it to create or manipulate manifests in scala.

### Design principles
- As extensible as possible
- As dependency free as possible
- As Un-opinionated as possible
- Provide seamless integrations
- All specs are generated from the spec directly and will be in sync with kubernetes all the time

## Installation

This library is currently available for Scala binary versions 2.12, 2.13 and 3.2 on JVM/JS/Native.  
This library is architecured in a microkernel fashion and all the main kubernetes stuff are implemented/generated in pure scala, and integration modules are provided separately.  
main modules are:

- `objects` raw kubernetes objects, which has no dependency
- `client` raw kubernetes client and requests, requests can also be extended in user land easily!

Latest version [![Latest version](https://index.scala-lang.org/hnaderi/scala-k8s/scala-k8s-objects/latest-by-scala-version.svg?style=flat-square)](https://index.scala-lang.org/hnaderi/scala-k8s/scala-k8s-objects)

``` scala
libraryDependencies ++= Seq(
  "dev.hnaderi" %% "scala-k8s-objects" % "[VERSION]", // JVM, JS, Native ; raw k8s objects
  "dev.hnaderi" %% "scala-k8s-client" % "[VERSION]", // JVM, JS, Native ; k8s client kernel and requests
  )
```

The following integrations are currently available:  

```scala
libraryDependencies ++= Seq(
  "dev.hnaderi" %% "scala-k8s-http4s" % "[VERSION]", // JVM, JS, Native ; http4s and fs2 integration
  "dev.hnaderi" %% "scala-k8s-zio" % "[VERSION]", // JVM ; ZIO native integration using zio-http and zio-json 
  "dev.hnaderi" %% "scala-k8s-sttp" % "[VERSION]", // JVM, JS, Native ; sttp integration using jawn parser
  "dev.hnaderi" %% "scala-k8s-circe" % "[VERSION]", // JVM, JS ; circe integration
  "dev.hnaderi" %% "scala-k8s-json4s" % "[VERSION]", // JVM, JS, Native; json4s integration
  "dev.hnaderi" %% "scala-k8s-spray-json" % "[VERSION]", // JVM ; spray-json integration
  "dev.hnaderi" %% "scala-k8s-play-json" % "[VERSION]", // JVM ; play-json integration
  "dev.hnaderi" %% "scala-k8s-zio-json" % "[VERSION]", // JVM, JS ; zio-json integration
  "dev.hnaderi" %% "scala-k8s-jawn" % "[VERSION]", // JVM, JS, Native ; jawn integration
  "dev.hnaderi" %% "scala-k8s-manifests" % "[VERSION]", // JVM ; yaml manifest generation
  "dev.hnaderi" %% "scala-k8s-scalacheck" % "[VERSION]" // JVM, JS, Native; scalacheck instances
)
```
This project also has a [sbt integration](https://github.com/hnaderi/sbt-k8s) you can use to generate manifests.

## Example

We can define any kubernetes object:

```scala
import dev.hnaderi.k8s._  // base packages
import dev.hnaderi.k8s.implicits._  // implicit coversions and helpers
import dev.hnaderi.k8s.manifest._  // manifest syntax

val config = ConfigMap(
  metadata = ObjectMeta(
    name = "example",
    namespace = "staging",
    labels = Map(
      Labels.name("example"),
      Labels.instance("one")
    )
  ),
  data = DataMap(
    "some config" -> "some value",
    "config file" -> Data.file("config.json")
  ),
  binaryData = DataMap.binary(
    "blob" -> Data.file("blob.dat"),
    "blob2" -> Paths.get("blob2.bin"),
    "other inline data" -> "some other data"
  )
)
```

or use `kubectl` style helpers:

```scala
val config2 = ConfigMap(
  data = DataMap.fromDir(new File("path/to/data-directory"))
)
```

And we can connect to our cluster and send requests:

```scala mdoc:silent
import dev.hnaderi.k8s.client.APIs
import dev.hnaderi.k8s.client.ZIOKubernetesClient

// This example uses `kubectl proxy` to simplify authentication
val client = ZIOKubernetesClient.make("http://localhost:8001")
val nodes = ZIOKubernetesClient.send(APIs.nodes.list())
```

Runnable example:
```scala mdoc:silent:reset
import dev.hnaderi.k8s.client._
import zio.http.Client
import zio._

//NOTE run `kubectl proxy` before running this example
object ZIOExample extends ZIOAppDefault {
  private val app = ZIO.scoped {
    for {
      n <- ZIOKubernetesClient.send(APIs.nodes.list())
      _ <- ZIO.foreach(n.items.map(_.metadata.flatMap(_.name)))(Console.printLine(_))
    } yield ()
  }

  override def run =
    app.provide(
      Client.default,
      ZIOBackend.make,
      ZIOKubernetesClient.make("http://localhost:8001")
    )

}
```

