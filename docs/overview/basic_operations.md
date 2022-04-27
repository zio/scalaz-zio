---
id: overview_basic_operations
title:  "Basic Operations"
---

```scala mdoc:invisible
import zio._
import zio.Console._
import java.io.IOException
```

## Mapping

You can map over the success channel of an effect by calling the `ZIO#map` method. This lets you transform the success values of effects.

```scala mdoc:silent
import zio._

val succeeded: UIO[Int] = ZIO.succeed(21).map(_ * 2)
```

You can map over the error channel of an effect by calling the `ZIO#mapError` method. This lets you transform the failure values of effects.

```scala mdoc:silent
val failed: IO[Exception, Unit] = 
  ZIO.fail("No no!").mapError(msg => new Exception(msg))
```

Note that mapping over an effect's success or error channel does not change the success or failure of the effect, in the same way that mapping over an `Either` does not change whether the `Either` is `Left` or `Right`.

## Chaining

You can execute two effects sequentially with the `flatMap` method; this requires that you pass a callback, which will receive the value of the first effect and can return a second effect that depends on this value:

```scala mdoc:silent
val sequenced: ZIO[Console, IOException, Unit] =
  readLine.flatMap(input => printLine(s"You entered: $input"))
```

If the first effect fails, the callback passed to `flatMap` will never be invoked and the composed effect returned by `flatMap` will also fail.

In _any_ chain of effects, the first failure will short-circuit the whole chain, just like throwing an exception will prematurely exit a sequence of statements.

## For Comprehensions

Because the `ZIO` data type supports both `flatMap` and `map`, you can use Scala's _for comprehensions_ to build sequential effects:

```scala mdoc:silent
val program: ZIO[Console, IOException, Unit] =
  for {
    _    <- printLine("Hello! What is your name?")
    name <- readLine
    _    <- printLine(s"Hello, ${name}, welcome to ZIO!")
  } yield ()
```

_For comprehensions_ provide a more procedural syntax for composing chains of effects.

## Zipping

You can combine two effects into a single effect with the `ZIO#zip` method. The resulting effect succeeds with a tuple that contains the success values of both effects:

```scala mdoc:silent
val zipped: UIO[(String, Int)] = 
  ZIO.succeed("4").zip(ZIO.succeed(2))
```

Note that `zip` operates sequentially: the effect on the left side is executed before the effect on the right side.

In any `zip` operation, if either the left or right-hand side fails, the composed effect will fail because _both_ values are required to construct the tuple.

Sometimes, when the success value of an effect is not useful (for example, if it is `Unit`), it can be more convenient to use the `ZIO#zipLeft` or `ZIO#zipRight` functions, which first perform a `zip` and then map over the tuple to discard one side or the other:

```scala mdoc:silent
val zipRight1: ZIO[Console, IOException, String] =
  printLine("What is your name?").zipRight(readLine)
```

The `zipRight` and `zipLeft` functions have symbolic aliases, known as `*>` and `<*`, respectively. Some developers find these operators easier to read:

```scala mdoc:silent
val zipRight2: ZIO[Console, IOException, String] =
  printLine("What is your name?") *>
    readLine
```

## Next Step

If you are comfortable with the basic operations on ZIO effects, the next step is to learn about [error handling](handling_errors.md).
