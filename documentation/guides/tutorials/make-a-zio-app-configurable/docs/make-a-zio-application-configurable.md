---
id: configurable-zio-application
title: "Tutorial: How to Make a ZIO Application Configurable?"
sidebar_label: "Making a ZIO application configurable"
---

## Introduction

One of the most common requirements for writing an application is to be able to configure it, especially when we are writing cloud-native applications.

In this tutorial, we will start with a simple ZIO application and then try to make it configurable using the ZIO Config library.

## Prerequisites

We will use the [ZIO Quickstart: Restful Web Service](../quickstarts/restful-webservice.md) as our ground project. So make sure you have downloaded and tested it before you start this tutorial.

## Problem

We have a web service that does not allow us to configure the host and port of the service:

```bash
git clone https://github.com/zio/zio-quickstarts.git
cd zio-quickstarts/zio-quickstart-restful-webservice
sbt run
```

The output is:

```
Server started on http://localhost:8080
```

We want to be able to configure the host and port of the service so that before running the application, we specify the host and port of the service.

In this article, we will see how we can make our application configurable using ZIO Config.

## Step 1: Define the Configuration Data Types (ADTs)

In this example our configuration data type is a case class that contains two fields:

```scala mdoc:silent
case class HttpServerConfig(host: String, port: Int, nThreads: Int)
```

## Step 2: Define the Configuration Descriptor

Next, we need to define the configuration descriptor that describes the configuration data type. The best practice is to define the configuration descriptor in the companion object of the configuration data type:

```scala mdoc:silent
import zio.config._
import zio.Config
import zio.config.magnolia.deriveConfig

object HttpServerConfig {
  implicit val config: Config[HttpServerConfig] =
    deriveConfig[HttpServerConfig].nested("HttpServerConfig")
}
```

## Step 3: Accessing Configuration Data using `ZIO.config`

By utilizing the `ZIO.config[HttpServerConfig]` function, we can obtain access to the configuration information that has been read by the current `ConfigProvider`:

```scala mdoc:compile-only
import zio._

ZIO.config[HttpServerConfig].flatMap { config =>
  ??? // Do something with the configuration
}
```

The above code is a ZIO effect that will access the `HttpServerConfig` configuration data and then by using flatMap, we can do something with it, for example, we can print it:

```scala mdoc:compile-only
import zio._

import java.io.IOException

val workflow: ZIO[Any, Exception, Unit] =
  ZIO.config[HttpServerConfig].flatMap { config =>
    Console.printLine(
      "Application started with following configuration:\n" +
        s"\thost: ${config.host}\n" +
        s"\tport: ${config.port}"
    )
  }
```

Let's run the above workflow and see the output:

```scala mdoc:invisible:reset

```

```scala mdoc
import zio._
import zio.config.magnolia._

import java.io.IOException

case class HttpServerConfig(host: String, port: Int)

object HttpServerConfig {
  implicit val config: Config[HttpServerConfig] = deriveConfig[HttpServerConfig].nested("HttpServerConfig")
}

object MainApp extends ZIOAppDefault {

  val workflow: Task[Unit] =
    ZIO.config[HttpServerConfig].flatMap { config =>
      Console.printLine(
        "Application started with following configuration:\n" +
          s"\thost: ${config.host}\n" +
          s"\tport: ${config.port}"
      )
    }

  def run = workflow
}
```

When try to run the above code, we will see the following output:

```log
timestamp=2023-04-01T14:00:13.902065Z level=ERROR thread=#zio-fiber-0 message="" cause="Exception in thread "zio-fiber-4" zio.Config$Error$And: ((((Missing data at HttpServerConfig.host: Expected HTTPSERVERCONFIG_HOST to be set in the environment) or (Missing data at HttpServerConfig.host: Expected HttpServerConfig.host to be set in properties)) and ((Missing data at HttpServerConfig.nThreads: Expected HTTPSERVERCONFIG_NTHREADS to be set in the environment) or (Missing data at HttpServerConfig.nThreads: Expected HttpServerConfig.nThreads to be set in properties))) and ((Missing data at HttpServerConfig.port: Expected HTTPSERVERCONFIG_PORT to be set in the environment) or (Missing data at HttpServerConfig.port: Expected HttpServerConfig.port to be set in properties)))
        at dev.zio.quickstart.MainApp.run(MainApp.scala:35)"
```

The above error is because we have not provided any configuration to the application. By default, ZIO will try to read configuration data from the application properties or environment variables.

So let's try to provide them as environment variables and see what happens:

```bash
HTTPSERVERCONFIG_HOST=localhost HTTPSERVERCONFIG_PORT=8080 HTTPSERVERCONFIG_NTHREADS=0 sbt "runMain dev.zio.quickstart.MainApp"
```

Now we can see this output:

