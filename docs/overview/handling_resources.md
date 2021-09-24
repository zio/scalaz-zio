---
id: overview_handling_resources
title:  "Handling Resources"
---

This section looks at some of the common ways to safely handle resources using ZIO.

ZIO's resource management features work across synchronous, asynchronous, concurrent, and other effect types, and provide strong guarantees even in the presence of failure, interruption, or defects in the application.

```scala mdoc:invisible
import zio._
```

## Finalizing

ZIO provides similar functionality to `try` / `finally` with the `ZIO#ensuring` method. 

Like `try` / `finally`, the `ensuring` operation guarantees that if an effect begins executing and then terminates (for whatever reason), then the finalizer will begin executing.

```scala mdoc
val finalizer = 
  UIO.succeed(println("Finalizing!"))

val finalized: IO[String, Unit] = 
  IO.fail("Failed!").ensuring(finalizer)
```

The finalizer is not allowed to fail, which means that it must handle any errors internally.

Like `try` / `finally`, finalizers can be nested, and the failure of any inner finalizer will not affect outer finalizers. Nested finalizers will be executed in reverse order, and linearly (not in parallel).

Unlike `try` / `finally`, `ensuring` works across all types of effects, including asynchronous and concurrent effects.

## Acquire Release 

A common use for `try` / `finally` is safely acquiring and releasing resources, such as new socket connections or opened files:

```scala 
val handle = openFile(name)

try {
  processFile(handle)
} finally closeFile(handle)
```

ZIO encapsulates this common pattern with `ZIO#acquireRelease`, which allows you to specify an _acquire_ effect, which acquires a resource; a _release_ effect, which releases it; and a _use_ effect, which uses the resource.

The release effect is guaranteed to be executed by the runtime system, even in the presence of errors or interruption.

```scala mdoc:invisible
import zio._
import java.io.{ File, IOException }

def openFile(s: String): IO[IOException, File] = IO.attempt(???).refineToOrDie[IOException]
def closeFile(f: File): UIO[Unit] = UIO.unit
def decodeData(f: File): IO[IOException, Unit] = IO.unit
def groupData(u: Unit): IO[IOException, Unit] = IO.unit
```

```scala mdoc:silent
val groupedFileData: IO[IOException, Unit] = 
  openFile("data.json").acquireReleaseWith(closeFile(_)) { file =>
    for {
      data    <- decodeData(file)
      grouped <- groupData(data)
    } yield grouped
  }
```

Like `ensuring`, acquire releases have compositional semantics, so if one acquire release is nested inside another acquire release, and the outer resource is acquired, then the outer release will always be called, even if, for example, the inner release fails.

## Next Steps

If you are comfortable with resource handling, then the next step is to learn about [basic concurrency](basic_concurrency.md).
