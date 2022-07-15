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
```

The `addFinalizerExit` operator lets us add a finalizer to the `Scope`. The `close` operator closes the scope, running all the finalizers that have been added to the scope.

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

When we are done working with the file we can close the scope using the `ZIO.scoped` operator, which creates a new `Scope`, provides it to the workflow, and closes the `Scope` when the workflow is done.

```scala mdoc
def contents(name: => String): ZIO[Any, IOException, Chunk[String]] =
  ZIO.scoped {
    source(name).flatMap { source =>
      ZIO.attemptBlockingIO(Chunk.fromIterator(source.getLines()))
    }
  }
```

The `scoped` operator removes the `Scope` from the environment, indicating that there are no longer any resources used by this workflow which require a scope. We now have a workflow that is ready to run.

In some cases ZIO applications may provide a `Scope` for us for resources that we don't specify a scope for. For example `ZIOApp` provides a `Scope` for our entire application and ZIO Test provides a `Scope` for each test.

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

In most cases we just use the `acquireRelease` constructor or one of its variants to construct our resource and either work with the resource and close its scope using `ZIO.scoped` or convert the resource into another ZIO data type using an operator such as `ZSteam.scoped` or `ZLayer.scoped`. However, for more advanced use cases we may need to work with scopes directly and `Scope` has several useful operators for helping us do so.

First, we can `use` a `Scope` by providing it to a workflow that needs a `Scope` and closing the `Scope` immediately after. This is analogous to the `ZIO.scoped` operator.

Second, we can use the `extend` operator on `Scope` to provide a workflow with a scope without closing it afterwards. This allows us to extend the lifetime of a scoped resource to the lifetime of a scope, effectively allowing us to "extend" the lifetime of that resource.

Third, we can `close` a `Scope`. One thing to note here is that by default only the creator of a `Scope` can close it.

Creating a new `Scope` returns a `Scope.Closeable` which can be closed. Normally users of a `Scope` will only be provided with a `Scope` which does not expose a `close` operator.

This way the creator of a `Scope` can be sure that someone else will not "pull the rug out from under them" by closing the scope prematurely.
