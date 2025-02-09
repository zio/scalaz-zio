---
id: scope
title: "Scope"
---

The `Scope` data type is the foundation of safe and composable resources handling in ZIO.

Conceptually, a scope represents the lifetime of one or more resources. The resources can be used in the scope and are guaranteed to be released when the scope is closed.

The `Scope` data type takes this idea and represents it as a first class value.

```scala mdoc:compile-only
import zio._

trait Scope {
  def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any]): UIO[Unit]
  def close(exit: => Exit[Any, Any]): UIO[Unit]
}

object Scope {
  def make: UIO[Scope] = ???
}
```

The `addFinalizerExit` operator lets us add a finalizer to the `Scope`. Based on the `Exit` value that the `Scope` is closed with, the finalizer will be run. The finalizer is guaranteed to be run when the scope is closed. The `close` operator closes the scope, running all the finalizers that have been added to the scope. It takes an `Exit` value and runs the finalizers based on that value.

In the following example, we create a `Scope`, add a finalizer to it, and then close the scope:

```scala mdoc:compile-only
import zio._

for {
  scope <- Scope.make
  _ <- ZIO.debug("Scope is created!")
  _ <- scope.addFinalizer(
    for {
      _ <- ZIO.debug("The finalizer is started!")
      _ <- ZIO.sleep(5.seconds)
      _ <- ZIO.debug("The finalizer is done!")
    } yield ()
  )
  _ <- ZIO.debug("Leaving scope!")
  _ <- scope.close(Exit.succeed(()))
  _ <- ZIO.debug("Scope is closed!")
} yield ()
```

The output of this program will be:

```
Scope is created!
Leaving scope!
The finalizer is started!
The finalizer is done!
Scope is closed!
```

We can see that the finalizer is run after we called `close` on the scope. So the finalizer is guaranteed to be run when the scope is closed.

The `Scope#extend` operator, takes a `ZIO` effect that requires a `Scope` and provides it with a `Scope` without closing it afterwards. This allows us to extend the lifetime of a scoped resource to the lifetime of a scope.

## Scopes and The ZIO Environment

In combination with the ZIO environment, `Scope` gives us an extremely powerful way to manage resources.

We can define a resource using operators such as `ZIO.acquireRelease`, which lets us construct a scoped value from an `acquire` and `release` workflow. For example, here is how we might define a simple resource:

```scala mdoc
import zio._

import java.io.IOException
import scala.io._

def acquire(name: => String): ZIO[Any, IOException, Source] =
  ZIO.attemptBlockingIO(Source.fromFile(name))

def release(source: => Source): ZIO[Any, Nothing, Unit] =
  ZIO.succeedBlocking(source.close())

def source(name: => String): ZIO[Scope, IOException, Source] =
  ZIO.acquireRelease(acquire(name))(release(_))
```

Notice that the `acquireRelease` operator added a `Scope` to the environment required by the workflow. This indicates that this workflow needs a `Scope` to be run and will add a finalizer that will close the resource when the scope is closed.

We can continue working with the resource as long as we want by using `flatMap` or other `ZIO` operators. For example, here is how we might read the contents of a file:

```scala mdoc
source("cool.txt").flatMap { source =>
  ZIO.attemptBlockingIO(source.getLines())
}
```

Once we are finished working with the file, we can close the scope using the `ZIO.scoped` operator. This function creates a new `Scope`, provides it to the workflow, and closes the `Scope` once the workflow is complete:

```scala
object ZIO {
  def scoped[R, E, A](zio: ZIO[Scope with R, E, A]): ZIO[R, E, A] = ???
}
```

The `scoped` operator removes the `Scope` from the environment, indicating that there are no longer any resources used by this workflow that require a scope. We now have a workflow that is ready to run:

```scala mdoc
def contents(name: => String): ZIO[Any, IOException, Chunk[String]] =
  ZIO.scoped {
    source(name).flatMap { source =>
      ZIO.attemptBlockingIO(Chunk.fromIterator(source.getLines()))
    }
  }
```

In some cases ZIO applications may provide a `Scope` for us for resources that we don't specify a scope for. For example `ZIOApp` provides a `Scope` for our entire application and ZIO Test provides a `Scope` for each test.

:::note
Please note that like any other services that we can obtain from the ZIO environment, we can do the same with `Scope`. By calling `ZIO.service[Scope]` we can obtain the `Scope` service and then use it to manage resources by adding finalizers to it:

```scala mdoc:silent
import zio._

val resourcefulApp: ZIO[Scope, Nothing, Unit] =
  for {
    scope <- ZIO.service[Scope]
    _     <- ZIO.debug("Entering the scope!")
    _ <- scope.addFinalizer(
      for {
        _ <- ZIO.debug("The finalizer is started!")
        _ <- ZIO.sleep(5.seconds)
        _ <- ZIO.debug("The finalizer is done!")
      } yield ()
    )
    _ <- ZIO.debug("Leaving scope!")
  } yield ()
```

Then we can run the `app` workflow by providing the `Scope` service to it:

