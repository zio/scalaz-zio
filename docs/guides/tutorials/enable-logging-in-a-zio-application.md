---
id: enable-logging-in-a-zio-application
title: "Tutorial: How to Enable Logging in a ZIO Application"
sidebar_label: "Enable Logging in a ZIO Application"
---

## Introduction

ZIO has built-in support for logging. This tutorial will show you how to enable logging for a ZIO application.

## Running The Examples

In [this quickstart](../quickstarts/restful-webservice.md), we developed a web service containing 4 different HTTP Applications, but we haven't enabled logging for them. In this tutorial, we are going to enable logging for the `UserApp` we have developed in this quickstart.

To access the code examples, you can clone the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-restful-webservice-logging
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

## Default Logger

ZIO has a default logger that prints any log messages that are equal, or above the `Level.Info` level:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = ZIO.log("Application started")
}
```

This will print the following message to the console:

```
timestamp=2022-06-01T09:43:08.848398Z level=INFO thread=#zio-fiber-6 message="Application started" location=zio.examples.MainApp.run file=MainApp.scala line=4
```

If we want to include the `Cause` in the log message, we can use the `ZIO.logCause` which takes the message and also the cause:

```scala modc:compile-only
import zio._

object MainApp extends ZIOAppDefault {

  def run =
    ZIO
      .dieMessage("Boom!")
      .foldCauseZIO(
        cause => ZIO.logErrorCause("application stopped working due to an unexpected error", cause),
        _ => ZIO.unit
      )
}
```

Here is the output which includes the cause:

```
timestamp=2022-06-02T05:18:04.131876Z level=ERROR thread=#zio-fiber-6 message="application stopped working due to an unexpected error" cause="Exception in thread "zio-fiber-6" java.lang.RuntimeException: Boom!
at zio.examples.MainApp.run(MainApp.scala:9)
at zio.examples.MainApp.run(MainApp.scala:10)" location=zio.examples.MainApp.run file=MainApp.scala line=11
```

## Supported Log Levels

To distinguish importance of log messages from each other, ZIO supports the following log levels. The default log level is `Info`, so when we use the `ZIO.log` or `ZIO.logCause` methods, the log message will be logged at the `Info` level. For other log levels, we can use any of the `ZIO.log*` or `ZIO.log*Cause` methods:

| LogLevel | Value        | Log Message    | Log with Cause      |
| -------- | ------------ | -------------- | ------------------- |
| All      | Int.MinValue |                |                     |
| Fatal    | 50000        | ZIO.logFatal   | ZIO.logFatalCause   |
| Error    | 40000        | ZIO.logError   | ZIO.logErrorCause   |
| Warning  | 30000        | ZIO.logWarning | ZIO.logWarningCause |
| Info     | 20000        | ZIO.logInfo    | ZIO.logInfoCause    |
| Debug    | 10000        | ZIO.logDebug   | ZIO.logDebugCause   |
| Trace    | 0            | ZIO.logTrace   | ZIO.logTraceCause   |
| None     | Int.MaxValue |                |                     |

As we said earlier, the default logger prints log messages equal or above the `Info` level. So, if we run the following code, only the `Info`, `Warning`, and `Error` and the `Fatal` log messages will be printed:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run =
    for {
      _ <- ZIO.logFatal("Fatal")
      _ <- ZIO.logError("Error")
      _ <- ZIO.logWarning("Warning")
      _ <- ZIO.logInfo("Info")
      _ <- ZIO.logDebug("Debug")
      _ <- ZIO.logTrace("Trace")
    } yield ()
}
```

The output will look like the following:

```
timestamp=2022-06-01T10:16:26.623633Z level=FATAL thread=#zio-fiber-6 message="Fatal" location=zio.examples.MainApp.run file=MainApp.scala line=6
timestamp=2022-06-01T10:16:26.638771Z level=ERROR thread=#zio-fiber-6 message="Error" location=zio.examples.MainApp.run file=MainApp.scala line=7
timestamp=2022-06-01T10:16:26.640827Z level=WARN thread=#zio-fiber-6 message="Warning" location=zio.examples.MainApp.run file=MainApp.scala line=8
timestamp=2022-06-01T10:16:26.642260Z level=INFO thread=#zio-fiber-6 message="Info" location=zio.examples.MainApp.run file=MainApp.scala line=9
```

## Overriding Log Levels

As we mentioned, the default log level for ZIO.log and ZIO.logCause is `Info`. We can use these two methods to log various part of our workflow, and then finally we can wrap the whole workflow with our desired log level:

