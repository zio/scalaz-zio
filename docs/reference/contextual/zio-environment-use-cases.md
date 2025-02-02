---
id: zio-environment-use-cases
title: "ZIO Environment Use-cases"
---

ZIO Environment allows us to describe workflows which carry some context that is used in the course of executing the workflow. This context can be dived into two categories:

1. Local Capabilities, e.g. scopes and transactions
2. Business Logic, e.g. services and repositories

Let's discuss each of these in turn.

## Local Capabilities

The most idiomatic use of the ZIO environment is for:

- Describing as values workflows that use capabilities that are "local" to a particular context
- Where tracking the use of this context at the type level is helpful for reasoning about programs

Let's look at [`Scope`](../resource/scope.md), which I would say is the most idiomatic usage of the environment in ZIO itself, to see what each of these mean:

### 1. Local Contexts

The first criteria is that a capability be "local" to a particular context and not shared throughout the entire application. For example, in the case of `Scope` we typically don't just have one scope for our entire application but many smaller scopes, such as the scope for using a particular file. Usages of local context also have an operator that allows locally eliminating this capability, such as the `ZIO.scoped` operator which transforms a `ZIO[R with Scope, E, A]` to a `ZIO[R, E, A]`.

Other potential examples of this would be the [`ZState`](../state-management/zstate.md) data type in ZIO, which describes some local use of state and is eliminated by the `ZIO.stateful` operator, a `Transaction` representing the usage of a database transaction, or the context of a particular HTTP request (`RequestContext`).

Notice that using the environment type allows workflows that require these capabilities to be first class values that **compose naturally** and can have their own operators for working with them, which would not be possible if we defined them as methods that required us to explicitly pass around these capabilities.

### 2. Type-level Reasoning

The second criteria is that tracking the usage of this context be helpful for reasoning about programs. We can also use a [`FiberRef`](../state-management/fiberref.md) value to maintain some local context. However, when we do so, our usage of that context is not reflected at the type level.

For example, when we log something it will use a variety of contextual information including the current log level, the current log span, and the current log annotations but none of this is reflected at the type level:

```scala
object ZIO {
  def log(message: => String)(implicit trace: Trace): ZIO[Any, Nothing, Unit]
}
```

Not reflecting this usage of contextual information at the type level can be both an advantage and a disadvantage:

- The advantage is that it can create a simpler API because we do not clutter up the environment with additional dependencies.
- The disadvantage is that we can't track at the type level whether we are using contextual information or whether we have provided it.

In the case of logging this is clearly the right trade-off. Logging is a low level concern that we don't want to require us to update our type signatures, and there is essentially no harm in running a ZIO workflow that does logging without providing this log context since we can just log at some default log level without any log spans or annotations.

In contrast, in the case of `Scope` there is tremendous value in reflecting the use of `Scope` at the type level so we know whether a workflow is resourceful and can have operators that reflect at the type level that we have provided a `Scope` to part of our application. Similarly if we have a **database transaction** reflecting at the type level that some workflow needs to be done as part of a transaction and when we are "executing" a transaction is extremely valuable.

## Business Logic

The other potential use of the ZIO environment is describing the dependencies of our business logic itself. Normally, we implement higher level services in terms of lower level services using [constructor based dependency injection with ZLayer](../di/index.md).

```scala mdoc:silent
import zio._

trait HighLevelService {
  def doSomething: ZIO[Any, Nothing, Unit]
}

object HighLevelService {
  val live: ZLayer[LowLevelService, Nothing, HighLevelService] =
    ZLayer.fromFunction(HighLevelServiceLive(_))

  final case class HighLevelServiceLive(lowLevelService: LowLevelService) extends HighLevelService {
    def doSomething: ZIO[Any, Nothing, Unit] =
      ??? // implemented in terms of `LowLevelService`
  }
}

trait LowLevelService {
  def doSomethingElse: ZIO[Any, Nothing, Unit]
}

object LowLevelService {
  val live: ZLayer[Any, Nothing, LowLevelService] = ???
}
```

This allows us to avoid using `LowLevelService` in the environment and to not "leak" implementation details, since the dependency on `LowLevelService` is an implementation detail of `HighLevelService` that might not even exist if `HighLevelService` is refactored.

However, the question arises then of how we should work with `HighLevelService` in the core of our business logic or the center of the "onion" in the [onion architecture](../architecture/architectural-patterns.md#onion-architecture)?

There are two approaches to this.

### Everything as a Service

The first approach is just that everything is a service:

```scala
sealed trait ApplicationService {
  def run: ZIO[Any, Nothing, Unit]
}

object ApplicationService {
  val live: ZLayer[Any, Nothing, LowLevelService] = ???
  
  final case class ApplicationServiceLive(highLevelService: HighLevelService) extends ApplicationService {
    val run: ZIO[Any, Nothing, Unit] = ??? // business logic implemented in terms of high level services
  }
}

object Main extends ZIOAppDefault {
  val run =
    ZIO
      .serviceWithZIO[ApplicationService](_.run)
      .provide(
        ApplicationService.live,
        HighLevelService.live,
        LowLevelService.live
      )
}
```

This style avoids any usage of the ZIO environment that is not a local capability except for possibly a single time within `ZIOAppDefault`.

### Using ZIO Environment

There can be a feeling that defining this final `ApplicationLevelService` is unnecessary, and we would like to be able to write our business logic in terms of high level services directly without making it another service:

```scala
import zio._

object Main extends ZIOAppDefault {

  val myProgramLogic: ZIO[HighLevelService, Nothing, Unit] =
    for {
      _ <- ZIO.serviceWithZIO[HighLevelService](_.doSomething)
      - <- otherLogicHere
    } yield ()

  val run =
    myProgramLogic.provide(
      HighLevelSevice.live,
      LowLevelService.live
    )
}
```

There has been some movement towards the "everything is a service" approach since it avoids the need to implement [service accessors](../service-pattern/accessor-methods.md) but it can be a matter of team style which of these approaches to use. Either way our program is the same except for whether in our business logic we call methods on services directly or use the environment for that.

To learn more about this approach please see [how we can use dependency injection with the service pattern](../di/dependency-injection-in-zio.md#dependency-injection-and-service-pattern).