```scala mdoc:compile-only
val finalApp: ZIO[Any, Nothing, Unit] =
  Scope.make.flatMap(scope => resourcefulApp.provide(ZLayer.succeed(scope)).onExit(scope.close(_)))
```

Here is the output of the program:

```
Entering the scope!
Leaving scope!
The finalizer is started!
The finalizer is done!
```

So we can think of `Scope` as a service that helps us manage resources effectfully. However, the way we utilized it in the previous example is not as per the best practices, and it was only for educational purposes.

In real-world applications, we can easily manage resources by utilizing high-level operators such as `ZIO.acquireRelease` and `ZIO.scoped`.
:::

## Scopes are Dynamic

One important thing to note about `Scope` is that they are dynamic. This means that if we have an effect that requires a `Scope` we can `flatMap` over that effect and use its value to create a new effect. The new effect extends the lifetime of the original scope. So as we don't close the scope (by calling `ZIO.scoped`) the resources will not be released, and they can become bigger and bigger until we close them:

```scala mdoc:invisible
def file(name: String): ZIO[Any, IOException, Source] =
  ZIO.attemptBlockingIO(Source.fromFile(name))

def getLines(source: Source): ZIO[Any, Throwable, Iterator[String]] =
  ZIO.from(source.getLines())

def processLines(lines: Iterator[String]): ZIO[Any, Nothing, Unit] =
  ZIO.succeed(lines.foreach(println))
```

```scala mdoc:compile-only
ZIO.scoped {
  file("path/to/file.txt").flatMap(getLines).flatMap(processLines) 
}
```

## Defining Resources

We have already seen the `acquireRelease` operator, which is one of the most fundamental operators for creating scoped resources.

```scala mdoc:compile-only
object ZIO {
  def acquireRelease[R, E, A](acquire: => ZIO[R, E, A])(release: A => ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
    ???
}
```

The `acquireRelease` operator performs the `acquire` workflow uninterruptibly. This is important because if we allowed interruption during resource acquisition we could be interrupted when the resource was partially acquired.

The guarantee of the `acquireRelease` operator is that if the `acquire` workflow successfully completes execution then the `release` workflow is guaranteed to be run when the `Scope` is closed.

In addition to the `acquireRelease` operator, there is a more powerful variant called `acquireReleaseExit` that lets the finalizer depend on the `Exit` value that the `Scope` is closed with. This can be useful if we want to run a different finalizer depending on whether the `Scope` was closed with a success or a failure.

```scala mdoc:compile-only
object ZIO {
  def acquireReleaseExit[R, E, A](acquire: => ZIO[R, E, A])(release: (A, Exit[Any, Any]) => ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
    ???
}
```

There is also another family of operators to be aware of that allow the `acquire` workflow to be interrupted.

```scala mdoc:compile-only
object ZIO {
  def acquireReleaseInterruptible[R, E, A](acquire: => ZIO[R, E, A])(release: ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
    ???
  def acquireReleaseInterruptibleExit[R, E, A](acquire: => ZIO[R, E, A])(release: Exit[Any, Any] => ZIO[R, Nothing, Any]): ZIO[R with Scope, E, A] =
    ???
}
```

In this case the `release` workflow is not allowed to depend on the resource, since the `acquire` workflow might be interrupted after partially acquiring the resource. The `release` workflow is responsible for independently determining what finalization is required, for example by inspecting in-memory state.

This is a more advanced variant so we should generally use the standard `acquireRelease` operator. However, the `acquireReleaseInterruptible` operator can be very useful to describe more advanced resource acquisition scenarios where part of the acquisition can be interruptible.

## Converting Resources Into Other ZIO Data Types

We will commonly want to convert scoped resources into other ZIO data types, particularly `ZLayer` for dependency injection and `ZStream`, `ZSink`, and `ZChannel` for streaming.

We can easily do this using the `scoped` constructor on each of these data types. For example, here is how we might convert the `source` resource above into a `ZStream` of the contents:

```scala mdoc
import zio.stream._

def lines(name: => String): ZStream[Any, IOException, String] =
  ZStream.scoped(source(name)).flatMap { source =>
    ZStream.fromIteratorSucceed(source.getLines())
  }
```

Just like the `scoped` operator on `ZIO`, the `scoped` operator on `ZStream` removes the `Scope` from the environment, indicating that there are no longer any resources used by this workflow which require a scope.

The lifetime of these resources will now be governed by the lifetime of the stream, which generally means that the resources will be released as soon as we are done pulling from the stream. This lets the lifetime of these resources be managed by various stream operators to release those resources as efficiently as possible, for example releasing resources associated with each stream as soon as we are done with that stream when we merge two streams.

Similarly, we can convert a scoped resource into a `ZLayer` by using the `scoped` constructor on `ZLayer`:

```scala mdoc
def sourceLayer(name: => String): ZLayer[Any, IOException, Source] =
  ZLayer.scoped(source(name))
```

