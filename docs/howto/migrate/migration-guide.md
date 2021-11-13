---
id: zio-2.x-migration-guide
title: "ZIO 2.x Migration Guide"
---

```scala mdoc:invisible
import zio._
```

In this guide we want to introduce the migration process to ZIO 2.x. So if you have a project written in ZIO 1.x and want to migrate that to ZIO 2.x, this article is for you. 

ZIO uses the [Scalafix](https://scalacenter.github.io/scalafix/) for automatic migration. Scalafix is a code migration tool that takes a rewrite rule and reads the source code, converting deprecated features to newer ones, and then writing the result back to the source code. 

ZIO has a migration rule named `Zio2Upgrade` which migrates a ZIO 1.x code base to the ZIO 2.x. This migration rule covers most of the changes. Therefore, to migrate a ZIO project to 2.x, we prefer to apply the `Zio2Upgrade` rule to the existing code. After that, we can go to the source code and fix the remaining compilation issues:

1. First, we should ensure that all of our direct and transitive dependencies [have released their compatible versions with ZIO 2.x](https://docs.google.com/spreadsheets/d/1QIKgavognTRgh84xAqPTJriJ1VDGbaw8S1fmzMGgf98/). Note that we shouldn't update our dependencies to the 2.x compatible versions, before running scalafix.

2. Next, we need to install the [Scalafix SBT Plugin](https://github.com/scalacenter/sbt-scalafix), by adding the following line into `project/plugins.sbt` file:
    ```scala
    // project/plugins.sbt
    addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "<version>")
    ```

3. We are ready to apply the migration rule:
    ```bash
    sbt "scalafixEnable; scalafixAll github:zio/zio/Zio2Upgrade?sha=series/2.x" 
    ```

4. After running scalafix, it's time to upgrade ZIO dependencies. If we are using one of the following dependencies, we need to bump them into the `2.x` version:

    ```scala
    libraryDependencies += "dev.zio" %% "zio"         % "2.0.0"
    libraryDependencies += "dev.zio" %% "zio-streams" % "2.0.0"
    libraryDependencies += "dev.zio" %% "zio-test"    % "2.0.0"
    ```

   Other than ZIO, we should upgrade all other (official or community) ZIO libraries we are using in our `build.sbt` file.

5. Now, we have performed most of the migration. Finally, we should fix the remaining compilation errors with the help of the remaining sections in this article.

## Guidelines for Library Authors

As a contributor to ZIO ecosystem libraries, we also should cover these guidelines:

1. We should add _implicit trace parameter_ to all our codebase, this prevents the guts of our library from messing up the user's execution trace. 
 
    Let's see an example of that in the ZIO source code:

    ```diff
    trait ZIO[-R, +E, +A] {
    -  def map[B](f: A => B): ZIO[R, E, B] =
         flatMap(a => ZIO.succeedNow(f(a)))
    +  def map[B](f: A => B)(implicit trace: ZTraceElement): ZIO[R, E, B] = 
         flatMap(a => ZIO.succeedNow(f(a)))
    }
    ```
2. All parameters to operators returning an effect [should be by-name](#lazy-evaluation-of-parameters). Also, we should be sure to capture any parameters that are referenced more than once as a lazy val in our implementation to prevent _double evaluation_. 
    
    The overall pattern in implementing such methods will be:

    ```diff
    - def foreachParN[A](n: Int)(a: Iterable[A]) = {
        ... // The function body
    - }
    + def foreachParN[A](n0: => Int)(a0: => Iterable[A]) = 
    +   ZIO.suspendSucceed {
    +    val n = n0 
    +    val a = a0
          ... // The function body
    +   }
    ```
   
    As a result, the code will be robust to _double evaluation_ as well as to _side-effects embedded within parameters_.

3. We should update names to match [ZIO 2.0 naming conventions](#zio-20-naming-conventions).
4. ZIO 2.0 introduced [new structured concurrently operators](#compositional-concurrency) which helps us to change the regional parallelism settings of our application. So if applicable, we should use these operators instead of the old parallel operators.

## ZIO

### Removed Methods

**Arrow Combinators** — (`+++`, `|||`, `onSecond`, `onFirst`, `second`, `first`, `onRight`, `onLeft`, `andThen`, `>>>`, `compose`, `<<<`, `identity`, `swap`, `join`)

As the _Module Pattern 2.0_ encourages users to use `Has` with the environment `R` (`Has[R]`), it doesn't make sense to have arrow combinators. An arrow makes the `R` parameter as the _input_ of the arrow function, and it doesn't match properly with environments with the `Has` data type. So In ZIO 2.0, all arrow combinators are removed, and we need to use alternatives like doing monadic for-comprehension style `flatMap` with combinators like `provide`, `zip`, and so on.

### ZIO 2.0 Naming Conventions

In ZIO 2.0, the name of constructors and operators becomes more ergonomic and simple. They reflect more about their purpose rather than just using idiomatic jargon of category theory or functional terms in functional programming with Haskell.

Here are some of the most important changes:

- **Multiple ways of doing the same thing are removed** — For example:
    - Both `ZIO.succeed` and `ZIO.effectTotal` do the same thing. So in ZIO 2.0 we just have one version of these constructors which is `ZIO.succeed`.
    - The bind operator `>>=` is removed. So we just have one way to flatMap which is the `flatMap` method. Therefore, the `>>=` method doesn't surprise the non-Haskellers.
    - The `ZIO#get` method was essentially a more constrained version of `ZIO#some`. So the `get` method is deprecated.

- **`ZIO.attempt` instead of `ZIO.effect`** — In ZIO 2.0 all ZIO constructors like `ZIO.effect*` that create a ZIO from a side effect are deprecated and renamed to the `ZIO.attempt*` version. For example, when we are reading from a file, it's more meaning full to say we are attempting to read from a file instead of saying we have an effect of reading from a file.

- **`ZIO` instead of the `M` suffix** — In effectful operations, the `M` suffix is renamed to the `ZIO` suffix. In ZIO 1.x, the `M` suffix in an effectful operation means that the operation works with monad in a monadic context. This naming convention is the legacy of Haskell jargon. In ZIO 2.x, all these suffixes are renamed to the `ZIO`. For example, the `ifM` operator is renamed to `ifZIO`.

- **`Discard` instead of the underscore `_` suffix** — The underscore suffix is another legacy naming convention from Haskell's world. In ZIO 1.x, the underscore suffix means we are going to discard the result. The underscore version works exactly like the one without the underscore, but it discards the result and returns `Unit` in the ZIO context. For example, the `collectAll_` operator renamed to `collectAllDiscard`.

- **`as`, `to`, `into` prefixes** — The `ZIO#asService` method is renamed to `ZIO#toServiceBuilder` and also the `ZIO#to` is renamed to the `ZIO#intoPromise`. So now we have three categories of conversion:
    1. **as** — The `ZIO#as` method and its variants like `ZIO#asSome`, `ZIO#asSomeError` and `ZIO#asService` are used when transforming the `A` inside of a `ZIO`, generally as shortcuts for `map(aToFoo(_))`.
    2. **to** — The `ZIO#to` method and its variants like `ZIO#toServiceBuilder`, `ZIO#toManaged`, and `ZIO#toFuture` are used when the `ZIO` is transformed into something else other than the `ZIO` data-type.
    3. **into** — All `into*` methods, accept secondary data-type, modify it with the result of the current effect (e.g. `ZIO#intoPromise`, `ZStream#intoHub`, `ZStream#intoQueue` and `ZStream#intoManaged`)

| ZIO 1.x                        | ZIO 2.x                           |
|--------------------------------|-----------------------------------|
| `ZIO#>>=`                      | `ZIO#flatMap`                     |
| `ZIO#bimap`                    | `ZIO#mapBoth`                     |
| `ZIO#mapEffect`                | `ZIO#mapAttempt`                  |
| `ZIO#filterOrElse_`            | `ZIO#filterOrElse`                |
| `ZIO#foldCauseM`               | `ZIO#foldCauseZIO`                |
| `ZIO#foldM`                    | `ZIO#foldZIO`                     |
| `ZIO#foldTraceM`               | `ZIO#foldTraceZIO`                |
|                                |                                   |
| `ZIO#get`                      | `ZIO#some`                        |
| `ZIO#optional`                 | `ZIO#unoption`                    |
| `ZIO#someOrElseM`              | `ZIO#someOrElseZIO`               |
|                                |                                   |
| `ZIO.forkAll_`                 | `ZIO.forkAllDiscard`              |
| `ZIO#forkInternal`             | `ZIO#fork`                        |
| `ZIO#forkOn`                   | `ZIO#onExecutionContext(ec).fork` |
| `ZIO.fromFiberM`               | `ZIO.fromFiberZIO`                |
| `ZIO.require`                  | `ZIO.someOrFail`                  |
| `ZIO#on`                       | `ZIO#onExecutionContext`          |
| `ZIO#rejectM`                  | `ZIO#rejectZIO`                   |
| `ZIO#run`                      | `ZIO#exit`                        |
| `ZIO#timeoutHalt`              | `ZIO#timeoutFailCause`            |
|                                |                                   |
| `ZIO#to`                       | `ZIO#intoPromise`                 |
| `ZIO#asService`                | `ZIO#toServiceBuilder`            |
|                                |                                   |
| `ZIO.accessM`                  | `ZIO.accessZIO`                   |
| `ZIO.fromFunctionM`            | `ZIO.accessZIO`                   |
| `ZIO.fromFunction`             | `ZIO.access`                      |
| `ZIO.services`                 | `ZIO.service`                     |
|                                |                                   |
| `ZIO.bracket`                  | `ZIO.acquireReleaseWith`          |
| `ZIO.bracketExit`              | `ZIO.acquireReleaseExitWith`      |
| `ZIO.bracketAuto`              | `ZIO.acquireReleaseWithAuto`      |
| `ZIO#bracket`                  | `ZIO#acquireReleaseWith`          |
| `ZIO#bracket_`                 | `ZIO#acquireRelease`              |
| `ZIO#bracketExit`              | `ZIO#acquireReleaseExitWith`      |
| `ZIO#bracketExit`              | `ZIO#acquireReleaseExitWith`      |
| `ZIO#bracketOnError`           | `ZIO#acquireReleaseOnErrorWith`   |
| `ZIO#toManaged_`               | `ZIO#toManaged`                   |
|                                |                                   |
| `ZIO.collectAll_`              | `ZIO.collectAllDiscard`           |
| `ZIO.collectAllPar_`           | `ZIO.collectAllParDiscard`        |
| `ZIO.collectAllParN_`          | `ZIO.collectAllParNDiscard`       |
| `ZIO#collectM`                 | `ZIO#collectZIO`                  |
|                                |                                   |
| `ZIO.effect`                   | `ZIO.attempt`                     |
| `ZIO.effectAsync`              | `ZIO.async`                       |
| `ZIO.effectAsyncInterrupt`     | `ZIO.asyncInterrupt`              |
| `ZIO.effectAsyncM`             | `ZIO.asyncZIO`                    |
| `ZIO.effectAsyncMaybe`         | `ZIO.asyncMaybe`                  |
| `ZIO.effectBlocking`           | `ZIO.attemptBlocking`             |
| `ZIO.effectBlockingCancelable` | `ZIO.attemptBlockingCancelable`   |
| `ZIO.effectBlockingIO`         | `ZIO.attemptBlockingIO`           |
| `ZIO.effectBlockingInterrupt`  | `ZIO.attemptBlockingInterrupt`    |
| `ZIO.effectSuspend`            | `ZIO.suspend`                     |
| `ZIO.effectSuspendTotal`       | `ZIO.suspendSucceed`              |
| `ZIO.effectSuspendTotalWith`   | `ZIO.suspendSucceedWith`          |
| `ZIO.effectSuspendWith`        | `ZIO.suspendWith`                 |
| `ZIO.effectTotal`              | `ZIO.succeed`                     |
|                                |                                   |
| `ZIO.foreach_`                 | `ZIO.foreachDiscard`              |
| `ZIO.foreachPar_`              | `ZIO.foreachParDiscard`           |
| `ZIO.foreachParN_`             | `ZIO.foreachParNDiscard`          |
| `ZIO#replicateM`               | `ZIO#replicateZIO`                |
| `ZIO#replicateM_`              | `ZIO#replicateZIODiscard`         |
|                                |                                   |
| `ZIO.halt`                     | `ZIO.failCause`                   |
| `ZIO.haltWith`                 | `ZIO.failCauseWith`               |
|                                |                                   |
| `ZIO.ifM`                      | `ZIO.ifZIO`                       |
| `ZIO.loop_`                    | `ZIO.loopDiscard`                 |
| `ZIO.whenCaseM`                | `ZIO.whenCaseZIO`                 |
| `ZIO.whenM`                    | `ZIO.whenZIO`                     |
| `ZIO.unlessM`                  | `ZIO.unlessZIO`                   |
| `ZIO#unlessM`                  | `ZIO#unlessZIO`                   |
| `ZIO#whenM`                    | `ZIO#whenZIO`                     |
| `ZIO#repeatUntilM`             | `ZIO#repeatUntilZIO`              |
| `ZIO#repeatWhileM`             | `ZIO#repeatWhileZIO`              |
| `ZIO#retryUntilM`              | `ZIO#retryUntilZIO`               |
| `ZIO#retryWhileM`              | `ZIO#retryWhileZIO`               |
| `ZIO.replicateM`               | `ZIO.replicateZIO`                |
| `ZIO.replicateM_`              | `ZIO.replicateZIODiscard`         |
|                                |                                   |
| `ZIO.validate_`                | `ZIO.validateDiscard`             |
| `ZIO.validatePar_`             | `ZIO.validateParDiscard`          |

### Lazy Evaluation of Parameters

In ZIO 2.x, we changed the signature of those functions that return effects to use _by-name parameters_. And we also encourage library authors to do the same for any functions that return effects.

Our motivation for this change was a common mistake among new users of ZIO, which they _accidentally embed raw effects_ inside the function they pass to ZIO constructors and operators. This mistake may produce some unwanted behaviors.

Let's see an example of this anti-pattern in ZIO 1.x:

```scala mdoc:silent:nest:warn
ZIO.bracket({
  val random = scala.util.Random.nextInt()
  ZIO.succeed(random)
})(_ => ZIO.unit)(x => console.putStrLn(x.toString)).repeatN(2)
```

The newbie user expects that this program prints 3 different random numbers, while the output would be something as follows:

```
1085597917
1085597917
1085597917
```

This is because the user incorrectly introduced a raw effect into the `acquire` parameter of `bracket` operation. As the `acuqire` is _by-value parameter_, the value passed to the function evaluated _eagerly_, only once:

```scala
def bracket[R, E, A](acquire: ZIO[R, E, A]): ZIO.BracketAcquire[R, E, A]
```

If we make the `acquire` to _by-name parameter_, we can prevent these mistakes:

```diff
- def bracket[R, E, A](acquire: ZIO[R, E, A]): ZIO.BracketAcquire[R, E, A]
+ def bracket[R, E, A](acquire: => ZIO[R, E, A]): ZIO.BracketAcquire[R, E, A]
```

So, in ZIO 2.x if we accidentally introduce an effect to the ZIO parameters, the lazy parameter prevents the program from producing undesired behaviors:

```scala mdoc:silent:nest:warn
// Note that in ZIO 2.x, the `bracket` is deprecated and renamed to the `acquireReleaseWith`. In this example to prevent the consistency of our example, we used the `bracket`.

ZIO.bracket({
  val random = scala.util.Random.nextInt()
  ZIO.succeed(random)
})(_ => ZIO.unit)(x => console.putStrLn(x.toString)).repeatN(2)
```

The output would be something like this:

```scala
355191016
2046799548
333146616
```

### Composable Zips

In ZIO 2.x, when we are zipping together different effects:
- `Tuple`s are not nested.
- `Unit`s do not contribute to the output.

Assume we have these effects

```scala mdoc:silent:nest
val x1: UIO[Int]     = ZIO.succeed(???)
val x2: UIO[Unit]    = ZIO.succeed(???)
val x3: UIO[String]  = ZIO.succeed(???)
val x4: UIO[Boolean] = ZIO.succeed(???)
```

In ZIO 1.x, the output of zipping together these effects are nested:

```scala
val zipped = x1 <*> x2 <*> x3 <*> x4
// zipped: ZIO[Any, Nothing, (((Int, Unit), String), Boolean)] = zio.ZIO$FlatMap@3ed3c202
```

While in ZIO 2.x, we have more ergonomics result type and also the `Unit` data-type doesn't contribute to the output:

```scala mdoc:nest
val zipped = x1 <*> x2 <*> x3 <*> x4
```

This change is not only for the `ZIO` data type but also for all other data types like `ZManaged`, `ZStream`, `ZSTM`, etc.

As we have compositional zips, we do not longer need higher arity zips in ZIO 1.x like `mapN`, `mapParN`, `Gen#zipN`, and `Gen#crossN`. They are deprecated in ZIO 2.x.

Here is the list of `zip` variants that are deprecated:

| ZIO 1.x         | ZIO 2.x      |
|-----------------|--------------|
| `ZIO#&&&`       | `ZIO#zip`    |
| `ZIO.tupled`    | `ZIO.zip`    |
| `ZIO.tupledPar` | `ZIO.zipPar` |
| `ZIO.mapN`      | `ZIO.zip`    |
| `ZIO.mapParN`   | `ZIO.zipPar` |

### Compositional Concurrency

We introduced two operations that modify the parallel factor of a concurrent ZIO effect, `ZIO#withParallelism` and `ZIO#withParallelismUnbounded`. This makes the maximum number of fibers for parallel operators as a regional setting. Therefore, all parallelism operators ending in `N`, such as `foreachParN` and `collectAllParN`, have been deprecated:

| ZIO 1.x                   | ZIO 2.x                  |
|---------------------------|--------------------------|
| `foreachParN`             | `foreachPar`             |
| `foreachParN_`            | `foreachParDiscard`      |
| `collectAllParN`          | `collectAllPar`          |
| `collectAllParN_`         | `collectAllParDiscard`   |
| `collectAllWithParN`      | `collectAllWithPar`      |
| `collectAllSuccessesParN` | `collectAllSuccessesPar` |

Having separate methods for changing the parallelism factor of a parallel effect deprecates lots of extra operators and makes concurrency more compositional.

So instead of writing a parallel task like this:

```scala mdoc:invisible
val urls: List[String] = List.empty
def download(url: String): Task[String] = Task.attempt(???)
```

```scala mdoc:silent:warn
ZIO.foreachParN(8)(urls)(download)
```

We should use the `withParallelism` method:

```scala mdoc:nest:silent
ZIO.foreachPar(urls)(download).withParallelism(8)
```

The `withParallelismUnbounded` method is useful when we want to run a parallel effect with an unbounded maximum number of fibers:

```scala mdoc:silent:nest
ZIO.foreachPar(urls)(download).withParallelismUnbounded
```

### Either Values

In ZIO 1.x, the `ZIO#left` and `ZIO#right` operators are lossy, and they don't preserve the information on the other side of `Either` after the transformation.

For example, assume we have an effect of type `ZIO[Any, Throwable, Left[Int, String]]`:

```scala
val effect         = Task.effect(Left[Int, String](5))
// effect: ZIO[Any, Throwable, Left[Int, String]]
val leftProjection = effect.left
// leftProjection: ZIO[Any, Option[Throwable], Int]
```

The error channel of `leftProjection` doesn't contain type information of the other side of the `Left[Int, String]`, which is `String`. So after projecting to the left, we can not go back to the original effect.

In ZIO 2.x, the `ZIO#left` and `ZIO#right`, contains all type information so then we can `unleft` or `unright` to inverse that projection:

```scala mdoc:nest
val effect         = ZIO.attempt(Left[Int, String](5))
val leftProjection = effect.left
val unlefted       = leftProjection.map(_ * 2).unleft 
```

So the error channel of the output of `left` and `right` operators is changed from `Option` to `Either`.

### Descriptive Errors

ZIO's type system uses implicit evidence to ensure type safety, and some level of correctness at compile time. In ZIO 2.x, the _subtype evidence_, `<:<` replaced by these two descriptive implicit evidences:

1. **`IsSubtypeOfOutput`** — The `O1 IsSubtypeOfOutput O2` ensures that the output type `O1` is subtype of `O2`

2. **`IsSubtypeOfError`** — The `E1 IsSubtypeOfError E2` ensures that the error type `E1` is a subtype of `E2`

Now we have more descriptive errors at compile time in the vast majority of operators.

Let's just see an example of each one. In ZIO 1.x, the compiler print obscurant error messages:

```scala
ZIO.fail("Boom!").orDie
// error: Cannot prove that String <:< Throwable.
// ZIO.fail("Boom!").orDie
// ^^^^^^^^^^^^^^^^^^^^^^^

ZIO.succeed(Set(3,4)).head
// error: Cannot prove that scala.collection.immutable.Set[Int] <:< List[B].
// ZIO.succeed(Set(3, 4)).head
// ^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

Now in ZIO 2.x we have such informative error messages:

```scala
ZIO.fail("Boom!").orDie
// error: This operator requires that the error type be a subtype of Throwable but the actual type was String.
// ZIO.fail("Boom!").orDie
// ^^^^^^^^^^^^^^^^^^^^^^^

ZIO.succeed(Set(3, 4, 3)).head
// error: This operator requires that the output type be a subtype of List[B] but the actual type was scala.collection.immutable.Set[Int].
// ZIO.succeed(Set(3, 4, 3)).head
// ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
```

## ZIO App

### ZIOApp

In ZIO 1.x, we were used to writing ZIO applications using the `zio.App` trait:

```scala mdoc:invisible
def startMyApp(arguments: Chunk[String]) = ZIO.succeed(???)
```

```scala mdoc:silent:nest:warn
import zio.App
import zio.Console._

object MyApp extends zio.App {
  def run(args: List[String]) = 
    startMyApp(args).exitCode
}
```

Now in ZIO 2.x, the `zio.App` trait is deprecated and, we have the `zio.ZIOAppDefault` trait which is simpler than the former approach (Note that the `ZApp` and `ManagedApp` are also deprecated, and we should use the `ZIOAppDefault` instead):

```scala mdoc:compile-only
import zio.ZIOAppDefault
import zio.Console._

object MyApp extends ZIOAppDefault {
  def run =
    for {
      arguments <- getArgs
      _         <- startMyApp(arguments) 
    } yield ()
}
```

In ZIO 1.x, `run` is the main function of our application, which will be passed the command-line arguments to our application:

```scala
def run(args: List[String]): URIO[R, ExitCode]
```

While in most cases we don't write command-line applications, and we don't use it, in ZIO 2.x, we created the `ZIOAppArgs` service and a helper method called `ZIOApp#args` which obtains access to the command-line arguments of our application:

```scala
trait ZIOApp { self =>
  final def args: ZIO[Has[ZIOAppArgs], Nothing, Chunk[String]] = ZIO.service[ZIOAppArgs].map(_.args)
}
```

## Fiber

We deprecated the `Fiber.ID` and moved it to the `zio` package and called it the `FiberId`:

| ZIO 1.0        | ZIO 2.x       |
|----------------|---------------|
| `zio.Fiber.ID` | `zio.FiberID` |

## Platform, Executor, and Runtime

### Method Deprecation and Renaming

We renamed the `Platform` data type to the `RuntimeConfig`, and moved it from the `zio.internal` to the `zio` package with some member deprecation:

| ZIO 1.0                         | ZIO 2.x              |
|---------------------------------|----------------------|
| `zio.internal.Platfom`          | `zio.RuntimeConfig`  |
| `Platform#withBlockingExecutor` | `RuntimeConfig#copy` |
| `Platform#withExecutor`         | `RuntimeConfig#copy` |
| `Platform#withFatal`            | `RuntimeConfig#copy` |
| `Platform#withReportFatal`      | `RuntimeConfig#copy` |
| `Platform#withReportFailure`    | `RuntimeConfig#copy` |
| `Platform#withSupervisor`       | `RuntimeConfig#copy` |
| `Platform#withTracing`          | `RuntimeConfig#copy` |

Also, we moved the `Executor` from `zio.internal` to the `zio` package:

| ZIO 1.0                 | ZIO 2.x        |
|-------------------------|----------------|
| `zio.internal.Executor` | `zio.Executor` |

In regard to renaming the `Platform` data-type to the `RuntimeConfig` we have some similar renaming in other data-types like `Runtime` and `ZIO`:

| ZIO 1.0               | ZIO 2.x                    |
|-----------------------|----------------------------|
| `Runtime#mapPlatform` | `Runtime#mapRuntimeConfig` |
| `Runtime#platfom`     | `Runtime#runtimeConfig`    |
| `ZIO.platfom`         | `ZIO.runtimeConfig`        |

### Runtime Config Aspect

ZIO 2.x, introduced a new data-type called `RuntimeConfigAspect` with the following methods:

* `addLogger`
* `addReportFailure`
* `addReportFatal`
* `addSupervisor`
* `identity`
* `setBlockingExecutor`
* `setExecutor`
* `setTracing`

### Compositional Runtime Config

ZIO 2.x allows us to run part of an effect on a different `RuntimeConfig`:

```scala
ZIO.withRuntimeConfig(newRuntimeConfiguration)(effect)
```
After running the effect on the specified runtime configuration, it will restore the old runtime configuration.

## ZServiceBuilder

### Functions to Service Builders

In ZIO 1.x, when we want to write a service that depends on other services, we need to use `ZServiceBuilder.fromService*` variants with a lot of boilerplate:

```scala
val live: URServiceBuilder[Clock with Console, Logging] =
  ZServiceBuilder.fromServices[Clock.Service, Console.Service, Logging.Service] {
    (clock: Clock.Service, console: Console.Service) =>
      new Service {
        override def log(line: String): UIO[Unit] =
          for {
            current <- clock.currentDateTime.orDie
            _ <- console.putStrLn(current.toString + "--" + line).orDie
          } yield ()
      }
  }
```

ZIO 2.x deprecates all `ZServiceBuilder.fromService*` functions:

| ZIO 1.0                          | ZIO 2.x |
|----------------------------------|---------|
| `ZServiceBuilder.fromService`             | `toServiceBuilder` |
| `ZServiceBuilder.fromServices`            | `toServiceBuilder` |
| `ZServiceBuilder.fromServiceM`            | `toServiceBuilder` |
| `ZServiceBuilder.fromServicesM`           | `toServiceBuilder` |
| `ZServiceBuilder.fromServiceManaged`      | `toServiceBuilder` |
| `ZServiceBuilder.fromServicesManaged`     | `toServiceBuilder` |
| `ZServiceBuilder.fromServiceMany`         | `toServiceBuilder` |
| `ZServiceBuilder.fromServicesMany`        | `toServiceBuilder` |
| `ZServiceBuilder.fromServiceManyM`        | `toServiceBuilder` |
| `ZServiceBuilder.fromServicesManyM`       | `toServiceBuilder` |
| `ZServiceBuilder.fromServiceManyManaged`  | `toServiceBuilder` |
| `ZServiceBuilder.fromServicesManyManaged` | `toServiceBuilder` |

Instead, it provides the `toServiceBuilder` extension methods for functions:

```scala
case class LoggingLive(console: Console, clock: Clock) extends Logging {
  override def log(line: String): UIO[Unit] =
    for {
      current <- clock.currentDateTime.orDie
      _       <- console.putStrLn(current.toString + "--" + line).orDie
    } yield ()
}

object LoggingLive {
  val serviceBuilder: URServiceBuilder[Has[Console] with Has[Clock], Has[Logging]] =
    (LoggingLive(_, _)).toServiceBuilder[Logging]
}
```

Note that the `LoggingLive(_, _)` is a `Function2` of type `(Console, Clock) => LoggingLive`. As the ZIO 2.x provides the `toServiceBuilder` extension method for all `Function` arities, we can call the `toServiceBuilder` on any function to convert that to the `ZServiceBuilder`. Unlike the `ZServiceBuilder.fromService*` functions, this can completely infer the input types, so it saves us from a lot of boilerplates we have had in ZIO 1.x.

### Accessing a Service from the Environment

Assume we have a service named `Logging`:

```scala mdoc:silent:nest
trait Logging {
  def log(line: String): UIO[Unit]
}
```

In ZIO 1.x, when we wanted to access a service from the environment, we used the `ZIO.access` + `Has#get` combination (`ZIO.access(_.get)`):

```scala mdoc:silent:nest
val logging: URIO[Has[Logging], Logging] = ZIO.access(_.get)
```

Also, to create accessor methods, we used the following code:

```scala mdoc:silent:nest:warn
def log(line: String): URIO[Has[Logging], Unit] = ZIO.accessM(_.get.log(line))
```

ZIO 2.x reduces one level of indirection by using `ZIO.service` operator:

```scala mdoc:silent:nest
val logging : URIO[Has[Logging], Logging] = ZIO.service
```

And to write the accessor method in ZIO 2.x, we can use `ZIO.serviceWith` operator:

```scala mdoc:silent:nest
def log(line: String): URIO[Has[Logging], Unit] = ZIO.serviceWith(_.log(line))
```

```scala mdoc:reset
import zio._
```

### Accessing Multiple Services in the Environment

In ZIO 1.x, we could access multiple services using higher arity service accessors like `ZIO.services` and `ZManaged.services`:

```scala mdoc:silent:nest:warn
for {
  (console, random) <- ZIO.services[Console, Random]
  randomInt         <- random.nextInt
  _                 <- console.printLine(s"The next random number: $randomInt")
} yield ()
```

They were _deprecated_ as we can achieve the same functionality using `ZIO.service` with for-comprehension syntax, which is more idiomatic and scalable way of accessing multiple services in the environment:

```scala mdoc:silent:nest
for {
  console   <- ZIO.service[Console]
  random    <- ZIO.service[Random]
  randomInt <- random.nextInt
  _         <- console.printLine(s"The next random number: $randomInt")
} yield ()
```

### Building the Dependency Graph

To create the dependency graph in ZIO 1.x, we should compose the required service buildera manually. As the ordering of service builder compositions matters, and also we should care about composing service builders in both vertical and horizontal manner, it would be a cumbersome job to create a dependency graph with a lot of boilerplates.

Assume we have the following dependency graph with two top-level dependencies:

```
           DocRepo                ++          UserRepo
      ____/   |   \____                       /     \
     /        |        \                     /       \
 Logging  Database  BlobStorage          Logging   Database
    |                    |                  |
 Console              Logging            Console
                         |       
                      Console    
```

In ZIO 1.x, we had to compose these different service builders together to create the whole application dependency graph:

```scala mdoc:invisible:nest
trait Logging {}

trait Database {}

trait BlobStorage {}

trait UserRepo {}

trait DocRepo {}

case class LoggerImpl(console: Console) extends Logging {}

case class DatabaseImp() extends Database {}

case class UserRepoImpl(logging: Logging, database: Database) extends UserRepo {}

case class BlobStorageImpl(logging: Logging) extends BlobStorage {}

case class DocRepoImpl(logging: Logging, database: Database, blobStorage: BlobStorage) extends DocRepo {}

object Logging {
  val live: URServiceBuilder[Has[Console], Has[Logging]] =
    LoggerImpl.toServiceBuilder[Logging]
}

object Database {
  val live: URServiceBuilder[Any, Has[Database]] =
    DatabaseImp.toServiceBuilder[Database]
}

object UserRepo {
  val live: URServiceBuilder[Has[Logging] with Has[Database], Has[UserRepo]] =
    (UserRepoImpl(_, _)).toServiceBuilder[UserRepo]
}


object BlobStorage {
  val live: URServiceBuilder[Has[Logging], Has[BlobStorage]] =
    BlobStorageImpl.toServiceBuilder[BlobStorage]
}

object DocRepo {
  val live: URServiceBuilder[Has[Logging] with Has[Database] with Has[BlobStorage], Has[DocRepo]] =
    (DocRepoImpl(_, _, _)).toServiceBuilder[DocRepo]
}
  
val myApp: ZIO[Has[DocRepo] with Has[UserRepo], Nothing, Unit] = ZIO.succeed(???)
```

```scala mdoc:silent:nest
val appServiceBuilder: URServiceBuilder[Any, Has[DocRepo] with Has[UserRepo]] =
  (((Console.live >>> Logging.live) ++ Database.live ++ (Console.live >>> Logging.live >>> BlobStorage.live)) >>> DocRepo.live) ++
    (((Console.live >>> Logging.live) ++ Database.live) >>> UserRepo.live)
    
val res: ZIO[Any, Nothing, Unit] = myApp.provideServices(appServiceBuilder)
```

As the development of our application progress, the number of service builders will grow, and maintaining the dependency graph would be tedious and hard to debug.

For example, if we miss the `Logging.live` dependency, the compile-time error would be very messy:

```scala
myApp.provideServices(
  ((Database.live ++ BlobStorage.live) >>> DocRepo.live) ++
    (Database.live >>> UserRepo.live)
)
```

```
type mismatch;
 found   : zio.URServiceBuilder[zio.Has[Logging] with zio.Has[Database] with zio.Has[BlobStorage],zio.Has[DocRepo]]
    (which expands to)  zio.ZServiceBuilder[zio.Has[Logging] with zio.Has[Database] with zio.Has[BlobStorage],Nothing,zio.Has[DocRepo]]
 required: zio.ZServiceBuilder[zio.Has[Database] with zio.Has[BlobStorage],?,?]
    ((Database.live ++ BlobStorage.live) >>> DocRepo.live) ++
```

In ZIO 2.x, we can automatically construct dependencies with friendly compile-time hints, using `ZIO#inject` operator:

```scala mdoc:silent:nest
val res: ZIO[Any, Nothing, Unit] =
  myApp.inject(
    Console.live,
    Logging.live,
    Database.live,
    BlobStorage.live,
    DocRepo.live,
    UserRepo.live
  )
```

The order of dependencies doesn't matter:

```scala mdoc:silent:nest
val res: ZIO[Any, Nothing, Unit] =
  myApp.inject(
    DocRepo.live,
    BlobStorage.live,
    Logging.live,
    Database.live,
    UserRepo.live,
    Console.live
  )
```

If we miss some dependencies, it doesn't compile, and the compiler gives us the clue:

```scala
val app: ZIO[Any, Nothing, Unit] =
  myApp.inject(
    DocRepo.live,
    BlobStorage.live,
//    Logging.live,
    Database.live,
    UserRepo.live,
    Console.live
  )
```

```
  ZServiceBuilder Wiring Error  

❯ missing Logging
❯     for DocRepo.live

❯ missing Logging
❯     for UserRepo.live
```

We can also directly construct a service builder using `ZServiceBuilder.wire`:

```scala mdoc:silent:nest
val serviceBuilder = ZServiceBuilder.wire[Has[DocRepo] with Has[UserRepo]](
  Console.live,
  Logging.live,
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live
)
```

And also the `ZServiceBuilder.wireSome` helps us to construct a service builder which requires on some service and produces some other services (`URServiceBuilder[Int, Out]`) using `ZServiceBuilder.wireSome[In, Out]`:

```scala mdoc:silent:nest
val serviceBuilder = ZServiceBuilder.wireSome[Has[Console], Has[DocRepo] with Has[UserRepo]](
  Logging.live,
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live
)
```

In ZIO 1.x, the `ZIO#provideSomeServices` provides environment partially:

```scala mdoc:silent:nest
val app: ZIO[Has[Console], Nothing, Unit] =
  myApp.provideSomeServices[Has[Console]](
    ((Logging.live ++ Database.live ++ (Console.live >>> Logging.live >>> BlobStorage.live)) >>> DocRepo.live) ++
      (((Console.live >>> Logging.live) ++ Database.live) >>> UserRepo.live)
  )
```

In ZIO 2.x, we have a similar functionality but for injection, which is the `ZIO#injectSome[Rest](l1, l2, ...)` operator:

```scala mdoc:silent:nest:warn
val app: ZIO[Has[Console], Nothing, Unit] =
  myApp.injectSome[Has[Console]](
    Logging.live,
    DocRepo.live,
    Database.live,
    BlobStorage.live,
    UserRepo.live
  )
```

In ZIO 1.x, the `ZIO#provideCustomServices` takes the part of the environment that is not part of `ZEnv` and gives us an effect that only depends on the `ZEnv`:

```scala mdoc:silent:nest
val app: ZIO[zio.ZEnv, Nothing, Unit] = 
  myApp.provideCustomServices(
    ((Logging.live ++ Database.live ++ (Logging.live >>> BlobStorage.live)) >>> DocRepo.live) ++
      ((Logging.live ++ Database.live) >>> UserRepo.live)
  )
```

In ZIO 2.x, the `ZIO#injectCustom` does the similar but for the injection:

```scala mdoc:silent:nest
val app: ZIO[zio.ZEnv, Nothing, Unit] =
  myApp.injectCustom(
    Logging.live,
    DocRepo.live,
    Database.live,
    BlobStorage.live,
    UserRepo.live
  )
```

> Note:
> All `provide*` methods are not deprecated, and they are still necessary and useful for low-level and custom cases. But, in ZIO 2.x, in most cases, it's easier to use `inject`/`wire` methods.


| ZIO 1.x and 2.x (manually)                             | ZIO 2.x (automatically)    |
|--------------------------------------------------------|----------------------------|
| `ZIO#provide`                                          | `ZIO#inject`               |
| `ZIO#provideSomeServices`                              | `ZIO#injectSome`           |
| `ZIO#provideCustomServices`                            | `ZIO#injectCustom`         |
| Composing manually using `ZServiceBuilder` combinators | `ZServiceBuilder#wire`     |
| Composing manually using `ZServiceBuilder` combinators | `ZServiceBuilder#wireSome` |

### ZServiceBuilder Debugging

To debug ZServiceBuilder construction, we have two built-in service builders, i.e., `ZServiceBuilder.Debug.tree` and `ZServiceBuilder.Debug.mermaid`. For example, by including `ZServiceBuilder.Debug.mermaid` into our service builder construction, the compiler generates the following debug information:

```scala
val serviceBuilder = ZServiceBuilder.wire[Has[DocRepo] with Has[UserRepo]](
  Console.live,
  Logging.live,
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live,
  ZServiceBuilder.Debug.mermaid
)
```

```scala
[info]   ZServiceBuilder Wiring Graph  
[info] 
[info] ◉ DocRepo.live
[info] ├─◑ Logging.live
[info] │ ╰─◑ Console.live
[info] ├─◑ Database.live
[info] ╰─◑ BlobStorage.live
[info]   ╰─◑ Logging.live
[info]     ╰─◑ Console.live
[info] 
[info] ◉ UserRepo.live
[info] ├─◑ Logging.live
[info] │ ╰─◑ Console.live
[info] ╰─◑ Database.live
[info] 
[info] Mermaid Live Editor Link
[info] https://mermaid-js.github.io/mermaid-live-editor/edit/#eyJjb2RlIjoiZ3JhcGhcbiAgICBDb25zb2xlLmxpdmVcbiAgICBCbG9iU3RvcmFnZS5saXZlIC0tPiBMb2dnaW5nLmxpdmVcbiAgICBMb2dnaW5nLmxpdmUgLS0+IENvbnNvbGUubGl2ZVxuICAgIFVzZXJSZXBvLmxpdmUgLS0+IExvZ2dpbmcubGl2ZVxuICAgIFVzZXJSZXBvLmxpdmUgLS0+IERhdGFiYXNlLmxpdmVcbiAgICBEb2NSZXBvLmxpdmUgLS0+IERhdGFiYXNlLmxpdmVcbiAgICBEb2NSZXBvLmxpdmUgLS0+IEJsb2JTdG9yYWdlLmxpdmVcbiAgICBEYXRhYmFzZS5saXZlXG4gICAgIiwibWVybWFpZCI6ICJ7XG4gIFwidGhlbWVcIjogXCJkZWZhdWx0XCJcbn0iLCAidXBkYXRlRWRpdG9yIjogdHJ1ZSwgImF1dG9TeW5jIjogdHJ1ZSwgInVwZGF0ZURpYWdyYW0iOiB0cnVlfQ==
```

### Module Pattern

The _Module Pattern_ is one of the most important changes in ZIO 2.x. Let's take a look at services in ZIO 1.x before discussing changes. Here is a `Logging` service that uses _Module Pattern 1.0_:

```scala
object logging {
  // Defining the service type by wrapping the service interface with Has[_] data type
  type Logging = Has[Logging.Service]

  // Companion object that holds service interface and its live implementation
  object Logging {
    trait Service {
      def log(line: String): UIO[Unit]
    }
    
    // Live implementation of the Logging service
    val live: ZServiceBuilder[Clock with Console, Nothing, Logging] =
      ZServiceBuilder.fromServices[Clock.Service, Console.Service, Logging.Service] {
        (clock: Clock.Service, console: Console.Service) =>
          new Logging.Service {
            override def log(line: String): UIO[Unit] =
              for {
                current <- clock.currentDateTime.orDie
                _       <- console.putStrLn(s"$current--$line")
              } yield ()
          }
      }
  }

  // Accessor Methods
  def log(line: => String): URIO[Logging, Unit] =
    ZIO.accessM(_.get.log(line))
}
```

The `Logging` service is a logger which depends on the `Console` and `Clock` services.

ZIO 2.x introduces the _Module Pattern 2.0_ which is much more concise and has more ergonomics. Let's see how the `Logging` service can be implemented using this new pattern:

```scala mdoc:invisible:reset
import zio._
```

```scala mdoc:silent:nest
// Defining the Service Interface
trait Logging {
  def log(line: String): UIO[Unit]
}

// Accessor Methods Inside the Companion Object
object Logging {
  def log(line: String): URIO[Has[Logging], Unit] =
    ZIO.serviceWith(_.log(line))
}

// Implementation of the Service Interface
case class LoggingLive(console: Console, clock: Clock) extends Logging {
  override def log(line: String): UIO[Unit] =
    for {
      time <- clock.currentDateTime
      _    <- console.printLine(s"$time--$line").orDie
    } yield ()
}

// Converting the Service Implementation into the ZServiceBuilder
object LoggingLive {
  val serviceBuilder: URServiceBuilder[Has[Console] with Has[Clock], Has[Logging]] =
    (LoggingLive(_, _)).toServiceBuilder[Logging]
}
```

As we see, we have the following changes:

1. **Deprecation of Type Alias for `Has` Wrappers** — In _Module Pattern 1.0_ although the type aliases were to prevent using `Has[ServiceName]` boilerplate everywhere, they were confusing, and led to doubly nested `Has[Has[ServiceName]]`. So the _Module Pattern 2.0_ doesn't anymore encourage using type aliases. Also, they were removed from all built-in ZIO services. So, the `type Console = Has[Console.Service]` removed and the `Console.Service` will just be `Console`. **We should explicitly wrap services with `Has` data types everywhere**. 

2. **Introducing Constructor-based Dependency Injection** — In _Module Pattern 1.0_ when we wanted to create a service builder that depends on other services, we had to use `ZServiceBuilder.fromService*` constructors. The problem with the `ZServiceBuilder` constructors is that there are too many constructors each one is useful for a specific use-case, but people had troubled in spending a lot of time figuring out which one to use. 

    In _Module Pattern 2.0_ we don't worry about all these different `ZServiceBuilder` constructors. It recommends **providing dependencies as interfaces through the case class constructor**, and then we have direct access to all of these dependencies to implement the service. Finally, to create the `ZServiceBuilder` we call `toServiceBuilder` on the service implementation.

    > **_Note:_**
    > 
    > When implementing a service that doesn't have any dependency, our code might not compile:
    > ```scala
    > case class LoggingLive() extends Logging {
    >   override def log(line: String): UIO[Unit] =
    >     ZIO.attempt(println(line)).orDie
    > }
    > 
    > object LoggingLive {
    >   val serviceBuilder: URServiceBuilder[Any, Has[Logging]] = LoggingLive().toServiceBuilder
    > }
    >``` 
    > Compiler Error:
    > ```
    > value toServiceBuilder is not a member of LoggingLive
    > val serviceBuilder: URServiceBuilder[Any, Has[Logging]] = LoggingLive().toServiceBuilder
    > ```
    > The problem here is that the companion object won't automatically extend `() => Logging`. So the workaround is doing that manually:
    > ```scala
    > object LoggingLive extends (() => Logging) {
    >   val serviceBuilder: URServiceBuilder[Any, Has[Logging]] = LoggingLive.toServiceBuilder
    > }
    > ```
    > Or we can just write the `val serviceBuilder: URServiceBuilder[Any, Has[Logging]] = (() => Logging).toServiceBuilder` to fix that.
 
    > **_Note:_**
    > 
    > The new pattern encourages us to parametrize _case classes_ to introduce service dependencies and then using `toServiceBuilder` syntax as a very simple way that always works. But it doesn't enforce us to do that. We can also just pull whatever services we want from the environment using `ZIO.service` or `ZManaged.service` and then implement the service and call `toServiceBuilder` on it:
    > ```scala mdoc:silent:nest
    > object LoggingLive {
    >   val serviceBuilder: ZServiceBuilder[Has[Clock] with Has[Console], Nothing, Has[Logging]] =
    >     (for {
    >       console <- ZIO.service[Console]
    >       clock   <- ZIO.service[Clock]
    >     } yield new Logging {
    >       override def log(line: String): UIO[Unit] =
    >         for {
    >           time <- clock.currentDateTime
    >           _    <- console.printLine(s"$time--$line").orDie
    >         } yield ()
    >     }).toServiceBuilder
    > }
    > ```

3. **Separated Interface** — In the _Module Pattern 2.0_, ZIO supports the _Separated Interface_ pattern which encourages keeping the implementation of an interface decoupled from the client and its definition.

    As our application grows, where we define our service builders matters more. _Separated Interface_ is a very useful pattern while we are developing a complex application. It helps us to reduce the coupling between application components. 

    Following two changes in _Module Pattern_ we can define the service definition in one package but its implementations in other packages:
    
   1. **Flattened Structure** — In the new pattern, everything is at the top level in a file. So the developer is not limited to package service definition and service implementation in one package.
   
      > **_Note_**:
      > 
      > Module Pattern 2.0 supports the idea of _Separated Interface_, but it doesn't enforce us grouping them into different packages and modules. The decision is up to us, based on the complexity and requirements of our application.
   
   2. **Decoupling Interfaces from Implementation** — Assume we have a complex application, and our interface is `Logging` with different implementations that potentially depend on entirely different modules. Putting service builders in the service definition means anyone depending on the service definition needs to depend on all the dependencies of all the implementations, which is not a good practice.
   
    In Module Pattern 2.0, service builders are defined in the implementation's companion object, not in the interface's companion object. So instead of calling `Logging.live` to access the live implementation we call `LoggingLive.serviceBuilder`.

4. **Accessor Methods** — The new pattern reduced one level of indirection on writing accessor methods. So instead of accessing the environment (`ZIO.access/ZIO.accessM`) and then retrieving the service from the environment (`Has#get`) and then calling the service method, the _Module Pattern 2.0_ introduced the `ZIO.serviceWith` that is a more concise way of writing accessor methods. For example, instead of `ZIO.accessM(_.get.log(line))` we write `ZIO.serviceWith(_.log(line))`.

   We also have accessor methods on the fly, by extending the companion object of the service interface with `Accessible`, e.g. `object Logging extends Accessible[Logging]`. So then we can simply access the `log` method by calling the `Logging(_.log(line))` method:
   
    ```scala mdoc:silent:nest
    trait Logging {
      def log(line: String): UIO[Unit]
    }
    
    object Logging extends Accessible[Logging]
    
    def log(line: String): ZIO[Has[Logging] with Has[Clock], Nothing, Unit] =
      for {
        clock <- ZIO.service[Clock]
        now   <- clock.localDateTime
        _     <- Logging(_.log(s"$now-$line"))
      } yield ()
    ```

While Scala 3 doesn't support macro annotation like, so instead of using `@accessible`, the `Accessible` trait is a macro-less approach to create accessor methods specially for Scala 3 users.

The _Module Pattern 1.0_ was somehow complicated and had some boilerplates. The _Module Pattern 2.0_ is so much familiar to people coming from an object-oriented world. So it is so much easy to learn for newcomers. The new pattern is much simpler.

### Other Changes

Here is list of other deprecated methods:

| ZIO 1.x                             | ZIO 2.x                               |
|-------------------------------------|---------------------------------------|
| `ZServiceBuilder.fromEffect`        | `ZServiceBuilder.fromZIO`             |
| `ZServiceBuilder.fromEffectMany`    | `ZServiceBuilder.fromZIOMany`         |
| `ZServiceBuilder.fromFunctionM`     | `ZServiceBuilder.fromFunctionZIO`     |
| `ZServiceBuilder.fromFunctionManyM` | `ZServiceBuilder.fromFunctionManyZIO` |
| `ZServiceBuilder.identity`          | `ZServiceBuilder.environment`         |
| `ZServiceBuilder.requires`          | `ZServiceBuilder.environment`         |

## ZManaged

| ZIO 1.x                              | ZIO 2.x                                    |
|--------------------------------------|--------------------------------------------|
| `ZManaged#&&&`                       | `ZManaged#zip`                             |
| `ZManaged#mapN`                      | `ZManaged#zip`                             |
| `ZManaged.mapM`                      | `ZManaged.mapZIO`                          |
| `ZManaged.mapParN`                   | `ZManaged.zipPar`                          |
| `ZManaged#>>=`                       | `ZManaged#flatMap`                         |
| `ZManaged#bimap`                     | `ZManaged#mapBoth`                         |
| `ZManaged#mapEffect`                 | `ZManaged#mapAttempt`                      |
| `ZManaged#flattenM`                  | `ZManaged#flattenZIO`                      |
|                                      |                                            |
| `ZManaged#get`                       | `ZManaged#some`                            |
| `ZManaged#someOrElseM`               | `ZManaged#someOrElseManaged`               |
|                                      |                                            |
| `ZManaged#asService`                 | `ZManaged#toServiceBuilder`                |
| `ZManaged.services`                  | `ZManaged.service`                         |
|                                      |                                            |
| `ZManaged.foreach_`                  | `ZManaged.foreachDiscard`                  |
| `ZManaged.foreachPar_`               | `ZManaged.foreachParDiscard`               |
| `ZManaged.foreachParN_`              | `ZManaged.foreachParNDiscard`              |
|                                      |                                            |
| `ZManaged#foldCauseM`                | `ZManaged#foldCauseManaged`                |
| `ZManaged#foldM`                     | `ZManaged#foldManaged`                     |
|                                      |                                            |
| `ZManaged.make`                      | `ZManaged.acquireReleaseWith`              |
| `ZManaged.make_`                     | `ZManaged.acquireRelease`                  |
| `ZManaged.makeEffect`                | `ZManaged.acquireReleaseAttemptWith`       |
| `ZManaged.makeEffect_`               | `ZManaged.acquireReleaseAttempt`           |
| `ZManaged.makeEffectTotal`           | `ZManaged.acquireReleaseSucceedWith`       |
| `ZManaged.makeEffectTotal_`          | `ZManaged.acquireReleaseSucceed`           |
| `ZManaged.makeExit`                  | `ZManaged.acquireReleaseExitWith`          |
| `ZManaged.makeExit_`                 | `ZManaged.acquireReleaseExit`              |
| `ZManaged.makeInterruptible`         | `ZManaged.acquireReleaseInterruptibleWith` |
| `ZManaged.makeInterruptible_`        | `ZManaged.acquireReleaseInterruptible`     |
| `ZManaged.makeReserve`               | `ZManaged.fromReservationZIO`              |
| `ZManaged.reserve`                   | `ZManaged.fromReservation`                 |
|                                      |                                            |
| `ZManaged#ifM`                       | `ZManaged#ifManaged`                       |
| `ZManaged.loop_`                     | `ZManaged.loopDiscard`                     |
| `ZManaged#unlessM`                   | `ZManaged#unlessManaged`                   |
| `ZManaged#whenCaseM`                 | `ZManaged#whenCaseManaged`                 |
| `ZManaged#whenM`                     | `ZManaged#whenManaged`                     |
|                                      |                                            |
| `ZManaged.fromFunction`              | `ZManaged.access`                          |
| `ZManaged.fromFunctionM`             | `ZManaged.accessManaged`                   |
| `ZManaged.fromEffect`                | `ZManaged.fromZIO`                         |
| `ZManaged.fromEffectUninterruptible` | `ZManaged.fromZIOUninterruptible`          |
| `ZManaged.effect`                    | `ZManaged.attempt`                         |
| `ZManaged.effectTotal`               | `ZManaged.succeed`                         |
|                                      |                                            |
| `ZManaged#collectM`                  | `ZManaged#collectManage`                   |
| `ZManaged#collectAll_`               | `ZManaged#collectAllDiscard`               |
| `ZManaged#collectAllPar_`            | `ZManaged#collectAllParDiscard`            |
| `ZManaged#collectAllParN_`           | `ZManaged#collectAllParNDiscard`           |
|                                      |                                            |
| `ZManaged#use_`                      | `ZManaged#useDiscard`                      |
| `ZManaged.require`                   | `ZManaged.someOrFail`                      |
| `ZManaged.accessM`                   | `ZManaged.accessZIO`                       |
| `ZManaged#rejectM`                   | `ZManaged#rejectManaged`                   |
| `ZManaged#tapM`                      | `ZManaged#tapZIO`                          |
| `ZManaged#on`                        | `ZManaged#onExecutionContext`              |
| `ZManaged#optional`                  | `ZManaged#unoption`                        |
| `ZManaged#halt`                      | `ZManaged#failCause`                       |

## ZRef

ZIO 2.x unifies `ZRef` and `ZRefM`. `ZRefM` becomes a subtype of `ZRef` that has additional capabilities (i.e. the ability to perform effects within the operations) at some cost to performance:

| ZIO 1.x | ZIO 2.x             |
|---------|---------------------|
| `ZRefM` | `ZRef.Synchronized` |
| `RefM`  | `Ref.Synchronized`  |
| `ERefM` | `ERef.Synchronized` |

As the `ZRefM` is renamed to `ZRef.Synchronized`; now the `Synchronized` is a subtype of `ZRef`. This change allows a `ZRef.Synchronized` to be used anywhere a `Ref` is currently being used.

To perform the migration, after renaming these types to the newer ones (e.g. `ZRefM` renamed to `ZRef.Synchronized`) we should perform the following method renames:

| ZIO 1.x                  | ZIO 2.x                                 |
|--------------------------|-----------------------------------------|
| `ZRefM#dequeueRef`       | `ZRef.Synchronized#SubscriptionRef`     |
| `ZRefM#getAndUpdate`     | `ZRef.Synchronized#getAndUpdateZIO`     |
| `ZRefM#getAndUpdateSome` | `ZRef.Synchronized#getAndUpdateSomeZIO` |
| `ZRefM#modify`           | `ZRef.Synchronized#modifyZIO`           |
| `ZRefM#modifySome`       | `ZRef.Synchronized#modifySomeZIO`       |
| `ZRefM#update`           | `ZRef.Synchronized#updateZIO`           |
| `ZRefM#updateAndGet`     | `ZRef.Synchronized#updateAndGetZIO`     |
| `ZRefM#updateSome`       | `ZRef.Synchronized#updateSomeZIO`       |
| `ZRefM#updateSomeAndGet` | `ZRef.Synchronized#updateSomeAndGetZIO` |

## Semaphore and TSemaphore

In ZIO 1.x, we have two versions of Semaphore, `zio.Semaphore` and `zio.stm.TSemaphore`. The former is the ordinary semaphore, and the latter is the STM one.

In ZIO 2.x, we removed the implementation of `zio.stm.Semaphore` and used the `TSemaphore` as its implementation. So, now the `Semaphore` uses the `TSemaphore` in its underlying. So to migrate a `Semaphore` code base to the ZIO 2.x, we just need to commit `STM` values to get back to the `ZIO` world.

ZIO 1.x:

```scala
import zio._
import zio.console.Console

val myApp: ZIO[Console, Nothing, Unit] =
  for {
    semaphore <- Semaphore.make(4)
    available <- ZIO.foreach((1 to 10).toList) { _ =>
      semaphore.withPermit(semaphore.available)
    }
    _ <- zio.console.putStrLn(available.toString()).orDie
  } yield ()
```

ZIO 2.x:

```scala mdoc:silent:nest
import zio._

val myApp: ZIO[Has[Console], Nothing, Unit] =
  for {
    semaphore <- Semaphore.make(4)
    available <- ZIO.foreach((1 to 10).toList) { _ =>
      semaphore.withPermit(semaphore.available.commit)
    }
    _ <- Console.printLine(available.toString()).orDie
  } yield ()
```

Also, there is a slight change on `TSemaphore#withPermit` method. In ZIO 2.x, instead of accepting `STM` values, it accepts only `ZIO` values and returns the `ZIO` value.

| `withPermit` | Input           | Output         |
|--------------|-----------------|----------------|
| ZIO 1.x      | `STM[E, B]`     | `STM[E, B]`    |
| ZIO 2.x      | `ZIO[R, E, A])` | `ZIO[R, E, A]` |

## ZQueue

In ZIO 2.x, the `ZQueue` uses `Chunk` consistently with other ZIO APIs like ZIO Streams. This will avoid unnecessary conversions between collection types, particularly for streaming applications where streams use `Chunk` internally, but bulk take operations previously returned a `List` on `ZQueue`.

Here is a list of affected APIs: `takeAll`, `takeUpTo`, `takeBetween`, `takeN`, `unsafePollAll`, `unsafePollN`, and `unsafeOfferAll`. Let's see an example:

ZIO 1.x:

```scala
val taken: UIO[List[Int]] = for {
  queue <- Queue.bounded[Int](100)
  _     <- queue.offer(10)
  _     <- queue.offer(20)
  list  <- queue.takeUpTo(5)
} yield list
```

ZIO 2.x:

```scala mdoc:silent:nest
val taken: UIO[Chunk[Int]] = for {
  queue <- Queue.bounded[Int](100)
  _     <- queue.offer(10)
  _     <- queue.offer(20)
  chunk <- queue.takeUpTo(5)
} yield chunk
```

## ZIO Test

### Smart Constructors

By introducing smart constructors, we do not longer have the `testM` function to create effectful test suits. Instead, we should use the `test` function:

ZIO 1.x:

```scala
suite("ZRef") {
  testM("updateAndGet") {
    val result = ZRef.make(0).flatMap(_.updateAndGet(_ + 1))
    assertM(result)(Assertion.equalTo(1))
  }
}
```

ZIO 2.x:

```scala mdoc:invisible
import zio.test._
```

```scala mdoc:silent:nest
suite("ZRef") {
  test("updateAndGet") {
    val result = ZRef.make(0).flatMap(_.updateAndGet(_ + 1))
    assertM(result)(Assertion.equalTo(1))
  }
}
```

In ZIO 2.x, to create a test suite, it's not important that whether we are testing pure or effectful tests. The syntax is the same, and the `test`, and `testM` are unified. So the `testM` was removed.

### Smart Assertion

ZIO 2.x, introduced a new test method, named `assertTrue` which allows us to assert an expected behavior using ordinary Scala expressions that return `Boolean` values instead of specialized assertion operators.

So instead of writing following test assertions:

```scala mdoc:silent:nest
val list   = List(1, 2, 3, 4, 5)
val number = 3
val option = Option.empty[Int]

suite("ZIO 1.x Test Assertions")(
  test("contains")(assert(list)(Assertion.contains(5))),
  test("forall")(assert(list)(Assertion.forall(Assertion.assertion("even")()(actual => actual % 2 == 0)))),
  test("less than")(assert(number)(Assertion.isLessThan(0))),
  test("isSome")(assert(option)(Assertion.equalTo(Some(3))))
)
```

We can write them like this:

```scala mdoc:silent:nest
suite("ZIO 2.x SmartAssertions")(
  test("contains")(assertTrue(list.contains(5))),
  test("forall")(assertTrue(list.forall(_ % 2 == 0))),
  test("less than")(assertTrue(number < 0)),
  test("isSome")(assertTrue(option.get == 3))
)
```

Smart Assertions are extremely expressive, so when a test fails:
- They highlight the exact section of the syntax with the path leading up to the left-hand side of the assertion that causes the failure.
- They have the strong and nice diffing capability which shows where our expectation varies.
- When using partial functions in test cases there is no problem with the happy path, but if something goes wrong, it is a little annoying to find what went wrong. But smart assertions are descriptive, e.g., when we call `Option#get` to an optional value that is `None` the test fails with a related error message: `Option was None`
- They have lots of domains specific errors that talk to us in a language that we understand.

### Compositional Specs

In ZIO 1.x, we cannot compose specs directly, although if we can combine all children's specs via the suite itself:

```scala mdoc:invisible
import zio.test._
val fooSpec = test("foo")(???)
val barSpec = test("bar")(???)
val bazSpec = test("baz")(???)
```

```scala mdoc:silent:nest
val fooSuite = suite("Foo")(fooSpec)
val barSuite = suite("Bar")(barSpec)
val bazSuite = suite("Baz")(bazSpec)

val bigSuite = suite("big suite")(fooSuite, barSuite, bazSuite)
```

Now in ZIO 2.x, we can compose two suites using _binary composition operator_ without having to unnecessarily nest them inside another suite just for purpose of composition:

```scala mdoc:silent:nest
val bigSuite = fooSuite + barSuite + bazSuite
```

## ZIO Streams

ZIO Streams 2.x, does not include any significant API changes. Almost the same code we have for ZIO Stream 1.x, this will continue working and doesn't break our code. So we don't need to relearn any APIs. So we have maintained a quite good source compatibility, but have to forget some API elements.

So far, before ZIO 2.0, the ZIO Stream has included three main abstractions:
1. **`ZStream`** — represents the source of elements
2. **`ZSink`** — represents consumers of elements that can be composed together to create composite consumers
3. **`ZTransducer`** — represents generalized stateful and effectful stream processing

![ZIO Streams 1.x](/img/assets/zio-streams-1.x.svg)

In ZIO 2.0, we added an underlying abstraction called `Channel`. Channels are underlying both the `Stream` and `Sink`. So streams and sinks are just channels. So the `Channel` is an abstraction that unifies everything in ZIO Streams.

![ZChannel](/img/assets/zio-streams-zchannel.svg)

Channels are nexuses of I/O operations, which support both reading and writing:

- A `Channel` can write some elements to the _output_, and it can terminate with some sort of _done_ value. The `Channel` uses this _done_ value to notify the downstream `Channel` that its emission of elements finished. In ZIO 2.x, the `ZStream` is encoded as an output side of the `Channel`.

- A `Channel` can read from its input, and it can also terminate with some sort of _done_ value, which is an upstream result. So a `Channel` has the _input type_, and the _input done type_. The `Channel` uses this _done_ value to determine when the upstream `Channel` finishes its emission. In ZIO 2.x, the `ZSink` is encoded as an input side of the `Channel`.

So we can say that streams are the output side and sinks are the input side of a `Channel`. What about the middle part? In ZIO 1.x, this used to be known as the`ZTransducer`. Transducers were great for writing high-performance codecs (e.g. compression). They were really just a specialization of sinks. We have added transducers because things were not sufficiently efficient using sinks. If we were to write streaming codecs using sinks, they could be quite slow.

In ZIO 2.x, we removed the transducers, and they were deprecated. Instead, we realized we need something else for the middle part, and now it's called a `Pipeline` in ZIO 2.x. Pipelines accept a stream as input and return the transformed stream as output.

![ZIO Streams 2.x](/img/assets/zio-streams-2.x.svg)

Pipelines are basically an abstraction for composing a bunch of operations together that can be later applied to a stream. For example, we can create a pipeline that reads bytes, decodes them to the UTF-8 and splits the lines, and then splits on commas. So this is a very simple CSV parsing pipeline which we can later use with another stream to pipe into. 

| ZIO Streams 1.x | ZIO Streams 2.x                  |
|-----------------|----------------------------------|
|                 | `ZChannel`                       |
| `ZStream`       | `ZStream` (backed by `ZChannel`) |
| `ZSink`         | `ZSink` (backed by `ZChannel`)   |
| `ZTransducer`   | `ZPipeline`                      |


## ZIO Services

There are two significant changes in ZIO Services:

1. All ZIO services moved to the `zio` package:

    | ZIO 1.x                 | ZIO 2.x                      |
    |-------------------------|------------------------------|
    | `zio.blocking.Blocking` | [Removed](#blocking-service) |
    | `zio.clock.Clock`       | `zio.Clock`                  |
    | `zio.console.Console`   | `zio.Console`                |
    | `zio.random.Random`     | `zio.Random`                 |
    | `zio.system.System`     | `zio.System`                 |

    And their live implementations renamed and moved to a new path:

    | ZIO 1.x                    | ZIO 2.x                   |
    |----------------------------|---------------------------|
    | `zio.Clock.Service.live`   | `zio.Clock.ClockLive`     |
    | `zio.Console.Service.live` | `zio.Console.ConsoleLive` |
    | `zio.System.Service.live`  | `zio.System.SystemLive`   |
    | `zio.Random.Service.live`  | `zio.Random.RandomLive`   |


2. In ZIO 2.0 all type aliases like `type Logging = Has[Logging.Service]` removed. So we should explicitly use `Has` wrappers when we want to specify dependencies on ZIO services.

So instead of writing `ZServiceBuilder[Console with Clock, Nothing, ConsoleLogger]`, we should write `ZServiceBuilder[Has[Console] with Has[Clock], Nothing, Has[ConsoleLogger]]`, or when accessing services instead of `ZIO.service[Console.Service]` we also now do `ZIO.service[Console]`.

### Blocking Service

Since there is rarely a need to use a separate blocking thread pool, ZIO 2.0 created _one global blocking pool_, and removed the Blocking service from `ZEnv` and the built-in services.

All blocking operations were moved to the `ZIO` data type:

| ZIO 1.x                   | ZIO 2.x |
|---------------------------|---------|
| `zio.blocking.Blocking.*` | `ZIO.*` |

With some renaming stuffs:

| ZIO 1.x (`zio.blocking.Blocking.*`) | ZIO 2.x (`ZIO.*`)               |
|-------------------------------------|---------------------------------|
| `effectBlocking`                    | `ZIO.attemptBlocking`           |
| `effectBlockingCancelable`          | `ZIO.attemptBlockingCancelable` |
| `effectBlockingIO`                  | `ZIO.attemptBlockingIO`         |
| `effectBlockingInterrupt`           | `ZIO.attemptBlockingInterrupt`  |

Now we have all the blocking operations under the `ZIO` data type as below:
- `ZIO.attemptBlocking`
- `ZIO.attemptBlockingCancelable`
- `ZIO.attemptBlockingIO`
- `ZIO.attemptBlockingInterrupt`
- `ZIO.blocking`
- `ZIO.blockingExecutor`

We can also provide a user-defined blocking executor in ZIO 2.x with the `Runtime#withBlockingExecutor` operator that constructs a new `Runtime` with the specified blocking executor.

### Clock Service

There is a slight change in the Clock service; the return value of the `currentDateTime`, and `localDateTime` methods changed from `IO` to `UIO`, so they do not longer throw `DateTimeException`:

| Method Name       | Return Type (ZIO 1.x) | Return Type (ZIO 2.x) |
|-------------------|-----------------------|-----------------------|
| `currentDateTime` | `IO[OffsetDateTime]`  | `UIO[OffsetDateTime]` |
| `localDateTime`   | `IO[LocalDateTime]`   | `UIO[LocalDateTime]`  |

In ZIO 2.0, without changing any API, the _retrying_, _repetition_, and _scheduling_ logic moved into the `Clock` service.

Working with these three time-related APIs, always made us require `Clock` as our environment. So by moving these primitives into the `Clock` service, now we can directly call them via the `Clock` service. This change solves a common anti-pattern in ZIO 1.0, whereby a middleware that uses `Clock` via this retrying, repetition, or scheduling logic must provide the `Clock` service builder on every method invocation:

```scala mdoc:silent:nest
trait Journal {
  def append(log: String): ZIO[Any, Throwable, Unit]
}

trait Logger {
  def log(line: String): UIO[Unit]
}

case class JournalLoggerLive(clock: Clock, journal: Journal) extends Logger {
  override def log(line: String): UIO[Unit] = {
    for {
      current <- clock.currentDateTime
      _ <- journal.append(s"$current--$line")
        .retry(Schedule.exponential(2.seconds))
        .provide(Has(clock))
        .orDie
    } yield ()
  }
}
```

In ZIO 2.0, we can implement the logger service with a better ergonomic:

```scala mdoc:silent:nest
case class JournalLoggerLive(clock: Clock, journal: Journal) extends Logger {
  override def log(line: String): UIO[Unit] = {
    for {
      current <- clock.currentDateTime
      _       <- clock.retry(
                   journal.append(s"$current--$line")
                )(Schedule.exponential(2.seconds)).orDie
    } yield ()
  }
}
```

Note that the ZIO API didn't change, but the `Clock` trait became a bigger one, having more clock-related methods.

### Console Service

Method names in the _Console_ service were renamed to the more readable names:

| ZIO 1.x       | ZIO 2.x          |
|---------------|------------------|
| `putStr`      | `print`          |
| `putStrErr`   | `printError`     |
| `putStrLn`    | `printLine`      |
| `putStrLnErr` | `printLineError` |
| `getStrLn`    | `readLine`       |

## Other New Features

### Smart Constructors

Every data type in ZIO (`ZIO`, `ZManaged`, `ZStream`, etc.) has a variety of constructor functions that are designed to _up convert_ some weaker type into the target type. Typically, these converter functions are named `fromXYZ`, e.g. `ZIO.fromEither`, `ZStream.fromZIO`, etc.

While these are precise, ZIO 2.0 provides the `ZIO.from` constructor which can intelligently choose the most likely constructor based on the input type. So instead of writing `ZIO.fromEither(Right(3))` we can easily write `ZIO.from(Right(3))`. Let's try some of them:

```scala mdoc:invisible
import zio.stream.ZStream
```
```scala mdoc:nest
ZIO.fromOption(Some("Ok!"))
ZIO.from(Some("Ok!"))

ZIO.fromEither(Right(3))
ZIO.from(Right(3))

ZIO.fromFiber(Fiber.succeed("Ok!"))
ZIO.from(Fiber.succeed("Ok!"))

ZManaged.fromZIO(ZIO.fromEither(Right("Ok!"))) 
ZManaged.from(ZIO(Right("Ok!")))

ZStream.fromIterable(List(1,2,3)) 
ZStream.from(List(1,1,3))

ZStream.fromChunk(Chunk(1,2,3))
ZStream.from(Chunk(1,2,3))

ZStream.fromIterableZIO(ZIO.succeed(List(1,2,3)))
ZStream.from(ZIO.succeed(List(1,2,3)))
```

### ZState

`ZState` is a new data type that the ZIO 2.0 introduced:

```scala
sealed trait ZState[S] {
  def get: UIO[S]
  def set(s: S): UIO[Unit]
  def update(f: S => S): UIO[Unit]
}
```

We can `set`, `get`, and `update` the state which is part of the ZIO environment using `ZIO.setState`, `ZIO.getState`, and `ZIO.updateState` operations:

```scala mdoc:compile-only
import zio._

object ZStateExample extends zio.ZIOAppDefault {
  final case class MyState(counter: Int)

  val app = for {
    _     <- ZIO.updateState[MyState](state => state.copy(counter = state.counter + 1))
    count <- ZIO.getStateWith[MyState](_.counter)
    _     <- Console.printLine(count)
  } yield count

  def run = app.injectCustom(ZState.makeServiceBuilder(MyState(0)))
}
```

### ZHub

`ZHub` is a new concurrent data structure like `ZQueue`. While `ZQueue` solves the problem of _distributing_ messages to multiple consumers, the `ZHub` solves the problem of _broadcasting_ the same message to multiple consumers.

![ZHub](/img/assets/zhub.svg)

Here is an example of broadcasting messages to multiple subscribers:

```scala mdoc:silent:nest
for {
  hub <- Hub.bounded[String](requestedCapacity = 2)
  s1 = hub.subscribe
  s2 = hub.subscribe
  _ <- s1.zip(s2).use { case (left, right) =>
    for {
      _ <- hub.publish("Hello from a hub!")
      _ <- left.take.flatMap(Console.printLine(_))
      _ <- right.take.flatMap(Console.printLine(_))
    } yield ()
  }
} yield ()
```

Visit the [Hub](../../datatypes/concurrency/hub.md) page to learn more about it.

### ZIO Aspects

We introduced the`ZIOAspect` which enables us to modify the existing `ZIO` effect with some additional aspects like debugging, tracing, retrying, and logging:

```scala mdoc:silent:nest
val myApp: ZIO[Has[Random], Nothing, String] =
  ZIO.ifZIO(
    Random.nextIntBounded(10) @@ ZIOAspect.debug map (_ % 2 == 0)
  )(onTrue = ZIO.succeed("Hello!"), onFalse = ZIO.succeed("Good Bye!")) @@
    ZIOAspect.debug @@ ZIOAspect.logged("result")
    
// Sample Output:     
// 2
// Hello!
// timestamp=2021-09-05T15:32:56.705901Z level=INFO thread=#2 message="result: Hello!" file=ZIOAspect.scala line=74 class=zio.ZIOAspect$$anon$4 method=apply
```

### Debugging

ZIO 2.x introduces the `debug` that is useful when we want to print something to the console for debugging purposes without introducing additional environmental requirements or error types:

```scala mdoc:silent:nest
val myApp: ZIO[Has[Random], Nothing, String] =
  ZIO
    .ifZIO(
      Random.nextIntBounded(10) debug("random") map (_ % 2 == 0)
    )(
      onTrue = ZIO.succeed("Hello!"),
      onFalse = ZIO.succeed("Good Bye!")
    )
    .debug("result")
// Sample Output
// random: 2
// result: Hello!
``` 

### Logging

ZIO 2.x supports a lightweight built-in logging facade that standardized the interface to logging functionality. So it doesn't replace existing logging libraries, but also we can plug it into one of the existing logging backends.

We can easily log using the `ZIO.log` function:

```scala mdoc:silent:nest
ZIO.log("Application started!")
```

To log with a specific log-level, we can use the `ZIO.logLevel` combinator:

```scala mdoc:silent:nest
ZIO.logLevel(LogLevel.Warning) {
  ZIO.log("The response time exceeded its threshold!")
}
```
Or we can use the following functions directly:

* `ZIO.logDebug`
* `ZIO.logError`
* `ZIO.logFatal`
* `ZIO.logInfo`
* `ZIO.logWarning`

```scala mdoc:silent:nest
ZIO.logError("File does not exist: ~/var/www/favicon.ico")
```

It also supports logging spans:

```scala mdoc:silent:nest
ZIO.logSpan("myspan") {
  ZIO.sleep(1.second) *> ZIO.log("The job is finished!")
}
```

ZIO Logging calculates the running duration of that span and includes that in the logging data corresponding to its span label.

### Compile-time Execution Tracing

ZIO 1.x's execution trace is not as useful as it could be because it contains tracing information for internal ZIO operators that it not helpful to the user is understanding where in their code an error occurred.

Let's say we have the following application, in ZIO 1.x:

```scala
import zio._
import zio.console.Console

object TracingExample extends zio.App {

  def doSomething(input: Int): ZIO[Console, String, Unit] =
    for {
      _ <- console.putStrLn(s"Do something $input").orDie // line number 8
      _ <- ZIO.fail("Boom!")
      _ <- console.putStrLn("Finished my job").orDie
    } yield ()

  def myApp: ZIO[Console, String, Unit] =
    for {
      _ <- console.putStrLn("Hello!").orDie
      _ <- doSomething(5)
      _ <- console.putStrLn("Bye Bye!").orDie
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.exitCode
}
```

The output would be something like this:

```
Hello!
Do something 5
Fiber failed.
A checked error was not handled.
Boom!

Fiber:Id(1634884059941,1) was supposed to continue to:
  a future continuation at TracingExample$.myApp(TracingExample.scala:16)
  a future continuation at zio.ZIO.exitCode(ZIO.scala:606)

Fiber:Id(1634884059941,1) execution trace:
  at TracingExample$.doSomething(TracingExample.scala:8)
  at zio.ZIO.orDieWith(ZIO.scala:1118)
  at zio.ZIO.refineOrDieWith(ZIO.scala:1497)
  at zio.console.package$Console$Service$.putStrLn(package.scala:44)
  at zio.console.package$.putStrLn(package.scala:88)
  at TracingExample$.myApp(TracingExample.scala:15)
  at zio.ZIO.orDieWith(ZIO.scala:1118)
  at zio.ZIO.refineOrDieWith(ZIO.scala:1497)
  at zio.console.package$Console$Service$.putStrLn(package.scala:44)
  at zio.console.package$.putStrLn(package.scala:88)

Fiber:Id(1634884059941,1) was spawned by:

Fiber:Id(1634884059516,0) was supposed to continue to:
  a future continuation at zio.App.main(App.scala:59)
  a future continuation at zio.App.main(App.scala:58)

Fiber:Id(1634884059516,0) ZIO Execution trace: <empty trace>

Fiber:Id(1634884059516,0) was spawned by: <empty trace>
```

The execution trace, is somehow at a good degree informative, but it doesn't lead us to the exact point where the failure happened. It's a little hard to see what is going here. 

Let's rewrite the previous example in ZIO 2.0:

```scala mdoc:compile-only
import zio._

object TracingExample extends ZIOAppDefault {

  def doSomething(input: Int): ZIO[Has[Console], String, Unit] =
    for {
      _ <- Console.printLine(s"Do something $input").orDie
      _ <- ZIO.fail("Boom!") // line number 8
      _ <- Console.printLine("Finished my job").orDie
    } yield ()

  def myApp: ZIO[Has[Console], String, Unit] =
    for {
      _ <- Console.printLine("Hello!").orDie
      _ <- doSomething(5)
      _ <- Console.printLine("Bye Bye!").orDie
    } yield ()

  def run = myApp
}
```

The output is more descriptive than the ZIO 1.x:

```
Hello!
Do something 5
timestamp=2021-10-22T06:24:57.958955503Z level=ERROR thread=#0 message="Fiber failed.
A checked error was not handled.
Boom!

Fiber:FiberId(1634883897813,2) was supposed to continue to:
  a future continuation at 
  a future continuation at 
  a future continuation at 
  a future continuation at 
  a future continuation at 
  a future continuation at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:15:9)

Fiber:FiberId(1634883897813,2) execution trace:
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:8:20)
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:9)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:54)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at 
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:7:29)
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:9)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:40)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
  at <empty>.TracingExample.myApp(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:14:29)
  at 
```

As we see, the first line of execution trace, point to the exact location on the source code which causes the failure (`ZIO.fail("Boom!")`), line number 8 and column 20:

```
Fiber:FiberId(1634883897813,2) execution trace:
  at <empty>.TracingExample.doSomething(/home/user/sources/scala/zio/examples/shared/src/main/scala/TracingExample.scala:8:20)
```

Another improvement about ZIO tracing is its performance. Tracing in ZIO 1.x slows down the application performance by two times. In ZIO 1.x, we wrap and unwrap every combinator at runtime to be able to trace the execution. While it is happening on the runtime, it takes a lot of allocations which all need to be garbage collected afterward. So it adds a huge amount of complexity at the runtime.

Some users often turn off the tracing when they need more speed, so they lose this ability to trace their application when something breaks.

In ZIO 2.x, we moved execution tracing from the run-time to the compile-time. This is done by capturing tracing information from source code at compile time using macros. So most tracing information is pre-allocated at startup and never needs garbage collected. As a result, we end up with much better performance in execution tracing.
