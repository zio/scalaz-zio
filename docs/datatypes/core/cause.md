---
id: cause
title: "Cause"
---

`Cause[E]` is a description of a full story of failure, which is included in an [Exit.Failure](exit.md). Many times in ZIO something can fail for a value of type `E`, but there are other ways things can fail too.

`IO[E, A]` is polymorphic in values of type `E` and we can work with any error type that we want, but there is a lot of information that is not inside an arbitrary `E` value. So as a result ZIO needs somewhere to store things like **unexpected exceptions or defects**, **stack and execution traces**, **cause of fiber interruptions**, and so forth.

## Cause Variations
`Cause` has several variations which encode all the cases:

1. `Fail[+E](value: E)` contains the cause of expected failure of type `E`.

2. `Die(value: Throwable)` contains the cause of a defect or in other words, an unexpected failure of type `Throwable`. If we have a bug in our code and something throws an unexpected exception, that information would be described inside a `Die`.

3. `Interrupt(fiberId)` contains information of the fiber id that causes fiber interruption.

4. `Traced(cause, trace)` stores stack traces and execution traces.

5. `Meta(cause, data)`

6. `Both(left, right)` & `Then(left, right)` store a composition of two parallel and sequential causes, respectively. Sometimes fibers can fail for more than one reason. If we are doing two things at once and both of them fail then we actually have two errors. Examples:
    + If we perform ZIO's analog of try-finally (e.g. ZIO#ensuring), and both of `try` and `finally` blocks fail, then their causes are encoded with `Then`.
    + If we run two parallel fibers with `zipPar` and both of them fail, then their causes are encoded with `Both`.

Let's try to create some of these causes:

```scala mdoc:silent
import zio._
for {
  failExit <- ZIO.fail("Oh! Error!").exit
  dieExit  <- ZIO.succeed(5 / 0).exit
  thenExit <- ZIO.fail("first").ensuring(ZIO.die(throw new Exception("second"))).exit
  bothExit <- ZIO.fail("first").zipPar(ZIO.die(throw new Exception("second"))).exit
  fiber    <- ZIO.sleep(1.second).fork
  _        <- fiber.interrupt
  interruptionExit <- fiber.join.exit
} yield ()
```

## Lossless Error Model
ZIO is very strict about preserving the full information related to a failure. ZIO captures all types of errors into the `Cause` data type. So its error model is lossless. It doesn't throw away any information related to the failure result. So we can figure out exactly what happened during the operation of our effects.