```scala mdoc:compile-only
import zio._

ZIO.logLevel(LogLevel.Debug) {
  for {
    _ <- ZIO.log("Workflow Started.")
    _ <- ZIO.log("Running task 1.")
    _ <- ZIO.log("Running task 2.")
    _ <- ZIO.log("Workflow Completed.")
  } yield ()
}
```

## UserApp: Add Logging

In this section, we are going to log all the HTTP requests coming to the `UserApp`, and then in each step, when we are handling a request we will log the result, whether is successful or not.

We demonstrate this for the "POST /users" endpoint. This process is the same for all the other endpoints:

```scala mdoc:invisible
import java.util.UUID
import zio.json._
import zio.http._

case class User(name: String, age: Int)

object User {
  implicit val encoder: JsonEncoder[User] =
    DeriveJsonEncoder.gen[User]
  implicit val decoder: JsonDecoder[User] =
    DeriveJsonDecoder.gen[User]
}

import zio._

trait UserRepo {
  def register(user: User): Task[String]

  def lookup(id: String): Task[Option[User]]

  def users: Task[List[User]]
}

object UserRepo {
  def register(user: User): ZIO[UserRepo, Throwable, String] =
    ZIO.serviceWithZIO[UserRepo](_.register(user))

  def lookup(id: String): ZIO[UserRepo, Throwable, Option[User]] =
    ZIO.serviceWithZIO[UserRepo](_.lookup(id))

  def users: ZIO[UserRepo, Throwable, List[User]] =
    ZIO.serviceWithZIO[UserRepo](_.users)
}

def logSpan(
    label: String
): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
  new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
    override def apply[R, E, A](zio: ZIO[R, E, A])(implicit
        trace: Trace
    ): ZIO[R, E, A] =
      ZIO.logSpan(label)(zio)
  }

def logAnnotateCorrelationId(
    req: Request
): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
  new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
    override def apply[R, E, A](
        zio: ZIO[R, E, A]
    )(implicit trace: Trace): ZIO[R, E, A] =
      correlationId(req).flatMap(id =>
        ZIO.logAnnotate("correlation-id", id)(zio)
      )

    def correlationId(req: Request): UIO[String] =
      ZIO
        .succeed(req.headers.get("X-Correlation-ID"))
        .flatMap(x => Random.nextUUID.map(uuid => x.getOrElse(uuid.toString)))
  }
```

```scala mdoc:compile-only
import zio._
import zio.json._
import zio.http._

Routes(
  // POST /users -d '{"name": "John", "age": 35}'
  Method.POST / "users" -> handler { (req: Request) =>
    (for {
      body <- req.body.asString
      _ <- ZIO.logInfo(s"POST /users -d $body")
      u = body.fromJson[User]
      r <- u match {
        case Left(e) =>
          ZIO.logErrorCause(s"Failed to parse the input", Cause.fail(e))
            .as(Response.text(e).status(Status.BadRequest))
        case Right(u) =>
          UserRepo.register(u)
            .foldCauseZIO(
              failure =>
                ZIO.logErrorCause(s"Failed to register user", Cause.fail(failure))
                  .as(Response.status(Status.InternalServerError)),
              success =>
                ZIO.logInfo(s"User registered: $success")
                  .as(Response.text(success))
            )
      }
    } yield r) @@ logSpan("register-user") @@ logAnnotateCorrelationId(req)
  }
)
```

## Logging Spans

ZIO supports logging spans. A span is a logical unit of work that is composed of a start and end time. The start time is when the span is created and the end time is when the span is completed. The span is useful for measuring the time it takes to execute a piece of code. To create a new span, we can use the `ZIO.logSpan` function as follows:

```scala
import zio._

ZIO.logSpan("span name") {
  // do some work
  // log inside the span
  // do some more work
  // another log inside the span
}
```

For example, assume we have a function that takes the `username` and returns the profile picture of the user. We can wrap the whole function in a new span called "get-profile-picture" and log inside the span:

```scala mdoc:compile-only
import zio._

case class User(id: String, name: String, profileImage: String)

def getProfilePicture(username: String) =
  ZIO.logSpan("get-profile-picture") {
    for {
      _    <- ZIO.log(s"Getting information of $username from the UserService")
      user <- ZIO.succeed(User("1", "john", "john.png"))
      _    <- ZIO.log(s"Downloading profile image ${user.profileImage}")
      img  <- ZIO.succeed(Array[Byte](1, 2, 3))
      _    <- ZIO.log("Profile image downloaded")
    } yield img
  }
```