Again, the `Scope` has been removed from the environment, indicating that the lifetime of this resource will no longer be governed by the `Scope` but by the lifetime of the layer. In this case, that means the resource will be released as soon as the workflow that the layer is provided to completes execution, whether by success, failure, or interruption.

We should generally use the `scoped` operators on other ZIO data types to convert a scoped resource into a value of that data type. Having the lifetime of resources governed by the lifetime of those data types makes our code simpler and easier to reason about.

## Controlling Finalizer Ordering

By default, when a `Scope` is closed all finalizers added to that `Scope` will be closed in the reverse of the order in which those finalizers were added to the `Scope`.

Releasing resources in the reverse order in which they were acquired makes sense because a resource that was acquired first may be necessary for a later acquired resource to be closed.

For example, if we open a network connection and then open a file on a remote server we need to close the file before closing the network connection. Otherwise we would no longer be able to interact with the remote server to close the file!

Therefore, in most cases we don't have to do anything with regard to order of finalizers. However, in some cases we may want to run finalizers in parallel instead of sequentially, for example when the resources were also acquired in parallel.

For this we can use the `ZIO.parallelFinalizers` operator to indicate that finalizers should be run in parallel instead of sequentially when a scope is closed. Here is how we could use it to implement an operator that acquires and releases two resources in parallel.

```scala mdoc
def zipScoped[R <: Scope, E, A, B](
  left: ZIO[R, E, A],
  right: ZIO[R, E, B]
): ZIO[R, E, (A, B)] =
  ZIO.parallelFinalizers(left.zipPar(right))
```

The `zipPar` operator on `ZIO` takes care of acquiring the resources in parallel and the `parallelFinalizers` operator handles releasing them in parallel. This makes it easy for us to do parallel resource acquisition by leveraging the powerful concurrency operators that already exist on `ZIO`.

## Advanced Scope Operators

So far we have seen that while `Scope` is the foundation of safe and composable resource handling in ZIO, we don't actually need to work with the `Scope` data type directly other than being able to inpect the type signature to see if a workflow is scoped.

In most cases we just use the `acquireRelease` constructor or one of its variants to construct our resource and either work with the resource and close its scope using `ZIO.scoped` or convert the resource into another ZIO data type using an operator such as `ZStream.scoped` or `ZLayer.scoped`. However, for more advanced use cases we may need to work with scopes directly and `Scope` has several useful operators for helping us do so.

### Using a Scope

First, we can `use` a `Scope` by providing it to a workflow that needs a `Scope` and closing the `Scope` immediately after. This is analogous to the `ZIO.scoped` operator:

```scala
trait Closeable extends Scope {
  def use[R, E, A](zio: => ZIO[R with Scope, E, A]): ZIO[R, E, A]
}

object ZIO {
  def scoped[R, E, A](zio: => ZIO[R with Scope, E, A]): ZIO[R, E, A] = ???
}
```

In the following example, we obtained a `Scope` and added a finalizer to it, and then extended its lifetime to the lifetime of the `resource1` and `resource2`:

```scala mdoc:compile-only
import zio._

object ExtendingScopesExample extends ZIOAppDefault {
  val resource1: ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(ZIO.debug("Acquiring the resource 1"))(_ =>
      ZIO.debug("Releasing the resource one") *> ZIO.sleep(5.seconds)
    )
  val resource2: ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(ZIO.debug("Acquiring the resource 2"))(_ =>
      ZIO.debug("Releasing the resource two") *> ZIO.sleep(3.seconds)
    )

  def run =
    ZIO.scoped(
      for {
        scope <- ZIO.scope
        _     <- ZIO.debug("Entering the main scope!")
        _     <- scope.addFinalizer(ZIO.debug("Releasing the main resource!") *> ZIO.sleep(2.seconds))
        _     <- scope.extend(resource1)
        _     <- scope.extend(resource2)
        _     <- ZIO.debug("Leaving scope!")
      } yield ()
    )

}
```

output:

```
Entering the main scope!
Acquiring the resource 1
Acquiring the resource 2
Leaving scope!
Releasing the resource two
Releasing the resource one
Releasing the main resource!
```

### Extending a Scope

Second, we can use the `extend` operator on `Scope` to provide a workflow with a scope without closing it afterwards. This allows us to extend the lifetime of a scoped resource to the lifetime of a scope, effectively allowing us to "extend" the lifetime of that resource:

```scala
trait Scope {
  def extend[R, E, A](zio: => ZIO[Scope with R, E, A]): ZIO[R, E, A]
}
```

### Closing a Scope

Third, we can `close` a `Scope`. One thing to note here is that by default only the creator of a `Scope` can close it:

```scala
trait Closeable extends Scope {
  def close(exit: => Exit[Any, Any]): UIO[Unit]
}
```

Creating a new `Scope` returns a `Scope.Closeable` which can be closed. Normally users of a `Scope` will only be provided with a `Scope` which does not expose a `close` operator.

This way the creator of a `Scope` can be sure that someone else will not "pull the rug out from under them" by closing the scope prematurely.