```
Application started with following configuration:
	host: localhost
	port: 8080
```

Great! We have ZIO application that can access the configuration data. It works! Now, let's apply the same approach to our RESTful Web Service.

```scala mdoc:passthrough
import scala.io.Source

// NOTE: Code copied from the zio-docs module to avoid circular dependency in SBT modules.
// This code does not show up on the website since we are using `mdoc:passthrough`.
object utils {

  def readSource(path: String, lines: Seq[(Int, Int)]): String = {
    def readFile(path: String) =
      try {
        Source.fromFile("../" + path)
      } catch {
        case _ => Source.fromFile(path)
      }

    if (lines.isEmpty) {
      val content = readFile(path).getLines().mkString("\n")
      content
    } else {
      val chunks = for {
        (from, to) <- lines
      } yield readFile(path)
        .getLines()
        .toArray[String]
        .slice(from - 1, to)
        .mkString("\n")

      chunks.mkString("\n\n")
    }
  }

  def fileExtension(path: String): String = {
    val javaPath      = java.nio.file.Paths.get(path)
    val fileExtension =
      javaPath.getFileName.toString
        .split('.')
        .lastOption
        .getOrElse("")
    fileExtension
  }

  def printSource(
    path: String,
    lines: Seq[(Int, Int)] = Seq.empty,
    comment: Boolean = true,
    showLineNumbers: Boolean = false,
  ) = {
    val title     = if (comment) s"""title="$path"""" else ""
    val showLines = if (showLineNumbers) "showLineNumbers" else ""
    println(s"""```${fileExtension(path)} ${title} ${showLines}""")
    println(readSource(path, lines))
    println("```")
  }

}

utils.printSource("documentation/guides/tutorials/make-a-zio-app-configurable/src/main/scala/dev/zio/quickstart/MainApp.scala")
```

Until now, we made our RESTful web service configurable to be able to use its config from the ZIO environment with a simple configuration layer.

Now let's move on to the next step: reading configuration data from HOCON files by utilizing custom `ConfigProvider`s.

## Step 3: Reading Configuration Data From HOCON Files

ZIO Config library provides various ways read configuration data from different sources, e.g.:
- HOCON files
- JSON files
- YAML files
- XML files

In this tutorial, we will use the HOCON files. [HOCON](https://github.com/lightbend/config) is config format which is superset of JSON developed by Lightbend.

### Adding ZIO Config Dependencies

We should add the following dependencies to our `build.sb` file:

```scala
libraryDependencies += "dev.zio" %% "zio-config"          % "4.0.2"
libraryDependencies += "dev.zio" %% "zio-config-typesafe" % "4.0.2"
libraryDependencies += "dev.zio" %% "zio-config-magnolia" % "4.0.2"
```

### Defining the HOCON Configuration File

We can define our configuration inside `application.conf` file in the `resources` directory:

```json
# application.conf

HttpServerConfig {
  # The port to listen on.
  port = 8080
  port = ${?PORT}

  # The hostname to listen on.
  host = "localhost"
  host = ${?HOST}

  nThreads = 0
  nThreads = ${?N_THREADS}
}
```

HOCON supports substitutions, so in the above configuration, we can use the environment variables `?PORT` and `?HOST` to substitute the values. We also provide a default value for the port and host.

### Changing the Default ConfigProvider to HOCON Provider

To be able to read the configuration data from the HOCON files, we can use the `TypesafeConfigProvider` to read the configuration data from the `application.conf` file:


```scala mdoc:compile-only
import zio._
import zio.config.typesafe.TypesafeConfigProvider

Runtime.setConfigProvider(
  TypesafeConfigProvider.fromResourcePath()
)
```

Then we should change the default `ConfigProvider` to the new one by using `Runtime.setConfigProvider` layer:

```scala mdoc:passthrough
utils.printSource("documentation/guides/tutorials/make-a-zio-app-configurable/src/main/scala/dev/zio/quickstart/MainApp.scala")
```

## Step 4: Running The Application

Now, if we run the application, it will start the server using the configuration defined in the `application.conf` file with its default values:

```
$ sbt run
Server started on port: 8080
```

We can set the `HOST` and `PORT` environment variables to override the default values:

```scala
$ HOST=localhost PORT=8081 sbt run
Server started on port: 8081
```

## Conclusion

This tutorial covered how to use ZIO Config to read configuration data from HOCON files and configure our application. We haven't covered all the features of the ZIO Config library. To learn more about this library please visit the [ZIO Config documentation](https://zio.github.io/zio-config/).

The complete working example of this tutorial is available on the `configurable-app` branch of our [ZIO Quickstart: Building RESTful Web Service](https://github.com/zio/zio-quickstart-restful-webservice/tree/configurable-app) quickstart on GitHub.