If we run this code with `getProfilePicture("john")`, the output will look like the following:

```
timestamp=2022-06-01T13:59:40.779263Z level=INFO thread=#zio-fiber-6 message="Getting information of john from the UserService" get-profile-picture=6ms location=zio.examples.MainApp.getProfilePicture file=MainApp.scala line=11
timestamp=2022-06-01T13:59:40.793804Z level=INFO thread=#zio-fiber-6 message="Downloading profile image john.png" get-profile-picture=20ms location=zio.examples.MainApp.getProfilePicture file=MainApp.scala line=13
timestamp=2022-06-01T13:59:40.795677Z level=INFO thread=#zio-fiber-6 message="Profile image downloaded" get-profile-picture=22ms location=zio.examples.MainApp.getProfilePicture file=MainApp.scala line=15
```

Any logs inside the span region will be logged with the span name and the duration from the start of the span.

## Multiple Spans

We can also create multiple spans and log inside them:

```scala mdoc:compile-only
import zio._

ZIO.logSpan("span1") {
  for {
    _ <- ZIO.log("log inside span1")
    _ <- ZIO.logSpan("span2") {
      ZIO.log("log inside span1 and span2")
    }
    _ <- ZIO.log("log inside span1")
  } yield ()
}
```

## UserApp: Logging Spans

To measure the time taken to process the request at different points of the code, we can wrap any workflow with `ZIO.logSpan`. In the `UserApp` example, we wrote a workflow that handles the registration of a new user. We can wrap the workflow in a span and log inside the span:

```scala mdoc:compile-only
import zio._
import zio.http._

Routes(
  // POST /users -d '{"name": "John", "age": 35}'
  Method.POST / "users" ->
    handler(ZIO.logSpan("register-user") {
      ??? // registration workflow
    })
)
```

As we need the same for all other endpoints, we introduced a new ZIO Aspect called `LogAspect.logSpan` which can be applied to any ZIO workflow. Let's see how it is implemented and how it works:

```scala mdoc:invisible:reset

```

```scala mdoc:silent
import zio._

object LogAspect {
  def logSpan(
      label: String
  ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](zio: ZIO[R, E, A])(
        implicit trace: Trace
      ): ZIO[R, E, A] =
        ZIO.logSpan(label)(zio)
    }
}
```

To apply this aspect to a ZIO workflow, we can use the `@@` operator:

```scala mdoc:silent
val workflow = ZIO.unit
```

```scala mdoc:compile-only
workflow @@ LogAspect.logSpan("register-user")
```

Now, let's run the web service and post a request to the `/users` endpoint and create a new user and see the logs:

```bash
$ curl -i http://localhost:8080/users -d '{"name": "John", "age": 42}'
```

And the logs:

```
timestamp=2022-06-03T09:42:15.590135Z level=INFO thread=#zio-fiber-16 message="POST /users -d {"name": "John", "age": 42}" register-user=16ms location=dev.zio.quickstart.users.UserApp.apply.applyOrElse file=UserApp.scala line=22
timestamp=2022-06-03T09:42:15.748359Z level=INFO thread=#zio-fiber-16 message="User registered: 24c4ed63-ecc2-41fb-ac0e-5cbf22f187f6" register-user=174ms location=dev.zio.quickstart.users.UserApp.apply.applyOrElse file=UserApp.scala line=35
```

In the above logs, we can see that the `register-user` span is created and the time taken to process the request is logged.

```bash
register-user=16ms
register-user=174ms
```

## Annotating Logs

The default log message is a string, containing a series of key-value pairs. It contains the following information:

- `timestamp`: the time when the log is created
- `level`: the log level
- `thread`: the thread name (fiber name)
- `message`: the log message
- `cause`: the cause of the log
- `location`: the location of the source code where the log is created (filename)
- `line`: the line number of the source code where the log is created

Other than these key-value pairs, we can also annotate the log message with other customized information using the `ZIO.logAnnotate` function. This is especially useful when we are writing multiple services and we want to correlate logs between them using the `CorrelationId`.

