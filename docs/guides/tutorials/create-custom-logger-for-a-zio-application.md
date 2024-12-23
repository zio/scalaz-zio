---
id: create-custom-logger-for-a-zio-application
title: "Tutorial: How to Create a Custom Logger for a ZIO Application?"
sidebar_label: "Create Custom Logger for a ZIO Application"
---

## Introduction

As we have seen in the [previous tutorial](enable-logging-in-a-zio-application.md), ZIO has a variety of built-in logging facilities. Also, it has a default logger that can be used to print log messages to the console. When we go to production, we may want to use a different logger with a customized configuration. For example, we may want to log to a file or a database instead of the console.

In this tutorial, we are going to see how we can create a custom logger for a ZIO application.

## Running the Examples

In [this article](enable-logging-in-a-zio-application.md), we enabled logging for `UserApp` http application. In this tutorial, we are going to create a custom logger for the `UserApp`.

To run the code, clone the repository and checkout the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-restful-webservice-custom-logger
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

## Creating a Custom Logger

To create a new logger for the ZIO application, we need to create a new `ZLogger` object. The `ZLogger` is a trait that defines the interface for a ZIO logger. The default logger has implemented this trait through the `ZLogger.default` object.

```scala mdoc:compile-only
import zio._

val logger: ZLogger[String, Unit] =
  new ZLogger[String, Unit] {
    override def apply(
      trace: Trace,
      fiberId: FiberId,
      logLevel: LogLevel,
      message: () => String,
      cause: Cause[Any],
      context: FiberRefs,
      spans: List[LogSpan],
      annotations: Map[String, String]
    ): Unit =
      println(s"${java.time.Instant.now()} - ${logLevel.label} - ${message()}")
  }
```

So then, we can remove all the default loggers and replace them with our custom logger:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  val logger: ZLogger[String, Unit] =
    new ZLogger[String, Unit] {
      override def apply(
        trace: Trace,
        fiberId: FiberId,
        logLevel: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ): Unit =
        println(s"${java.time.Instant.now()} - ${logLevel.label} - ${message()}")
    }

  override val bootstrap = Runtime.removeDefaultLoggers ++ Runtime.addLogger(logger)

  def run =
    for {
      _ <- ZIO.log("Application started!")
      _ <- ZIO.log("Another log message.")
      _ <- ZIO.log("Application stopped!")
    } yield ()
}
```

By running this application, the log messages will be printed like this:

```bash
2022-06-04T13:49:19.554648Z - INFO - Application started!
2022-06-04T13:49:19.567854Z - INFO - Another log message.
2022-06-04T13:49:19.568831Z - INFO - Application stopped!
```

## Using SLF4J Logger in a ZIO Application

So far, we learned how to write a custom logger for a ZIO application. Now, in this section, we want to add SLF4J Logging support to the `UserApp` we have developed in the [Restful Web Service](../quickstarts/restful-webservice.md) quickstart.

SLF4J is a logging facade that decouples our application code from any underlying logging implementation. To enable SLF4J logging for a ZIO application, we need to implement the `ZLogger` trait using SLF4J. Fortunately, the ZIO Logging project has done this for us.

So we can simply add this library to our project by adding the following dependencies to our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-logging"       % "2.0.0"
libraryDependencies += "dev.zio" %% "zio-logging-slf4j" % "2.0.0"
```

Now we can use the `SLF4J.sl4j` layer to enable SLF4J logging:

```scala mdoc:compile-only
import zio._
import zio.logging.LogFormat
import zio.logging.backend.SLF4J

object MainApp extends ZIOAppDefault {
  override val bootstrap = SLF4J.slf4j(LogFormat.colored)

  def run = ZIO.log("Application started!")
}
```

Let's run the application and see the output:

```bash
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
SLF4J: Defaulting to no-operation (NOP) logger implementation
SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
```

Oops! The SLF4J failed to find any binding in the classpath. To fix this, we need to add an SLF4J binding to our classpath.

## Adding a Simple SLF4J Binding to the Classpath

The SLF4J has a simple binding that can be used by adding the `slf4j-simple` dependency to our `build.sbt` file:

```scala
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.36"
```

And then we can run the application again:

```bash
[ZScheduler-Worker-7] INFO zio-slf4j-logger - timestamp=2022-06-04T19:36:43.768256+04:30 level=INFO thread=zio-fiber-6 message="Application started!"
```

It works! Now, our ZIO application uses SLF4J for its logging backend.

Similarly, we can bind our application to any other logging framework by adding the appropriate dependency to our `build.sbt` file:

- `slf4j-log4j12`
- `slf4j-reload4j`
- `slf4j-jdk14`
- `slf4j-nop`
- `slf4j-jcl`
- `logback-classic`

To switch to another logging framework, we need to provide one of the above dependencies instead of `slf4j-simple`. In the next section, we will learn how to switch to the `reload4j` logging framework.

## Switching to the Reload4j Logging Framework

To use the `reload4j` logging framework, we need to add the following dependencies to our `build.sbt` file:

```diff
- libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.36"
+ libraryDependencies += "org.slf4j" % "slf4j-reload4j" % "1.7.36"
```

Now we can configure our logger by adding the `log4j.properties` to the resources directory:

```
log4j.rootLogger = Info, consoleAppender
log4j.appender.consoleAppender=org.apache.log4j.ConsoleAppender
log4j.appender.consoleAppender.layout=org.apache.log4j.PatternLayout
log4j.appender.consoleAppender.layout.ConversionPattern=[%p] %d %c %M - %m%n
```

By customizing the `ConversionPattern` we can control the format of the log messages.

## Switching to the Logback Logging Framework

In the same way, we can switch to the `logback-classic` logging framework by adding the following dependencies to our `build.sbt` file:

```diff
- libraryDependencies += "org.slf4j"      % "slf4j-reload4j"  % "1.7.36"
+ libraryDependencies += "ch.qos.logback" % "logback-classic" % "1.2.11"
```

Then we can configure our logger by adding the `logback.xml` to the resources directory:

```xml
<?xml version="1.0" encoding="UTF-8"?>

<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="info">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

## Conclusion

In this article, we have learned how to create a custom logger for a ZIO application. We also covered how to add SLF4J logging support instead of default ZIO logging.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) project.
