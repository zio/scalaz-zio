---
id: with-java
title: "How to Interop with Java?"
---

ZIO has full interoperability with foreign Java code. Let me show you how it works and then *BOOM*, tomorrow you can show off your purely functional Java at work.

ZIO has built-in conversion between ZIO data types (like `ZIO` and `Fiber`) and Java concurrent data types like [`CompletionStage`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletionStage.html), [`Future`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Future.html) and [`CompletionHandler`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/CompletionHandler.html).
 
## From Java CompletionStage and back

`CompletionStage` is the interface that comes closest to emulate a functional asynchronous effects API like ZIO's, so we start with it. It's a breeze:

```scala
def loggedStage[A](stage: => CompletionStage[A]): Task[A] =
    ZIO.fromCompletionStage(UIO {
        stage.thenApplyAsync { a =>
            println("Stage completed with " + a)
            a
        }
    })
```

By Jove, you can even turn it into fiber!

```scala
def stageToFiber[A](stage: => CompletionStage[A]): Fiber[Throwable, A] = 
  Fiber.fromCompletionStage(future)
````

This API creates a synthetic fiber which doesn't have any notion of identity.

Additionally, you may want to go the other way and convert a ZIO value into a `CompletionStage`. Easy as pie:

```scala
def taskToStage[A](task: Task[A]): UIO[CompletableFuture[A]] =
    task.toCompletableFuture
```

As you can see, it commits to a concrete class implementing the `CompletionStage` interface, i.e. `CompletableFuture`. It is worth to point out that any `IO[E, A]` can be turned into a completable future provided you can turn a value of type `E` into a `Throwable`:

```scala
def ioToStage[E, A](io: IO[E, A])(toThrowable: E => Throwable): UIO[CompletableFuture[A]] =
    io.toCompletableFutureWith(toThrowable)
```

## Java Future

You can embed any `java.util.concurrent.Future` in a ZIO computation via `ZIO.fromFutureJava`. A toy wrapper around Apache Async HTTP client could look like:

```scala
def execute(client: HttpAsyncClient, request: HttpUriRequest): RIO[Blocking, HttpResponse] =
    ZIO.fromFutureJava(UIO {
        client.execute(request, null)
    })
```

That's it. Just a bit of a warning here, mate. As you can see from the requirement on the produced value, ZIO uses the blocking `Future#get` call internally. It is running on the blocking thread pool, of course, but I thought you should know. If possible, use `ZIO.fromCompletionStage` instead, as detailed above.

Should you need it, it is also possible to convert a future into a fiber using `Fiber.fromFutureJava`. Same same, but different:

```scala
def execute(client: HttpAsyncClient, request: HttpUriRequest): Fiber[Throwable, HttpResponse] =
    Fiber.fromFutureJava {
        client.execute(request, null)
    }
```

## NIO Completion handler

Java libraries using channels from the NIO API for asynchronous, interruptible I/O can be hooked into by providing completion handlers. As in, reading the contents of a file:

```scala
def readFile(file: AsynchronousFileChannel): Task[Chunk[Byte]] = for {
    pos <- Ref.make(0)
    buf <- ZIO.effectTotal(ByteBuffer.allocate(1024))
    contents <- Ref.make[Chunk[Byte]](Chunk.empty)
    def go = pos.get.flatMap { p =>
        ZIO.effectAsyncWithCompletionHandler[Chunk[Byte]] { handler =>
            file.read(buf, p, buf, handler)
        }.flatMap {
            case -1 => contents.get
            case n  =>
                ZIO.effectTotal {
                    val arr = Array.ofDim[Byte](n)
                    buf.get(arr, 0, n)
                    buf.clear()
                    Chunk.fromArray(arr)
                }.flatMap { slice =>
                    contents.update(_ ++ slice)
                } *> pos.update(_ + n) *> go
        }
    }
    dump <- go
} yield dump
```

As you can see, ZIO provides a CPS-style API here which is a bit different from the two sections above, but hey still super elegant.