For example, when we are handling an HTTP request, we can annotate the log message with the user's id:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  // We use random delay to simulate interleaving of operations in real world
  def randomDelay = Random.nextIntBounded(1000).flatMap(t => ZIO.sleep(t.millis))

  def run =
    ZIO.foreachParDiscard(Chunk("UserA", "UserB", "UserC")) { user =>
      ZIO.logAnnotate("user-id", user) {
        for {
          _ <- randomDelay
          _ <- ZIO.log("fetching user from database")
          _ <- randomDelay
          _ <- ZIO.log("downloading user's profile picture")
        } yield ()
      }
    }
}
```

The output will look as follows:

```
timestamp=2022-06-01T16:31:00.058151Z level=INFO thread=#zio-fiber-9 message="fetching user from database" location=zio.examples.MainApp.run file=MainApp.scala line=14 user-id=UserC
timestamp=2022-06-01T16:31:00.413569Z level=INFO thread=#zio-fiber-7 message="fetching user from database" location=zio.examples.MainApp.run file=MainApp.scala line=14 user-id=UserA
timestamp=2022-06-01T16:31:00.525170Z level=INFO thread=#zio-fiber-9 message="downloading user's profile picture" location=zio.examples.MainApp.run file=MainApp.scala line=16 user-id=UserC
timestamp=2022-06-01T16:31:00.807873Z level=INFO thread=#zio-fiber-8 message="fetching user from database" location=zio.examples.MainApp.run file=MainApp.scala line=14 user-id=UserB
timestamp=2022-06-01T16:31:00.830572Z level=INFO thread=#zio-fiber-7 message="downloading user's profile picture" location=zio.examples.MainApp.run file=MainApp.scala line=16 user-id=UserA
timestamp=2022-06-01T16:31:00.839411Z level=INFO thread=#zio-fiber-8 message="downloading user's profile picture" location=zio.examples.MainApp.run file=MainApp.scala line=16 user-id=UserB
```

As we can see, the `user-id` with its value is annotated to each log message.

## UserApp: Logging Correlation Ids

To add a Correlation ID to the logs, we should first extract the `X-Correlation-ID` header from the request and use it as the Correlation ID.

As this is a common pattern along with all other endpoints, we created a new ZIO Aspect called `LogAspect.logAnnotateCorrelationId` which can be applied to any ZIO workflow:

```scala mdoc:invisible:reset

```

```scala mdoc:silent
import zio._
import zio.http.Request

object LogAspect {
  def logAnnotateCorrelationId(
      req: Request
  ): ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] {
      override def apply[R, E, A](
          zio: ZIO[R, E, A]
      )(implicit trace: Trace): ZIO[R, E, A] =
        correlationId(req).flatMap(id => ZIO.logAnnotate("correlation-id", id)(zio))

      def correlationId(req: Request): UIO[String] =
        ZIO
          .succeed(req.headers.get("X-Correlation-ID"))
          .flatMap(id => Random.nextUUID.map(uuid => id.getOrElse(uuid.toString)))
    }
}
```

Now, we can apply this aspect to any ZIO workflow:

```scala mdoc:invisible
import zio.http._

val workflow = ZIO.unit
val req = Request(headers = Headers("X-Correlation-ID" -> "123"))
```

```scala mdoc:compile-only
workflow @@ LogAspect.logAnnotateCorrelationId(req)
```

By applying this aspect to a ZIO workflow, all logs inside that workflow will be annotated with the `correlation-id` annotation:

Let's run the web service and post a request to the `/users` endpoint and create a new user and see the logs:

```bash
$ curl -i -H "X-Correlation-ID: f798d2f2-abf2-46ff-b3f4-ae1888256706" \
      http://localhost:8080/users -d '{"name": "John", "age": 42}'
```

Here are the logs:

```scala
timestamp=2022-06-03T10:13:18.334468Z level=INFO thread=#zio-fiber-32 message="POST /users -d {"name": "John", "age": 42}" register-user=1ms location=dev.zio.quickstart.users.UserApp.apply.applyOrElse file=UserApp.scala line=22 correlation-id=f798d2f2-abf2-46ff-b3f4-ae1888256706
timestamp=2022-06-03T10:13:18.335034Z level=INFO thread=#zio-fiber-32 message="User registered: ec02143a-8030-4c70-a110-a497617c5c72" register-user=2ms location=dev.zio.quickstart.users.UserApp.apply.applyOrElse file=UserApp.scala line=35 correlation-id=f798d2f2-abf2-46ff-b3f4-ae1888256706
```

We can see that both log lines have the same `correlation-id` annotation.

## Conclusion

In this tutorial, we learned how to enable logging in a ZIO application and how to use the built-in logging facilities of ZIO without requiring any additional dependencies.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) on Github.
