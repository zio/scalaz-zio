---
id: zio-2.x-migration-guide
title: "ZIO 2.x Migration Guide"
---

```scala mdoc:invisible
import zio._
```

In this guide we want to introduce the migration process to ZIO 2.x. So if you have a project written in ZIO 1.x and want to migrate that to ZIO 2.x, this article is for you. 

## Automatic Migration

Before you migrate your own codebase, confirm that all of your ZIO-related dependencies have been migrated to ZIO 2.x with our [ZIO Ecosystem Tool](https://zio-ecosystem.herokuapp.com/).

ZIO uses the [Scalafix](https://scalacenter.github.io/scalafix/) for automatic migration. Scalafix is a code migration tool that takes a rewrite rule and reads the source code, converting deprecated features to newer ones, and then writing the result back to the source code. 

ZIO has a migration rule named `Zio2Upgrade` which migrates a ZIO 1.x code base to ZIO 2.x. This migration rule covers most of the changes. Therefore, to migrate a ZIO project to 2.x, we prefer to apply the `Zio2Upgrade` rule to the existing code. After that, we can go to the source code and fix the remaining compilation issues:

1. First, we should ensure that all of our direct and transitive dependencies [have released their compatible versions with ZIO 2.x](https://zio-ecosystem.herokuapp.com/). Note that we shouldn't update our dependencies to the 2.x compatible versions, before running scalafix.

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
     flatMap(a => ZIO.succeed(f(a)))
+  def map[B](f: A => B)(implicit trace: Trace): ZIO[R, E, B] = 
     flatMap(a => ZIO.succeed(f(a)))
}
```

Assume we have written the `FooLibrary` as below:

```scala mdoc:silent
import zio._

object FooLibrary {
  def foo = bar.flatMap(x => ZIO.succeed(x * 2))  // line 4
  private def bar = baz.flatMap(x => ZIO.succeed(x * x))  // line 5
  private def baz = ZIO.fail("Oh uh!").as(5)              // line 6
}
```

Without _implicit trace parameter_, the user of our library will get so many unrelated stack trace messages:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = FooLibrary.foo
}
// timestamp=2022-04-05T11:37:59.336623325Z level=ERROR thread=#zio-fiber-0 message="" cause="Exception in thread "zio-fiber-2" java.lang.String: Oh uh!
//	at <empty>.FooLibrary.baz(MainApp.scala:6)
//	at <empty>.FooLibrary.bar(MainApp.scala:5)
//	at <empty>.FooLibrary.foo(MainApp.scala:4)"
```

To avoid messing up our user's execution trace, we should add implicit trace parameters to our methods:

```scala mdoc:compile-only
import zio._

object FooLibrary {
  def foo(implicit trace: Trace) = bar.flatMap(x => ZIO.succeed(x * 2))
  private def bar(implicit trace: Trace) = baz.flatMap(x => ZIO.succeed(x * x))
  private def baz(implicit trace: Trace) = ZIO.fail("Oh uh!").as(5)
}

object MainApp extends ZIOAppDefault {
  def run = FooLibrary.foo // line 10
}
//timestamp=2022-04-05T11:47:59.773409363Z level=ERROR thread=#zio-fiber-0 message="" cause="Exception in thread "zio-fiber-2" java.lang.String: Oh uh!
//	at <empty>.MainApp.run(MainApp.scala:10)"
```

2. All parameters to operators returning an effect [should be by-name](#lazy-evaluation-of-parameters). Also, we should be sure to capture any parameters that are referenced more than once as values suspended in a ZIO constructor such as `suspendSucceed` to prevent _double evaluation_. 
    
    The overall pattern in implementing such methods will be:

    ```diff
    - def foreachParN[A](n: Int)(a: Iterable[A]) = {
        ... // The function body
    - }
    + def foreachParN[A](n0: => Int)(a0: => Iterable[A]) = 
    +   ZIO.suspendSucceed {
    +     val n = n0 
    +     val a = a0
          ... // The function body
    +   }
    ```
   
    As a result, the code will be robust to _double evaluation_ as well as to _side-effects embedded within parameters_.

3. We should update names to match [ZIO 2.0 naming conventions](#zio-20-naming-conventions).

4. ZIO 2.0 introduced [new structured concurrently operators](#compositional-concurrency) which helps us to change the regional parallelism settings of our application. So if applicable, we should use these operators instead of the old parallel operators.

5. If we are exposing [unsafe operators](#unsafe-marker) in one of our interfaces we should use the `Unsafe` data type to indicate this. By convention we define these operators in an `UnsafeAPI` trait in our interface that can be accessed using as `unsafe` operator.

```diff
trait MyInterface {
-  def unsafeDoSomething(): Unit
+  def unsafe: UnsafeAPI
+
+  trait UnsafeAPI {
+    def doSomething()(implicit unsafe: Unsafe): Unit
+  }
}
```

## Deletion of Type Alias Companion Objects

In ZIO 1.x, using the type aliases as objects created another way to do things and potentially led to confusion about whether these were the same or somehow different with little benefit.

In ZIO 2.x, we removed companion objects for type aliases. We still can use type aliases such as `UIO[Int]`, but we couldn't do `UIO.succeed(1)` anymore:

```diff
- val effect: UIO[Int] = UIO.succeed(1)
+ val effect: UIO[Int] = Exit.succeed(1)

// another examp:
- val stream: UStream[Int] = UStream.succeed(1)
+ val stream: UStream[Int] = ZStream.succeed(1)
```

The [migration script](#automatic-migration) will automatically convert all the usages of type aliases to the corresponding objects.

## Deletion of Has Data Type

The Has data type, which was used for combining services, was removed. Therefore, we no longer need to wrap services in the `Has` data type.

For example, in ZIO 1.x, the following layer denotes this layer requires `Logging`, `Random`, `Database` and produce the `UserRepo`:

```scala
val userRepo: ZLayer[Has[Logging] with Has[Random] with Has[Database], Throwable, Has[UserRepo]] = ???
```

In ZIO 2.x, the `Has` has been removed and simplified for better ergonomics:

```scala mdoc:invisible
trait UserRepo
trait Database
trait Logging
```

```scala mdoc:compile-only
val userRepo: ZLayer[Logging with Random with Database, Throwable, UserRepo] = ???
```

Also in ZIO 2.x instead of the `Has` data type, a type-level map called `ZEnvironment` has been built into ZIO. Let's see how this changes the way we can provide a service to the environment.

Using the following code snippet, we demonstrate how we used to access and provide instances of `Config` service to the application environment using ZIO 1.x:

```scala
// ZIO 1.x
import zio._

case class Config(url: String, port: Int)

object ConfigExample extends zio.App {

  val app: ZIO[Has[console.Console.Service] with Has[Config], Nothing, Unit] = for {
    config <- ZIO.service[Config]
    _      <- console.putStrLn(s"application config: $config").orDie
  } yield ()
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {

    app.provideSome[Has[console.Console.Service]](_ ++ Has(Config("localhost", 8080))).exitCode
  }
}
```

To migrate this snippet to ZIO 2.x, we need to remove all the `Has` service wrappers, and finally, we will use the `ZIO#provideSomeEnvironment` method to append the `Config` instance to the `ZEnvironment`:

```scala mdoc:compile-only
// ZIO 2.x
import zio._

case class Config(url: String, port: Int)

object ConfigExample extends ZIOAppDefault {
  val app: ZIO[Config, Nothing, Unit] = for {
    config <- ZIO.service[Config]
    _      <- Console.printLine(s"application config: $config").orDie
  } yield ()
    
  def run = 
    app.provideEnvironment(ZEnvironment(Config("localhost", 8080)))
}
```

Note that in ZIO 2.x, default services (e.g `Console`) are eliminated from the environment.

## ZIO

### Removed Methods

**Arrow Combinators** — (`+++`, `|||`, `onSecond`, `onFirst`, `second`, `first`, `onRight`, `onLeft`, `andThen`, `>>>`, `compose`, `<<<`, `identity`, `swap`, `join`)

In ZIO 2.0, all arrow combinators are removed, and we need to use alternatives like doing monadic for-comprehension style `flatMap` with combinators like `provide`, `zip`, and so on.

### ZIO 2.0 Naming Conventions

In ZIO 2.0, the name of constructors and operators becomes more ergonomic and simple. They reflect more about their purpose rather than just using idiomatic jargon of category theory or functional terms in functional programming with Haskell.

Here are some of the most important changes:

- **Multiple ways of doing the same thing are removed** — For example:
    - Both `ZIO.succeed` and `ZIO.effectTotal` do the same thing. So in ZIO 2.0 we just have one version of these constructors which is `ZIO.succeed`.
    - The bind operator `>>=` is removed. So we just have one way to flatMap which is the `flatMap` method. Therefore, the `>>=` method doesn't surprise the non-Haskellers.
    - The `ZIO#get` method was essentially a more constrained version of `ZIO#some`. So the `get` method is deprecated.

- **`ZIO.attempt` instead of `ZIO.effect`** — In ZIO 2.0 all ZIO constructors like `ZIO.effect*` that create a ZIO from a side effect are deprecated and renamed to the `ZIO.attempt*` version. For example, when we are reading from a file, it's more meaningful to say we are attempting to read from a file instead of saying we have an effect of reading from a file.

- **`ZIO` instead of the `M` suffix** — In effectful operations, the `M` suffix is renamed to the `ZIO` suffix. In ZIO 1.x, the `M` suffix in an effectful operation means that the operation works with monad in a monadic context. This naming convention is the legacy of Haskell jargon. In ZIO 2.x, all these suffixes are renamed to `ZIO`. For example, the `ifM` operator is renamed to `ifZIO`.

- **`Discard` instead of the underscore `_` suffix** — The underscore suffix is another legacy naming convention from Haskell's world. In ZIO 1.x, the underscore suffix means we are going to discard the result. The underscore version works exactly like the one without the underscore, but it discards the result and returns `Unit` in the ZIO context. For example, the `collectAll_` operator renamed to `collectAllDiscard`.

- **`as`, `to`, `into` prefixes** — The `ZIO#to` is renamed to the `ZIO#intoPromise`. So now we have three categories of conversion:
    1. **as** — The `ZIO#as` method and its variants like `ZIO#asSome`, `ZIO#asSomeError` and `ZIO#asService` are used when transforming the `A` inside of a `ZIO`, generally as shortcuts for `map(aToFoo(_))`.
    2. **to** — The `ZIO#to` method and its variants like `ZIO#toFuture` are used when the `ZIO` is transformed into something else other than the `ZIO` data-type.
    3. **into** — All `into*` methods, accept secondary data-type, modify it with the result of the current effect (e.g. `ZIO#intoPromise`, `ZStream#intoHub`, and `ZStream#intoQueue`)

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
| `ZIO#optional`                 | `ZIO#unsome`                    |
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
|                                |                                   |
| `ZIO.access`                   | `ZIO.environmentWith`             |
| `ZIO.accessM`                  | `ZIO.environmentWithZIO`          |
| `ZIO.fromFunction`             | `ZIO.environmentWith`             |
| `ZIO.fromFunctionM`            | `ZIO.environmentWithZIO`          |
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
|                                |                                   |
| `ZIO.tapCause`                 | `ZIO.tapErrorCause`               |

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

This is because the user incorrectly introduced a raw effect into the `acquire` parameter of `bracket` operation. As the `acquire` is _by-value parameter_, the value passed to the function evaluated _eagerly_, only once:

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

Assume we have these effects:

```scala mdoc:silent:nest
val x1: UIO[Int]     = ZIO.succeed(???)
val x2: UIO[Unit]    = ZIO.succeed(???)
val x3: UIO[String]  = ZIO.succeed(???)
val x4: UIO[Boolean] = ZIO.succeed(???)
```

In ZIO 1.x, the output of zipping together these effects are nested:

```scala mdoc:compile-only
val zipped = x1 <*> x2 <*> x3 <*> x4
// zipped: ZIO[Any, Nothing, (((Int, Unit), String), Boolean)] = ...
```

While in ZIO 2.x, we have more ergonomics result type and also the `Unit` data-type doesn't contribute to the output:

```scala mdoc:compile-only
val zipped = x1 <*> x2 <*> x3 <*> x4
// zipped: ZIO[Any, Nothing, (Int, String, Boolean)] = ...
```

This change is not only for the `ZIO` data type but also for all other data types like `ZStream`, `ZSTM`, etc.

As we have compositional zips, we no longer need higher arity zips in ZIO 1.x like `mapN`, `mapParN`, `Gen#zipN`, and `Gen#crossN`. They are deprecated in ZIO 2.x.

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
def download(url: String): Task[String] = ZIO.attempt(???)
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

```scala mdoc:compile-only
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

## Elimination of Default Services From The ZIO Environment

In ZIO 1.x we used default ZIO services such as `Clock`, `Console`, `Random`, and `System` along with the service pattern. So each time we used one of these services by obtaining them from the environment, the requirement of our effect becomes bigger and bigger. Finally, at the end of the world, we had two options, one was to use the default implementation of these services, and the other one was to use our own implementations.

For example, in ZIO 1.x, we have the following boilerplate code to print random numbers every second. The environment type of the `myApp` effect is `Console with Clock with Random`:

```scala
import zio._
import zio.clock.Clock
import zio.duration.durationInt
import zio.random.Random
import java.io.IOException
import zio.console._

object MainApp extends App {
  val myApp: ZIO[Clock with Console with Random, IOException, Unit] =
    for {
      rnd <- random.nextIntBounded(100)
      _   <- console.putStrLn(s"Random number: $rnd")
      _   <- clock.sleep(1.second)
    } yield ()
  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.forever.exitCode  
    // or we can provide our own implementation 
    // myApp.forever.provideLayer(Console.live ++ Clock.live ++ Random.live).exitCode
}
```

But these services did not fit well with the _service pattern_ because they were too low level and users frequently used them directly in their code to use the default implementation out of the box. So in most cases, users were not meant to provide their own implementation of these services. In another hand, as they are low level, they are used very often, so they pollute the environment type of the effect. They are too small to be used with the _service pattern_.

To improve on this, in ZIO 2.x, we deleted default services from the environment, instead, we built these services into the ZIO Runtime. So these services can still be modified and testable. In ZIO 2.x we encourage using the environment for higher-level services.

Therefore, the previous example In ZIO 2.x can be rewritten very simply as below:

```scala mdoc:compile-only
import zio._

import java.io.IOException

object MainApp extends App {
  val myApp: ZIO[Any, IOException, Unit] =
    for {
      rnd <- Random.nextIntBounded(100)
      _   <- Console.printLine(s"Random number: $rnd")
      _   <- Clock.sleep(1.second)
    } yield ()

  def run = myApp.forever
}
```

In nutshell, to migrate from ZIO 1.x to ZIO 2.x, we need follow these steps:

1. We aren't required to obtain default services from the environment using functions like `ZIO.service[Console]`, instead we should obtain the `Console` service using `ZIO.console`. So there is no need to access these services from the environment anymore, they are built into the ZIO Runtime. If we want to access them, we can use these functions instead:
- `ZIO.console`/`ZIO.consoleWith`
- `ZIO.clock`/`ZIO.clockWith`
- `ZIO.random`/`ZIO.randomWith`
- `ZIO.system`/`ZIO.systemWith`

```diff
for {
-  random <- ZIO.service[Random]
+  random <- ZIO.random
} yield ()
```

2. By removing these services from the environment, all usage of `ZEnv`, `Console`, `Clock`, `Random`, or `System` in the environment type of `ZIO`, `ZStream` and `ZLayer` should be generally deleted:

```diff
- val myApp: ZIO[Clock with Console with Random with UserRepo with Logging, IOException, Unit] = ???
+ val myApp: ZIO[UserRepo with Logging, IOException, Unit] = ???
```

3. If we want to use the live version in tests we can use these test aspects instead of providing them as layers:
   `withLiveClock`
   `withLiveConsole`
   `withLiveRandom`
   `withLiveSystem`
   `withLiveEnvironment`

For example:

```diff
- testM("TestLiveClock") { ... }.provideLayer(Clock.live)
+ test("TestLiveClock") { ... } @@ withLiveClock
```

4. In ZIO 1.x, whenever we wanted to provide our own versions of ZIO default services, we could do that using one of the `ZIO#provide*` operators. In ZIO 2.x if we need to modify the implementation of one of these services on a more fine-grained basis we can use of the following combinators:
  - `ZIO.withConsole`/`ZIO.withConsoleScoped`
  - `ZIO.withClock`/`ZIO.withClockScoped`
  - `ZIO.withRandom`/`ZIO.withRandomScoped`
  - `ZIO.withSystem`/`ZIO.withSystemScoped`

```scala
import zio._

object MyClockLive extends Clock {
  ... 
}

ZIO.withClock(MyClockLive)(effect)
```

5. According to the removal of default services from the ZIO environment we no longer need layers defined in the ZIO library which produce default ZIO services. So all these layers were removed, such as the following layers:
- `Console.live`, `Clock.any`
- `Clock.live`, `Clock.javaClock`, `Clock.any`
- `Random.live`, `Random.scalaRandom`, `Random.any`
- `System.live`, `System.any`

6. In ZIO some services have an alternative implementation rather than the default one. In ZIO 1.x, the default implementation of these services was provided by the environment. So when we wanted to use the default implementation, we didn't have to provide them explicitly at the end of the world. But in case we wanted to use an alternative implementation, we had to provide them explicitly. For example, to use the java implementation of `Clock`, we had to provide the `Clock.javaClock` layer:

```scala
import zio._
import zio.clock.Clock

object MainApp extends App {
  def run(args: List[String]) =
    clock.localDateTime
      .debug("local date time")
      .provideCustomLayer(
        ZLayer.succeed(
          java.time.Clock.systemDefaultZone()
        ) >>> Clock.javaClock
      )
      .orDie
      .exitCode
}
```

By removal of default services from the environment, their corresponding layers were removed. So we should call them directly as follows:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run =
    Clock.ClockJava(java.time.Clock.systemDefaultZone())
      .currentDateTime
      .debug("current date time")
}
```

The same approach applies to the `Random` service:

| ZIO 1.x (ZLayer)     | ZIO 2.x              |
|----------------------|----------------------|
| `Clock.javaClock`    | `Clock.ClockJava`    |
| `Random.scalaRandom` | `Random.RandomScala` |

7. In ZIO 1.x, the `ZEnv` was the type alias for all default services used in the ZIO environment. In ZIO 2.x, as the default services were removed from the environment, the `ZEnv` type alias was removed. And we have the `DefaultServices.live` to access the live implementation of default services.

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

Now in ZIO 2.x, the `zio.App` trait is deprecated and, we have the `zio.ZIOAppDefault` trait which is simpler than the former approach (Note that the `ZApp` is also deprecated, and we should use the `ZIOAppDefault` instead):

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
  final def args: ZIO[ZIOAppArgs, Nothing, Chunk[String]] = ZIO.service[ZIOAppArgs].map(_.args)
}
```

## Fiber

We deprecated the `Fiber.ID` and moved it to the `zio` package and called it the `FiberId`:

| ZIO 1.0        | ZIO 2.x       |
|----------------|---------------|
| `zio.Fiber.ID` | `zio.FiberID` |

## Runtime, Platform and Executor

### Unsafe Marker

To run a ZIO workflow, we usually use `ZIOAppDefault` or `ZIOApp` traits. These traits provide the `run` method which will run the workflow using their default `Runtime` system. But when we want to work with a low-level API or want to integrate with a legacy code, we need to unsafely run the workflow.

In ZIO 1.x, we used the `zio.Runtime.unsafeRun` method to run a ZIO workflow:

```scala
trait Runtime[+R] {
  def unsafeRun[E, A](zio: => ZIO[R, E, A]): A
}
```

For example, if we wanted to integrate a ZIO workflow with a legacy unsafe code, we used to write something like this:

```scala
import zio._

object MainApp {
  val zioWorkflow: ZIO[Any, Nothing, Int] = ???
  
  def legacyApplication(input: Int): Unit = ???
  
  def zioApplication: Int = 
    Runtime.default.unsafeRun(zioWorkflow)
  

  def main(args: Array[String]): Unit = {
    legacyApplication(zioApplication)
  }

}
```

In ZIO 2.x, we added the `Unsafe` data type to help developers to differentiate lower-level codes that are not purely functional from the higher-level codes which are always pure, total, and type safe. So the `Unsafe` is just a marker capability to indicate that something is unsafe:

```scala
object Unsafe {
  def unsafe[A](f: Unsafe => A): A = ???
}

trait Runtime[+R] { self =>
  def unsafe: UnsafeAPI
  
  trait UnsafeAPI {
    def run[E, A](zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): Exit[E, A]
  }
}
```

So to migrate the previous code to ZIO 2.x, we need to use the `Unsafe` data type like below:

```diff
import zio._

object MainApp {
  val zioWorkflow: ZIO[Any, Nothing, Int] = ???

  def legacyApplication(input: Int): Unit = ???

  def zioApplication: Int =
-    Runtime.default.unsafeRun(zioWorkflow)
+    Unsafe.unsafe { implicit unsafe =>
+      Runtime.default.unsafe.run(zioWorkflow).getOrThrowFiberFailure()
+    }

  def main(args: Array[String]): Unit = {
    legacyApplication(zioApplication)
  }

}
```

This way it is easy to distinguish between _safe_ and _unsafe_ variants of the same operator. 

To run an unsafe operator, we need implicit value of `Unsafe` in scope. This works particularly well in Scala 3 due to its support for implicit function types championed by Martin Odersky. In Scala 3 we can use the `Unsafe.unsafely` operator to create a block of code in which we can freely call unsafe operators:

```scala
Unsafe.unsafely {
  Runtime.default.unsafe.run(Console.printLine("Hello, World!"))
}
```

If we want to support Scala 2 we need to use a slightly more verbose syntax with `unsafe` and a lambda that takes an implicit value of `Unsafe`:

```scala mdoc:compile-only
import zio._

Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe.run(Console.printLine("Hello, World!"))
}
```

In summary, here are the rules for migrating from ZIO 1.x to ZIO 2.x corresponding to the unsafe operators:

|         | ZIO 1.0                | ZIO 2.x                                                                               |
|---------|------------------------|---------------------------------------------------------------------------------------|
| Scala 2 | `runtime.unsafeRun(x)` | `Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(x).getOrThrowFiberFailure() }` |
| Scala 3 | `runtime.unsafeRun(x)` | `Unsafe.unsafely { runtime.unsafe.run(x).getOrThrowFiberFailure() }`                    |

### Unsafe Variants

In ZIO 1.x, the `Runtime` had several methods for running ZIO workflows unsafely:

```scala
trait Runtime[+R] {
  def unsafeRun[E, A](zio: => ZIO[R, E, A]): A
  def unsafeRunTask[A](task: => RIO[R, A]): A
  def unsafeRunSync[E, A](zio: => ZIO[R, E, A]): Exit[E, A]
  def unsafeRunAsync[E, A](zio: => ZIO[R, E, A])(k: Exit[E, A] => Any): Unit
  def unsafeRunAsyncCancelable[E, A](zio: => ZIO[R, E, A])(k: Exit[E, A] => Any): Fiber.Id => Exit[E, A]
  def unsafeRunAsync_[E, A](zio: ZIO[R, E, A]): Unit
  def unsafeRunToFuture[E <: Throwable, A](zio: ZIO[R, E, A]): CancelableFuture[A]
}
```

We can group these unsafe methods into two categories: synchronous and asynchronous. The synchronous operators are the ones that are used when we want to wait for the result of the workflow to be available. The asynchronous operators are used when we want to execute the workflow asynchronously by providing a callback function that will be called when the workflow is completed.

In the previous section, we described the new `run` method inside the `unsafe` object of the `Runtime` trait. We can use this method to unsafely run workflows synchronously. There is another method, called `fork`, that can be used to unsafely run workflows asynchronously:

```scala
trait Runtime {
  def unsafe: UnsafeAPI
  
  trait UnsafeAPI {
    def run[E, A](zio: ZIO[R, E, A])(implicit unsafe: Unsafe): Exit[E, A]

    def fork[E, A](zio: ZIO[R, E, A])(implicit unsafe: Unsafe): Fiber.Runtime[E, A]
  }
}
```

The `fork` method returns a `Fiber.Runtime` that can be used to control the execution of the workflow. We have added a new `unsafe` object to the `Fiber.Runtime` class that has several unsafe methods including the `addObserver`:


```scala
object Fiber {
  sealed abstract class Runtime[+E, +A] extends Fiber[E, A] {
    def unsafe: UnsafeAPI

    trait UnsafeAPI {
      def addObserver(observer: Exit[E, A] => Unit)(implicit unsafe: Unsafe): Unit
    }
  }
}
```

Using the `addObserver` method, we can add a callback function of type `Exit[E, A] => Unit` to the underlying fiber. This callback function will be called when the fiber completes. Using these new functionalities, we can implement asynchronous unsafe operators like before. For example, assume we have the following code in ZIO 1.x:

```scala
// ZIO 1.x
import zio._
import zio.console._
import zio.duration._

Runtime.default.unsafeRunAsync(
  console.putStrLn("After 3 seconds I will return 5").delay(3.seconds).as(5)
)(
  _.fold(
    e => println(s"Failure: $e"),
    v => println(s"Success: $v")
  )
)
```

We can rewrite it in ZIO 2.x as follows:

```scala mdoc:compile-only
// ZIO 2.x
import zio._

Unsafe.unsafe { implicit unsafe =>
  Runtime.default.unsafe
    .fork(
      Console
        .printLine("After 3 seconds I will return 5")
        .delay(3.second)
        .as(5)
    )
    .unsafe
    .addObserver(
      _.fold(
        e => println(s"Failure: $e"),
        v => println(s"Success: $v")
      )
    )
}
```

Similarly, we can do the same for other unsafe operators. Here are some of them:

| ZIO 1.0                        | ZIO 2.x                                                                   |
|--------------------------------|---------------------------------------------------------------------------|
| `runtime.unsafeRunSync(x)`     | `Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(x) }`              |
| `runtime.unsafeRunTask(x)`     | `Unsafe.unsafe { implicit unsafe => runtime.unsafe.run(x).getOrThrow() }` |
| `runtime.unsafeRunAsync_(x)`   | `Unsafe.unsafe { implicit unsafe => runtime.unsafe.fork(x) }`             |
| `runtime.unsafeRunToFuture(x)` | `Unsafe.unsafe { implicit unsafe => runtime.unsafe.runToFuture(x) }`      |

### Runtime Customization using Layers

In ZIO 2.x we deleted the `zio.internal.Platform` data type, and instead, we use layers to customize the runtime. This allows us to use ZIO workflows in customizing our runtime (e.g. loading some configuration information to set up logging).

In ZIO 1.x, we had the `Platform` data type useful for providing custom execution configurations to the runtime:
- `Platform#withExecutor`— To provide a custom `Executor`
- `Platform#withTracing` to config tracing functionality
- `Platform#withSupervisor` to provide a `Supervisor`
- `Platform#withScheduler` to provide a `Scheduler`
- etc.

Here is an example of creating a custom `Runtime` in ZIO 1.x:

```scala
import zio._
import zio.internal.Executor

object MainApp extends zio.App {
  val customExecutor: Executor = ???

  val myApp: UIO[Unit] =
    ZIO.debug("Application started")

  def run(args: List[String]): URIO[ZEnv, ExitCode] =
    ZIO
      .runtime[ZEnv]
      .map { runtime =>
        Unsafe.unsafe { implicit unsafe =>
          runtime
            .mapPlatform(_.withExecutor(customExecutor))
            .unsafe
            .run(myApp)
            .getOrThrowFiberFailure()
        }
      }
      .exitCode
}
```

In ZIO 2.x, the whole `Platform` was deleted and instead, we have several out-of-the-box layers for runtime customization, defined in the companion object of the `Runtime` trait. Here are some of them:
- `Runtime.addLogger` to add a logger
- `Runtime.setExecutor` to provide a custom `Executor`
- `Runtime.enableOpLog` to log runtime information
- `Runtime.enableRuntimeMetrics` to track runtime metrics
- etc.

Let's see how a previous example can be rewritten in ZIO 2.x:

```scala
import zio._

object MainApp extends ZIOAppDefault {
  val customExecutor: zio.Executor = ???

  val myApp = 
    ZIO.debug("Application started")

  def run =
    myApp.provide(
      Runtime.setExecutor(customExecutor)
    )
}
```

Note that ZIO ecosystem libraries like ZMX may have their own layers that install all necessary functionality.

### Runtime Customization Using ZIO Data Type

To access information about the configuration of our ZIO program as we are running, there are some more specific operators that we can use, such as:
- `ZIO.executor`/`ZIO.executorWith`
- `ZIO.logger`/`ZIO.loggerWith`
- `ZIO.isFatal`/`ZIO.isFatalWith`

### Runtime Configurations are Scoped

When we access a `Runtime` using `ZIO.runtime` it will inherit all the configuration of the current workflow so if we use it to run effects they will be run with the same logger and so on:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  val workflow1 = ZIO.debug("workflow1 is running") *> ZIO.log("This line will never get logged")
  val workflow2 = ZIO.debug("workflow2 is running") *> ZIO.log("This line will get logged")
  val workflow3 = ZIO.debug("workflow3 is running") *> ZIO.log("This line will never get logged")

  def run =
    ZIO.provideLayer(Runtime.removeDefaultLoggers) {
      ZIO.runtime[Any].flatMap(_.run(workflow1)) *>
        ZIO.provideLayer(Runtime.addLogger(Runtime.defaultLoggers.head)) {
          ZIO.runtime[Any].flatMap(_.run(workflow2))
        } *> workflow3
    }
}
```

### Custom Runtime for Mixed Applications

In ZIO 2.x, to create a custom runtime in mixed applications we combine all the layers that do our customization and then perform the `Runtime.unsafe.fromLayer` operation:

```scala mdoc:compile-only
import zio._

object MainApp {
  val sl4jlogger: ZLogger[String, Any] = ???

  def legacyApplication(input: Int): Unit = ???

  val zioWorkflow: ZIO[Any, Nothing, Int] = ???

  def zioApplication(): Int =
      Unsafe.unsafe { implicit unsafe =>
        Runtime
          .unsafe
          .fromLayer(
            Runtime.removeDefaultLoggers ++ Runtime.addLogger(sl4jlogger)
          )
          .unsafe
          .run(zioWorkflow)
          .getOrThrowFiberFailure()
      }

  def main(args: Array[String]): Unit = {
    val result = zioApplication()
    legacyApplication(result)
  }  

}
```

### Executor

We moved the `Executor` from `zio.internal` to the `zio` package:

| ZIO 1.0                 | ZIO 2.x        |
|-------------------------|----------------|
| `zio.internal.Executor` | `zio.Executor` |

### Auto-Blocking

In ZIO 1.x, we have two groups of constructors for importing _synchronous side effects_, one for importing synchronous side effects, such as `zio.effect` and the other one for importing synchronous side effects that are known to be blocking, such as `zio.blocking.effectBlocking`.

The first one uses an asynchronous thread pool to execute side effects, while the second one uses a blocking thread pool.

For performance reasons, the number of asynchronous threads is limited to a fixed number. So if the programmer mistakenly imports a blocking operation using the `ZIO.effect` instead of `zio.blocking.effectBlocking` it might block all limited threads in the asynchronous pool, and then starvation might occur.

So if we run the following code if the `ioBoundWorkflow` starts executing before the `cpuBoundWorkflow` for some amount of time, all threads in the asynchronous thread pool will be blocked and the `cpuBoundWorkflow` will never get executed:

```scala
// ZIO 1.x
import zio._
import zio.duration.durationInt

import scala.annotation.tailrec

object MainApp extends App {

  def fib(n: Int): BigInt = {
    @tailrec
    def go(n: BigInt, a: BigInt, b: BigInt): BigInt = {
      if (n == 0) a
      else go(n - 1, b, a + b)
    }
    go(n, 0, 1)
  }

  def ioBoundWorkflow =
    ZIO.debug("Starting I/O bound workflow") *>
      ZIO.foreachPar_(1 to 100)(_ => ZIO.effect(Thread.sleep(Long.MaxValue))) *>
      ZIO.debug("Finished I/O bound workflow")

  def cpuBoundWorkflow =
    ZIO.debug("Starting CPU bound workflow") *>
      ZIO.foreachPar_(1 to 100)(i => ZIO.effect(fib(i))) *>
      ZIO.debug("Finished CPU bound workflow")

  // the delay for the CPU bound workflow is not needed but we want to take 
  // a chance that all threads in the asynchronous thread pool will be blocked
  def run(args: List[String]) =
    (ioBoundWorkflow <&> cpuBoundWorkflow.delay(1.second)).exitCode
}
```

This is why we should import blocking synchronous side effects using the `zio.blocking.effectBlocking` instead of the `ZIO.effect`.

In ZIO 2.x, the same as in ZIO 1.x, we encourage separating blocking operations (I/O work operations) from the ordinary side effects (CPU work operations).

But the one thing that makes ZIO 2.x more powerful than ZIO 1.x is that if the programmer accidentally imports a blocking synchronous side effect using the `ZIO.attempt` instead of `ZIO.attemptBlocking` the runtime scheduler will automatically detect blocking workflows and shift them to the blocking executor:

```scala mdoc:compile-only
// ZIO 2.x
import zio._

import scala.annotation.tailrec

object MainApp extends ZIOAppDefault {

  def fib(n: Int): BigInt = {
    @tailrec
    def go(n: BigInt, a: BigInt, b: BigInt): BigInt = {
      if (n == 0) a
      else go(n - 1, b, a + b)
    }
    go(n, 0, 1)
  }

  def ioBoundWorkflow =
    ZIO.debug("Starting I/O bound workflow") *>
      ZIO.foreachParDiscard(1 to 100)(_ =>
        ZIO.attempt(Thread.sleep(Long.MaxValue))
      ) *>
      ZIO.debug("Finished I/O bound workflow")

  def cpuBoundWorkflow =
    ZIO.debug("Starting CPU bound workflow") *>
      ZIO.foreachParDiscard(1 to 100)(i => ZIO.attempt(fib(i))) *>
      ZIO.debug("Finished CPU bound workflow")

  def run = ioBoundWorkflow <&> cpuBoundWorkflow.delay(1.second)
}
```

In the above example, although we imported the blocking operation wrongly, the runtime scheduler will detect that and prevent the blocking operation from being executed in the asynchronous thread pool. So the `cpuBoundWorkflow` will be executed without any starvation problem.

## ZLayer

### Constructing Layers

In ZIO 1.x, when we want to write a service that depends on other services, we need to use `ZLayer.fromService*` variants with a lot of boilerplate:

```scala
val live: URLayer[FooService with BarService, BazService] =
  ZLayer.fromServices[FooService.Service, BarService.Service, BazService.Service] {
    (fooService: FooService.Service, barService: BarService.Service) =>
      new BazService.Service {
        override def baz: UIO[Unit] =
          for {
            _ <- fooService.foo
            _ <- barService.bar
          } yield ()
      }
  }
```

ZIO 2.x deprecates all `ZLayer.fromService*` functions. Instead, we use a for comprehension:

```scala mdoc:compile-only
trait FooService {
  def foo: UIO[Unit]
}

trait BarService {
  def bar: UIO[Unit]
}

trait BazService {
  def baz: UIO[Unit]
}

case class BazServiceImpl(fooService: FooService, barService: BarService) extends BazService {
  override def baz: UIO[Unit] =
    for {
      _ <- fooService.foo
      _ <- barService.bar
    } yield ()
}

object LoggingLive {
  val layer: ZLayer[FooService & BarService, Nothing, BazService] =
    ZLayer {
      for {
        fooService <- ZIO.service[FooService]
        barService <- ZIO.service[BarService]
      } yield BazServiceImpl(fooService, barService)
    }
}
```

### Accessing a Service from the Environment

Assume we have a service named `Logging`:

```scala mdoc:silent:nest
trait Logging {
  def log(line: String): UIO[Unit]
}
```

In ZIO 1.x, when we wanted to access a service from the environment, we used the `ZIO.access` + `Has#get` combination (`ZIO.access(_.get)`):

```scala mdoc:silent:nest:warn
val logging: URIO[Logging, Logging] = ZIO.access(_.get)
```

Also, to create accessor methods, we used the following code:

```scala mdoc:silent:nest:warn
def log(line: String): URIO[Logging, Unit] = ZIO.accessM(_.get.log(line))
```

ZIO 2.x reduces one level of indirection by using `ZIO.service` operator:

```scala mdoc:silent:nest
val logging : URIO[Logging, Logging] = ZIO.service
```

And to write the accessor method in ZIO 2.x, we can use `ZIO.serviceWithZIO` operator:

```scala mdoc:silent:nest
def log(line: String): URIO[Logging, Unit] = ZIO.serviceWithZIO(_.log(line))
```

```scala mdoc:reset:invisible
import zio._
```

Note that [accessor methods](../../reference/service-pattern/accessor-methods.md) are no longer recommended.

### Accessing Multiple Services in the Environment

In ZIO 1.x, we could access multiple services using higher arity service accessors like `ZIO.services`.

```scala
for {
  (fooService, barService) <- ZIO.services[FooService, BarService]
  foo                      <- fooService.foo()
  bar                      <- barService.bar()
  _                        <- console.putStrLn(s"foo: $foo, bar: $bar")
} yield ()
```

These accessor methods are now _deprecated_ as we can achieve the same functionality using `ZIO.service` with for-comprehension syntax, which is more idiomatic and scalable way of accessing multiple services in the environment:

```scala mdoc:invisible
trait FooService {
  def foo(): UIO[String]
}

trait BarService {
  def bar(): UIO[Int]
}
```

```scala mdoc:silent:nest
for {
  fooService <- ZIO.service[FooService]
  barService <- ZIO.service[BarService]
  foo        <- fooService.foo()
  bar        <- barService.bar()
  _          <- Console.printLine(s"foo: $foo, bar: $bar")
} yield ()
```

### Building the Dependency Graph

To create the dependency graph in ZIO 1.x, we should compose the required layer manually. As the ordering of layer compositions matters, and also we should care about composing layers in both vertical and horizontal manner, it would be a cumbersome job to create a dependency graph with a lot of boilerplates.

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

In ZIO 1.x, we had to compose these different layers together to create the whole application dependency graph:

```scala mdoc:invisible:nest
trait Logging {}

trait Database {}

trait BlobStorage {}

trait UserRepo {}

trait DocRepo {}

case class LoggerImpl() extends Logging {}

case class DatabaseImp() extends Database {}

case class UserRepoImpl(logging: Logging, database: Database) extends UserRepo {}

case class BlobStorageImpl(logging: Logging) extends BlobStorage {}

case class DocRepoImpl(logging: Logging, database: Database, blobStorage: BlobStorage) extends DocRepo {}

object Logging {
  val live: URLayer[Any, Logging] =
    ZLayer.succeed(LoggerImpl())
}

object Database {
  val live: URLayer[Any, Database] =
    ZLayer.succeed(DatabaseImp())
}

object UserRepo {
  val live: URLayer[Logging with Database, UserRepo] =
    ZLayer {
      for {
        logging  <- ZIO.service[Logging]
        database <- ZIO.service[Database]
      } yield UserRepoImpl(logging, database)
    }
}


object BlobStorage {
  val live: URLayer[Logging, BlobStorage] =
    ZLayer {
      for {
        logging <- ZIO.service[Logging]
      } yield BlobStorageImpl(logging)
    }
}

object DocRepo {
  val live: URLayer[Logging with Database with BlobStorage, DocRepo] =
    ZLayer {
      for {
        logging     <- ZIO.service[Logging]
        database    <- ZIO.service[Database]
        blobStorage <- ZIO.service[BlobStorage]
      } yield DocRepoImpl(logging, database, blobStorage)
    }
}
  
val myApp: ZIO[DocRepo with UserRepo, Nothing, Unit] = ZIO.succeed(???)
```

```scala
val appLayer: URLayer[Any, DocRepo with UserRepo] =
  (((Console.live >>> Logging.live) ++ Database.live ++ (Console.live >>> Logging.live >>> BlobStorage.live)) >>> DocRepo.live) ++
    (((Console.live >>> Logging.live) ++ Database.live) >>> UserRepo.live)
    
val res: ZIO[Any, Nothing, Unit] = myApp.provideLayer(appLayer)
```

As the development of our application progress, the number of layers will grow, and maintaining the dependency graph would be tedious and hard to debug.

For example, if we miss the `Logging.live` dependency, the compile-time error would be very messy:

```scala
myApp.provideLayer(
  ((Database.live ++ BlobStorage.live) >>> DocRepo.live) ++
    (Database.live >>> UserRepo.live)
)
```

```
type mismatch;
 found   : zio.URLayer[zio.Logging with zio.Database with zio.BlobStorage,zio.DocRepo]
    (which expands to)  zio.ZLayer[zio.Logging with zio.Database with zio.BlobStorage,Nothing,zio.DocRepo]
 required: zio.ZLayer[zio.Database with zio.BlobStorage,?,?]
    ((Database.live ++ BlobStorage.live) >>> DocRepo.live) ++
```

In ZIO 2.x, we can automatically construct dependencies with friendly compile-time hints, using `ZIO#provide` operator:

```scala mdoc:silent:nest
val res: ZIO[Any, Nothing, Unit] =
  myApp.provide(
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
  myApp.provide(
    DocRepo.live,
    BlobStorage.live,
    Logging.live,
    Database.live,
    UserRepo.live
  )
```

If we miss some dependencies, it doesn't compile, and the compiler gives us the clue:

```scala
val app: ZIO[Any, Nothing, Unit] =
  myApp.provide(
    DocRepo.live,
    BlobStorage.live,
//    Logging.live,
    Database.live,
    UserRepo.live
  )
```

```
  ZLayer Wiring Error  

❯ missing Logging
❯     for DocRepo.live

❯ missing Logging
❯     for UserRepo.live
```

We can also directly construct a layer using `ZLayer.make`:

```scala mdoc:silent:nest
val layer = ZLayer.make[DocRepo with UserRepo](
  Logging.live,
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live
)
```

And also the `ZLayer.makeSome` helps us to construct a layer which requires some service and produces some other services (`URLayer[Int, Out]`) using `ZLayer.makeSome[In, Out]`:

```scala mdoc:silent:nest
val layer = ZLayer.makeSome[Logging, DocRepo with UserRepo](
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live
)
```

In ZIO 1.x, the `ZIO#provideSomeLayer` provides environment partially:

```scala
val app: ZIO[Console, Nothing, Unit] =
  myApp.provideSomeLayer[Console](
    ((Logging.live ++ Database.live ++ (Console.live >>> Logging.live >>> BlobStorage.live)) >>> DocRepo.live) ++
      (((Console.live >>> Logging.live) ++ Database.live) >>> UserRepo.live)
  )
```

In ZIO 2.x, we have a similar functionality but for injection, which is the `ZIO#provideSome[Rest](l1, l2, ...)` operator:

```scala mdoc:silent:nest:warn
val app: ZIO[Any, Nothing, Unit] =
  myApp.provideSome[Logging](
    DocRepo.live,
    Database.live,
    BlobStorage.live,
    UserRepo.live
  )
```

:::note
In ZIO 2.x, the `ZIO#provide` method — together with its variant `ZIO#provideSome` — is a default and easier way of injecting dependencies to the environmental effect. We do not have to create the dependency graph manually, it will be built automatically. In contrast, the `ZIO#provideLayer` — and its variant `ZIO#provideSomeLayer` — is useful for low-level and custom cases.
:::

### ZLayer Debugging

To debug ZLayer construction, we have two built-in layers, i.e., `ZLayer.Debug.tree` and `ZLayer.Debug.mermaid`. For example, by including `ZLayer.Debug.mermaid` into our layer construction, the compiler generates the following debug information:

```scala
val layer = ZLayer.make[DocRepo with UserRepo](
  Logging.live,
  DocRepo.live,
  Database.live,
  BlobStorage.live,
  UserRepo.live,
  ZLayer.Debug.mermaid
)
```

```scala
[info]   ZLayer Wiring Graph  
[info] 
[info] ◉ DocRepo.live
[info] ├─◑ Logging.live
[info] ├─◑ Database.live
[info] ╰─◑ BlobStorage.live
[info]   ╰─◑ Logging.live
[info] 
[info] ◉ UserRepo.live
[info] ├─◑ Logging.live
[info] ╰─◑ Database.live
[info] 
[info] Mermaid Live Editor Link
[info] https://mermaid-js.github.io/mermaid-live-editor/edit/#eyJjb2RlIjoiZ3JhcGhcbiAgICBDb25zb2xlLmxpdmVcbiAgICBCbG9iU3RvcmFnZS5saXZlIC0tPiBMb2dnaW5nLmxpdmVcbiAgICBMb2dnaW5nLmxpdmUgLS0+IENvbnNvbGUubGl2ZVxuICAgIFVzZXJSZXBvLmxpdmUgLS0+IExvZ2dpbmcubGl2ZVxuICAgIFVzZXJSZXBvLmxpdmUgLS0+IERhdGFiYXNlLmxpdmVcbiAgICBEb2NSZXBvLmxpdmUgLS0+IERhdGFiYXNlLmxpdmVcbiAgICBEb2NSZXBvLmxpdmUgLS0+IEJsb2JTdG9yYWdlLmxpdmVcbiAgICBEYXRhYmFzZS5saXZlXG4gICAgIiwibWVybWFpZCI6ICJ7XG4gIFwidGhlbWVcIjogXCJkZWZhdWx0XCJcbn0iLCAidXBkYXRlRWRpdG9yIjogdHJ1ZSwgImF1dG9TeW5jIjogdHJ1ZSwgInVwZGF0ZURpYWdyYW0iOiB0cnVlfQ==
```

### Descriptive ZIOApp Environment Compiler Errors

In ZIO 1.x, if we mistakenly forget to provide some requirements, we have some complicated compile errors. Assume we have the following example:

```scala
// ZIO 1.x
import zio._

case class Config(host: String, port: Int)

trait Logger {
  def log(line: Any): Task[Unit]
}

object MainApp extends App {
  val myApp =
    for {
      config <- ZIO.service[Config]
      logger <- ZIO.service[Logger]
      _ <- logger.log(s"Application started with the following config: $config")
    } yield ()

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    myApp.exitCode
}
```

This program needs `Config` and `Logger` layers, but we missed them. The compiler prints this mystic error, which is hard to reason about:

```scala
type mismatch;
found   : zio.URIO[zio.Has[Config] with zio.Has[Logger] with zio.console.Console,zio.ExitCode]
(which expands to)  zio.ZIO[zio.Has[Config] with zio.Has[Logger] with zio.Has[zio.console.Console.Service],Nothing,zio.ExitCode]
required: zio.URIO[zio.ZEnv,zio.ExitCode]
(which expands to)  zio.ZIO[zio.Has[zio.clock.Clock.Service] with zio.Has[zio.console.Console.Service] with zio.Has[zio.system.System.Service] with zio.Has[zio.random.Random.Service] with zio.Has[zio.blocking.Blocking.Service],Nothing,zio.ExitCode]
myApp.exitCode
```

In ZIO 2.x, we have descriptive errors. Let's try the above example in ZIO 2.x:

```scala
import zio._

case class Config(host: String, port: Int)

trait Logger {
  def log(line: Any): Task[Unit]
}

object MainApp extends ZIOAppDefault {
  def run =
    for {
      config <- ZIO.service[Config]
      logger <- ZIO.service[Logger]
      _ <- logger.log(s"Application started with the following config: $config")
    } yield ()
}
```

This will print the following error message:

```
[error] ──── ZIO APP ERROR ───────────────────────────────────────────────────
[error] 
[error]  Your effect requires a service that is not in the environment.
[error]  Please provide a layer for the following type:
[error] 
[error] 
[error]    1. Logger
[error] 
[error] 
[error]  Call your effect's provide method with the layers you need.
[error]  You can read more about layers and providing services here:
[error]  
[error]    https://zio.dev/version-1.x/dataypes/contextual/index
[error] 
[error] ──────────────────────────────────────────────────────────────────────
[error] 
[error]       logger <- ZIO.service[Logger]
[error]              ^
[error] one error found
```

It also warns if we provide more layers than needed:

```scala
import zio._

case class Config(host: String, port: Int)

trait Logger {
  def log(line: Any): Task[Unit]
}

case class LoggerLive(console: Console) extends Logger {
  override def log(line: Any): Task[Unit] =
    console.printLine(line)
}

object LoggerLive {
  val layer =
    ZLayer {
      for {
        console <- ZIO.service[Console]
      } yield LoggerLive(console)
    }
}

object MainApp extends ZIOAppDefault {
  val myApp = for {
    config <- ZIO.service[Config]
    _ <- Console.printLine(s"Application started with the following config: $config")
  } yield ()

  def run =
    myApp.provide(
      Random.live,
      ZLayer.succeed(Config("localhost", 8080)),
      LoggerLive.layer
    )
}
```

It will print the following warning message:

```
[error] ──── ZLAYER WARNING ──────────────────────────────────────────────────
[error] 
[error]  You have provided more than is required.
[error]  You may remove the following 2 layers:
[error]    
[error]    1. Random.live
[error]    2. LoggerLive.layer
[error] 
[error]   
[error] ──────────────────────────────────────────────────────────────────────
[error] 
[error]     myApp.provide(
[error]                  ^
[error] one error found
```

### Eliminators for Environmental Effects

In ZIO 2.x, layers become eliminators for environmental effects:

```scala mdoc:compile-only
trait Foo
trait Bar

val fooLayer   : ZLayer[Any, Nothing, Foo]          = ZLayer.succeed(???)
val fooWithBar : ZIO[Foo with Bar, Throwable, Unit] = ZIO.succeed(???)
val bar        : ZIO[Bar, Any, Unit]                = fooLayer(fooWithBar)
```

In this example, by applying the `fooLayer` to the `fooWithBarWithBaz` effect it will eliminate the contextual `Foo` service from the environment of the effect.

Furthermore, we can compose multiple layers together and then eliminate services from the environmental effects:

```scala mdoc:compile-only
import zio._

trait Foo
trait Bar
trait Baz

object MainApp extends ZIOAppDefault {

  val needsFooAndBarAndBaz: ZIO[Foo & Bar & Baz, Nothing, Unit] =
    for {
      foo <- ZIO.service[Foo]
      bar <- ZIO.service[Bar]
      baz <- ZIO.service[Baz]
      _ <- ZIO.debug(s"Foo: $foo, Bar: $bar, Baz: $baz")
    } yield ()

  val fooAndBarLayer: ULayer[Foo with Bar] =
    ZLayer.succeed(new Foo {}) ++ ZLayer.succeed(new Bar {})

  val needsBaz: ZIO[Baz, Nothing, Unit] =
    fooAndBarLayer(needsFooAndBarAndBaz)

  def run = needsBaz.provide(ZLayer.succeed(new Baz {}))

}
```

It helps us to provide contextual environments like the DSL below:

```scala mdoc:compile-only
import zio._

trait Connection

val dbTransaction: ZLayer[Any, Throwable, Connection] = ZLayer.succeed(???)
val effect: ZIO[Connection, Throwable, Unit] = ZIO.succeed(???)

val result: ZIO[Any, Throwable, Unit] =
  dbTransaction {
    effect
  }
```

The `dbTransaction` is a `ZLayer` of type `ZLayer[Any, Throwable, Connection]` which can eliminate the `Connection` service from the `effect`.

### Service Pattern

The _Service Pattern_, formerly called "Module Pattern", is one of the most important changes in ZIO 2.x. Let's take a look at services in ZIO 1.x before discussing changes. 

Assume we have already defined the `Foo` and `Bar` services using _Service Pattern 1.0_ as follows:

```scala
// ZIO 1.x
object foo {
  type Foo = Has[Foo.Service]

  object Foo {
    trait Service {
      def foo: UIO[String]
    }
  }
}

object bar {
  type Bar = Has[Bar.Service]

  object Bar {
    trait Service {
      def bar: UIO[String]
    }
  }
}
```

Here is a `Baz` service that uses the `Foo` and `Bar` services:

```scala
// ZIO 1.x
object baz {
  // Defining the service type by wrapping the service interface with Has[_] data type
  type Baz = Has[Baz.Service]

  // Companion object that holds service interface and its live implementation
  object Baz {
    trait Service {
      def baz(input: String): UIO[Unit]
    }
    
    // Live implementation of the Foo service
    val live: ZLayer[Foo with Bar, Nothing, Baz] =
      ZLayer.fromServices[Foo.Service, Bar.Service, Baz.Service] {
        (fooSrv: Foo.Service, barSrv: Bar.Service) =>
          new Baz.Service {
            override def baz(input: String): UIO[Unit] =
              for {
                _ <- fooSrv.foo(input)
                _ <- barSrv.bar(input)
              } yield ()
          }
      }
  }

  // Accessor Methods
  def baz(input: String): URIO[Baz, Unit] =
    ZIO.accessM(_.get.baz(input))
}
```

The `Baz` service is a service which depends on the `Foo` and `Bar` services.

ZIO 2.x introduces the _Service Pattern 2.0_ which is much more concise and has more ergonomics. Let's see how the `Baz` service can be implemented using this new pattern.

Assume we have already defined the `Foo` and `Bar` services, as below:

```scala mdoc:silent
// ZIO 2.x

trait Foo {
  def foo(input: String): UIO[Unit]
}

trait Bar {
  def bar(input: String): UIO[Unit]
}
```

Now, here is the implementation of the `Baz` service based on the _Service Pattern 2.0_:

```scala mdoc:silent:nest
// ZIO 2.x

import zio._

// Defining the Service Interface
trait Baz {
  def baz(input: String): UIO[Unit]
}

// Implementation of the Service Interface
case class BazLive(fooSrv: Foo, barSrv: Bar) extends Baz {
  override def baz(input: String): UIO[Unit] =
    for {
      _ <- fooSrv.foo(input)
      _ <- barSrv.bar(input) 
    } yield ()
}

// Converting the Service Implementation into the ZLayer
object BazLive {
  val layer: URLayer[Foo & Bar, Baz] =
    ZLayer {
      for {
        fooSrv <- ZIO.service[Foo]
        barSrv <- ZIO.service[Bar]
      } yield BazLive(fooSrv, barSrv)
    }
}
```

As we see, we have the following changes:

1. **Deprecation of Type Alias for `Has` Wrappers** — In _Service Pattern 1.0_ although the type aliases were to prevent using `Has[ServiceName]` boilerplate everywhere, they were confusing, and led to doubly nested `Has[Has[ServiceName]]`. So the _Service Pattern 2.0_ doesn't anymore encourage using type aliases. Also, they were removed from all built-in ZIO services. So the `type Foo = Has[Foo.Service]` is removed and the `Foo.Service` will just be `Foo`.

2. **Introducing Constructor-based Dependency Injection** — In _Service Pattern 1.0_ when we wanted to create a layer that depends on other services, we had to use `ZLayer.fromService*` constructors. The problem with those constructors is that there are too many of them, each one is useful for a specific use-case, but people had trouble figuring out which one to use. 

    In _Service Pattern 2.0_ we don't worry about all these different `ZLayer` constructors. The recommendation is to  **provide dependencies as interfaces through the case class constructor**, and then we have direct access to all of the dependencies to implement the service. Finally, to create the `ZLayer` we use a for comprehension.

3. **Separated Interface** — In _Service Pattern 2.0_, ZIO supports the _Separated Interface_ pattern which encourages keeping the implementation of an interface decoupled from the client and its definition.

    As our application grows, where we define our layers matters more. _Separated Interface_ is a very useful pattern while we are developing a complex application. It helps us to reduce the coupling between application components. 

    Following two changes in _Service Pattern_ we can define the service definition in one package but its implementations in other packages:
    
   1. **Flattened Structure** — In the new pattern, everything is at the top level in a file. So the developer is not limited to package service definition and service implementation in one package.
   
      :::note
      Service Pattern 2.0 supports the idea of _Separated Interface_, but it doesn't enforce us grouping them into different packages and modules. The decision is up to us, based on the complexity and requirements of our application.
      :::
   
   2. **Decoupling Interfaces from Implementation** — Assume we have a complex application, and our interface is `Baz` with different implementations that potentially depend on entirely different modules. Putting layers in the service definition means anyone depending on the service definition needs to depend on all the dependencies of all the implementations, which is not a good practice.
   
    In _Service Pattern 2.0_, layers are defined in the implementation's companion object, not in the interface's companion object. So instead of calling `Baz.live` to access the live implementation we call `BazLive.layer`.

4. **Accessor Methods** — The new pattern reduced one level of indirection on writing accessor methods. So instead of accessing the environment (`ZIO.access/ZIO.accessM`) and then retrieving the service from the environment (`Has#get`) and then calling the service method, _Service Pattern 2.0_ introduced `ZIO.serviceWith` that is a more concise way of writing accessor methods. For example, instead of `ZIO.accessM(_.get.baz(input))` we write `ZIO.serviceWithZIO(_.baz(input))`.

_Service Pattern 1.0_ was somewhat complicated and had some boilerplate. [_Service Pattern 2.0_](../../reference/service-pattern/service-pattern.md) is much more familiar for people coming from an object-oriented world. It is simpler and easier to learn for newcomers.

### Other Changes

Here is list of other deprecated methods:

| ZIO 1.x                    | ZIO 2.x                      |
|----------------------------|------------------------------|
| `ZLayer.fromEffect`        | `ZLayer.fromZIO`             |
| `ZLayer.fromEffectMany`    | `ZLayer.fromZIOMany`         |
| `ZLayer.fromFunctionM`     | `ZLayer.fromFunctionZIO`     |
| `ZLayer.fromFunctionManyM` | `ZLayer.fromFunctionManyZIO` |
| `ZLayer.identity`          | `ZLayer.service`             |
| `ZLayer.requires`          | `ZLayer.service`             |

## Scopes

ZIO 2.x introduced a new data type called `Scope`. Scopes are a huge simplification to resource management in ZIO 2.0 that brings new levels of simplicity, power, and performance. They are a replacement for the old `ZManaged` data type in ZIO 1.x.

### ZManaged

In ZIO 1.x, we used a data type called `ZManaged` to provide resource safety. Although inspired by the Managed data type from Haskell, ZIO Managed innovated in a number of ways over both Haskell and Cats Effect Resource:

1. `ZManaged` supported interruptible acquisition, which is useful for concurrent data structures like semaphores, where it is safe to interrupt acquisition because the cleanup can determine (by inspecting the in-memory state) whether the acquisition succeeded or not.

2. `ZManaged` supported parallel operations, all with the most desirable semantics possible. For example, if we acquired resources in parallel, then they would be released in parallel, and if anything went wrong, of course, the acquisition would be aborted and resources released.

3. `ZManaged` has an API almost identical to `ZIO`, by design, so our knowledge of `ZIO` transfers to `Managed`. The main difference is that in `ZManaged`, `flatMap` lets us _use and keep open a resource_, while `use` lets us _use and release the resource (going back to `ZIO`)_.

4. In addition, `ZManaged` has new constructors, so we can create them from a pair of acquire/release actions, or from just a finalizer (which would be invoked during finalization).

Despite the innovation and numerous benefits, however, `ZManaged` presents some serious drawbacks:

- First, `ZManaged` is yet another thing to teach to developers. Many new `ZIO` developers try to avoid using `ZManaged`, because they are not sure exactly what it's for or how it differs from `ZIO`. Those who use it, sometimes wonder when to use `ZIO` versus `ZManaged`.

- Second, all the methods on `ZIO` must be manually and painstakingly re-implemented on `ZManaged`, but with much more complex implementations due to the complications of handling resource-safety in the presence of concurrency. In practice, `ZIO` still has more methods than `ZManaged`.

- Third, `ZManaged` is a layer over `ZIO`, and is slower than `ZIO` itself, because of the additional complications and wrapping. Unlike other approaches, `ZIO` uses an executable encoding, so it's able to avoid double-interpretation, but it still has measurable overhead over `ZIO`.

Despite the drawbacks of `ZManaged`, the benefits of concurrent resource safety are significant, so we documented, supported, and tried to optimize `ZManaged` over the life of ZIO 1.x, not having a suitable alternative.

### Scopes

The concept of scopes has been implicit in ZIO since before ZIO 1.0, including in `ZManaged`, `FiberRef`, interruptibility, and thread pool shifting. In each of these cases we "do something" at the beginning of the scope (e.g. acquire a resource, set a `FiberRef`, change the interruptibility of the thread pool) and "do something else" (release the resource, restore the `FiberRef`, restore the interruptibility or thread pool) at the end of the scope.

However, scopes have not been first-class values, which has required the use of other data types such as `ZManaged` to represent this concept. With ZIO 2.0, all of this is radically changing for the better! Thanks to other ZIO 2.0 innovations, including the [removal of `Has`](#deletion-of-has-data-type) (which bakes a compositional environment directly into the ZIO data type), we have found a way to delete `ZManaged` entirely, while preserving all of its benefits!

ZIO 2.x addresses this by introducing the concept of a Scope as a first class value:

```scala
trait Scope {
  def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any]): UIO[Unit]
  def close(exit: Exit[Any, Any]): UIO[Unit]
}
```

That is, a `Scope` is something that we can add finalizers to and eventually close, running all of the finalizers in the scope. Operations that acquire resources add their finalizers directly to the current scope, stored in ZIO Environment. So we found that the ZIO Environment is now powerful enough to provide resource-safety by itself!

For example, an operation that opens a file might have this type signature:

```scala
def openFile(name: String): ZIO[Scope, IOException, FileInputStream] =
  ZIO.acquireRelease(acquire)(release)
```

The `openFile` workflow requires a `Scope` to be run and its implementation will add a finalizer to the scope that will close the file. So, in combination with the environment, we can use `Scope` to represent resources.

This allows us to work with the resource and compose it with other resources, much like we do with `ZManaged`. Then, when we are ready to close the scope we use `ZIO.scoped` to provide the scope and eliminate it from the environment, much the same way we do with `use` on `ZManaged`:

```scala
ZIO.scoped {
  openFile(name).flatMap(file => useFile(file))  // ZIO[Scoped, IOException, Unit]
}                                                // ZIO[Any, IOException, Unit]
```

`ZIO.scoped` eliminates `Scope` from the environment, leaving the rest of the environment unchanged. It converts the type of enclosed effect from `ZIO[Scoped, IOException, Unit]` to `ZIO[Any, IOException, Unit]`. So, we can think of it as an algebraic effect handler that handles the `Scope` effect by eliminating it from the set of algebraic effects being used. This is another use case of [eliminators for environmental effect](#eliminators-for-environmental-effects).

This simple, beautiful, and powerful design gives us bulletproof parallel and concurrent operators that may acquire resources with well-defined and optimal semantics in successful and failure scenarios. All the `ZManaged` semantics arise for free atop `ZIO`.

Scopes are simple because they don't require us to learn any new data types. Scopes are powerful because ZIO has more operators than `ZManaged`, and is always _up to date_ with the latest and greatest. Scopes are fast because there are no layers atop ZIO.

In addition to providing simpler, more powerful, and faster resource management, the replacement of Managed with scopes is going to tremendously simplify the ZIO API: there will not be any more `toManaged`, `mapManaged`, etc, variants. Layers will always be constructed with `ZIO`.

### Migration from `ZManaged` to `Scope`

#### Deferred Migration
For reasons of backward compatibility, `ZManaged` won't actually be deleted, but rather, moved to a separate library called `zio-managed` that our ZIO 2.0 application can depend on. However, ZIO Core, including ZIO Streams and ZIO Test, will no longer use `ZManaged`.

So, if we have a lot of code that used `ZManaged` and we are not ready to deal with it right now we can still use the `ZManaged` data type and compile our code. We can add the `zio-managed` dependency into the `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-managed" % "<2.x version>"
```
And then by adding `import zio.managed._` we can access all `ZManaged` capabilities including extension methods on ZIO data types. This helps us to compile the ZIO 1.x code base which uses the `ZManaged` data type. Then we can smoothly refactor it to use the `Scope` data type instead.

#### Immediate Migration

Migration to `Scope` is easy and straightforward. As the `ZManaged` data type is removed from `ZIO` and all usages in ZIO Core, ZIO Stream, and ZIO Test are reimplemented in terms of `Scope`. We should follow these steps to migrate the `ZManaged` codebase to `Scope`:

1. Replace all references to `ZManaged[R, E, A]` with `ZIO[R with Scope, E, A]`.

Example: 

```diff
object HttpClient {
-   def make(): ZManaged[Config, IOException, HttpClient] = ???
+   def make(): ZIO[Config with Scope, IOException, HttpClient] = ???
}
```

2. Replace all references to `resource.use(f)` with `ZIO.scoped(resource.flatMap(f))`:

```diff
- resource.use(f)
+ ZIO.scoped {
+  resource.flatMap(f) 
+ }
```

Example:

```diff
- ZManaged
-  .fromAutoCloseable(zio.blocking.effectBlockingIO(scala.io.Source.fromFile("file.txt")))
-  .use(x => ZIO.succeed(x.getLines().length))
+ ZIO.scoped {
+   ZIO
+    .fromAutoCloseable(ZIO.attemptBlockingIO(scala.io.Source.fromFile("file.txt")))
+    .flatMap(x => ZIO.succeed(x.getLines().length))
+ }
```

3. Replace all `ZManaged` constructors with `ZIO.acquireRelease` or one of its variants:

```diff
- ZManaged.make(acquire)(release)
+ ZIO.acquireRelease(acquire)(release)
```

Example: 

```diff
- ZManaged.fromAutoCloseable(
-   zio.blocking.effectBlockingIO(new FileInputStream("file.txt")) 
- )
+ ZIO.fromAutoCloseable(
+   ZIO.attemptBlockingIO(new FileInputStream("file.txt")) 
+ )
```

4. Replace all usages of `ZLayer(resource)` or `resource.toLayer` with `ZLayer.scoped(resource)`, all references to `ZStream.managed(resource)` with `ZStream.scoped(resource)`, and so on for similar constructors:

```diff
- ZLayer {
-   resource // with type of ZManaged[R, E, A]
- }
+ ZLayer.scoped {
+   resource // with type of ZIO[R with Scope, E, A]
+ }

- resource.toLayer
+ ZLayer.scoped(resource)

- ZStream.managed(resource)
+ ZStream.scoped(resource)
```

5. Delete all uses of `toManaged_`:

```diff
- effect.toManaged_
+ effect 
```

6. Replace all uses of `toManaged(finalizer)` with `withFinalizer(finalizer)`:

```diff
- effect.toManaged(finalizer)
+ effect.withFinalizer(finalizer)
```

Example:

```diff
val effect: ZIO[Any, IOException, FileInputStream] = ???
- effect.toManaged(is => ZIO.succeed(is.close))
+ effect.withFinalizer(is => ZIO.succeed(is.close()))
```

Finally, let's try a full example of converting a ZManaged codebase to the Scoped one. Assume we have written the following `transfer` function in ZIO 1.x using `ZManaged`:

```scala
import zio._
import zio.blocking._

import java.io._

def close(resource: Closeable): URIO[Blocking, Unit] =
  effectBlockingIO(resource.close()).orDie

def is(file: String): ZManaged[Blocking, IOException, FileInputStream] =
  ZManaged.make(effectBlockingIO(new FileInputStream(file)))(close)

def os(file: String): ZManaged[Blocking, IOException, FileOutputStream] =
  ZManaged.make(effectBlockingIO(new FileOutputStream(file)))(close)

def copy(
    from: FileInputStream,
    to: FileOutputStream
): ZIO[Blocking, IOException, Unit] =
  effectBlockingIO {
    val buf = new Array[Byte](1024)
    var length = 0
    length = from.read(buf)

    while (length > 0) {
      to.write(buf, 0, length)
      length = from.read(buf)
    }
  }

def transfer(from: String, to: String): ZIO[Blocking, IOException, Unit] = {
  val resource = for {
    from <- is(from)
    to   <- os(to)
    _    <- copy(from, to).toManaged_
  } yield ()
  resource.useNow
}
```

As of ZIO 2.x, we should rewrite it as follows:

```scala mdoc:compile-only
import zio._

import java.io._

def close(resource: Closeable): UIO[Unit] =
  ZIO.attempt(resource.close()).orDie

def is(file: String): ZIO[Scope, IOException, FileInputStream] =
  ZIO.acquireRelease(ZIO.attemptBlockingIO(new FileInputStream(file)))(close)

def os(file: String): ZIO[Scope, IOException, FileOutputStream] =
  ZIO.acquireRelease(ZIO.attemptBlockingIO(new FileOutputStream(file)))(close)

def copy(
    from: FileInputStream,
    to: FileOutputStream
): IO[IOException, Unit] =
  ZIO.attemptBlockingIO(???)

def transfer(from: String, to: String): IO[Throwable, Unit] =
  ZIO.scoped {
    for {
      from <- is(from)
      to   <- os(to)
      _    <- copy(from, to)
    } yield ()
  }
```

As we can see, the migration is quite straightforward, and it doesn't require much extra work.

## Simplification of Concurrent Data Types

Even though highly polymorphic versions of ZIO concurrent data structures (e.g. `ZRef`, `ZQueue`) were elegant, they were used rarely. There was also some cost associated with polymorphism, such as errors, readability, and maintainability.

Therefore, we simplified these data structures by specializing them in their more monomorphic versions without significant loss of features:

| ZIO 1.x (removed)                    | ZIO 2.x               |
|--------------------------------------|-----------------------|
| `ZRef[+EA, +EB, -A, +B]`             | `Ref[A]`              |
| `ZTRef[+EA, +EB, -A, +B]`            | `TRef[A]`             |
| `ZRefM[-RA, -RB, +EA, +EB, -A, +B]`  | `Ref.Synchronized[A]` |
| `ZQueue[-RA, -RB, +EA, +EB, -A, +B]` | `Queue[A]`            |
| `ZHub[-RA, -RB, +EA, +EB, -A, +B]`   | `Hub[A]`              |

## Ref

ZIO 2.x unifies `Ref` and `RefM`. `RefM` becomes a subtype of `Ref` that has additional capabilities (i.e. the ability to perform effects within the operations) at some cost to performance:

| ZIO 1.x | ZIO 2.x             |
|---------|---------------------|
| `RefM`  | `Ref.Synchronized`  |

As the `RefM` is renamed to `Ref.Synchronized`; now the `Synchronized` is a subtype of `Ref`. This change allows a `Ref.Synchronized` to be used anywhere a `Ref` is currently being used.

To perform the migration, after renaming these types to the newer ones (e.g. `RefM` renamed to `Ref.Synchronized`) we should perform the following method renames:

| ZIO 1.x                 | ZIO 2.x                                |
|-------------------------|----------------------------------------|
| `RefM#dequeueRef`       | `zio.stream.SubscriptionRef#changes`   |
| `RefM#getAndUpdate`     | `Ref.Synchronized#getAndUpdateZIO`     |
| `RefM#getAndUpdateSome` | `Ref.Synchronized#getAndUpdateSomeZIO` |
| `RefM#modify`           | `Ref.Synchronized#modifyZIO`           |
| `RefM#modifySome`       | `Ref.Synchronized#modifySomeZIO`       |
| `RefM#update`           | `Ref.Synchronized#updateZIO`           |
| `RefM#updateAndGet`     | `Ref.Synchronized#updateAndGetZIO`     |
| `RefM#updateSome`       | `Ref.Synchronized#updateSomeZIO`       |
| `RefM#updateSomeAndGet` | `Ref.Synchronized#updateSomeAndGetZIO` |

## Semaphore and TSemaphore

There is a slight change on `TSemaphore#withPermit` method. In ZIO 2.x, instead of accepting `STM` values, it accepts only `ZIO` values and returns the `ZIO` value.

| `withPermit` | Input          | Output         |
|--------------|----------------|----------------|
| ZIO 1.x      | `STM[E, B]`    | `STM[E, B]`    |
| ZIO 2.x      | `ZIO[R, E, A]` | `ZIO[R, E, A]` |

## Queue

In ZIO 2.x, the `Queue` uses `Chunk` consistently with other ZIO APIs like ZIO Streams. This will avoid unnecessary conversions between collection types, particularly for streaming applications where streams use `Chunk` internally, but bulk take operations previously returned a `List` on `Queue`.

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

### ZSpec

The `ZSpec` data type has been renamed to `Spec`.

| ZIO 1.x | ZIO 2.x |
|---------|---------|
| `ZSpec` | `Spec`  |

So without any special effort, whenever we use `ZSpec` we should change it to `Spec`, e.g.:

```diff
- val myspec: ZSpec[Any, Nothing] =
+ val myspec: Spec[Any, Nothing] =
  test("my spec") {
    assertTrue(true)
  }
```

### Sharing Layers between Specs
In ZIO 1.x, in order to share costly layers between specs, you needed a "Master" spec that would invoke test code from individual Spec classes.
In ZIO 2.x, sharing layers is much simpler. 
Use `ZIOSpec[YourSharedType]` and plug your layer into the `bootstrap` field.

```scala mdoc:compile-only
import zio.test._

class SharedService()

object Layers {
  val sharedLayer = 
    ZLayer.succeed(new SharedService())
}

object UseSharedLayerA extends ZIOSpec[SharedService]{
  override def spec =
    test("use the shared layer in test A") {
      assertCompletes
    }

  override def bootstrap= Layers.sharedLayer
}

object UseSharedLayerB extends ZIOSpec[SharedService]{
  override def spec =
    test("use the shared layer in test B") {
      assertCompletes
    }

  override def bootstrap = Layers.sharedLayer
}
```
Then use the standard
```bash
sbt test
```
in order to run the tests.

Note - If you assign the results of a function call to `bootstrap`, like this:

```scala mdoc:compile-only
import zio.test._
class SharedService()

object LayerBuilder {
  def createLayer() = ZLayer.succeed(new SharedService())
}

object NotUsingSharedLayerA extends ZIOSpec[SharedService]{
  override def spec =
    test("use the shared layer in test A") {
      assertCompletes
    }

  override def bootstrap = LayerBuilder.createLayer()
}

object NotUsingSharedLayerB extends ZIOSpec[SharedService]{
  override def spec =
    test("use the shared layer in test B") {
      assertCompletes
    }

  override def bootstrap = LayerBuilder.createLayer()
}
```

These Specs will *not* share the same instance, as they are different references.


### Smart Constructors

By introducing smart constructors, we do not longer have the `testM` function to create effectful test suits. Instead, we should use the `test` function:

ZIO 1.x:

```scala
suite("Ref") {
  testM("updateAndGet") {
    val result = Ref.make(0).flatMap(_.updateAndGet(_ + 1))
    assertM(result)(Assertion.equalTo(1))
  }
}
```

ZIO 2.x:

```scala mdoc:invisible
import zio.test._
```

```scala mdoc:silent:nest
suite("Ref") {
  test("updateAndGet") {
    val result = Ref.make(0).flatMap(_.updateAndGet(_ + 1))
    assertZIO(result)(Assertion.equalTo(1))
  }
}
```

In ZIO 2.x, when creating a test suite, it's not important whether we are testing pure or effectful code. `testM` was removed, and we can unifromly use `test` in both cases.

### Unification of `Assertion` and `AssertionM`

In ZIO 2.x, `Assertion` and `AssertionM` were unified to a single type, `Assertion`, so `AssertionM` was removed. In ZIO 2.x, instead of writing effectful assertions (`AssertionM`) and then asserting workflows, we should perform workflows and then simply assert the result of the workflow.

Assume we have written the following test in ZIO 1.x:

```scala
testM("Effectful Assertion ZIO 1.x") {
  def myEffectfulAssertion[Int](reference: Int): AssertionM[Int] = ???

  val sut = ZIO.effect(???)
  assertM(sut)(effectfulAssertion(5))
}
```

We can extract effectful operations from the effectful assertion and then perform the assertion like below:

```scala
test("Effectful Assertion ZIO 2.x") {
  def myAssertion[Int](reference: Int): Assertion[Int] = ???
  
  val res = for {
    sut <- ZIO.effect(???)
    res <- extractedOperations(sut)
  } yield assert(res)(myAssertion(5))
}
```

### Smart Assertion

ZIO 2.x introduced a new test method, named `assertTrue` which allows us to assert an expected behavior using ordinary Scala expressions that return `Boolean` values instead of specialized assertion operators.

So instead of writing following test assertions:

```scala mdoc:silent:nest
val list   = List(1, 2, 3, 4, 5)
val number = 3
val option = Option.empty[Int]

suite("ZIO 1.x Test Assertions")(
  test("contains")(assert(list)(Assertion.contains(5))),
  test("forall")(assert(list)(Assertion.forall(Assertion.assertion("even")(actual => actual % 2 == 0)))),
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
- When using partial functions in test cases there is no problem with the happy path, but if something goes wrong, it is a little annoying to find what went wrong. But smart assertions are descriptive, e.g. when we call `Option#get` to an optional value that is `None` the test fails with a related error message: `Option was None`
- They have lots of domain specific errors that talk to us in a language that we understand.

### Compositional Specs

In ZIO 1.x, we cannot compose specs directly, although we can combine all children's specs via the suite itself:

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

ZIO Streams 2.x does not include any significant API changes. Almost the same code we have for ZIO Stream 1.x will continue working, so we don't need to relearn any APIs. Even though a good level of source compatibility has been maintained, some API elements have been changed.

So far, before ZIO 2.0, ZIO Stream has included three main abstractions:
1. **`ZStream`** — represents the source of elements
2. **`ZSink`** — represents consumers of elements that can be composed together to create composite consumers
3. **`ZTransducer`** — represents generalized stateful and effectful stream processing

![ZIO Streams 1.x](/img/assets/zio-streams-1.x.svg)

In ZIO 2.0, we added an underlying abstraction called `Channel`. Channels are underlying both `Stream` and `Sink`, so it's an abstraction that unifies everything in ZIO Streams.

![ZChannel](/img/assets/zio-streams-zchannel.svg)

Channels are nexuses of I/O operations, which support both reading and writing:

- A `Channel` can write some elements to the _output_, and it can terminate with some sort of _done_ value. The `Channel` uses this _done_ value to notify the downstream `Channel` that its emission of elements finished. In ZIO 2.x, `ZStream` is encoded as the output side of a `Channel`.

- A `Channel` can read from its input, and it can also terminate with some sort of _done_ value that is an upstream result. A `Channel` has an _input type_, and an _input done type_. The `Channel` uses the _done_ value to determine when the upstream `Channel` finishes its emission. In ZIO 2.x, `ZSink` is encoded as the input side of a `Channel`.

So we can say that streams are the output side and sinks are the input side of a `Channel`. What about the middle part? In ZIO 1.x, this used to be known as the `ZTransducer`. Transducers were great for writing high-performance codecs (e.g. compression). They were really just a specialization of sinks. We have added transducers because things were not sufficiently efficient using sinks. If we were to write streaming codecs using sinks, they could be quite slow.

In ZIO 2.x, we removed the transducers. Instead, we realized we need something else for the middle part, and now it's called a `Pipeline` in ZIO 2.x. Pipelines accept a stream as input and return the transformed stream as output.

![ZIO Streams 2.x](/img/assets/zio-streams-2.x.svg)

Pipelines are basically an abstraction for composing a bunch of operations together that can be later applied to a stream. For example, we can create a pipeline that reads bytes, decodes them to a UTF-8 string, splits that into lines, and then splits on commas. That is a very simple CSV parsing pipeline which we can later use by piping other streams into it. 

| ZIO Streams 1.x | ZIO Streams 2.x                  |
|-----------------|----------------------------------|
|                 | `ZChannel`                       |
| `ZStream`       | `ZStream` (backed by `ZChannel`) |
| `ZSink`         | `ZSink` (backed by `ZChannel`)   |
| `ZTransducer`   | `ZPipeline`                      |


## ZIO Schedules

`Schedule.jittered` in ZIO 1 is equivalent to `Schedule.jittered(0.0, 1.0)`. In ZIO 2 this changed to `Schedule.jittered(0.8, 1.2)`.

In ZIO 1 the average throughput of the updated schedule was twice the original schedule. In ZIO 2 the average throughput of the updated schedule is the same as the original schedule.

The new behavior is more in line with the definition of 'jitter' as used in signal processing.
Even so, [research](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/) shows that `Schedule.jittered(0.0, 1.0)` is better for retrying.

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


2. In ZIO 2.0 all type aliases like `type Foo = Has[Foo.Service]` were removed. So when accessing services instead of `ZIO.service[Foo.Service]` we now do `ZIO.service[Foo]`.

### Blocking Service

Since there is rarely a need to use a separate blocking thread pool, ZIO 2.0 created _one global blocking pool_, and removed the Blocking service from `ZEnv` and the built-in services.

All blocking operations were moved to the `ZIO` data type:

| ZIO 1.x                   | ZIO 2.x |
|---------------------------|---------|
| `zio.blocking.Blocking.*` | `ZIO.*` |

With some renaming:

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

Working with these three time-related APIs, always made us require `Clock` as our environment. So by moving these primitives into the `Clock` service, now we can directly call them via the `Clock` service. This change solves a common anti-pattern in ZIO 1.0, whereby a middleware that uses `Clock` via this retrying, repetition, or scheduling logic must provide the `Clock` layer on every method invocation:

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
        .provideEnvironment(ZEnvironment(clock))
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
      _       <- journal.append(s"$current--$line").retry(Schedule.exponential(2.seconds)).orDie
    } yield ()
  }
}
```

Note that the ZIO API didn't change, but the `Clock` trait became a bigger one, having more clock-related methods.

### Console Service

Method names in the _Console_ service were renamed to more readable names:

| ZIO 1.x       | ZIO 2.x          |
|---------------|------------------|
| `putStr`      | `print`          |
| `putStrErr`   | `printError`     |
| `putStrLn`    | `printLine`      |
| `putStrLnErr` | `printLineError` |
| `getStrLn`    | `readLine`       |

## Other New Features

### Smart Constructors

Every data type in ZIO (`ZIO`, `ZStream`, etc.) has a variety of constructor functions that are designed to _up convert_ some weaker type into the target type. Typically, these converter functions are named `fromXYZ`, e.g. `ZIO.fromEither`, `ZStream.fromZIO`, etc.

While these are precise, ZIO 2.0 provides the `ZIO.from` constructor which can intelligently choose the most likely constructor based on the input type. So instead of writing `ZIO.fromEither(Right(3))` we can easily write `ZIO.from(Right(3))`. Let's try some of them:

```scala mdoc:compile-only
import zio.stream.ZStream

ZIO.fromOption(Some("Ok!"))
ZIO.from(Some("Ok!"))

ZIO.fromEither(Right(3))
ZIO.from(Right(3))

ZIO.fromFiber(Fiber.succeed("Ok!"))
ZIO.from(Fiber.succeed("Ok!"))

ZStream.fromIterable(List(1,2,3)) 
ZStream.from(List(1,1,3))

ZStream.fromChunk(Chunk(1,2,3))
ZStream.from(Chunk(1,2,3))

ZStream.fromIterableZIO(ZIO.succeed(List(1,2,3)))
ZStream.from(ZIO.succeed(List(1,2,3)))
```

### ZState

`ZState` is a new data type that ZIO 2.0 introduced:

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

  def run = app.provide(ZState.initial(MyState(0)))
}
```

### Hub

`Hub` is a new concurrent data structure like `Queue`. While `Queue` solves the problem of _distributing_ messages to multiple consumers, `Hub` solves the problem of _broadcasting_ the same message to multiple consumers.

![Hub](/img/assets/hub.svg)

Here is an example of broadcasting messages to multiple subscribers:

```scala mdoc:silent:nest
for {
  hub <- Hub.bounded[String](requestedCapacity = 2)
  s1 = hub.subscribe
  s2 = hub.subscribe
  _ <- ZIO.scoped {
    s1.zip(s2).flatMap { case (left, right) =>
      for {
        _ <- hub.publish("Hello from a hub!")
        _ <- left.take.flatMap(Console.printLine(_))
        _ <- right.take.flatMap(Console.printLine(_))
      } yield ()
    }
  }
} yield ()
```

Visit the [Hub](../../reference/concurrency/hub.md) page to learn more about it.

### ZIO Aspects

We introduced `ZIOAspect` which enables us to modify the existing `ZIO` effect with some additional aspects like debugging, tracing, retrying, and logging:

```scala mdoc:silent:nest
val myApp: ZIO[Random, Nothing, String] =
  ZIO.ifZIO(
    Random.nextIntBounded(10) @@ ZIOAspect.debug map (_ % 2 == 0)
  )(
    onTrue = ZIO.succeed("Hello!"), 
    onFalse = ZIO.succeed("Good Bye!")) @@ ZIOAspect.debug @@ ZIOAspect.logged("result")
    
// Sample Output:     
// 2
// Hello!
// timestamp=2021-09-05T15:32:56.705901Z level=INFO thread=#2 message="result: Hello!" file=ZIOAspect.scala line=74 class=zio.ZIOAspect$$anon$4 method=apply
```

### Debugging

ZIO 2.x introduces the `debug` method that is useful when we want to print something to the console for debugging purposes without introducing additional environmental requirements or error types:

```scala mdoc:silent:nest
val myApp: ZIO[Random, Nothing, String] =
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

ZIO 2.x supports a lightweight built-in logging facade that standardizes the interface for logging functionality. So it doesn't replace existing logging libraries, but also we can plug it into one of the existing logging backends.

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

ZIO Logging calculates the running duration of that span, and includes that in the logging data associating to its span label.

### Compile-time Execution Tracing

ZIO 1.x's execution trace is not as useful as it could be, because it contains tracing information for internal ZIO operators that is not very helpful, when trying to understand, where an error occurred.

Let's say we have the following application in ZIO 1.x:

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

The execution trace is informative, but it doesn't lead us to the exact point, where the failure happened. It's a little hard to see what is going on here. 

Let's rewrite the previous example in ZIO 2.x:

```scala mdoc:compile-only
import zio._

object TracingExample extends ZIOAppDefault {

  def doSomething(input: Int): ZIO[Any, String, Unit] =
    for {
      _ <- Console.printLine(s"Do something $input").orDie
      _ <- ZIO.fail("Boom!") // line number 8
      _ <- Console.printLine("Finished my job").orDie
    } yield ()

  def myApp: ZIO[Any, String, Unit] =
    for {
      _ <- Console.printLine("Hello!").orDie
      _ <- doSomething(5)   // line number 15
      _ <- Console.printLine("Bye Bye!").orDie
    } yield ()

  def run = myApp
}
```

The output is more descriptive than in ZIO 1.x. It is similar to a Java stacktrace:

```
Hello!
Do something 5
timestamp=2021-12-19T08:25:09.372926403Z level=ERROR thread=#zio-fiber-1639902309 message="Exception in thread "zio-fiber-1639902309" java.lang.String: Boom!
	at zio.examples.TracingExample.doSomething(TracingExample.scala:8)
	at zio.examples.TracingExample.myApp(TracingExample.scala:15)"
```

As we see, the first line of the execution trace points to the exact location in the source code which causes the failure (`ZIO.fail("Boom!")`) that is line number 8.

In ZIO 2.x, tracing is not optional, and unlike in ZIO 1.x, it is impossible to disable async tracing, either globally, or for specific effects. ZIO now always generates async stack traces, and it is impossible to turn this feature off, either at the global level or at the level of individual effects. Since nearly all users were running ZIO with tracing turned on, this change should have minimal impact on ZIO applications.

Another improvement about ZIO tracing is its performance. Tracing in ZIO 1.x slows down the application performance by two times. In ZIO 1.x, we wrap and unwrap every combinator at runtime to be able to trace the execution. While it is happening on the runtime, it takes a lot of allocations which all need to be garbage collected afterwards adding a huge amount of complexity at runtime.

Some users turned off tracing to achieve better performance, but that results in losing the ability to trace the application, when something breaks.

In ZIO 2.x, we moved execution tracing from the runtime to the compile-time. This is done by capturing tracing information from the source code at compile time using macros. Most tracing information is pre-allocated at startup, and never needs to be garbage collected. As a result, we end up with much better performance in execution tracing.
