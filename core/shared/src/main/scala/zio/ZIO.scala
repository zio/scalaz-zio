/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio

import zio.internal.{FiberScope, Platform}
import zio.metrics.{MetricLabel, Metrics}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.IOException
import java.util.function.IntFunction
import scala.annotation.implicitNotFound
import scala.collection.mutable.{Builder, ListBuffer}
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace
import izumi.reflect.macrortti.LightTypeTag

/**
 * A `ZIO[R, E, A]` value is an immutable value (called an "effect") that
 * describes an async, concurrent workflow. In order to be executed, the
 * workflow requires a value of type `ZEnvironment[R]`, and when executed, the
 * workflow will either produce a failure of type `E`, or a success of type `A`.
 *
 * ZIO effects may informally be thought of as functions of the following form:
 *
 * {{{
 * ZEnvironment[R] => Either[E, A]
 * }}}
 *
 * ZIO effects model resourceful interaction with the outside world, including
 * synchronous, asynchronous, concurrent, and parallel interaction.
 *
 * The async and concurrent operations of ZIO effects are powered by fibers,
 * which are lightweight, green threads that enable high scalability.
 *
 * To run an effect, you need a `Runtime`, which is capable of executing
 * effects. Runtimes bundle a thread pool together with the environment that
 * effects need.
 */
sealed trait ZIO[-R, +E, +A]
    extends Product
    with Serializable
    with ZIOPlatformSpecific[R, E, A]
    with ZIOVersionSpecific[R, E, A] {
  self =>

  /**
   * Returns a new effect that applies the specified aspect to this effect.
   * Aspects are "transformers" that modify the behavior of their input in some
   * well-defined way (for example, adding a timeout).
   */
  final def @@[LowerR <: UpperR, UpperR <: R, LowerE >: E, UpperE >: LowerE, LowerA >: A, UpperA >: LowerA](
    aspect: => ZIOAspect[LowerR, UpperR, LowerE, UpperE, LowerA, UpperA]
  )(implicit trace: Trace): ZIO[UpperR, LowerE, LowerA] =
    ZIO.suspendSucceed(aspect(self))

  /**
   * A symbolic alias for `orDie`.
   */
  final def !(implicit ev1: E <:< Throwable, ev2: CanFail[E], trace: Trace): ZIO[R, Nothing, A] =
    self.orDie

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, returning result of provided effect. If either side fails,
   * then the other side will be interrupted.
   */
  final def &>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    self.zipWithPar(that)((_, b) => b)

  /**
   * A variant of `flatMap` that ignores the value produced by this effect.
   */
  final def *>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    self.flatMap(_ => that)

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, this effect result returned. If either side fails, then the
   * other side will be interrupted.
   */
  final def <&[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, A] =
    self.zipWithPar(that)((a, _) => a)

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results into a tuple. If either side fails,
   * then the other side will be interrupted.
   */
  final def <&>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: Trace
  ): ZIO[R1, E1, zippable.Out] =
    self.zipWithPar(that)((a, b) => zippable.zip(a, b))

  /**
   * Sequences the specified effect after this effect, but ignores the value
   * produced by the effect.
   */
  final def <*[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, A] =
    self.flatMap(a => that.as(a))

  /**
   * Sequentially zips this effect with the specified effect, combining the
   * results into a tuple.
   */
  final def <*>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: Trace
  ): ZIO[R1, E1, zippable.Out] =
    self zip that

  /**
   * A symbolic alias for `orElseEither`.
   */
  final def <+>[R1 <: R, E1, B](
    that: => ZIO[R1, E1, B]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, Either[A, B]] =
    self.orElseEither(that)

  /**
   * Operator alias for `orElse`.
   */
  final def <>[R1 <: R, E2, A1 >: A](
    that: => ZIO[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, A1] =
    orElse(that)

  /**
   * A symbolic alias for `raceEither`.
   */
  final def <|>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    trace: Trace
  ): ZIO[R1, E1, Either[A, B]] =
    self.raceEither(that)

  /**
   * Returns the logical negation of the `Boolean` value returned by this
   * effect.
   */
  @deprecated("use negate", "2.0.6")
  final def unary_![R1 <: R, E1 >: E](implicit ev: A <:< Boolean, trace: Trace): ZIO[R1, E1, Boolean] =
    self.map(a => !ev(a))

  /**
   * Returns an effect that submerges the error case of an `Either` into the
   * `ZIO`. The inverse operation of `ZIO.either`.
   */
  final def absolve[E1 >: E, B](implicit ev: A IsSubtypeOfOutput Either[E1, B], trace: Trace): ZIO[R, E1, B] =
    self.flatMap(ev(_) match {
      case Right(v) => Exit.succeed(v)
      case Left(e)  => ZIO.fail(e)
    })

  /**
   * Attempts to convert defects into a failure, throwing away all information
   * about the cause of the failure.
   */
  final def absorb(implicit ev: E IsSubtypeOfError Throwable, trace: Trace): RIO[R, A] =
    absorbWith(ev)

  /**
   * Attempts to convert defects into a failure, throwing away all information
   * about the cause of the failure.
   */
  final def absorbWith(f: E => Throwable)(implicit trace: Trace): RIO[R, A] =
    self.sandbox
      .foldZIO(
        cause => Exit.failCause(Cause.fail(cause.squashWith(f))),
        ZIO.successFn
      )

  /**
   * Maps the success value of this effect to the specified constant value.
   */
  def as[B](b: => B)(implicit trace: Trace): ZIO[R, E, B] =
    self.map(_ => b)

  /**
   * Maps the success value of this effect to a left value.
   */
  final def asLeft(implicit trace: Trace): ZIO[R, E, Either[A, Nothing]] =
    map(Left(_))

  /**
   * Maps the error value of this effect to a left value.
   */
  final def asLeftError(implicit trace: Trace): ZIO[R, Either[E, Nothing], A] =
    mapError(Left(_))

  /**
   * Maps the success value of this effect to a right value.
   */
  final def asRight(implicit trace: Trace): ZIO[R, E, Either[Nothing, A]] =
    map(Right(_))

  /**
   * Maps the error value of this effect to a right value.
   */
  final def asRightError(implicit trace: Trace): ZIO[R, Either[Nothing, E], A] =
    mapError(Right(_))

  /**
   * Maps the success value of this effect to an optional value.
   */
  final def asSome(implicit trace: Trace): ZIO[R, E, Option[A]] =
    map(Some(_))

  /**
   * Maps the error value of this effect to an optional value.
   */
  final def asSomeError(implicit trace: Trace): ZIO[R, Option[E], A] =
    mapError(Some(_))

  /**
   * Returns a new effect that will not succeed with its value before first
   * waiting for the end of all child fibers forked by the effect.
   */
  final def awaitAllChildren(implicit trace: Trace): ZIO[R, E, A] =
    ensuringChildren(Fiber.awaitAll(_))

  /**
   * Returns an effect that, if evaluated, will return the cached result of this
   * effect. Cached results will expire after `timeToLive` duration.
   */
  final def cached(timeToLive: => Duration)(implicit trace: Trace): ZIO[R, Nothing, IO[E, A]] =
    cachedInvalidate(timeToLive).map(_._1)

  /**
   * Returns an effect that, if evaluated, will return the cached result of this
   * effect. Cached results will expire after `timeToLive` duration. In
   * addition, returns an effect that can be used to invalidate the current
   * cached value before the `timeToLive` duration expires.
   */
  final def cachedInvalidate(
    timeToLive0: => Duration
  )(implicit trace: Trace): ZIO[R, Nothing, (IO[E, A], UIO[Unit])] =
    ZIO.suspendSucceed {
      val timeToLive = timeToLive0

      def get(cache: Ref.Synchronized[Option[(Long, Promise[E, A])]]): ZIO[R, E, A] =
        ZIO.uninterruptibleMask { restore =>
          Clock.nanoTime.flatMap { time =>
            cache.modifyZIO {
              case Some((end, p)) if end - time > 0 =>
                Exit.succeed(p.await -> Some((end, p)))
              case _ =>
                Promise.make[E, A].map { p =>
                  val effect = self.onExit(p.done(_))
                  effect -> Some((time + timeToLive.toNanos, p))
                }
            }.flatMap(restore(_))
          }
        }

      def invalidate(cache: Ref.Synchronized[Option[(Long, Promise[E, A])]]): UIO[Unit] =
        cache.set(None)

      for {
        r     <- ZIO.environment[R]
        cache <- Ref.Synchronized.make[Option[(Long, Promise[E, A])]](None)
      } yield (get(cache).provideEnvironment(r), invalidate(cache))
    }

  /**
   * Recovers from all errors.
   *
   * {{{
   * openFile("config.json").catchAll(_ => ZIO.succeed(defaultConfig))
   * }}}
   */
  final def catchAll[R1 <: R, E2, A1 >: A](
    h: E => ZIO[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, A1] =
    self.foldZIO[R1, E2, A1](h, ZIO.successFn)

  /**
   * A version of `catchAll` that gives you the (optional) trace of the error.
   */
  final def catchAllTrace[R1 <: R, E2, A1 >: A](
    h: ((E, StackTrace)) => ZIO[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, A1] =
    self.foldTraceZIO[R1, E2, A1](h, ZIO.successFn)

  /**
   * Recovers from all errors with provided Cause.
   *
   * {{{
   * openFile("config.json").catchAllCause(_ => ZIO.succeed(defaultConfig))
   * }}}
   *
   * @see
   *   [[absorb]], [[sandbox]], [[mapErrorCause]] - other functions that can
   *   recover from defects
   */
  final def catchAllCause[R1 <: R, E2, A1 >: A](k: Cause[E] => ZIO[R1, E2, A1])(implicit
    trace: Trace
  ): ZIO[R1, E2, A1] =
    ZIO.FoldZIO(trace, self, ZIO.successFn[A], k)

  /**
   * Recovers from all defects with provided function.
   *
   * {{{
   * effect.catchSomeDefect(_ => backup())
   * }}}
   *
   * '''WARNING''': There is no sensible way to recover from defects. This
   * method should be used only at the boundary between ZIO and an external
   * system, to transmit information on a defect for diagnostic or explanatory
   * purposes.
   */
  final def catchAllDefect[R1 <: R, E1 >: E, A1 >: A](h: Throwable => ZIO[R1, E1, A1])(implicit
    trace: Trace
  ): ZIO[R1, E1, A1] =
    catchSomeDefect { case t => h(t) }

  /**
   * Recovers from all NonFatal Throwables.
   *
   * {{{
   * openFile("data.json").catchNonFatalOrDie(_ => openFile("backup.json"))
   * }}}
   */
  final def catchNonFatalOrDie[R1 <: R, E2, A1 >: A](
    h: E => ZIO[R1, E2, A1]
  )(implicit ev1: CanFail[E], ev2: E <:< Throwable, trace: Trace): ZIO[R1, E2, A1] = {

    def hh(e: E) =
      ZIO.isFatalWith(isFatal => if (isFatal(e)) ZIO.die(e) else h(e))
    self.foldZIO[R1, E2, A1](hh, ZIO.successFn)
  }

  /**
   * Recovers from some or all of the error cases.
   *
   * {{{
   * openFile("data.json").catchSome {
   *   case _: FileNotFoundException => openFile("backup.json")
   * }
   * }}}
   */
  final def catchSome[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[E, ZIO[R1, E1, A1]]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      c.failureOrCause.fold(t => pf.applyOrElse(t, (_: E) => Exit.failCause(c)), Exit.failCause)

    self.foldCauseZIO[R1, E1, A1](tryRescue, ZIO.successFn)
  }

  /**
   * A version of `catchSome` that gives you the trace of the error.
   */
  final def catchSomeTrace[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[(E, StackTrace), ZIO[R1, E1, A1]]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      c.failureTraceOrCause.fold(t => pf.applyOrElse(t, (_: (E, StackTrace)) => Exit.failCause(c)), Exit.failCause)

    self.foldCauseZIO[R1, E1, A1](tryRescue, ZIO.successFn)
  }

  /**
   * Recovers from some or all of the error cases with provided cause.
   *
   * {{{
   * openFile("data.json").catchSomeCause {
   *   case c if (c.interrupted) => openFile("backup.json")
   * }
   * }}}
   */
  final def catchSomeCause[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[Cause[E], ZIO[R1, E1, A1]]
  )(implicit trace: Trace): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      pf.applyOrElse(c, (_: Cause[E]) => Exit.failCause(c))

    self.foldCauseZIO[R1, E1, A1](tryRescue, ZIO.successFn)
  }

  /**
   * Recovers from some or all of the defects with provided partial function.
   *
   * {{{
   * effect.catchSomeDefect {
   *   case _: SecurityException => backup()
   * }
   * }}}
   *
   * '''WARNING''': There is no sensible way to recover from defects. This
   * method should be used only at the boundary between ZIO and an external
   * system, to transmit information on a defect for diagnostic or explanatory
   * purposes.
   */
  final def catchSomeDefect[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[Throwable, ZIO[R1, E1, A1]]
  )(implicit trace: Trace): ZIO[R1, E1, A1] =
    unrefineWith(pf)(ZIO.failFn).catchAll(identity)

  /**
   * Returns an effect that succeeds with the cause of failure of this effect,
   * or `Cause.empty` if the effect did succeed.
   */
  final def cause(implicit trace: Trace): URIO[R, Cause[E]] =
    self.foldCause(c => c, _ => Cause.empty)

  /**
   * Fail with `e` if the supplied `PartialFunction` does not match, otherwise
   * succeed with the returned value.
   */
  final def collect[E1 >: E, B](e: => E1)(pf: PartialFunction[A, B])(implicit trace: Trace): ZIO[R, E1, B] =
    collectZIO(e)(pf.andThen(ZIO.successFn))

  /**
   * Fail with `e` if the supplied `PartialFunction` does not match, otherwise
   * continue with the returned value.
   */
  final def collectZIO[R1 <: R, E1 >: E, B](e: => E1)(pf: PartialFunction[A, ZIO[R1, E1, B]])(implicit
    trace: Trace
  ): ZIO[R1, E1, B] =
    self.flatMap(v => pf.applyOrElse[A, ZIO[R1, E1, B]](v, _ => ZIO.fail(e)))

  /**
   * Returns a new effect that will not supervise any fibers forked by this
   * effect.
   */
  final def daemonChildren(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.forkScopeOverride.locally(Some(FiberScope.global))(self)

  /**
   * Taps the effect, printing the result of calling `.toString` on the value.
   */
  final def debug(implicit trace: Trace): ZIO[R, E, A] =
    self
      .tap(value => ZIO.succeed(println(value)))
      .tapErrorCause(error => ZIO.succeed(println(s"<FAIL> $error")))

  /**
   * Taps the effect, printing the result of calling `.toString` on the value.
   * Prefixes the output with the given message.
   */
  final def debug(prefix: => String)(implicit trace: Trace): ZIO[R, E, A] =
    self
      .tap(value => ZIO.succeed(println(s"$prefix: $value")))
      .tapErrorCause(error => ZIO.succeed(println(s"<FAIL> $prefix: $error")))

  /**
   * Returns an effect that is delayed from this effect by the specified
   * [[zio.Duration]].
   */
  final def delay(duration: => Duration)(implicit trace: Trace): ZIO[R, E, A] =
    Clock.sleep(duration) *> self

  /**
   * Returns a new workflow that executes this one and captures the changes in
   * `FiberRef` values.
   */
  def diffFiberRefs(implicit trace: Trace): ZIO[R, E, (FiberRefs.Patch, A)] =
    summarized(ZIO.getFiberRefs)(FiberRefs.Patch.diff)

  /**
   * Returns an effect that is always interruptible, but whose interruption will
   * be performed in the background.
   *
   * This method is useful to create "fast interrupting" effects. For example,
   * if you call this on an acquire release effect, then even if the effect is
   * "stuck" in acquire or release, its interruption will return immediately,
   * while the acquire / release are performed in the background.
   *
   * See timeout and race for other applications.
   */
  final def disconnect(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.uninterruptibleMask(restore =>
      ZIO.fiberIdWith(fiberId =>
        for {
          fiber <- restore(self).forkDaemon
          a     <- restore(fiber.join).onInterrupt(fiber.interruptAsFork(fiberId))
        } yield a
      )
    )

  /**
   * Returns an effect whose failure and success have been lifted into an
   * `Either`. The resulting effect cannot fail, because the failure case has
   * been exposed as part of the `Either` success case.
   *
   * This method is useful for recovering from `ZIO` effects that may fail.
   *
   * The error parameter of the returned `ZIO` is `Nothing`, since it is
   * guaranteed the `ZIO` effect does not model failure.
   */
  final def either(implicit ev: CanFail[E], trace: Trace): URIO[R, Either[E, A]] =
    self.foldZIO(ZIO.succeedLeft, ZIO.succeedRight)

  /**
   * Returns an effect that, if this effect _starts_ execution, then the
   * specified `finalizer` is guaranteed to begin execution, whether this effect
   * succeeds, fails, or is interrupted.
   *
   * For use cases that need access to the effect's result, see [[ZIO#onExit]].
   *
   * Finalizers offer very powerful guarantees, but they are low-level, and
   * should generally not be used for releasing resources. For higher-level
   * logic built on `ensuring`, see `ZIO#acquireReleaseWith`.
   */
  final def ensuring[R1 <: R](finalizer: => URIO[R1, Any])(implicit trace: Trace): ZIO[R1, E, A] =
    onExit(_ => finalizer)

  /**
   * Acts on the children of this fiber (collected into a single fiber),
   * guaranteeing the specified callback will be invoked, whether or not this
   * effect succeeds.
   */
  final def ensuringChild[R1 <: R](f: Fiber[Any, Iterable[Any]] => ZIO[R1, Nothing, Any])(implicit
    trace: Trace
  ): ZIO[R1, E, A] =
    ensuringChildren(children => f(Fiber.collectAll(children)))

  /**
   * Acts on the children of this fiber, guaranteeing the specified callback
   * will be invoked, whether or not this effect succeeds.
   */
  def ensuringChildren[R1 <: R](
    children: Chunk[Fiber.Runtime[Any, Any]] => ZIO[R1, Nothing, Any]
  )(implicit trace: Trace): ZIO[R1, E, A] =
    Supervisor
      .track(true)
      .flatMap(supervisor => self.supervised(supervisor).ensuring(supervisor.value.flatMap(children)))

  /**
   * Returns an effect that ignores errors and runs repeatedly until it
   * eventually succeeds.
   */
  final def eventually(implicit ev: CanFail[E], trace: Trace): URIO[R, A] =
    self <> ZIO.yieldNow *> eventually

  /**
   * Returns an effect that semantically runs the effect on a fiber, producing
   * an [[zio.Exit]] for the completion value of the fiber.
   */
  final def exit(implicit trace: Trace): URIO[R, Exit[E, A]] =
    self.foldCause(Exit.failCause, Exit.succeed(_))

  /**
   * Extracts this effect as an [[zio.Exit]] and then applies the provided
   * function to produce a new effect
   */
  final def exitWith[R1 <: R, E1, B](f: Exit[E, A] => ZIO[R1, E1, B])(implicit
    trace: Trace
  ): ZIO[R1, E1, B] =
    self.foldCauseZIO(c => f(Exit.failCause(c)), a => f(Exit.succeed(a)))

  /**
   * Maps this effect to the default exit codes.
   */
  final def exitCode(implicit trace: Trace): URIO[R, ExitCode] =
    self.foldCause(
      _ => ExitCode.failure,
      _ => ExitCode.success
    )

  /**
   * Dies with specified `Throwable` if the predicate fails.
   */
  final def filterOrDie(p: A => Boolean)(t: => Throwable)(implicit trace: Trace): ZIO[R, E, A] =
    self.filterOrElse(p)(ZIO.die(t))

  /**
   * Dies with a [[java.lang.RuntimeException]] having the specified text
   * message if the predicate fails.
   */
  final def filterOrDieMessage(p: A => Boolean)(message: => String)(implicit trace: Trace): ZIO[R, E, A] =
    self.filterOrElse(p)(ZIO.dieMessage(message))

  /**
   * Dies with `t` if the predicate fails.
   */
  final def filterOrDieWith(p: A => Boolean)(t: A => Throwable)(implicit trace: Trace): ZIO[R, E, A] =
    self.filterOrElseWith(p)(a => ZIO.die(t(a)))

  /**
   * Supplies `zio` if the predicate fails.
   */
  final def filterOrElse[R1 <: R, E1 >: E, A1 >: A](p: A => Boolean)(zio: => ZIO[R1, E1, A1])(implicit
    trace: Trace
  ): ZIO[R1, E1, A1] =
    filterOrElseWith[R1, E1, A1](p)(_ => zio)

  /**
   * Applies `f` if the predicate fails.
   */
  final def filterOrElseWith[R1 <: R, E1 >: E, A1 >: A](
    p: A => Boolean
  )(f: A => ZIO[R1, E1, A1])(implicit trace: Trace): ZIO[R1, E1, A1] =
    self.flatMap {
      case v if !p(v) => f(v)
      case v          => Exit.succeed(v)
    }

  /**
   * Fails with `e` if the predicate fails.
   */
  final def filterOrFail[E1 >: E](p: A => Boolean)(e: => E1)(implicit trace: Trace): ZIO[R, E1, A] =
    filterOrElse[R, E1, A](p)(ZIO.fail(e))

  /**
   * Returns an effect that runs this effect and in case of failure, runs each
   * of the specified effects in order until one of them succeeds.
   */
  final def firstSuccessOf[R1 <: R, E1 >: E, A1 >: A](rest: => Iterable[ZIO[R1, E1, A1]])(implicit
    trace: Trace
  ): ZIO[R1, E1, A1] =
    ZIO.firstSuccessOf(self, rest)

  /**
   * Returns an effect that models the execution of this effect, followed by the
   * passing of its value to the specified continuation function `k`, followed
   * by the effect that it returns.
   *
   * {{{
   * val parsed = readFile("foo.txt").flatMap(file => parseFile(file))
   * }}}
   */
  def flatMap[R1 <: R, E1 >: E, B](k: A => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    ZIO.FlatMap(trace, self, k)

  /**
   * Creates a composite effect that represents this effect followed by another
   * one that may depend on the error produced by this one.
   *
   * {{{
   * val parsed = readFile("foo.txt").flatMapError(error => logErrorToFile(error))
   * }}}
   */
  final def flatMapError[R1 <: R, E2](
    f: E => URIO[R1, E2]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, A] =
    flipWith(_ flatMap f)

  /**
   * Returns an effect that performs the outer effect first, followed by the
   * inner effect, yielding the value of the inner effect.
   *
   * This method can be used to "flatten" nested effects.
   */
  final def flatten[R1 <: R, E1 >: E, B](implicit
    ev1: A IsSubtypeOfOutput ZIO[R1, E1, B],
    trace: Trace
  ): ZIO[R1, E1, B] =
    self.flatMap(a => ev1(a))

  /**
   * Returns an effect that swaps the error/success cases. This allows you to
   * use all methods on the error channel, possibly before flipping back.
   */
  final def flip(implicit trace: Trace): ZIO[R, A, E] =
    self.foldZIO(ZIO.successFn, ZIO.failFn)

  /**
   * Swaps the error/value parameters, applies the function `f` and flips the
   * parameters back
   */
  final def flipWith[R1, A1, E1](f: ZIO[R, A, E] => ZIO[R1, A1, E1])(implicit trace: Trace): ZIO[R1, E1, A1] =
    f(self.flip).flip

  /**
   * Folds over the failure value or the success value to yield an effect that
   * does not fail, but succeeds with the value returned by the left or right
   * function passed to `fold`.
   */
  def fold[B](failure: E => B, success: A => B)(implicit ev: CanFail[E], trace: Trace): URIO[R, B] =
    foldZIO(e => ZIO.succeed(failure(e)), a => ZIO.succeed(success(a)))

  /**
   * A more powerful version of `fold` that allows recovering from any kind of
   * failure except external interruption.
   */
  def foldCause[B](failure: Cause[E] => B, success: A => B)(implicit trace: Trace): URIO[R, B] =
    foldCauseZIO(c => ZIO.succeed(failure(c)), a => ZIO.succeed(success(a)))

  /**
   * A more powerful version of `foldZIO` that allows recovering from any kind
   * of failure except external interruption.
   */
  def foldCauseZIO[R1 <: R, E2, B](
    failure: Cause[E] => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  )(implicit trace: Trace): ZIO[R1, E2, B] =
    ZIO.FoldZIO(trace, self, success, failure)

  /**
   * A version of `foldZIO` that gives you the trace of the error.
   */
  final def foldTraceZIO[R1 <: R, E2, B](
    failure: ((E, StackTrace)) => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  )(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R1, E2, B] =
    foldCauseZIO(c => c.failureTraceOrCause.fold(failure, Exit.failCause), success)

  /**
   * Recovers from errors by accepting one effect to execute for the case of an
   * error, and one effect to execute for the case of success.
   *
   * This method has better performance than `either` since no intermediate
   * value is allocated and does not require subsequent calls to `flatMap` to
   * define the next effect.
   *
   * The error parameter of the returned `IO` may be chosen arbitrarily, since
   * it will depend on the `IO`s returned by the given continuations.
   */
  final def foldZIO[R1 <: R, E2, B](failure: E => ZIO[R1, E2, B], success: A => ZIO[R1, E2, B])(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R1, E2, B] =
    foldCauseZIO(c => c.failureOrCause.fold(failure, Exit.failCause), success)

  /**
   * Returns a new effect that will pass the success value of this effect to the
   * provided callback. If this effect fails, then the failure will be ignored.
   */
  final def forEachZIO[R1 <: R, E2, B](f: A => ZIO[R1, E2, B])(implicit trace: Trace): ZIO[R1, E2, Option[B]] =
    self.foldCauseZIO(_ => ZIO.none, a => f(a).map(Some(_)))

  final def forever(implicit trace: Trace): ZIO[R, E, Nothing] =
    ZIO.suspendSucceed {
      ZIO
        .whileLoop(true)(self)(ZIO.unitFn)
        .asInstanceOf[ZIO[R, E, Nothing]]
    }

  /**
   * Returns an effect that forks this effect into its own separate fiber,
   * returning the fiber immediately, without waiting for it to begin executing
   * the effect.
   *
   * You can use the `fork` method whenever you want to execute an effect in a
   * new fiber, concurrently and without "blocking" the fiber executing other
   * effects. Using fibers can be tricky, so instead of using this method
   * directly, consider other higher-level methods, such as `raceWith`,
   * `zipPar`, and so forth.
   *
   * The fiber returned by this method has methods to interrupt the fiber and to
   * wait for it to finish executing the effect. See [[zio.Fiber]] for more
   * information.
   *
   * Whenever you use this method to launch a new fiber, the new fiber is
   * attached to the parent fiber's scope. This means when the parent fiber
   * terminates, the child fiber will be terminated as well, ensuring that no
   * fibers leak. This behavior is called "auto supervision", and if this
   * behavior is not desired, you may use the [[forkDaemon]] or [[forkIn]]
   * methods.
   *
   * {{{
   * for {
   *   fiber <- subtask.fork
   *   // Do stuff...
   *   a <- fiber.join
   * } yield a
   * }}}
   */
  final def fork(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]] =
    self.forkWithScopeOverride(null)

  /**
   * Forks the effect in the specified scope. The fiber will be interrupted when
   * the scope is closed.
   */
  final def forkIn(scope: => Scope)(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]] =
    ZIO.uninterruptibleMask { restore =>
      def interrupt(fiber: Fiber.Runtime[Any, Any]): ZIO[Any, Nothing, Any] =
        ZIO.fiberIdWith { fiberId =>
          if (fiberId == fiber.id) Exit.unit else fiber.interrupt
        }

      scope.fork.flatMap { child =>
        restore(self).onExit(child.close(_)).forkDaemon.tap { fiber =>
          child.addFinalizer(interrupt(fiber))
        }
      }
    }

  /**
   * Forks the effect into a new fiber attached to the global scope. Because the
   * new fiber is attached to the global scope, when the fiber executing the
   * returned effect terminates, the forked fiber will continue running.
   */
  final def forkDaemon(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]] =
    self.forkWithScopeOverride(FiberScope.global)

  /**
   * Forks the fiber in a [[Scope]], interrupting it when the scope is closed.
   */
  final def forkScoped(implicit trace: Trace): ZIO[R with Scope, Nothing, Fiber.Runtime[E, A]] =
    ZIO.scopeWith(scope => self.forkIn(scope))

  /**
   * Like fork but handles an error with the provided handler.
   */
  final def forkWithErrorHandler[R1 <: R](handler: E => URIO[R1, Any])(implicit
    trace: Trace
  ): URIO[R1, Fiber.Runtime[E, A]] =
    onError(c => c.failureOrCause.fold(handler, Exit.failCause)).fork

  private[zio] final def forkWithScopeOverride(
    scopeOverride: FiberScope
  )(implicit trace: Trace): URIO[R, Fiber.Runtime[E, A]] =
    ZIO.withFiberRuntime[R, Nothing, Fiber.Runtime[E, A]] { (parentFiber, parentStatus) =>
      val f = ZIO.succeed(
        ZIO.unsafe.fork(trace, self, parentFiber, parentStatus.runtimeFlags, scopeOverride)(Unsafe)
      )
      if (parentFiber.shouldYieldBeforeFork()) ZIO.yieldNow *> f else f
    }

  /**
   * Unwraps the optional error, defaulting to the provided value.
   */
  final def flattenErrorOption[E1, E2 <: E1](default: => E2)(implicit
    ev: E IsSubtypeOfError Option[E1],
    trace: Trace
  ): ZIO[R, E1, A] =
    self.mapError(e => ev(e).getOrElse(default))

  /**
   * Returns a successful effect with the head of the list if the list is
   * non-empty or fails with the error `None` if the list is empty.
   */
  final def head[B](implicit ev: A IsSubtypeOfOutput List[B], trace: Trace): ZIO[R, Option[E], B] =
    self.foldZIO(
      e => Exit.fail(Some(e)),
      a => ev(a).headOption.fold[ZIO[R, Option[E], B]](Exit.failNone)(ZIO.successFn)
    )

  /**
   * Returns a new effect that ignores the success or failure of this effect.
   */
  final def ignore(implicit trace: Trace): URIO[R, Unit] =
    self.fold(ZIO.unitFn, ZIO.unitFn)

  /**
   * Returns a new effect that ignores the success or failure of this effect,
   * but which also logs failures at the Debug level, just in case the failure
   * turns out to be important.
   */
  final def ignoreLogged(implicit trace: Trace): URIO[R, Unit] =
    self.foldCauseZIO(
      cause =>
        ZIO.logLevel(LogLevel.Debug) {
          ZIO.logCause("An error was silently ignored because it is not anticipated to be useful", cause)
        },
      _ => Exit.unit
    )

  /**
   * Returns a new effect that will not succeed with its value before first
   * interrupting all child fibers forked by the effect.
   */
  final def interruptAllChildren(implicit trace: Trace): ZIO[R, E, A] =
    ensuringChildren(Fiber.interruptAll(_))

  /**
   * Returns a new effect that performs the same operations as this effect, but
   * interruptibly, even if composed inside of an uninterruptible region.
   *
   * Note that effects are interruptible by default, so this function only has
   * meaning if used within an uninterruptible region.
   *
   * WARNING: This operator "punches holes" into effects, allowing them to be
   * interrupted in unexpected places. Do not use this operator unless you know
   * exactly what you are doing. Instead, you should use
   * [[ZIO.uninterruptibleMask]].
   */
  final def interruptible(implicit trace: Trace): ZIO[R, E, A] =
    withRuntimeFlags(RuntimeFlags.enableInterruption)

  /**
   * Switches the interrupt status for this effect. If `true` is used, then the
   * effect becomes interruptible (the default), while if `false` is used, then
   * the effect becomes uninterruptible. These changes are compositional, so
   * they only affect regions of the effect.
   */
  final def interruptStatus(flag: => InterruptStatus)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed {
      if (flag.isInterruptible) self.interruptible
      else self.uninterruptible
    }

  /**
   * Returns an effect that keeps or breaks a promise based on the result of
   * this effect. Synchronizes interruption, so if this effect is interrupted,
   * the specified promise will be interrupted, too.
   */
  final def intoPromise[E1 >: E, A1 >: A](p: => Promise[E1, A1])(implicit trace: Trace): URIO[R, Boolean] =
    ZIO.uninterruptibleMask(restore => restore(self).exitWith(p.done(_)))

  /**
   * Returns whether this effect is a failure.
   */
  final def isFailure(implicit trace: Trace): URIO[R, Boolean] =
    foldZIO(_ => Exit.`true`, _ => Exit.`false`)

  /**
   * Returns whether this effect is a success.
   */
  final def isSuccess(implicit trace: Trace): URIO[R, Boolean] =
    foldZIO(_ => Exit.`false`, _ => Exit.`true`)

  /**
   * "Zooms in" on the value in the `Left` side of an `Either`, moving the
   * possibility that the value is a `Right` to the error channel.
   */
  final def left[B, C](implicit ev: A IsSubtypeOfOutput Either[B, C], trace: Trace): ZIO[R, Either[E, C], B] =
    self.foldZIO(
      e => Exit.fail(Left(e)),
      a => ev(a).fold(ZIO.successFn, c => ZIO.fail(Right(c)))
    )

  /**
   * Performs the specified operation while "zoomed in" on the `Left` case of an
   * `Either`.
   */
  final def leftWith[R1, E1, A1, B, B1, C, C1](
    f: ZIO[R, Either[E, C], B] => ZIO[R1, Either[E1, C1], B1]
  )(implicit ev: A IsSubtypeOfOutput Either[B, C], trace: Trace): ZIO[R1, E1, Either[B1, C1]] =
    f(self.left).unleft

  /**
   * Logs the cause of failure of this workflow.
   */
  final def logError(implicit trace: Trace): ZIO[R, E, A] =
    logError("")

  /**
   * Logs the cause of failure of this workflow with the specified message.
   */
  final def logError(message: => String)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(self).tapErrorCause { cause =>
        ZIO.logErrorCause(message, cause)
      }
    }

  /**
   * Adjusts the label for the current logging span.
   * {{{
   * parseRequest(req).logSpan("parsing")
   * }}}
   */
  def logSpan(label: => String)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.logSpan(label)(self)

  /**
   * Returns an effect whose success is mapped by the specified `f` function.
   */
  def map[B](f: A => B)(implicit trace: Trace): ZIO[R, E, B] =
    flatMap(a => ZIO.succeed(f(a)))

  /**
   * Returns an effect whose success is mapped by the specified side effecting
   * `f` function, translating any thrown exceptions into typed failed effects.
   */
  final def mapAttempt[B](f: A => B)(implicit ev: E IsSubtypeOfError Throwable, trace: Trace): RIO[R, B] =
    foldZIO(e => Exit.fail(ev(e)), a => ZIO.attempt(f(a)))

  /**
   * Returns an effect whose failure and success channels have been mapped by
   * the specified pair of functions, `f` and `g`.
   */
  def mapBoth[E2, B](f: E => E2, g: A => B)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E2, B] =
    foldZIO(e => Exit.fail(f(e)), a => ZIO.succeed(g(a)))

  /**
   * Returns an effect with its error channel mapped using the specified
   * function. This can be used to lift a "smaller" error into a "larger" error.
   */
  def mapError[E2](f: E => E2)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E2, A] =
    self.mapErrorCause(_.map(f))

  /**
   * Returns an effect with its full cause of failure mapped using the specified
   * function. This can be used to transform errors while preserving the
   * original structure of `Cause`.
   *
   * @see
   *   [[absorb]], [[sandbox]], [[catchAllCause]] - other functions for dealing
   *   with defects
   */
  def mapErrorCause[E2](h: Cause[E] => Cause[E2])(implicit trace: Trace): ZIO[R, E2, A] =
    self.foldCauseZIO(c => Exit.failCause(h(c)), ZIO.successFn)

  /**
   * Returns an effect that, if evaluated, will return the lazily computed
   * result of this effect.
   */
  final def memoize(implicit trace: Trace): UIO[ZIO[R, E, A]] =
    for {
      promise  <- Promise.make[E, (FiberRefs.Patch, A)]
      complete <- self.diffFiberRefs.intoPromise(promise).once
    } yield complete *> promise.await.flatMap { case (patch, a) => ZIO.patchFiberRefs(patch).as(a) }

  /**
   * Returns a new effect where the error channel has been merged into the
   * success channel to their common combined type.
   */
  final def merge[A1 >: A](implicit ev1: E IsSubtypeOfError A1, ev2: CanFail[E], trace: Trace): URIO[R, A1] =
    self.foldZIO(e => Exit.succeed(ev1(e)), ZIO.successFn)

  /**
   * Returns a new effect where boolean value of this effect is negated.
   */
  final def negate(implicit ev: A IsSubtypeOfOutput Boolean, trace: Trace): ZIO[R, E, Boolean] =
    map(result => !ev(result))

  /**
   * Requires the option produced by this value to be `None`.
   */
  final def none[B](implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R, Option[E], Unit] =
    self.foldZIO(
      e => Exit.fail(Some(e)),
      a => ev(a).fold[ZIO[R, Option[E], Unit]](Exit.unit)(_ => Exit.failNone)
    )

  /**
   * Returns an effect that will be executed at most once, even if it is
   * evaluated multiple times.
   */
  final def once(implicit trace: Trace): UIO[ZIO[R, E, Unit]] =
    Ref.make(true).map(ref => self.whenZIO(ref.getAndSet(false)).unit)

  /**
   * Executes the specified success or error callback depending on the result of
   * this effect, synchronously completing the effect before returning.
   */
  final def onDone[R1 <: R](
    error: E => ZIO[R1, Nothing, Any],
    success: A => ZIO[R1, Nothing, Any]
  )(implicit trace: Trace): ZIO[R1, Nothing, Unit] =
    onDoneCause(
      _.failureOrCause.fold(error, Exit.failCause(_)),
      success
    )

  /**
   * Executes the specified success or cause-based error callback depending on
   * the result of this effect, synchronously completing the effect before
   * returning.
   */
  final def onDoneCause[R1 <: R](
    error: Cause[E] => ZIO[R1, Nothing, Any],
    success: A => ZIO[R1, Nothing, Any]
  )(implicit trace: Trace): ZIO[R1, Nothing, Unit] =
    onExit {
      case Exit.Success(value) => success(value)
      case Exit.Failure(cause) => error(cause)
    }.ignore

  /**
   * Runs the specified effect if this effect fails, providing the error to the
   * effect if it exists. The provided effect will not be interrupted.
   */
  final def onError[R1 <: R](cleanup: Cause[E] => URIO[R1, Any])(implicit trace: Trace): ZIO[R1, E, A] =
    onExit {
      case _: Exit.Success[?]  => Exit.unit
      case Exit.Failure(cause) => cleanup(cause)
    }

  /**
   * Returns an effect which is guaranteed to be executed on the specified
   * executor. The specified effect will always run on the specified executor,
   * even in the presence of asynchronous boundaries.
   *
   * This is useful when an effect must be executed somewhere, for example: on a
   * UI thread, inside a client library's thread pool, inside a blocking thread
   * pool, inside a low-latency thread pool, or elsewhere.
   *
   * The `onExecutor` function composes with the innermost `onExecutor` taking
   * priority. Use of this method does not alter the execution semantics of
   * other effects composed with this one, making it easy to compositionally
   * reason about where effects are running.
   */
  final def onExecutor(executor: => Executor)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.onExecutor(executor)(self)

  /**
   * Executes the effect on the specified `ExecutionContext` and then shifts
   * back to the default one.
   */
  final def onExecutionContext(ec: => ExecutionContext)(implicit trace: Trace): ZIO[R, E, A] =
    self.onExecutor(Executor.fromExecutionContext(ec))

  /**
   * Ensures that a cleanup functions runs, whether this effect succeeds, fails,
   * or is interrupted.
   */
  final def onExit[R1 <: R](cleanup: Exit[E, A] => URIO[R1, Any])(implicit trace: Trace): ZIO[R1, E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(self).foldCauseZIO(
        failure1 => {
          val result = Exit.failCause(failure1)
          cleanup(result).foldCauseZIO(
            failure2 => Exit.failCause(failure1 ++ failure2),
            _ => result
          )
        },
        success => {
          val result = Exit.succeed(success)

          cleanup(result).flatMap(_ => result)
        }
      )
    }

  /**
   * Runs the specified effect if this effect is interrupted.
   */
  final def onInterrupt[R1 <: R](cleanup: => URIO[R1, Any])(implicit trace: Trace): ZIO[R1, E, A] =
    onExit {
      case _: Exit.Success[?]  => Exit.unit
      case Exit.Failure(cause) => if (cause.isInterruptedOnly) cleanup else Exit.unit
    }

  /**
   * Calls the specified function, and runs the effect it returns, if this
   * effect is interrupted.
   */
  final def onInterrupt[R1 <: R](cleanup: Set[FiberId] => URIO[R1, Any])(implicit trace: Trace): ZIO[R1, E, A] =
    // TODO: isInterrupted or isInterruptedOnly?
    onExit {
      case _: Exit.Success[?]  => Exit.unit
      case Exit.Failure(cause) => if (cause.isInterruptedOnly) cleanup(cause.interruptors) else Exit.unit
    }

  /**
   * Runs the specified effect if this effect is terminated, either because of a
   * defect or because of interruption.
   */
  final def onTermination[R1 <: R](
    cleanup: Cause[Nothing] => URIO[R1, Any]
  )(implicit trace: Trace): ZIO[R1, E, A] =
    onExit {
      case _: Exit.Success[?] => Exit.unit
      case Exit.Failure(cause) =>
        if (cause.isFailure) Exit.unit
        else cleanup(cause.asInstanceOf[Cause[Nothing]])
    }

  /**
   * Executes this effect, skipping the error but returning optionally the
   * success.
   */
  final def option(implicit ev: CanFail[E], trace: Trace): URIO[R, Option[A]] =
    self.foldZIO(_ => Exit.none, a => Exit.succeed(Some(a)))

  /**
   * Translates effect failure into death of the fiber, making all failures
   * unchecked and not a part of the type of the effect.
   */
  final def orDie(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E], trace: Trace): URIO[R, A] =
    orDieWith(ev1)

  /**
   * Keeps none of the errors, and terminates the fiber with them, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  final def orDieWith(f: E => Throwable)(implicit ev: CanFail[E], trace: Trace): URIO[R, A] =
    mapErrorCause(_.flatMap(e => Cause.die(f(e))))

  /**
   * Unearth the unchecked failure of the effect. (opposite of `orDie`)
   * {{{
   *   val f0: Task[Unit] = ZIO.fail(new Exception("failing")).unit
   *   val f1: UIO[Unit]  = f0.orDie
   *   val f2: Task[Unit] = f1.resurrect
   * }}}
   */
  final def resurrect(implicit ev1: E IsSubtypeOfError Throwable, trace: Trace): RIO[R, A] =
    self.unrefineWith { case e => e }(ev1)

  /**
   * Executes this effect and returns its value, if it succeeds, but otherwise
   * executes the specified effect.
   */
  final def orElse[R1 <: R, E2, A1 >: A](
    that: => ZIO[R1, E2, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, A1] =
    tryOrElse(that, ZIO.successFn)

  /**
   * Returns an effect that will produce the value of this effect, unless it
   * fails, in which case, it will produce the value of the specified effect.
   */
  final def orElseEither[R1 <: R, E2, B](
    that: => ZIO[R1, E2, B]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E2, Either[A, B]] =
    tryOrElse(that.map(Right(_)), ZIO.succeedLeft)

  /**
   * Executes this effect and returns its value, if it succeeds, but otherwise
   * fails with the specified error.
   */
  def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E1, A] =
    orElse(ZIO.fail(e1))

  /**
   * Returns an effect that will produce the value of this effect, unless it
   * fails with the `None` value, in which case it will produce the value of the
   * specified effect.
   */
  final def orElseOptional[R1 <: R, E1, A1 >: A](
    that: => ZIO[R1, Option[E1], A1]
  )(implicit ev: E IsSubtypeOfError Option[E1], trace: Trace): ZIO[R1, Option[E1], A1] =
    catchAll(ev(_).fold(that)(e => Exit.fail(Some(e))))

  /**
   * Executes this effect and returns its value, if it succeeds, but otherwise
   * succeeds with the specified value.
   */
  final def orElseSucceed[A1 >: A](a1: => A1)(implicit ev: CanFail[E], trace: Trace): URIO[R, A1] =
    orElse(ZIO.succeed(a1))

  /**
   * Exposes all parallel errors in a single call
   */
  final def parallelErrors[E1 >: E](implicit trace: Trace): ZIO[R, ::[E1], A] =
    self.foldCauseZIO(
      cause =>
        cause.failures match {
          case Nil            => Exit.failCause(cause.asInstanceOf[Cause[Nothing]])
          case ::(head, tail) => Exit.failCause(Cause.fail(::[E1](head, tail), cause.trace))
        },
      ZIO.successFn
    )

  /**
   * Returns a new scoped workflow that runs finalizers added to the scope of
   * this workflow in parallel.
   */
  final def parallelFinalizers(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.parallelFinalizers(self)

  /**
   * Provides the `ZIO` effect with its required environment, which eliminates
   * its dependency on `R`.
   */
  final def provideEnvironment(r: => ZEnvironment[R])(implicit trace: Trace): IO[E, A] =
    FiberRef.currentEnvironment.locally(r)(self.asInstanceOf[ZIO[Any, E, A]])

  /**
   * Provides a layer to the ZIO effect, which translates it to another level.
   */
  final def provideLayer[E1 >: E, R0](
    layer: => ZLayer[R0, E1, R]
  )(implicit trace: Trace): ZIO[R0, E1, A] =
    ZIO.scopedWith(scope => layer.build(scope).flatMap(r => self.provideEnvironment(r)))

  /**
   * Transforms the environment being provided to this effect with the specified
   * function.
   */
  final def provideSomeEnvironment[R0](
    f: ZEnvironment[R0] => ZEnvironment[R]
  )(implicit trace: Trace): ZIO[R0, E, A] =
    ZIO.environmentWithZIO(r0 => self.provideEnvironment(f(r0)))

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val zio: ZIO[Logging with Database, Nothing, Unit] = ???
   *
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val zio2 = zio.provideSomeLayer[Database](loggingLayer)
   * }}}
   */
  final def provideSomeLayer[R0]: ZIO.ProvideSomeLayer[R0, R, E, A] =
    new ZIO.ProvideSomeLayer[R0, R, E, A](self)

  /**
   * Returns an effect that races this effect with the specified effect,
   * returning the first successful `A` from the faster side. If one effect
   * succeeds, the other will be interrupted. If neither succeeds, then the
   * effect will fail with some error.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l race r`, perform `l.disconnect race
   * r.disconnect`, which disconnects left and right interrupt signal, allowing
   * a fast return, with interruption performed in the background.
   */
  final def race[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1])(implicit trace: Trace): ZIO[R1, E1, A1] =
    ZIO.fiberIdWith { parentFiberId =>
      (self.raceWith(that))(
        (exit, right) =>
          exit.foldExitZIO[Any, E1, A1](
            cause => right.join.mapErrorCause(cause && _),
            a => right.interruptAs(parentFiberId).as(a)
          ),
        (exit, left) =>
          exit.foldExitZIO[Any, E1, A1](
            cause => left.join.mapErrorCause(_ && cause),
            a => left.interruptAs(parentFiberId).as(a)
          )
      )
    }

  @deprecated("use race", "2.0.7")
  final def raceAwait[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1])(implicit trace: Trace): ZIO[R1, E1, A1] =
    self race that

  /**
   * Returns an effect that races this effect with all the specified effects,
   * yielding the value of the first effect to succeed with a value. Losers of
   * the race will be interrupted immediately
   */
  final def raceAll[R1 <: R, E1 >: E, A1 >: A](
    ios0: => Iterable[ZIO[R1, E1, A1]]
  )(implicit trace: Trace): ZIO[R1, E1, A1] = ZIO.transplant { graft =>
    val ios = ios0

    def arbiter[E1, A1](
      fibers: List[Fiber[E1, A1]],
      winner: Fiber[E1, A1],
      promise: Promise[E1, (A1, Fiber[E1, A1])],
      fails: Ref[Int]
    )(res: Exit[E1, A1]): URIO[R1, Any] =
      res.foldExitZIO[R1, Nothing, Unit](
        e => ZIO.flatten(fails.modify((c: Int) => (if (c == 0) promise.failCause(e).unit else Exit.unit) -> (c - 1))),
        a =>
          promise
            .succeed(a -> winner)
            .flatMap(set =>
              if (set) fibers.foldLeft(ZIO.unit)((io, f) => if (f eq winner) io else io <* f.interrupt)
              else ZIO.unit
            )
      )

    for {
      done  <- Promise.make[E1, (A1, Fiber[E1, A1])]
      fails <- Ref.make[Int](ios.size)
      c <- ZIO.uninterruptibleMask { restore =>
             for {
               fs <- ZIO.foreach(self :: ios.toList)(io => graft.applyOnExit(ZIO.interruptible(io)).fork)
               _ <- fs.foldLeft[ZIO[R1, E1, Any]](ZIO.unit) { case (io, f) =>
                      io *> f.await.flatMap(arbiter(fs, f, done, fails)).fork
                    }

               inheritAll = { (res: (A1, Fiber[E1, A1])) => res._2.inheritAll.as(res._1) }

               c <- restore(done.await.flatMap(inheritAll))
                      .onInterrupt(fs.foldLeft(ZIO.unit)((io, f) => io <* f.interrupt))
             } yield c
           }
    } yield c
  }

  /**
   * Returns an effect that races this effect with the specified effect,
   * yielding the first result to complete, whether by success or failure. If
   * neither effect completes, then the composed effect will not complete.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l raceFirst r`, perform `l.disconnect
   * raceFirst r.disconnect`, which disconnects left and right interrupt signal,
   * allowing a fast return, with interruption performed in the background.
   */
  final def raceFirst[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1])(implicit
    trace: Trace
  ): ZIO[R1, E1, A1] =
    (self.exit race that.exit).unexit

  @deprecated("use raceFirst", "2.0.7")
  final def raceFirstAwait[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1])(implicit
    trace: Trace
  ): ZIO[R1, E1, A1] =
    self raceFirst that

  /**
   * Returns an effect that races this effect with the specified effect,
   * yielding the first result to succeed. If neither effect succeeds, then the
   * composed effect will fail with some error.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l raceEither r`, perform `l.disconnect
   * raceEither r.disconnect`, which disconnects left and right interrupt
   * signal, allowing the earliest possible return.
   */
  final def raceEither[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    trace: Trace
  ): ZIO[R1, E1, Either[A, B]] =
    self.map(Left(_)) race that.map(Right(_))

  /**
   * Forks this effect and the specified effect into their own fibers, and races
   * them, calling one of two specified callbacks depending on which fiber wins
   * the race. This method does not interrupt, join, or otherwise do anything
   * with the fibers. It can be considered a low-level building block for
   * higher-level operators like `race`.
   */
  private final def raceFibersWith[R1 <: R, ER, E2, B, C](right: ZIO[R1, ER, B])(
    leftWins: (Fiber.Runtime[E, A], Fiber.Runtime[ER, B]) => ZIO[R1, E2, C],
    rightWins: (Fiber.Runtime[ER, B], Fiber.Runtime[E, A]) => ZIO[R1, E2, C],
    leftScope: FiberScope = null,
    rightScope: FiberScope = null
  )(implicit trace: Trace): ZIO[R1, E2, C] =
    ZIO.withFiberRuntime[R1, E2, C] { (parentFiber, parentStatus) =>
      val graft = ZIO.Grafter(parentFiber)
      import java.util.concurrent.atomic.AtomicBoolean

      implicit val unsafe: Unsafe = Unsafe

      val parentRuntimeFlags = parentStatus.runtimeFlags

      @inline def complete[E0, E1, A, B](
        winner: Fiber.Runtime[E0, A],
        loser: Fiber.Runtime[E1, B],
        cont: (Fiber.Runtime[E0, A], Fiber.Runtime[E1, B]) => ZIO[R1, E2, C],
        ab: AtomicBoolean,
        cb: ZIO[R1, E2, C] => Any
      ): Any =
        if (ab.compareAndSet(true, false)) {
          cb(cont(winner, loser))
        }

      val raceIndicator = new AtomicBoolean(true)

      val leftFiber  = ZIO.unsafe.makeChildFiber(trace, self, parentFiber, parentRuntimeFlags, leftScope)
      val rightFiber = ZIO.unsafe.makeChildFiber(trace, right, parentFiber, parentRuntimeFlags, rightScope)

      val startLeftFiber  = leftFiber.startSuspended()
      val startRightFiber = rightFiber.startSuspended()

      ZIO
        .async[R1, E2, C](
          { cb =>
            leftFiber.addObserver { _ =>
              complete(leftFiber, rightFiber, leftWins, raceIndicator, cb)
            }

            rightFiber.addObserver { _ =>
              complete(rightFiber, leftFiber, rightWins, raceIndicator, cb)
            }

            startLeftFiber(graft.applyOnExit(self))
            startRightFiber(graft.applyOnExit(right))
          },
          leftFiber.id <> rightFiber.id
        )
    }

  /**
   * Returns an effect that races this effect with the specified effect, calling
   * the specified finisher as soon as one result or the other has been
   * computed.
   */
  final def raceWith[R1 <: R, E1, E2, B, C](that: => ZIO[R1, E1, B])(
    leftDone: (Exit[E, A], Fiber[E1, B]) => ZIO[R1, E2, C],
    rightDone: (Exit[E1, B], Fiber[E, A]) => ZIO[R1, E2, C]
  )(implicit trace: Trace): ZIO[R1, E2, C] =
    self.raceFibersWith(that)(
      (winner, loser) =>
        winner.await.flatMap {
          case exit: Exit.Success[?] =>
            winner.inheritAll *> leftDone(exit, loser)
          case exit: Exit.Failure[_] =>
            leftDone(exit, loser)
        },
      (winner, loser) =>
        winner.await.flatMap {
          case exit: Exit.Success[B] =>
            winner.inheritAll *> rightDone(exit, loser)
          case exit: Exit.Failure[E1] =>
            rightDone(exit, loser)
        }
    )

  /**
   * Keeps some of the errors, and terminates the fiber with the rest
   */
  final def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E], trace: Trace): ZIO[R, E1, A] =
    refineOrDieWith(pf)(ev1)

  /**
   * Keeps some of the errors, and terminates the fiber with the rest, using the
   * specified function to convert the `E` into a `Throwable`.
   */
  final def refineOrDieWith[E1](pf: PartialFunction[E, E1])(
    f: E => Throwable
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R, E1, A] =
    mapErrorCause(_.flatMap(pf.andThen(Cause.fail(_)).applyOrElse(_, (e: E) => Cause.die(f(e)))))

  /**
   * Fail with the returned value if the `PartialFunction` matches, otherwise
   * continue with our held value.
   */
  final def reject[E1 >: E](pf: PartialFunction[A, E1])(implicit trace: Trace): ZIO[R, E1, A] =
    rejectZIO(pf.andThen(ZIO.fail(_)))

  /**
   * Continue with the returned computation if the `PartialFunction` matches,
   * translating the successful match into a failure, otherwise continue with
   * our held value.
   */
  final def rejectZIO[R1 <: R, E1 >: E](
    pf: PartialFunction[A, ZIO[R1, E1, E1]]
  )(implicit trace: Trace): ZIO[R1, E1, A] =
    self.flatMap { v =>
      pf.andThen[ZIO[R1, E1, A]](_.flatMap(ZIO.failFn))
        .applyOrElse[A, ZIO[R1, E1, A]](v, ZIO.successFn)
    }

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure. Scheduled recurrences are in addition
   * to the first execution, so that `io.repeat(Schedule.once)` yields an effect
   * that executes `io`, and then if that succeeds, executes `io` an additional
   * time.
   */
  final def repeat[R1 <: R, B](schedule: => Schedule[R1, A, B])(implicit
    trace: Trace
  ): ZIO[R1, E, B] =
    repeatOrElse[R1, E, B](schedule, (e, _) => Exit.fail(e))

  /**
   * Returns a new effect that repeats this effect the specified number of times
   * or until the first failure. Repeats are in addition to the first execution,
   * so that `io.repeatN(1)` yields an effect that executes `io`, and then if
   * that succeeds, executes `io` an additional time.
   */
  final def repeatN(n: => Int)(implicit trace: Trace): ZIO[R, E, A] =
    self.flatMap { a =>
      var i = n
      var s = a
      ZIO
        .whileLoop(i > 0) {
          i -= 1
          self
        }(s = _)
        .as(s)
    }

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure, at which point, the failure value and
   * schedule output are passed to the specified handler.
   *
   * Scheduled recurrences are in addition to the first execution, so that
   * `io.repeat(Schedule.once)` yields an effect that executes `io`, and then if
   * that succeeds, executes `io` an additional time.
   */
  final def repeatOrElse[R1 <: R, E2, B](
    schedule: => Schedule[R1, A, B],
    orElse: (E, Option[B]) => ZIO[R1, E2, B]
  )(implicit trace: Trace): ZIO[R1, E2, B] =
    repeatOrElseEither[R1, B, E2, B](schedule, orElse).map(_.merge)

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure, at which point, the failure value and
   * schedule output are passed to the specified handler.
   *
   * Scheduled recurrences are in addition to the first execution, so that
   * `io.repeat(Schedule.once)` yields an effect that executes `io`, and then if
   * that succeeds, executes `io` an additional time.
   */
  final def repeatOrElseEither[R1 <: R, B, E2, C](
    schedule0: => Schedule[R1, A, B],
    orElse: (E, Option[B]) => ZIO[R1, E2, C]
  )(implicit trace: Trace): ZIO[R1, E2, Either[C, B]] =
    ZIO.suspendSucceed {
      val schedule = schedule0

      schedule.driver.flatMap { driver =>
        def loop(a: A): ZIO[R1, E2, Either[C, B]] =
          driver
            .next(a)
            .foldZIO(
              _ => driver.last.orDie.map(Right(_)),
              b =>
                self.foldZIO(
                  e => orElse(e, Some(b)).map(Left(_)),
                  a => loop(a)
                )
            )

        self.foldZIO(
          e => orElse(e, None).map(Left(_)),
          a => loop(a)
        )
      }
    }

  /**
   * Repeats this effect until its value satisfies the specified predicate or
   * until the first failure.
   */
  final def repeatUntil(p: A => Boolean)(implicit trace: Trace): ZIO[R, E, A] =
    repeatUntilZIO(a => ZIO.succeed(p(a)))

  /**
   * Repeats this effect until its value is equal to the specified value or
   * until the first failure.
   */
  final def repeatUntilEquals[A1 >: A](a: => A1)(implicit trace: Trace): ZIO[R, E, A1] =
    repeatUntil(_ == a)

  /**
   * Repeats this effect until its value satisfies the specified effectful
   * predicate or until the first failure.
   */
  final def repeatUntilZIO[R1 <: R](f: A => URIO[R1, Boolean])(implicit trace: Trace): ZIO[R1, E, A] =
    self.flatMap(a => f(a).flatMap(b => if (b) Exit.succeed(a) else ZIO.yieldNow *> repeatUntilZIO(f)))

  /**
   * Repeats this effect while its value satisfies the specified predicate or
   * until the first failure.
   */
  final def repeatWhile(p: A => Boolean)(implicit trace: Trace): ZIO[R, E, A] =
    repeatWhileZIO(a => ZIO.succeed(p(a)))

  /**
   * Repeats this effect for as long as its value is equal to the specified
   * value or until the first failure.
   */
  final def repeatWhileEquals[A1 >: A](a: => A1)(implicit trace: Trace): ZIO[R, E, A1] =
    repeatWhile(_ == a)

  /**
   * Repeats this effect while its value satisfies the specified effectful
   * predicate or until the first failure.
   */
  final def repeatWhileZIO[R1 <: R](f: A => URIO[R1, Boolean])(implicit trace: Trace): ZIO[R1, E, A] =
    repeatUntilZIO(e => f(e).map(!_))

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  final def replicateZIO(n: => Int)(implicit trace: Trace): ZIO[R, E, Iterable[A]] =
    ZIO.replicateZIO(n)(self)

  /**
   * Performs this effect the specified number of times, discarding the results.
   */
  final def replicateZIODiscard(n: => Int)(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.replicateZIODiscard(n)(self)

  /**
   * Retries with the specified retry policy. Retries are done following the
   * failure of the original `io` (up to a fixed maximum with `once` or `recurs`
   * for example), so that that `io.retry(Schedule.once)` means "execute `io`
   * and in case of failure, try again once".
   */
  final def retry[R1 <: R, S](
    policy: => Schedule[R1, E, S]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E, A] =
    ZIO.suspendSucceed {

      def loop(driver: Schedule.Driver[Any, R1, E, S]): ZIO[R1, E, A] =
        self.catchAllCause { cause =>
          cause.failureOrCause.fold(
            e =>
              driver
                .next(e)
                .foldZIO(
                  _ => driver.last.orDie.flatMap(_ => Exit.failCause(cause)),
                  _ => loop(driver)
                ),
            cause => Exit.failCause(cause)
          )
        }

      policy.driver.flatMap(loop(_))
    }

  /**
   * Retries this effect the specified number of times.
   */
  final def retryN(n: => Int)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed {

      def loop(n: Int): ZIO[R, E, A] =
        self.catchAllCause { cause =>
          cause.failureOrCause.fold(
            _ => if (n <= 0) Exit.failCause(cause) else ZIO.yieldNow *> loop(n - 1),
            cause => Exit.failCause(cause)
          )
        }

      loop(n)
    }

  /**
   * Retries with the specified schedule, until it fails, and then both the
   * value produced by the schedule together with the last error are passed to
   * the recovery function.
   */
  final def retryOrElse[R1 <: R, A1 >: A, S, E1](
    policy: => Schedule[R1, E, S],
    orElse: (E, S) => ZIO[R1, E1, A1]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A1] =
    retryOrElseEither(policy, orElse).map(_.merge)

  /**
   * Returns an effect that retries this effect with the specified schedule when
   * it fails, until the schedule is done, then both the value produced by the
   * schedule together with the last error are passed to the specified recovery
   * function.
   */
  final def retryOrElseEither[R1 <: R, Out, E1, B](
    schedule0: => Schedule[R1, E, Out],
    orElse: (E, Out) => ZIO[R1, E1, B]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, Either[B, A]] =
    ZIO.suspendSucceed {
      val schedule = schedule0

      def loop(driver: Schedule.Driver[Any, R1, E, Out]): ZIO[R1, E1, Either[B, A]] =
        self
          .map(Right(_))
          .catchAll(e =>
            driver
              .next(e)
              .foldZIO(
                _ => driver.last.orDie.flatMap(out => orElse(e, out).map(Left(_))),
                _ => loop(driver)
              )
          )

      schedule.driver.flatMap(loop(_))
    }

  /**
   * Retries this effect until its error satisfies the specified predicate.
   */
  final def retryUntil(f: E => Boolean)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E, A] =
    retryUntilZIO(e => ZIO.succeed(f(e)))

  /**
   * Retries this effect until its error is equal to the specified error.
   */
  final def retryUntilEquals[E1 >: E](e: => E1)(implicit ev: CanFail[E1], trace: Trace): ZIO[R, E1, A] =
    retryUntil(_ == e)

  /**
   * Retries this effect until its error satisfies the specified effectful
   * predicate.
   */
  final def retryUntilZIO[R1 <: R](
    f: E => URIO[R1, Boolean]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E, A] =
    self.catchAll(e => f(e).flatMap(b => if (b) Exit.fail(e) else ZIO.yieldNow *> retryUntilZIO(f)))

  /**
   * Retries this effect while its error satisfies the specified predicate.
   */
  final def retryWhile(f: E => Boolean)(implicit ev: CanFail[E], trace: Trace): ZIO[R, E, A] =
    retryWhileZIO(e => ZIO.succeed(f(e)))

  /**
   * Retries this effect for as long as its error is equal to the specified
   * error.
   */
  final def retryWhileEquals[E1 >: E](e: => E1)(implicit ev: CanFail[E1], trace: Trace): ZIO[R, E1, A] =
    retryWhile(_ == e)

  /**
   * Retries this effect while its error satisfies the specified effectful
   * predicate.
   */
  final def retryWhileZIO[R1 <: R](
    f: E => URIO[R1, Boolean]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E, A] =
    retryUntilZIO(e => f(e).map(!_))

  /**
   * "Zooms in" on the value in the `Right` side of an `Either`, moving the
   * possibility that the value is a `Left` to the error channel.
   */
  final def right[B, C](implicit ev: A IsSubtypeOfOutput Either[B, C], trace: Trace): ZIO[R, Either[B, E], C] =
    self.foldZIO(
      e => Exit.fail(Right(e)),
      a => ev(a).fold(b => ZIO.fail(Left(b)), ZIO.successFn)
    )

  /**
   * Performs the specified operation while "zoomed in" on the `Right` case of
   * an `Either`.
   */
  final def rightWith[R1, E1, A1, B, B1, C, C1](
    f: ZIO[R, Either[B, E], C] => ZIO[R1, Either[B1, E1], C1]
  )(implicit ev: A IsSubtypeOfOutput Either[B, C], trace: Trace): ZIO[R1, E1, Either[B1, C1]] =
    f(self.right).unright

  /**
   * Exposes the full cause of failure of this effect.
   *
   * {{{
   * final case class DomainError()
   *
   * val veryBadIO: IO[DomainError, Unit] =
   *   ZIO.succeed(5 / 0) *> ZIO.fail(DomainError())
   *
   * val caught: IO[DomainError, Unit] =
   *   veryBadIO.sandbox.mapError(_.untraced).catchAll {
   *     case Cause.Die(_: ArithmeticException) =>
   *       // Caught defect: divided by zero!
   *       ZIO.unit
   *     case Cause.Fail(_) =>
   *       // Caught error: DomainError!
   *       ZIO.unit
   *     case cause =>
   *       // Caught unknown defects, shouldn't recover!
   *       ZIO.refailCause(cause)
   *   }
   * }}}
   */
  final def sandbox(implicit trace: Trace): ZIO[R, Cause[E], A] =
    foldCauseZIO(ZIO.failFn, ZIO.successFn)

  /**
   * Runs this effect according to the specified schedule.
   *
   * See [[scheduleFrom]] for a variant that allows the schedule's decision to
   * depend on the result of this effect.
   */
  final def schedule[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: Trace
  ): ZIO[R1, E, B] =
    scheduleFrom(())(schedule)

  /**
   * Runs this effect according to the specified schedule starting from the
   * specified input value.
   */
  final def scheduleFrom[R1 <: R, A1 >: A, B](a: => A1)(
    schedule0: => Schedule[R1, A1, B]
  )(implicit trace: Trace): ZIO[R1, E, B] =
    ZIO.suspendSucceed {
      val schedule = schedule0

      schedule.driver.flatMap { driver =>
        def loop(a: A1): ZIO[R1, E, B] =
          driver.next(a).foldZIO(_ => driver.last.orDie, _ => self.flatMap(loop))

        loop(a)
      }
    }

  /**
   * Runs this effect according to the specified schedule in a new fiber
   * attached to the current scope.
   */
  final def scheduleFork[R1 <: R, B](schedule: => Schedule[R1, Any, B])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, Nothing, Fiber.Runtime[E, B]] =
    self.schedule(schedule).forkScoped

  /**
   * Returns a new scoped workflow that runs finalizers added to the scope of
   * this workflow sequentially in the reverse of the order in which they were
   * added. Note that finalizers are run sequentially by default so this only
   * has meaning if used within a scope where finalizers are being run in
   * parallel.
   */
  final def sequentialFinalizers(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.sequentialFinalizers(self)

  /**
   * Converts an option on values into an option on errors.
   */
  final def some[B](implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R, Option[E], B] =
    self.foldZIO(
      e => Exit.fail(Some(e)),
      a => ev(a).fold[ZIO[R, Option[E], B]](ZIO.fail(Option.empty[E]))(ZIO.successFn)
    )

  /**
   * Perfoms the specified operation while "zoomed in" on the `Some` case of an
   * `Option`.
   */
  final def someWith[R1, E1, A1, B, B1](
    f: ZIO[R, Option[E], B] => ZIO[R1, Option[E1], B1]
  )(implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R1, E1, Option[B1]] =
    f(self.some).unsome

  /**
   * Extracts the optional value, or returns the given 'default'. Superseded by
   * `someOrElse` with better type inference. This method was left for binary
   * compatibility.
   */
  protected final def someOrElse[B](
    default: => B
  )(implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R, E, B] =
    map(a => ev(a).getOrElse(default))

  /**
   * Extracts the optional value, or returns the given 'default'.
   */
  final def someOrElse[B, C](
    default: => C
  )(implicit ev0: A IsSubtypeOfOutput Option[B], ev1: C <:< B, trace: Trace): ZIO[R, E, B] =
    map(a => ev0(a).getOrElse(default))

  /**
   * Extracts the optional value, or executes the effect 'default'. Superseded
   * by someOrElseZIO with better type inference. This method was left for
   * binary compatibility.
   */
  protected final def someOrElseZIO[B, R1 <: R, E1 >: E](
    default: => ZIO[R1, E1, B]
  )(implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R1, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => Exit.succeed(value)
      case None        => default
    })

  /**
   * Extracts the optional value, or executes the effect 'default'.
   */
  final def someOrElseZIO[B, R1 <: R, E1 >: E, C](
    default: => ZIO[R1, E1, C]
  )(implicit ev0: A IsSubtypeOfOutput Option[B], ev1: C <:< B, trace: Trace): ZIO[R1, E1, B] =
    self.flatMap(ev0(_) match {
      case Some(value) => Exit.succeed(value)
      case None        => default.map(ev1)
    })

  /**
   * Extracts the optional value, or fails with the given error 'e'.
   */
  final def someOrFail[B, E1 >: E](
    e: => E1
  )(implicit ev: A IsSubtypeOfOutput Option[B], trace: Trace): ZIO[R, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => Exit.succeed(value)
      case None        => ZIO.fail(e)
    })

  /**
   * Extracts the optional value, or fails with a
   * [[java.util.NoSuchElementException]]
   */
  final def someOrFailException[B, E1 >: E](implicit
    ev: A IsSubtypeOfOutput Option[B],
    ev2: NoSuchElementException <:< E1,
    trace: Trace
  ): ZIO[R, E1, B] =
    self.foldCauseZIO(
      e => Exit.failCause(e),
      ev(_) match {
        case Some(value) => Exit.succeed(value)
        case None        => ZIO.fail(ev2(new NoSuchElementException("None.get")))
      }
    )

  /**
   * Companion helper to `sandbox`. Allows recovery, and partial recovery, from
   * errors and defects alike, as in:
   *
   * {{{
   * case class DomainError()
   *
   * val veryBadIO: IO[DomainError, Unit] =
   *   ZIO.succeed(5 / 0) *> ZIO.fail(DomainError())
   *
   * val caught: IO[DomainError, Unit] =
   *   veryBadIO.sandboxWith[Any, DomainError, Unit](_.catchSome {
   *     case Cause.Die(_: ArithmeticException, _)=>
   *       // Caught defect: divided by zero!
   *       ZIO.succeed(0)
   *   })
   * }}}
   *
   * Using `sandboxWith` with `catchSome` is better than using
   * `io.sandbox.catchAll` with a partial match, because in the latter, if the
   * match fails, the original defects will be lost and replaced by a
   * `MatchError`
   */
  final def sandboxWith[R1 <: R, E2, B](f: ZIO[R1, Cause[E], A] => ZIO[R1, Cause[E2], B])(implicit
    trace: Trace
  ): ZIO[R1, E2, B] =
    ZIO.unsandbox(f(self.sandbox))

  /**
   * Summarizes a effect by computing some value before and after execution, and
   * then combining the values to produce a summary, together with the result of
   * execution.
   */
  final def summarized[R1 <: R, E1 >: E, B, C](
    summary0: => ZIO[R1, E1, B]
  )(f: (B, B) => C)(implicit trace: Trace): ZIO[R1, E1, (C, A)] =
    ZIO.suspendSucceed {
      val summary = summary0

      for {
        start <- summary
        value <- self
        end   <- summary
      } yield (f(start, end), value)
    }

  /**
   * Returns an effect with the behavior of this one, but where all child fibers
   * forked in the effect are reported to the specified supervisor.
   */
  final def supervised(supervisor: => Supervisor[Any])(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.currentSupervisor.locallyWith(_ ++ supervisor)(self)

  /**
   * Returns an effect that effectfully "peeks" at the success of this effect.
   *
   * {{{
   * readFile("data.json").tap(printLine)
   * }}}
   */
  final def tap[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any])(implicit trace: Trace): ZIO[R1, E1, A] =
    self.flatMap(a => f(a).as(a))

  /**
   * Returns an effect that effectfully "peeks" at the failure or success of
   * this effect.
   * {{{
   * readFile("data.json").tapBoth(logError(_), logData(_))
   * }}}
   */
  final def tapBoth[R1 <: R, E1 >: E](f: E => ZIO[R1, E1, Any], g: A => ZIO[R1, E1, Any])(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R1, E1, A] =
    self.foldCauseZIO(c => c.failureOrCause.fold(f(_) *> Exit.failCause(c), _ => Exit.failCause(c)), a => g(a).as(a))

  /**
   * Returns an effect that effectually "peeks" at the defect of this effect.
   */
  final def tapDefect[R1 <: R, E1 >: E](f: Cause[Nothing] => ZIO[R1, E1, Any])(implicit
    trace: Trace
  ): ZIO[R1, E1, A] =
    self.catchAllCause { cause =>
      cause.keepDefects.fold[ZIO[R1, E1, A]](Exit.failCause(cause))(f(_) *> Exit.failCause(cause))
    }

  /**
   * Returns an effect that effectfully "peeks" at the result of this effect.
   * {{{
   * readFile("data.json").tapEither(result => log(result.fold("Error: " + _, "Success: " + _)))
   * }}}
   */
  final def tapEither[R1 <: R, E1 >: E](f: Either[E, A] => ZIO[R1, E1, Any])(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R1, E1, A] =
    self.foldCauseZIO(
      c => c.failureOrCause.fold(e => f(Left(e)) *> Exit.failCause(c), _ => Exit.failCause(c)),
      a => f(Right(a)).as(a)
    )

  /**
   * Returns an effect that effectfully "peeks" at the failure of this effect.
   * {{{
   * readFile("data.json").tapError(logError(_))
   * }}}
   */
  final def tapError[R1 <: R, E1 >: E](
    f: E => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A] =
    self.foldCauseZIO(c => c.failureOrCause.fold(f(_) *> Exit.failCause(c), _ => Exit.failCause(c)), ZIO.successFn)

  /**
   * Returns an effect that effectually "peeks" at the cause of the failure of
   * this effect.
   * {{{
   * readFile("data.json").tapErrorCause(logCause(_))
   * }}}
   */
  final def tapErrorCause[R1 <: R, E1 >: E](f: Cause[E] => ZIO[R1, E1, Any])(implicit
    trace: Trace
  ): ZIO[R1, E1, A] =
    self.foldCauseZIO(c => f(c) *> Exit.failCause(c), ZIO.successFn)

  /**
   * A version of `tapError` that gives you the trace of the error.
   */
  final def tapErrorTrace[R1 <: R, E1 >: E](
    f: ((E, StackTrace)) => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A] =
    self.foldCauseZIO(
      c => c.failureTraceOrCause.fold(f(_) *> Exit.failCause(c), _ => Exit.failCause(c)),
      ZIO.successFn
    )

  /**
   * Returns an effect that effectfully "peeks" at the success of this effect.
   * If the partial function isn't defined at the input, the result is
   * equivalent to the original effect.
   *
   * {{{
   * readFile("data.json").tapSome {
   *   case content if content.nonEmpty => putStrLn(content)
   * }
   * }}}
   */
  final def tapSome[R1 <: R, E1 >: E](f: PartialFunction[A, ZIO[R1, E1, Any]])(implicit
    trace: Trace
  ): ZIO[R1, E1, A] =
    self.tap(f.applyOrElse(_, (_: A) => Exit.unit))

  /**
   * Returns an effect that effectfully "peeks" at the failure of this effect.
   * If the partial function isn't defined at the input, the result is
   * equivalent to the original effect.
   */
  final def tapSomeError[R1 <: R, E1 >: E](
    f: PartialFunction[E, ZIO[R1, E1, Any]]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R1, E1, A] =
    self.tapError(f.applyOrElse(_, (_: E) => Exit.unit))

  /**
   * Returns a new effect that executes this one and times the execution.
   */
  final def timed(implicit trace: Trace): ZIO[R, E, (Duration, A)] =
    timedWith(Clock.nanoTime)

  /**
   * A more powerful variation of `timed` that allows specifying the workflow
   * that will be used to calculate the current time.
   */
  final def timedWith[R1 <: R, E1 >: E](nanoTime: => ZIO[R1, E1, Long])(implicit
    trace: Trace
  ): ZIO[R1, E1, (Duration, A)] =
    summarized(nanoTime)((start, end) => Duration.fromNanos(end - start))

  /**
   * Returns an effect that will timeout this effect, returning `None` if the
   * timeout elapses before the effect has produced a value; and returning
   * `Some` of the produced value otherwise.
   *
   * If the timeout elapses without producing a value, the running effect will
   * be safely interrupted.
   *
   * WARNING: The effect returned by this method will not itself return until
   * the underlying effect is actually interrupted. This leads to more
   * predictable resource utilization. If early return is desired, then instead
   * of using `effect.timeout(d)`, use `effect.disconnect.timeout(d)`, which
   * first disconnects the effect's interruption signal before performing the
   * timeout, resulting in earliest possible return, before an underlying effect
   * has been successfully interrupted.
   */
  final def timeout(d: => Duration)(implicit trace: Trace): ZIO[R, E, Option[A]] =
    timeoutTo(None)(Some(_))(d)

  /**
   * The same as [[timeout]], but instead of producing a `None` in the event of
   * timeout, it will produce the specified error.
   */
  final def timeoutFail[E1 >: E](e: => E1)(d: => Duration)(implicit
    trace: Trace
  ): ZIO[R, E1, A] =
    ZIO.flatten(timeoutTo(ZIO.fail(e))(ZIO.successFn)(d))

  /**
   * The same as [[timeout]], but instead of producing a `None` in the event of
   * timeout, it will produce the specified failure.
   */
  final def timeoutFailCause[E1 >: E](cause: => Cause[E1])(d: => Duration)(implicit
    trace: Trace
  ): ZIO[R, E1, A] =
    ZIO.flatten(timeoutTo(Exit.failCause(cause))(ZIO.successFn)(d))

  /**
   * Returns an effect that will timeout this effect, returning either the
   * default value if the timeout elapses before the effect has produced a
   * value; and or returning the result of applying the function `f` to the
   * success value of the effect.
   *
   * If the timeout elapses without producing a value, the running effect will
   * be safely interrupted
   *
   * {{{
   * ZIO.succeed(1).timeoutTo(None)(Some(_))(1.second)
   * }}}
   */
  final def timeoutTo[B](b: => B): ZIO.TimeoutTo[R, E, A, B] =
    new ZIO.TimeoutTo(self, () => b)

  /**
   * Converts the effect into a [[scala.concurrent.Future]].
   */
  final def toFuture(implicit ev2: E IsSubtypeOfError Throwable, trace: Trace): URIO[R, CancelableFuture[A]] =
    self toFutureWith ev2

  /**
   * Converts the effect into a [[scala.concurrent.Future]].
   */
  final def toFutureWith(f: E => Throwable)(implicit trace: Trace): URIO[R, CancelableFuture[A]] =
    self.fork.flatMap(_.toFutureWith(f))

  /**
   * When this effect succeeds with a cause, then this method returns a new
   * effect that either fails with the cause that this effect succeeded with, or
   * succeeds with unit, depending on whether the cause is empty.
   *
   * This operation is the opposite of [[cause]].
   */
  final def uncause[E1 >: E](implicit ev: A IsSubtypeOfOutput Cause[E1], trace: Trace): ZIO[R, E1, Unit] =
    self.flatMap { a =>
      val cause = ev(a)

      if (cause.isEmpty) Exit.unit
      else Exit.failCause(cause)
    }

  /**
   * Performs this effect uninterruptibly. This will prevent the effect from
   * being terminated externally, but the effect may fail for internal reasons
   * (e.g. an uncaught error) or terminate due to defect.
   *
   * Uninterruptible effects may recover from all failure causes (including
   * interruption of an inner effect that has been made interruptible).
   */
  final def uninterruptible(implicit trace: Trace): ZIO[R, E, A] =
    withRuntimeFlags(RuntimeFlags.disableInterruption)

  /**
   * Returns the effect resulting from mapping the success of this effect to
   * unit.
   */
  def unit(implicit trace: Trace): ZIO[R, E, Unit] =
    self.flatMap(ZIO.unitZIOFn)

  /**
   * Converts a `ZIO[R, Either[E, B], A]` into a `ZIO[R, E, Either[A, B]]`. The
   * inverse of `left`.
   */
  final def unleft[E1, B](implicit
    ev: E IsSubtypeOfError Either[E1, B],
    trace: Trace
  ): ZIO[R, E1, Either[A, B]] =
    self.foldZIO(
      e => ev(e).fold(e1 => ZIO.fail(e1), b => Exit.succeed(Right(b))),
      a => Exit.succeed(Left(a))
    )

  /**
   * The moral equivalent of `if (!p) Some(exp) else None`
   */
  final def unless(p: => Boolean)(implicit trace: Trace): ZIO[R, E, Option[A]] =
    ZIO.unless(p)(self)

  /**
   * The moral equivalent of `if (!p) { expr; () }`
   */
  final def unlessDiscard(p: => Boolean)(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.unlessDiscard(p)(self)

  /**
   * The moral equivalent of `if (!p) Some(exp) else None` when `p` has
   * side-effects
   */
  final def unlessZIO[R1 <: R, E1 >: E](p: => ZIO[R1, E1, Boolean])(implicit
    trace: Trace
  ): ZIO[R1, E1, Option[A]] =
    ZIO.unlessZIO(p)(self)

  /**
   * The moral equivalent of `if (!p) { expr; () }` when `p` has side-effects
   */
  final def unlessZIODiscard[R1 <: R, E1 >: E](p: => ZIO[R1, E1, Boolean])(implicit
    trace: Trace
  ): ZIO[R1, E1, Unit] =
    ZIO.unlessZIODiscard(p)(self)

  /**
   * Takes some fiber failures and converts them into errors.
   */
  final def unrefine[E1 >: E](pf: PartialFunction[Throwable, E1])(implicit trace: Trace): ZIO[R, E1, A] =
    unrefineWith(pf)(identity)

  /**
   * Takes some fiber failures and converts them into errors.
   */
  final def unrefineTo[E1 >: E: ClassTag](implicit trace: Trace): ZIO[R, E1, A] =
    unrefine { case e: E1 => e }

  /**
   * Takes some fiber failures and converts them into errors, using the
   * specified function to convert the `E` into an `E1`.
   */
  final def unrefineWith[E1](
    pf: PartialFunction[Throwable, E1]
  )(f: E => E1)(implicit trace: Trace): ZIO[R, E1, A] =
    catchAllCause { cause =>
      cause.find {
        case Cause.Die(t, _) if pf.isDefinedAt(t) => pf(t)
      }.fold(Exit.failCause(cause.map(f)))(ZIO.failFn)
    }

  /**
   * Converts a `ZIO[R, Either[B, E], A]` into a `ZIO[R, E, Either[B, A]]`. The
   * inverse of `right`.
   */
  final def unright[E1, B](implicit
    ev: E IsSubtypeOfError Either[B, E1],
    trace: Trace
  ): ZIO[R, E1, Either[B, A]] =
    self.foldZIO(
      e => ev(e).fold(b => Exit.succeed(Left(b)), e1 => Exit.fail(e1)),
      a => Exit.succeed(Right(a))
    )

  /**
   * Converts an option on errors into an option on values.
   */
  final def unsome[E1](implicit ev: E IsSubtypeOfError Option[E1], trace: Trace): ZIO[R, E1, Option[A]] =
    self.foldZIO(
      e => ev(e).fold[ZIO[R, E1, Option[A]]](Exit.none)(ZIO.failFn),
      a => Exit.succeed(Some(a))
    )

  /**
   * Updates a service in the environment of this effect.
   */
  final def updateService[M] =
    new ZIO.UpdateService[R, E, A, M](self)

  /**
   * Updates a service at the specified key in the environment of this effect.
   */
  final def updateServiceAt[Service]: ZIO.UpdateServiceAt[R, E, A, Service] =
    new ZIO.UpdateServiceAt[R, E, A, Service](self)

  final def unexit[E1 >: E, A2](implicit ev: A <:< Exit[E1, A2], trace: Trace): ZIO[R, E1, A2] =
    self.flatMap(exit => exit)

  /**
   * The inverse operation to `sandbox`. Submerges the full cause of failure.
   */
  final def unsandbox[E1](implicit ev: E IsSubtypeOfError Cause[E1], trace: Trace): ZIO[R, E1, A] =
    ZIO.unsandbox(self.mapError(ev))

  /**
   * Sequentially zips the this result with the specified result. Combines both
   * `Cause[E1]` when both effects fail.
   */
  final def validate[R1 <: R, E1 >: E, B](
    that: => ZIO[R1, E1, B]
  )(implicit zippable: Zippable[A, B], trace: Trace): ZIO[R1, E1, zippable.Out] =
    validateWith(that)(zippable.zip(_, _))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel. Combines both Cause[E1]` when both effects fail.
   */
  final def validatePar[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    trace: Trace
  ): ZIO[R1, E1, (A, B)] =
    validateWithPar(that)((_, _))

  /**
   * Sequentially zips this effect with the specified effect using the specified
   * combiner function. Combines the causes in case both effect fail.
   */
  final def validateWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C)(implicit
    trace: Trace
  ): ZIO[R1, E1, C] =
    self.exit.zipWith(that.exit)(_.zipWith(_)(f, _ ++ _)).unexit

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results with the specified `f` function. If
   * both sides fail, then the cause will be combined.
   */
  final def validateWithPar[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C)(implicit
    trace: Trace
  ): ZIO[R1, E1, C] =
    self.exit.zipWithPar(that.exit)(_.zipWith(_)(f, _ && _)).unexit

  /**
   * The moral equivalent of `if (p) Some(exp) else None`
   */
  final def when(p: => Boolean)(implicit trace: Trace): ZIO[R, E, Option[A]] =
    ZIO.when(p)(self)

  /**
   * The moral equivalent of `if (p) { expr; () }`
   */
  final def whenDiscard(p: => Boolean)(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.whenDiscard(p)(self)

  /**
   * Executes this workflow when value of the specified `FiberRef` satisfies the
   * predicate.
   */
  final def whenFiberRef[S](ref: => FiberRef[S])(f: S => Boolean)(implicit trace: Trace): ZIO[R, E, (S, Option[A])] =
    ref.getWith { s =>
      if (f(s)) self.map(a => (s, Some(a)))
      else Exit.succeed((s, None))
    }

  /**
   * Executes this workflow when the value of the `Ref` satisfies the predicate.
   */
  final def whenRef[S](ref: => Ref[S])(f: S => Boolean)(implicit trace: Trace): ZIO[R, E, (S, Option[A])] =
    ref.get.flatMap { s =>
      if (f(s)) self.map(a => (s, Some(a)))
      else Exit.succeed((s, None))
    }

  /**
   * The moral equivalent of `if (p) Some(exp) else None` when `p` has
   * side-effects
   */
  final def whenZIO[R1 <: R, E1 >: E](
    p: => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZIO[R1, E1, Option[A]] =
    ZIO.whenZIO(p)(self)

  /**
   * The moral equivalent of `if (p) { expr; () }` when `p` has side-effects
   */
  final def whenZIODiscard[R1 <: R, E1 >: E](
    p: => ZIO[R1, E1, Boolean]
  )(implicit trace: Trace): ZIO[R1, E1, Unit] =
    ZIO.whenZIODiscard(p)(self)

  /**
   * Executes this workflow with the specified implementation of the clock
   * service.
   */
  final def withClock[B <: Clock](clock: => B)(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withClock(clock)(self)

  /**
   * Executes this workflow with the specified configuration provider.
   */
  final def withConfigProvider[B <: ConfigProvider](
    configProvider: => B
  )(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withConfigProvider(configProvider)(self)

  /**
   * Executes this workflow with the specified implementation of the console
   * service.
   */
  final def withConsole[B <: Console](console: => B)(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withConsole(console)(self)

  /**
   * Returns a new scoped workflow that returns the result of this workflow as
   * well as a finalizer that can be run to close the scope of this workflow.
   */
  final def withEarlyRelease(implicit trace: Trace): ZIO[R with Scope, E, (UIO[Unit], A)] =
    ZIO.scopeWith { parent =>
      parent.fork.flatMap { child =>
        child.extend[R](self).map { a =>
          (ZIO.fiberIdWith(fiberId => child.close(Exit.interrupt(fiberId))), a)
        }
      }
    }

  /**
   * Treats this effect as the acquisition of a resource and adds the specified
   * finalizer to the current scope. This effect will be run uninterruptibly and
   * the finalizer will be run when the scope is closed.
   */
  final def withFinalizer[R1 <: R](finalizer: A => URIO[R1, Any])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E, A] =
    ZIO.acquireRelease(self)(finalizer)

  /**
   * A more powerful variant of `withFinalizer` that allows the finalizer to
   * depend on the `Exit` value that the scope is closed with.
   */
  final def withFinalizerExit[R1 <: R](finalizer: (A, Exit[Any, Any]) => URIO[R1, Any])(implicit
    trace: Trace
  ): ZIO[R1 with Scope, E, A] =
    ZIO.acquireReleaseExit(self)(finalizer)

  /**
   * Executes this workflow with the specified logger added.
   */
  final def withLogger[B <: ZLogger[String, Any]](logger: => B)(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withLogger(logger)(self)

  /**
   * Runs this effect with the specified maximum number of fibers for parallel
   * operators.
   */
  final def withParallelism(n: => Int)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withParallelism(n)(self)

  /**
   * Runs this effect with an unbounded maximum number of fibers for parallel
   * operators.
   */
  def withParallelismUnbounded(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withParallelismUnbounded(self)

  /**
   * Executes this workflow with the specified implementation of the random
   * service.
   */
  final def withRandom[B <: Random](random: => B)(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withRandom(random)(self)

  /**
   * Returns a new ZIO effect that will update the runtime flags according to
   * the specified patch within the scope of this ZIO effect.
   */
  final def withRuntimeFlags(patch: RuntimeFlags.Patch)(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.UpdateRuntimeFlagsWithin(trace, patch, _ => self)

  /**
   * Executes this workflow with the specified implementation of the system
   * service.
   */
  final def withSystem[B <: System](system: => B)(implicit tag: Tag[B], trace: Trace): ZIO[R, E, A] =
    ZIO.withSystem(system)(self)

  /**
   * A named alias for `<*>`.
   */
  final def zip[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: Trace
  ): ZIO[R1, E1, zippable.Out] =
    self.zipWith(that)((a, b) => zippable.zip(a, b))

  /**
   * A named alias for `<*`.
   */
  final def zipLeft[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, A] =
    self <* that

  /**
   * A named alias for `<&>`.
   */
  final def zipPar[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B],
    trace: Trace
  ): ZIO[R1, E1, zippable.Out] =
    self <&> that

  /**
   * A named alias for `<&`.
   */
  final def zipParLeft[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, A] =
    self <& that

  /**
   * A named alias for `&>`.
   */
  final def zipParRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    self &> that

  /**
   * A named alias for `*>`.
   */
  final def zipRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    self *> that

  /**
   * Sequentially zips this effect with the specified effect using the specified
   * combiner function.
   */
  final def zipWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C)(implicit
    trace: Trace
  ): ZIO[R1, E1, C] =
    self.flatMap(a => that.map(b => f(a, b)))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results with the specified `f` function. If
   * either side fails, then the other side will be interrupted.
   */
  final def zipWithPar[R1 <: R, E1 >: E, B, C](
    that: => ZIO[R1, E1, B]
  )(f: (A, B) => C)(implicit trace: Trace): ZIO[R1, E1, C] =
    ZIO.uninterruptibleMask { restore =>
      val that0 = that
      if (self.isInstanceOf[Exit.Success[?]]) {
        self.zipWith(restore(that0))(f)
      } else if (that0.isInstanceOf[Exit.Success[?]]) {
        restore(self).zipWith(that0)(f)
      } else
        ZIO.transplant { graft =>
          val promise = Promise.unsafe.make[Unit, Boolean](FiberId.None)(Unsafe)
          val ref     = new java.util.concurrent.atomic.AtomicBoolean(false)

          def fork[R, E, A](zio: => ZIO[R, E, A], side: Boolean): ZIO[R, Nothing, Fiber[E, A]] =
            graft
              .applyOnExitWith(restore(zio)) {
                case _: Exit.Success[?] => if (ref.getAndSet(true)) promise.unsafe.done(Exit.boolean(side))(Unsafe)
                case _                  => promise.unsafe.done(Exit.failUnit)(Unsafe)
              }
              .forkDaemon

          fork(self, false).zip(fork(that0, true)).flatMap { case (left, right) =>
            restore(promise.await).foldCauseZIO(
              cause =>
                left.interruptFork *> right.interruptFork *>
                  left.await.zip(right.await).flatMap { case (left, right) =>
                    left.zipWith(right)(f, _ && _) match {
                      case Exit.Failure(causes) => Exit.failCause(cause.stripFailures && causes)
                      case _                    => Exit.failCause(cause.stripFailures)
                    }
                  },
              leftWins =>
                if (leftWins) left.join.zipWith(right.join)((a, b) => f(a, b))
                else right.join.zipWith(left.join)((b, a) => f(a, b))
            )
          }
        }
    }

  private[this] final def tryOrElse[R1 <: R, E2, B](
    that: => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  )(implicit trace: Trace): ZIO[R1, E2, B] =
    self.foldCauseZIO(
      cause =>
        cause.keepDefects match {
          case None    => that
          case Some(c) => Exit.failCause(c)
        },
      success
    )

  private[zio] def trace: Trace
}

object ZIO extends ZIOCompanionPlatformSpecific with ZIOCompanionVersionSpecific {
  private[zio] object unsafe {
    def fork[R, E1, E2, A, B](
      trace: Trace,
      effect: ZIO[R, E1, A],
      parentFiber: Fiber.Runtime[E2, B],
      parentRuntimeFlags: RuntimeFlags,
      overrideScope: FiberScope = null
    )(implicit unsafe: Unsafe): internal.FiberRuntime[E1, A] = {
      val childFiber = ZIO.unsafe.makeChildFiber(trace, effect, parentFiber, parentRuntimeFlags, overrideScope)

      childFiber.startConcurrently(effect)

      childFiber
    }

    def makeChildFiber[R, E1, E2, A, B](
      trace: Trace,
      effect: ZIO[R, E1, A],
      parentFiber: Fiber.Runtime[E2, B],
      parentRuntimeFlags: RuntimeFlags,
      overrideScope: FiberScope
    )(implicit unsafe: Unsafe): internal.FiberRuntime[E1, A] = {
      val parentFiberRefs = parentFiber.getFiberRefs()
      val childId         = FiberId.generate(parentFiberRefs)(trace)
      val childFiberRefs  = parentFiberRefs.forkAs(childId) // TODO: Optimize

      val childFiber = internal.FiberRuntime[E1, A](childId, childFiberRefs, parentRuntimeFlags)

      // Call the supervisor who can observe the fork of the child fiber
      val supervisor = childFiber.getSupervisor()

      if (supervisor ne Supervisor.none) {
        val childEnvironment = childFiberRefs.getOrDefault(FiberRef.currentEnvironment)
        supervisor.onStart(
          childEnvironment,
          effect.asInstanceOf[ZIO[Any, Any, Any]],
          Some(parentFiber),
          childFiber
        )

        childFiber.addObserver(exit => supervisor.onEnd(exit, childFiber))
      }

      val parentScope =
        if (overrideScope ne null) overrideScope
        else parentFiber.getFiberRef(FiberRef.forkScopeOverride).getOrElse(parentFiber.scope)

      parentScope.add(parentFiber, parentRuntimeFlags, childFiber)(trace, unsafe)

      childFiber
    }

  }

  /**
   * The level of parallelism for parallel operators.
   */
  final lazy val Parallelism: FiberRef[Option[Int]] =
    FiberRef.unsafe.make[Option[Int]](None)(Unsafe)

  /**
   * Submerges the error case of an `Either` into the `ZIO`. The inverse
   * operation of `IO.either`.
   */
  def absolve[R, E, A](v: => ZIO[R, E, Either[E, A]])(implicit trace: Trace): ZIO[R, E, A] =
    suspendSucceed(v).absolve

  /**
   * Constructs a scoped resource from an `acquire` and `release` effect. If
   * `acquire` successfully completes execution then `release` will be added to
   * the finalizers associated with the scope of this effect and is guaranteed
   * to be run when the scope is closed.
   *
   * The `acquire` and `release` effects will be run uninterruptibly.
   */
  def acquireRelease[R, R1, E, A](acquire: => ZIO[R, E, A])(release: A => ZIO[R1, Nothing, Any])(implicit
    trace: Trace
  ): ZIO[R with R1 with Scope, E, A] =
    acquireReleaseExit(acquire)((a, _) => release(a))

  /**
   * A more powerful variant of `acquireRelease` that allows the `release`
   * effect to depend on the `Exit` value specified when the scope is closed.
   */
  def acquireReleaseExit[R, R1, E, A](
    acquire: => ZIO[R, E, A]
  )(release: (A, Exit[Any, Any]) => ZIO[R1, Nothing, Any])(implicit
    trace: Trace
  ): ZIO[R with R1 with Scope, E, A] =
    ZIO.uninterruptible(acquire.tap(a => ZIO.addFinalizerExit(exit => release(a, exit))))

  /**
   * A variant of `acquireRelease` that allows the `acquire` effect to be
   * interruptible. Since the `acquire` effect could be interrupted after
   * partially acquiring resources, the `release` effect is not allowed to
   * access the resource produced by `acquire` and must independently determine
   * what finalization, if any, needs to be performed (e.g. by examining in
   * memory state).
   */
  def acquireReleaseInterruptible[R, R1, E, A](acquire: => ZIO[R, E, A])(release: ZIO[R1, Nothing, Any])(implicit
    trace: Trace
  ): ZIO[R with R1 with Scope, E, A] =
    acquireReleaseInterruptibleExit(acquire)(_ => release)

  /**
   * A more powerful variant of `acquireReleaseInterruptible` that allows the
   * `release` effect to depend on the `Exit` value specified when the scope is
   * closed.
   */
  def acquireReleaseInterruptibleExit[R, R1, E, A](acquire: => ZIO[R, E, A])(
    release: Exit[Any, Any] => ZIO[R1, Nothing, Any]
  )(implicit trace: Trace): ZIO[R with R1 with Scope, E, A] =
    ZIO.suspendSucceed(acquire.ensuring(ZIO.addFinalizerExit(release)))

  /**
   * Given an effect representing acquisition of a resource (for example,
   * opening a file, launching a thread, etc.), `acquireReleaseWith` can be used
   * to ensure the acquisition is not interrupted and the resource is always
   * released.
   *
   * The function does two things:
   *
   *   1. Ensures this `acquire` effect will not be interrupted. Of course,
   *      acquisition may fail for internal reasons (an uncaught exception).
   *
   *   1. Ensures the `release` effect will not be interrupted, and will be
   *      executed so long as this effect successfully acquires the resource.
   *
   * In between acquisition and release of the resource, the `use` effect is
   * executed.
   *
   * If the `release` effect fails, then the entire effect will fail even if the
   * `use` effect succeeds. If this fail-fast behavior is not desired, errors
   * produced by the `release` effect can be caught and ignored.
   *
   * {{{
   * ZIO.acquireReleaseWith(openFile("data.json"))(closeFile) { file =>
   *   for {
   *     header <- readHeader(file)
   *     ...
   *   } yield result
   * }
   * }}}
   */
  def acquireReleaseWith[R, E, A](acquire: => ZIO[R, E, A]): ZIO.Acquire[R, E, A] =
    new ZIO.Acquire[R, E, A](() => acquire)

  /**
   * Acquires a resource, uses the resource, and then releases the resource.
   * Neither the acquisition nor the release will be interrupted, and the
   * resource is guaranteed to be released, so long as the `acquire` effect
   * succeeds. If `use` fails, then after release, the returned effect will fail
   * with the same error.
   */
  def acquireReleaseExitWith[R, E, A](acquire: => ZIO[R, E, A]): ZIO.AcquireExit[R, E, A] =
    new ZIO.AcquireExit(() => acquire)

  /**
   * Adds a finalizer to the scope of this effect. The finalizer is guaranteed
   * to be run when the scope is closed.
   */
  def addFinalizer[R](finalizer: => URIO[R, Any])(implicit trace: Trace): ZIO[R with Scope, Nothing, Any] =
    addFinalizerExit(_ => finalizer)

  /**
   * A more powerful variant of `addFinalizer` that allows the finalizer to
   * depend on the `Exit` value that the scope is closed with.
   */
  def addFinalizerExit[R](
    finalizer: Exit[Any, Any] => URIO[R, Any]
  )(implicit trace: Trace): ZIO[R with Scope, Nothing, Any] =
    ZIO.environmentWithZIO[R] { environment =>
      ZIO.scopeWith { scope =>
        scope.addFinalizerExit(exit => finalizer(exit).provideEnvironment(environment))
      }
    }

  /**
   * Makes an explicit check to see if the fiber has been interrupted, and if
   * so, performs self-interruption
   */
  def allowInterrupt(implicit trace: Trace): UIO[Unit] =
    descriptorWith(d => if (d.interrupters.nonEmpty) interrupt else Exit.unit)

  def asyncInterruptUnsafe[R, E, A](
    register: Unsafe => (ZIO[R, E, A] => Unit) => Either[URIO[R, Any], ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  )(implicit trace: Trace): ZIO[R, E, A] =
    asyncInterrupt(cb => register(Unsafe)(cb), blockingOn)

  /**
   * Converts an asynchronous, callback-style API into a ZIO effect, which will
   * be executed asynchronously.
   *
   * With this variant, the registration function may return a ZIO effect.
   */
  def asyncZIO[R, E, A](
    register: (ZIO[R, E, A] => Unit) => ZIO[R, E, Any]
  )(implicit trace: Trace): ZIO[R, E, A] =
    for {
      p <- Promise.make[E, A]
      r <- ZIO.runtime[R]
      a <- ZIO.uninterruptibleMask { restore =>
             val f = register(k => r.unsafe.fork(k.intoPromise(p))(trace, Unsafe))

             restore(f.catchAllCause(p.refailCause)).fork *> restore(p.await)
           }
    } yield a

  def attemptUnsafe[A](a: Unsafe => A)(implicit trace: Trace): Task[A] =
    ZIO.attempt(a(Unsafe))

  def attemptBlockingIOUnsafe[A](effect: Unsafe => A)(implicit trace: Trace): IO[IOException, A] =
    attemptBlockingIO(effect(Unsafe))

  /**
   * Returns a new effect that, when executed, will execute the original effect
   * on the blocking thread pool.
   */
  def blocking[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.currentBlockingExecutor.getWith(zio.onExecutor(_))

  /**
   * Retrieves the executor for all blocking tasks.
   */
  def blockingExecutor(implicit trace: Trace): UIO[Executor] =
    FiberRef.currentBlockingExecutor.get

  /**
   * Checks the interrupt status, and produces the effect returned by the
   * specified callback.
   */
  def checkInterruptible[R, E, A](f: zio.InterruptStatus => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withFiberRuntime[R, E, A] { (_, status) =>
      f(InterruptStatus.fromBoolean(RuntimeFlags.interruption(status.runtimeFlags)))
    }

  /**
   * Retrieves the `Clock` service for this workflow.
   */
  def clock(implicit trace: Trace): UIO[Clock] =
    ZIO.clockWith(ZIO.successFn)

  /**
   * Retrieves the `Clock` service for this workflow and uses it to run the
   * specified workflow.
   */
  def clockWith[R, E, A](f: Clock => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.get(Clock.tag)))

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel
   * version, see `collectPar`.
   */
  def collect[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(
    f: A => ZIO[R, Option[E], B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    foreach[R, E, A, Option[B], Iterable](in)(a => f(a).unsome).map(_.flatten).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel
   * version, see `collectPar`.
   */
  def collect[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, Option[E], (Key2, Value2)])(implicit trace: Trace): ZIO[R, E, Map[Key2, Value2]] =
    foreach[R, E, (Key, Value), Option[(Key2, Value2)], Iterable](map)(f.tupled(_).unsome).map(_.flatten.toMap)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit
    bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]],
    trace: Trace
  ): ZIO[R, E, Collection[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A](in: Set[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A: ClassTag](in: Array[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Array[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate effect if present, and return its result as `Option[A]`.
   */
  def collectAll[R, E, A](in: Option[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Option[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A](in: NonEmptyChunk[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, NonEmptyChunk[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and discard the
   * results. For a parallel version, see `collectAllParDiscard`.
   */
  def collectAllDiscard[R, E, A](in: => Iterable[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Unit] =
    foreachDiscard(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the results.
   * For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[ZIO[R, E, A]]
  )(implicit
    bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]],
    trace: Trace
  ): ZIO[R, E, Collection[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the results.
   * For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A](as: Set[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the results.
   * For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A: ClassTag](as: Array[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Array[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the results.
   * For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A](as: NonEmptyChunk[ZIO[R, E, A]])(implicit
    trace: Trace
  ): ZIO[R, E, NonEmptyChunk[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and discard the results.
   * For a sequential version, see `collectAllDiscard`.
   */
  def collectAllParDiscard[R, E, A](as: => Iterable[ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, Unit] =
    foreachParDiscard(as)(ZIO.identityFn)

  /**
   * Evaluate and run each effect in the structure and collect discarding failed
   * ones.
   */
  def collectAllSuccesses[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]], trace: Trace): URIO[R, Collection[A]] =
    collectAllWith(in.map(_.either)) { case Right(a) => a }.map(bf.fromSpecific(in))

  /**
   * Evaluate and run each effect in the structure in parallel, and collect
   * discarding failed ones.
   */
  def collectAllSuccessesPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]], trace: Trace): URIO[R, Collection[A]] =
    collectAllWithPar(in.map(_.either)) { case Right(a) => a }.map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure with `collectAll`, and collect the
   * results with given partial function.
   */
  def collectAllWith[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[ZIO[R, E, A]])(
    f: PartialFunction[A, B]
  )(implicit
    bf: BuildFrom[Collection[ZIO[R, E, A]], B, Collection[B]],
    trace: Trace
  ): ZIO[R, E, Collection[B]] =
    ZIO.collectAll[R, E, A, Iterable](in).map(_.collect(f)).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure with `collectAllPar`, and collect the
   * results with given partial function.
   */
  def collectAllWithPar[R, E, A, U, Collection[+Element] <: Iterable[Element]](in: Collection[ZIO[R, E, A]])(
    f: PartialFunction[A, U]
  )(implicit
    bf: BuildFrom[Collection[ZIO[R, E, A]], U, Collection[U]],
    trace: Trace
  ): ZIO[R, E, Collection[U]] =
    ZIO.collectAllPar[R, E, A, Iterable](in).map(_.collect(f)).map(bf.fromSpecific(in))

  /**
   * Collects the first element of the `Iterable[A]` for which the effectual
   * function `f` returns `Some`.
   */
  def collectFirst[R, E, A, B](
    as: => Iterable[A]
  )(f: A => ZIO[R, E, Option[B]])(implicit trace: Trace): ZIO[R, E, Option[B]] =
    succeed(as.iterator).flatMap { iterator =>
      var a: Option[B] = None
      ZIO.whileLoop(iterator.hasNext && a.isEmpty)(f(iterator.next()))(a = _).as(a)
    }

  /**
   * Evaluate each effect in the structure in parallel, collecting the the
   * successful values and discarding the empty cases.
   */
  def collectPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(
    f: A => ZIO[R, Option[E], B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    foreachPar[R, E, A, Option[B], Iterable](in)(a => f(a).unsome).map(_.flatten).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel
   * version, see `collectPar`.
   */
  def collectPar[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, Option[E], (Key2, Value2)])(implicit trace: Trace): ZIO[R, E, Map[Key2, Value2]] =
    foreachPar[R, E, (Key, Value), Option[(Key2, Value2)], Iterable](map)(f.tupled(_).unsome).map(_.flatten.toMap)

  /**
   * Similar to Either.cond, evaluate the predicate, return the given A as
   * success if predicate returns true, and the given E as error otherwise
   *
   * For effectful conditionals, see [[ZIO.ifZIO]]
   */
  def cond[E, A](predicate: => Boolean, result: => A, error: => E)(implicit trace: Trace): IO[E, A] =
    ZIO.suspendSucceed(if (predicate) ZIO.succeed(result) else ZIO.fail(error))

  /**
   * Uses the current config provider to load the specified config, or fail with
   * an error of type Config.Error.
   */
  def config[A](config: Config[A])(implicit trace: Trace): ZIO[Any, Config.Error, A] =
    ZIO.configProviderWith(_.load(config))

  /**
   * Uses the current config provider to load the config of type `A`, or fail
   * with an error of type Config.Error.
   */
  def config[A](implicit trace: Trace, config: Config[A]): ZIO[Any, Config.Error, A] =
    ZIO.configProviderWith(_.load[A])

  /**
   * Retrieves the current config provider, and passes it to the specified
   * function, which may return an effect that uses the provider to perform some
   * work or compute some value.
   */
  def configProviderWith[R, E, A](f: ConfigProvider => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.get(ConfigProvider.tag)))

  /**
   * Retrieves the `Console` service for this workflow.
   */
  def console(implicit trace: Trace): UIO[Console] =
    ZIO.consoleWith(ZIO.successFn)

  /**
   * Retrieves the `Console` service for this workflow and uses it to run the
   * specified workflow.
   */
  def consoleWith[R, E, A](f: Console => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.get(Console.tag)))

  /**
   * Prints the specified message to the console for debugging purposes.
   */
  def debug(value: => Any)(implicit trace: Trace): UIO[Unit] =
    ZIO.succeed(println(value))

  /**
   * Returns information about the current fiber, such as its identity.
   */
  def descriptor(implicit trace: Trace): UIO[Fiber.Descriptor] =
    descriptorWith(ZIO.successFn)

  /**
   * Constructs an effect based on information about the current fiber, such as
   * its identity.
   */
  def descriptorWith[R, E, A](f: Fiber.Descriptor => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withFiberRuntime[R, E, A] { (fiberState, status) =>
      val descriptor =
        Fiber.Descriptor(
          fiberState.id,
          status,
          fiberState.getFiberRef(FiberRef.interruptedCause).interruptors,
          fiberState.getCurrentExecutor(),
          isLocked = RuntimeFlags.eagerShiftBack(status.runtimeFlags) ||
            fiberState.getFiberRef(FiberRef.overrideExecutor).isDefined
        )

      f(descriptor)
    }

  /**
   * Returns an effect that dies with the specified `Throwable`. This method can
   * be used for terminating a fiber because a defect has been detected in the
   * code.
   */
  def die(t: => Throwable)(implicit trace: Trace): UIO[Nothing] =
    failCause(Cause.Die(t, StackTrace.none))

  /**
   * Returns an effect that dies with a [[java.lang.RuntimeException]] having
   * the specified text message. This method can be used for terminating a fiber
   * because a defect has been detected in the code.
   */
  def dieMessage(message: => String)(implicit trace: Trace): UIO[Nothing] =
    failCause(Cause.stackless(Cause.die(new RuntimeException(message))))

  /**
   * Returns an effect from a [[zio.Exit]] value.
   */
  @deprecated(
    "For suspending a side-effecting method that produces an Exit, use `ZIO.suspendSucceed` instead.",
    "2.1.14"
  )
  def done[E, A](r: => Exit[E, A])(implicit trace: Trace): IO[E, A] =
    ZIO.suspendSucceed(r)

  /**
   * Accesses the whole environment of the effect.
   */
  def environment[R](implicit trace: Trace): URIO[R, ZEnvironment[R]] =
    FiberRef.currentEnvironment.get.asInstanceOf[URIO[R, ZEnvironment[R]]]

  /**
   * Accesses the environment of the effect.
   */
  def environmentWith[R]: ZIO.EnvironmentWithPartiallyApplied[R] =
    new ZIO.EnvironmentWithPartiallyApplied[R]

  /**
   * Effectually accesses the environment of the effect.
   */
  def environmentWithZIO[R]: ZIO.EnvironmentWithZIOPartiallyApplied[R] =
    new ZIO.EnvironmentWithZIOPartiallyApplied[R]

  /**
   * Retrieves the executor for this effect.
   */
  def executor(implicit trace: Trace): UIO[Executor] =
    ZIO.executorWith(ZIO.successFn)

  /**
   * Constructs an effect based on the current executor.
   */
  def executorWith[R, E, A](f: Executor => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withFiberRuntime[R, E, A]((fiberState, _) => f(fiberState.getCurrentExecutor()))

  /**
   * Determines whether any element of the `Iterable[A]` satisfies the effectual
   * predicate `f`.
   */
  def exists[R, E, A](
    as: => Iterable[A]
  )(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Boolean] =
    succeed(as.iterator).flatMap { iterator =>
      ZIO.iterate(false)(!_ && iterator.hasNext)(_ => f(iterator.next()))
    }

  /**
   * Returns an effect that models failure with the specified error. The moral
   * equivalent of `throw` for pure code.
   */
  def fail[E](error: => E)(implicit trace: Trace): IO[E, Nothing] =
    failCause(Cause.fail(error))

  /**
   * Returns an effect that models failure with the specified `Cause`.
   */
  def failCause[E](cause: => Cause[E])(implicit trace0: Trace): IO[E, Nothing] =
    ZIO.stackTrace(trace0).flatMap { trace =>
      FiberRef.currentLogSpan.getWith { spans =>
        FiberRef.currentLogAnnotations.getWith { annotations =>
          Exit.failCause(cause.applyAll(trace, spans, annotations))
        }
      }
    }

  /**
   * Returns the `FiberId` of the fiber executing the effect that calls this
   * method.
   */
  def fiberId(implicit trace: Trace): UIO[FiberId.Runtime] =
    fiberIdWith(ZIO.successFn)

  /**
   * Constructs an effect based on the `FiberId` of the fiber executing the
   * effect that calls this method.
   */
  def fiberIdWith[R, E, A](f: FiberId.Runtime => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withFiberRuntime[R, E, A]((fiberState, _) => f(fiberState.id))

  /**
   * Filters the collection using the specified effectual predicate.
   */
  def filter[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, Boolean]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): ZIO[R, E, Collection[A]] =
    ZIO
      .foldLeft(as)(bf.newBuilder(as)) { (builder, a) =>
        f(a).map(b => if (b) builder += a else builder)
      }
      .map(_.result())

  /**
   * Filters the Set[A] using the specified effectual predicate.
   */
  def filter[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    filter[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection in parallel using the specified effectual predicate.
   *
   * See [[[zio.ZIO.filter[R,E,A,Collection*]]] for a sequential version.
   */
  def filterPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, Boolean]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): ZIO[R, E, Collection[A]] =
    ZIO
      .foreachPar[R, E, A, Option[A], Iterable](as)(a => f(a).map(if (_) Some(a) else None))
      .map(_.flatten)
      .map(bf.fromSpecific(as))

  /**
   * Filters the Set[A] in parallel using the specified effectual predicate.
   *
   * See [[[zio.ZIO.filter[R,E,A,Collection*]]] for a sequential version.
   */
  def filterPar[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    filterPar[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection using the specified effectual predicate, removing
   * all elements that satisfy the predicate.
   */
  def filterNot[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, Boolean]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): ZIO[R, E, Collection[A]] =
    filter(as)(f(_).map(!_))

  /**
   * Filters the Set[A] using the specified effectual predicate, removing all
   * elements that satisfy the predicate.
   */
  def filterNot[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    filterNot[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection in parallel using the specified effectual predicate,
   * emoving all elements that satisfy the predicate.
   *
   * See [[[zio.ZIO.filterNot[R,E,A,Collection*]]] for a sequential version.
   */
  def filterNotPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, Boolean]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): ZIO[R, E, Collection[A]] =
    filterPar(as)(f(_).map(!_))

  /**
   * Filters the Set[A] in parallel using the specified effectual predicate,
   * removing all elements that satisfy the predicate.
   *
   * See [[[zio.ZIO.filterNot[R,E,A](as:Set*]]] for a sequential version.
   */
  def filterNotPar[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Set[A]] =
    filterNotPar[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Returns an effect that runs the first effect and in case of failure, runs
   * each of the specified effects in order until one of them succeeds.
   */
  def firstSuccessOf[R, R1 <: R, E, A](
    zio: => ZIO[R, E, A],
    rest: => Iterable[ZIO[R1, E, A]]
  )(implicit trace: Trace): ZIO[R1, E, A] =
    ZIO.suspendSucceed {
      val iterator = rest.iterator

      def loop(zio: ZIO[R1, E, A]): ZIO[R1, E, A] =
        zio.catchAllCause { cause =>
          cause.failureOrCause.fold(
            _ => if (iterator.hasNext) loop(iterator.next()) else Exit.failCause(cause),
            cause => Exit.failCause(cause)
          )
        }

      loop(zio)
    }

  /**
   * Returns an effect that first executes the outer effect, and then executes
   * the inner effect, returning the value from the inner effect, and
   * effectively flattening a nested effect.
   */
  def flatten[R, E, A](zio: => ZIO[R, E, ZIO[R, E, A]])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio.flatMap(ZIO.identityFn))

  /**
   * Folds an Iterable[A] using an effectual function f, working sequentially
   * from left to right.
   */
  def foldLeft[R, E, S, A](
    in: => Iterable[A]
  )(zero: => S)(f: (S, A) => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S] =
    ZIO.suspendSucceed {
      val iterator = in.iterator
      var s        = zero

      ZIO.whileLoop(iterator.hasNext)(f(s, iterator.next()))(s = _).as(s)
    }

  /**
   * Folds an Iterable[A] using an effectual function f, working sequentially
   * from right to left.
   */
  def foldRight[R, E, S, A](
    in: => Iterable[A]
  )(zero: => S)(f: (A, S) => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S] =
    foldLeft(in.toSeq.reverse)(zero)((s, a) => f(a, s))

  /**
   * Determines whether all elements of the `Iterable[A]` satisfy the effectual
   * predicate `f`.
   */
  def forall[R, E, A](
    as: => Iterable[A]
  )(f: A => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Boolean] =
    succeed(as.iterator).flatMap { iterator =>
      ZIO.iterate(true)(_ && iterator.hasNext)(_ => f(iterator.next()))
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` and returns
   * the results in a new `Collection[B]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not need
   * the results, see `foreachDiscard` for a more efficient implementation.
   */
  def foreach[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    if (in.isEmpty) ZIO.succeed(bf.fromSpecific(in)(Nil))
    else
      ZIO.suspendSucceed {
        val iterator = in.iterator
        val builder  = bf.newBuilder(in)

        ZIO.whileLoop(iterator.hasNext)(f(iterator.next()))(builder += _).as(builder.result())
      }

  /**
   * Applies the function `f` to each element of the `Set[A]` and returns the
   * results in a new `Set[B]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not need
   * the results, see `foreachDiscard` for a more efficient implementation.
   */
  final def foreach[R, E, A, B](in: Set[A])(f: A => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, Set[B]] =
    if (in.isEmpty) ZIO.succeed(Set.empty)
    else
      ZIO.suspendSucceed {
        val iterator = in.iterator
        val builder  = Set.newBuilder[B]

        ZIO.whileLoop(iterator.hasNext)(f(iterator.next()))(builder += _).as(builder.result())
      }

  /**
   * Applies the function `f` to each element of the `Array[A]` and returns the
   * results in a new `Array[B]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not need
   * the results, see `foreachDiscard` for a more efficient implementation.
   */
  final def foreach[R, E, A, B: ClassTag](in: Array[A])(f: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, Array[B]] =
    if (in.isEmpty) ZIO.succeed(Array.empty[B])
    else
      ZIO.suspendSucceed {
        val iterator = in.iterator
        val builder  = Array.newBuilder[B]
        builder.sizeHint(in.length)

        ZIO.whileLoop(iterator.hasNext)(f(iterator.next()))(builder += _).as(builder.result())
      }

  /**
   * Applies the function `f` to each element of the `Map[Key, Value]` and
   * returns the results in a new `Map[Key2, Value2]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not need
   * the results, see `foreachDiscard` for a more efficient implementation.
   */
  def foreach[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, E, (Key2, Value2)])(implicit trace: Trace): ZIO[R, E, Map[Key2, Value2]] =
    if (map.isEmpty) ZIO.succeed(Map.empty)
    else
      ZIO.suspendSucceed {
        val iterator = map.iterator
        val builder  = Map.newBuilder[Key2, Value2]

        val ft = f.tupled
        ZIO.whileLoop(iterator.hasNext)(ft(iterator.next()))(builder += _).as(builder.result())
      }

  /**
   * Applies the function `f` if the argument is non-empty and returns the
   * results in a new `Option[B]`.
   */
  final def foreach[R, E, A, B](in: Option[A])(f: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, Option[B]] =
    in.fold[ZIO[R, E, Option[B]]](none)(f(_).map(Some(_)))

  /**
   * Applies the function `f` to each element of the `NonEmptyChunk[A]` and
   * returns the results in a new `NonEmptyChunk[B]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not need
   * the results, see `foreachDiscard` for a more efficient implementation.
   */
  final def foreach[R, E, A, B](in: NonEmptyChunk[A])(f: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, NonEmptyChunk[B]] =
    in.mapZIO(f)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects sequentially.
   *
   * Equivalent to `foreach(as)(f).unit`, but without the cost of building the
   * list of results.
   */
  def foreachDiscard[R, E, A](
    as: => Iterable[A]
  )(f: A => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      val as0 = as
      if (as0.isEmpty) Exit.unit
      else {
        val iterator = as0.iterator
        ZIO.whileLoop(iterator.hasNext)(f(iterator.next()))(ZIO.unitFn)
      }
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` and returns
   * the result in a new `Collection[B]` using the specified execution strategy.
   */
  final def foreachExec[R, E, A, B, Collection[+Element] <: Iterable[Element]](as: Collection[A])(
    exec: => ExecutionStrategy
  )(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    if (as.isEmpty) ZIO.succeed(bf.fromSpecific(as)(Nil))
    else
      ZIO.suspendSucceed {
        exec match {
          case ExecutionStrategy.Parallel =>
            ZIO.withParallelismUnboundedMask(restore => ZIO.foreachPar(as)(a => restore(f(a))))
          case ExecutionStrategy.ParallelN(n) =>
            ZIO.withParallelismMask(n)(restore => ZIO.foreachPar(as)(a => restore(f(a))))
          case ExecutionStrategy.Sequential =>
            ZIO.foreach(as)(f)
        }
      }

  /**
   * Applies the function `f` to each element of the `Collection[A]` in
   * parallel, and returns the results in a new `Collection[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  def foreachPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    if (as.isEmpty) ZIO.succeed(bf.fromSpecific(as)(Nil))
    else
      ZIO.parallelismWith {
        case Some(n) => foreachPar(n)(as)(f)
        case None    => foreachParUnbounded(as)(f)
      }

  /**
   * Applies the function `f` to each element of the `Set[A]` in parallel, and
   * returns the results in a new `Set[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B](as: Set[A])(fn: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, Set[B]] =
    foreachPar[R, E, A, B, Iterable](as)(fn).map(_.toSet)

  /**
   * Applies the function `f` to each element of the `Array[A]` in parallel, and
   * returns the results in a new `Array[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B: ClassTag](as: Array[A])(f: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, Array[B]] =
    foreachPar[R, E, A, B, Iterable](as)(f).map(_.toArray)

  /**
   * Applies the function `f` to each element of the `Map[Key, Value]` in
   * parallel and returns the results in a new `Map[Key2, Value2]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  def foreachPar[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, E, (Key2, Value2)])(implicit trace: Trace): ZIO[R, E, Map[Key2, Value2]] =
    foreachPar[R, E, (Key, Value), (Key2, Value2), Iterable](map)(f.tupled).map(_.toMap)

  /**
   * Applies the function `f` to each element of the `NonEmptyChunk[A]` in
   * parallel, and returns the results in a new `NonEmptyChunk[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B](as: NonEmptyChunk[A])(fn: A => ZIO[R, E, B])(implicit
    trace: Trace
  ): ZIO[R, E, NonEmptyChunk[B]] =
    as.mapZIOPar(fn)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects in parallel, discarding the results.
   *
   * For a sequential version of this method, see `foreachDiscard`.
   *
   * Optimized to avoid keeping full tree of effects, so that method could be
   * able to handle large input sequences. Behaves almost like this code:
   *
   * {{{
   * as.foldLeft(ZIO.unit) { (acc, a) => acc.zipParLeft(f(a)) }
   * }}}
   *
   * Additionally, interrupts all effects on any failure.
   */
  def foreachParDiscard[R, E, A](
    as: => Iterable[A]
  )(f: A => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.parallelismWith {
      case Some(n) => foreachParDiscard(n)(as)(f)
      case None    => foreachParUnboundedDiscard(as)(f)
    }

  /**
   * Returns an effect that forks all of the specified values, and returns a
   * composite fiber that produces a list of their results, in order.
   */
  def forkAll[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[ZIO[R, E, A]]
  )(implicit
    bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]],
    trace: Trace
  ): URIO[R, Fiber[E, Collection[A]]] =
    ZIO
      .foreach[R, Nothing, ZIO[R, E, A], Fiber[E, A], Iterable](as)(_.fork)
      .map(Fiber.collectAll[E, A, Iterable](_).map(bf.fromSpecific(as)))

  /**
   * Returns an effect that forks all of the specified values, and returns a
   * composite fiber that produces unit. This version is faster than [[forkAll]]
   * in cases where the results of the forked fibers are not needed.
   */
  def forkAllDiscard[R, E, A](as: => Iterable[ZIO[R, E, A]])(implicit trace: Trace): URIO[R, Fiber[E, Unit]] =
    ZIO
      .foreach[R, Nothing, ZIO[R, E, A], Fiber[E, A], Iterable](as)(_.fork)
      .map(Fiber.collectAllDiscard(_))

  /**
   * Constructs a `ZIO` value of the appropriate type for the specified input.
   */
  def from[Input](input: => Input)(implicit
    constructor: ZIOConstructor[Nothing, Any, Input],
    trace: Trace
  ): ZIO[constructor.OutEnvironment, constructor.OutError, constructor.OutSuccess] =
    constructor.make(input)

  def fromAutoCloseable[R, E, A <: AutoCloseable](fa: => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R with Scope, E, A] =
    acquireRelease(fa)(a => if (a eq null) Exit.unit else ZIO.succeed(a.close()))

  /**
   * Lifts an `Either` into a `ZIO` value.
   */
  def fromEither[E, A](v: => Either[E, A])(implicit trace: Trace): IO[E, A] =
    succeed(v).flatMap(_.fold(ZIO.failFn, ZIO.successFn))

  /**
   * Lifts an `Either` into a `ZIO` value.
   */
  def fromEitherCause[E, A](v: => Either[Cause[E], A])(implicit trace: Trace): IO[E, A] =
    succeed(v).flatMap(_.fold(Exit.failCause, ZIO.successFn))

  /**
   * Creates a `ZIO` value that represents the exit value of the specified
   * fiber.
   */
  def fromFiber[E, A](fiber: => Fiber[E, A])(implicit trace: Trace): IO[E, A] =
    succeed(fiber).flatMap(_.join)

  /**
   * Creates a `ZIO` value that represents the exit value of the specified
   * fiber.
   */
  def fromFiberZIO[R, E, A](fiber: => ZIO[R, E, Fiber[E, A]])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(fiber.flatMap(_.join))

  /**
   * Returns an effect that, when executed, will both create and launch a
   * [[scala.concurrent.Future]], feeding it an
   * [[scala.concurrent.ExecutionContext]] that is backed by ZIO's own executor.
   */
  def fromFuture[A](make: ExecutionContext => scala.concurrent.Future[A])(implicit trace: Trace): Task[A] =
    ZIO.executorWith { executor =>
      ZIO.suspend {
        import scala.util.{Failure, Success}

        val ec = executor.asExecutionContext
        val f  = make(ec)
        f.value match {
          case None =>
            ZIO.asyncInterrupt { (k: Task[A] => Unit) =>
              f.onComplete {
                case Success(a) => k(Exit.succeed(a))
                case Failure(t) => k(ZIO.fail(t))
              }(ec)

              Left {
                f match {
                  case cf: CancelableFuture[A] =>
                    ZIO.suspendSucceedUnsafe(implicit u =>
                      if (cf.isCompleted) Exit.unit else ZIO.fromFutureNow(cf.cancel()).ignore
                    )
                  case _ =>
                    Exit.unit
                }
              }
            }
          case Some(outcome) => outcome.fold(ZIO.fail(_), ZIO.successFn)
        }
      }
    }

  /**
   * Similar to [[fromFuture]], but doesn't attempt to suspend the future. Use
   * only when the future is already suspended by another effect
   *
   * @see
   *   Overloaded method that allows using the ZIO executor-backed execution
   *   context
   */
  private[zio] def fromFutureNow[A](f: concurrent.Future[A])(implicit trace: Trace, unsafe: Unsafe): Task[A] = {
    import scala.util.{Failure, Success}
    f.value match {
      case None =>
        ZIO.executorWith { executor =>
          val ec = executor.asExecutionContext
          ZIO.asyncInterrupt { (k: Task[A] => Unit) =>
            f.onComplete {
              case Success(a) => k(Exit.succeed(a))
              case Failure(t) => k(ZIO.fail(t))
            }(ec)
            Left {
              f match {
                case cf: CancelableFuture[A] =>
                  ZIO.suspendSucceed(if (cf.isCompleted) Exit.unit else ZIO.fromFutureNow(cf.cancel()).ignore)
                case _ => Exit.unit
              }
            }
          }
        }
      case Some(outcome) => outcome.fold(ZIO.fail(_), ZIO.successFn)
    }
  }

  /**
   * Imports a [[scala.concurrent.Promise]] we generate a future from promise,
   * and we pass to [fromFuture] to transform into Task[A]
   */
  def fromPromiseScala[A](promise: => scala.concurrent.Promise[A])(implicit trace: Trace): Task[A] =
    ZIO.suspend(ZIO.fromFutureNow(promise.future)(trace, Unsafe))

  /**
   * Imports a function that creates a [[scala.concurrent.Future]] from an
   * [[scala.concurrent.ExecutionContext]] into a `ZIO`. The provided
   * `ExecutionContext` will interrupt the `Future` between asynchronous
   * operations such as `map` and `flatMap` if this effect is interrupted. Note
   * that no attempt will be made to interrupt a `Future` blocking on a
   * synchronous operation and that the `Future` must be created using the
   * provided `ExecutionContext`.
   */
  def fromFutureInterrupt[A](
    make: ExecutionContext => scala.concurrent.Future[A]
  )(implicit trace: Trace): Task[A] =
    ZIO.executorWith { executor =>
      import scala.util.{Failure, Success}
      val interrupted = new java.util.concurrent.atomic.AtomicBoolean(false)
      val latch       = scala.concurrent.Promise[Unit]()
      ZIO.suspend {
        val ec = executor.asExecutionContext
        val interruptibleEC = new scala.concurrent.ExecutionContext {
          def execute(runnable: Runnable): Unit =
            if (!interrupted.get) ec.execute(runnable)
            else latch.success(())
          def reportFailure(cause: Throwable): Unit =
            ec.reportFailure(cause)
        }
        val f = make(interruptibleEC)
        f.value match {
          case None =>
            ZIO.async { (cb: Task[A] => Any) =>
              f.onComplete {
                case Success(a) => latch.success(()); cb(Exit.succeed(a))
                case Failure(t) => latch.success(()); cb(ZIO.fail(t))
              }(interruptibleEC)
            }
          case Some(outcome) => outcome.fold(ZIO.fail(_), ZIO.successFn)
        }
      }.onInterrupt(
        ZIO.suspendSucceedUnsafe { implicit u =>
          interrupted.set(true)
          ZIO.fromFutureNow(latch.future).orDie
        }
      )
    }

  /**
   * Lifts an `Option` into a `ZIO` but preserves the error as an option in the
   * error channel, making it easier to compose in some scenarios.
   */
  def fromOption[A](v: => Option[A])(implicit trace: Trace): IO[Option[Nothing], A] =
    succeed(v).flatMap(_.fold[IO[Option[Nothing], A]](fail(None))(ZIO.successFn))

  /**
   * Lifts a `Try` into a `ZIO`.
   */
  def fromTry[A](value: => scala.util.Try[A])(implicit trace: Trace): Task[A] =
    attempt(value).flatMap {
      case scala.util.Success(v) => Exit.succeed(v)
      case scala.util.Failure(t) => ZIO.fail(t)
    }

  /**
   * Returns a collection of all `FiberRef` values for the fiber running this
   * effect.
   */
  def getFiberRefs(implicit trace: Trace): UIO[FiberRefs] =
    ZIO.withFiberRuntime[Any, Nothing, FiberRefs] { (fiberState, _) =>
      Exit.succeed(fiberState.getFiberRefs())
    }

  /**
   * Lifts an Option into a ZIO, if the option is not defined it fails with
   * NoSuchElementException.
   */
  final def getOrFail[A](v: => Option[A])(implicit trace: Trace): Task[A] =
    getOrFailWith(new NoSuchElementException("None.get"))(v)

  /**
   * Lifts an Option into a IO, if the option is not defined it fails with Unit.
   */
  final def getOrFailUnit[A](v: => Option[A])(implicit trace: Trace): IO[Unit, A] =
    getOrFailWith(())(v)

  /**
   * Lifts an Option into a ZIO. If the option is not defined, fail with the `e`
   * value.
   */
  final def getOrFailWith[E, A](e: => E)(v: => Option[A])(implicit trace: Trace): IO[E, A] =
    suspendSucceed(v match {
      case None    => ZIO.fail(e)
      case Some(v) => Exit.succeed(v)
    })

  /**
   * Gets a state from the environment.
   */
  def getState[S: EnvironmentTag](implicit trace: Trace): ZIO[ZState[S], Nothing, S] =
    ZIO.serviceWithZIO(_.get)

  /**
   * Gets a state from the environment and uses it to run the specified
   * function.
   */
  def getStateWith[S]: ZIO.GetStateWithPartiallyApplied[S] =
    new ZIO.GetStateWithPartiallyApplied[S]

  /**
   * Runs `onTrue` if the result of `b` is `true` and `onFalse` otherwise.
   */
  def ifZIO[R, E](b: => ZIO[R, E, Boolean]): ZIO.IfZIO[R, E] =
    new ZIO.IfZIO(() => b)

  /**
   * Like [[never]], but fibers that running this effect won't be garbage
   * collected unless interrupted.
   */
  def infinity(implicit trace: Trace): UIO[Nothing] =
    ZIO.sleep(Duration.fromNanos(Long.MaxValue)) *> ZIO.never

  /**
   * Inherits values from all [[FiberRef]] instances into current fiber.
   */
  def inheritFiberRefs(childFiberRefs: FiberRefs)(implicit trace: Trace): UIO[Unit] =
    ZIO.updateFiberRefs { (parentFiberId, parentFiberRefs) =>
      parentFiberRefs.joinAs(parentFiberId)(childFiberRefs)
    }

  /**
   * Returns an effect that is interrupted as if by the fiber calling this
   * method.
   */
  def interrupt(implicit trace: Trace): UIO[Nothing] =
    fiberIdWith(interruptAs(_))

  /**
   * Returns an effect that is interrupted as if by the specified fiber.
   */
  def interruptAs(fiberId: => FiberId)(implicit trace: Trace): UIO[Nothing] =
    failCause(Cause.interrupt(fiberId))

  /**
   * Prefix form of `ZIO#interruptible`.
   */
  def interruptible[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio.interruptible)

  /**
   * Makes the effect interruptible, but passes it a restore function that can
   * be used to restore the inherited interruptibility from whatever region the
   * effect is composed into.
   */
  def interruptibleMask[R, E, A](
    k: ZIO.InterruptibilityRestorer => ZIO[R, E, A]
  )(implicit trace: Trace): ZIO[R, E, A] =
    interruptionMasked(enable = true, k)

  private def interruptionMasked[R, E, A](
    enable: Boolean,
    f: InterruptibilityRestorer => ZIO[R, E, A]
  )(implicit trace: Trace) =
    ZIO.UpdateRuntimeFlagsWithin(
      trace,
      if (enable) RuntimeFlags.enableInterruption else RuntimeFlags.disableInterruption,
      oldFlags =>
        if (RuntimeFlags.interruption(oldFlags)) f(InterruptibilityRestorer.MakeInterruptible)
        else f(InterruptibilityRestorer.MakeUninterruptible)
    )

  /**
   * Retrieves the definition of a fatal error.
   */
  def isFatal(implicit trace: Trace): UIO[Throwable => Boolean] =
    isFatalWith(ZIO.successFn)

  /**
   * Constructs an effect based on the definition of a fatal error.
   */
  def isFatalWith[R, E, A](f: (Throwable => Boolean) => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.currentFatal.getWith(f)

  /**
   * Iterates with the specified effectual function. The moral equivalent of:
   *
   * {{{
   * var s = initial
   *
   * while (cont(s)) {
   *   s = body(s)
   * }
   *
   * s
   * }}}
   */
  def iterate[R, E, S](
    initial: => S
  )(cont: S => Boolean)(body: S => ZIO[R, E, S])(implicit trace: Trace): ZIO[R, E, S] =
    ZIO.suspendSucceed {
      var result = initial

      ZIO
        .whileLoop(cont(result))(body(result))(result = _)
        .as(result)
    }

  /**
   * A low-level while-loop with direct support in the ZIO runtime. The only
   * reason to use this constructor is performance.
   *
   * See [[ZIO.iterate]] for a user-friendly version of this operator that is
   * compatible with purely functional code.
   */
  def whileLoop[R, E, A](check: => Boolean)(body: => ZIO[R, E, A])(process: A => Any)(implicit
    trace: Trace
  ): ZIO[R, E, Unit] =
    ZIO.WhileLoop(trace, () => check, () => body, process)

  /**
   * Returns an effect with the value on the left part.
   */
  def left[A](a: => A)(implicit trace: Trace): UIO[Either[A, Nothing]] =
    succeed(Left(a))

  /**
   * Retrieves the current loggers for this effect.
   */
  def loggers(implicit trace: Trace): UIO[Set[ZLogger[String, Any]]] =
    FiberRef.currentLoggers.get

  /**
   * Constructs an effect based on the current loggers.
   */
  def loggersWith[R, E, A](f: Set[ZLogger[String, Any]] => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    FiberRef.currentLoggers.getWith(f)

  /**
   * Loops with the specified effectual function, collecting the results into a
   * list. The moral equivalent of:
   *
   * {{{
   * var s  = initial
   * var as = List.empty[A]
   *
   * while (cont(s)) {
   *   as = body(s) :: as
   *   s  = inc(s)
   * }
   *
   * as.reverse
   * }}}
   */
  def loop[R, E, A, S](
    initial: => S
  )(cont: S => Boolean, inc: S => S)(body: S => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, List[A]] =
    ZIO.suspendSucceed {
      var s       = initial
      val builder = List.newBuilder[A]

      ZIO
        .whileLoop(cont(s)) {
          val effect = body(s)
          s = inc(s)
          effect
        }(builder += _)
        .as(builder.result())
    }

  /**
   * Loops with the specified effectual function purely for its effects. The
   * moral equivalent of:
   *
   * {{{
   * var s = initial
   *
   * while (cont(s)) {
   *   body(s)
   *   s = inc(s)
   * }
   * }}}
   */
  def loopDiscard[R, E, S](
    initial: => S
  )(cont: S => Boolean, inc: S => S)(body: S => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      var s = initial
      ZIO.whileLoop(cont(s)) {
        val effect = body(s)
        s = inc(s)
        effect
      }(ZIO.unitFn)
    }

  /**
   * Returns a new effect where boolean value of this effect is negated.
   */
  def not[R, E](effect: => ZIO[R, E, Boolean])(implicit trace: Trace): ZIO[R, E, Boolean] =
    ZIO.suspendSucceed(effect.negate)

  /**
   * Logs the specified message at the current log level.
   */
  def log(message: => String)(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, Cause.empty, None, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the current log level.
   */
  def logCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logCause("An error occurred", cause)

  /**
   * Logs the specified message and cause at the current log level.
   */
  def logCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, None, trace)

      Exit.unit
    }

  /**
   * Annotates each log in this effect with the specified log annotation.
   */
  def logAnnotate(key: => String, value: => String): LogAnnotate =
    logAnnotate(LogAnnotation(key, value))

  /**
   * Annotates each log in this effect with the specified log annotation.
   */
  def logAnnotate(logAnnotation: => LogAnnotation, logAnnotations: LogAnnotation*): LogAnnotate =
    logAnnotate(Set(logAnnotation) ++ logAnnotations.toSet)

  /**
   * Annotates each log in this effect with the specified log annotation.
   */
  def logAnnotate(logAnnotations: => Set[LogAnnotation]): LogAnnotate =
    new LogAnnotate(() => logAnnotations)

  def logAnnotateScoped(key: => String, value: => String)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    logAnnotateScoped(LogAnnotation(key, value))

  def logAnnotateScoped(logAnnotation: => LogAnnotation, logAnnotations: LogAnnotation*)(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, Unit] =
    logAnnotateScoped(Set(logAnnotation) ++ logAnnotations.toSet)

  def logAnnotateScoped(logAnnotations: => Set[LogAnnotation])(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    FiberRef.currentLogAnnotations.locallyScopedWith(_ ++ logAnnotations.map { case LogAnnotation(key, value) =>
      key -> value
    })

  /**
   * Retrieves the log annotations associated with the current scope.
   */
  def logAnnotations(implicit trace: Trace): UIO[Map[String, String]] =
    FiberRef.currentLogAnnotations.get

  /**
   * Logs the specified message at the debug log level.
   */
  def logDebug(message: => String)(implicit trace: Trace): UIO[Unit] =
    logDebugCause(message, Cause.empty)

  /**
   * Logs the specified cause at the debug log level.
   */
  def logDebugCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someDebug, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the debug log level..
   */
  def logDebugCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logDebugCause("", cause)

  /**
   * Logs the specified message at the error log level.
   */
  def logError(message: => String)(implicit trace: Trace): UIO[Unit] =
    logErrorCause(message, Cause.empty)

  /**
   * Logs the specified cause as an error.
   */
  def logErrorCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someError, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause as an error.
   */
  def logErrorCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logErrorCause("", cause)

  /**
   * Logs the specified message at the fatal log level.
   */
  def logFatal(message: => String)(implicit trace: Trace): UIO[Unit] =
    logFatalCause(message, Cause.empty)

  /**
   * Logs the specified cause at the fatal log level.
   */
  def logFatalCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someFatal, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the fatal log level.
   */
  def logFatalCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logFatalCause("", cause)

  /**
   * Logs the specified message at the informational log level.
   */
  def logInfo(message: => String)(implicit trace: Trace): UIO[Unit] =
    logInfoCause(message, Cause.empty)

  /**
   * Logs the specified cause at the informational log level.
   */
  def logInfoCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someInfo, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the informational log level..
   */
  def logInfoCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logInfoCause("", cause)

  /**
   * Sets the log level for this effect.
   * {{{
   * ZIO.logLevel(LogLevel.Warning) {
   *   ZIO.log("The response time exceeded its threshold!")
   * }
   * }}}
   */
  def logLevel(level: LogLevel): LogLevel =
    level

  def logLevelScoped(level: LogLevel)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    FiberRef.currentLogLevel.locallyScoped(level)

  /**
   * Adjusts the label for the current logging span.
   * {{{
   * ZIO.logSpan("parsing") { parseRequest(req) }
   * }}}
   */
  def logSpan(label: => String): LogSpan = new LogSpan(() => label)

  def logSpanScoped(label: => String)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    FiberRef.currentLogSpan.getWith { stack =>
      val instant = java.lang.System.currentTimeMillis()
      val logSpan = zio.LogSpan(label, instant)

      FiberRef.currentLogSpan.locallyScoped(logSpan :: stack)
    }

  /**
   * Retrieves the log spans associated with the current scope.
   */
  def logSpans(implicit trace: Trace): UIO[List[zio.LogSpan]] =
    FiberRef.currentLogSpan.get

  /**
   * Logs the specified message at the trace log level.
   */
  def logTrace(message: => String)(implicit trace: Trace): UIO[Unit] =
    logTraceCause(message, Cause.empty)

  /**
   * Logs the specified cause at the trace log level.
   */
  def logTraceCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someTrace, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the trace log level..
   */
  def logTraceCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logTraceCause("", cause)

  /**
   * Logs the specified message at the warning log level.
   */
  def logWarning(message: => String)(implicit trace: Trace): UIO[Unit] =
    logWarningCause(message, Cause.empty)

  /**
   * Logs the specified cause at the warning log level.
   */
  def logWarningCause(message: => String, cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.log(() => message, cause, someWarning, trace)

      Exit.unit
    }

  /**
   * Logs the specified cause at the warning log level..
   */
  def logWarningCause(cause: => Cause[Any])(implicit trace: Trace): UIO[Unit] =
    logWarningCause("", cause)

  /**
   * Returns a memoized version of the specified effectual function.
   */
  def memoize[R, E, A, B](f: A => ZIO[R, E, B])(implicit trace: Trace): UIO[A => ZIO[R, E, B]] =
    Ref.Synchronized.make(Map.empty[A, Promise[E, (FiberRefs.Patch, B)]]).map { ref => a =>
      for {
        promise <- ref.modifyZIO { map =>
                     map.get(a) match {
                       case Some(promise) => Exit.succeed((promise, map))
                       case None =>
                         for {
                           promise <- Promise.make[E, (FiberRefs.Patch, B)]
                           _       <- f(a).diffFiberRefs.intoPromise(promise).fork
                         } yield (promise, map.updated(a, promise))
                     }
                   }
        b <- promise.await.flatMap { case (patch, b) => ZIO.patchFiberRefs(patch).as(b) }
      } yield b
    }

  /**
   * Merges an `Iterable[IO]` to a single IO, working sequentially.
   */
  def mergeAll[R, E, A, B](
    in: => Iterable[ZIO[R, E, A]]
  )(zero: => B)(f: (B, A) => B)(implicit trace: Trace): ZIO[R, E, B] =
    ZIO.foldLeft(in)(zero)((b, zio) => zio.map(a => f(b, a)))

  /**
   * Merges an `Iterable[IO]` to a single IO, working in parallel.
   *
   * Due to the parallel nature of this combinator, `f` must be both:
   *   - commutative: `f(a, b) == f(b, a)`
   *   - associative: `f(a, f(b, c)) == f(f(a, b), c)`
   *
   * It's unsafe to execute side effects inside `f`, as `f` may be executed more
   * than once for some of `in` elements during effect execution.
   */
  def mergeAllPar[R, E, A, B](
    in: => Iterable[ZIO[R, E, A]]
  )(zero: => B)(f: (B, A) => B)(implicit trace: Trace): ZIO[R, E, B] =
    Ref.make(zero).flatMap(acc => foreachParDiscard(in)(_.flatMap(a => acc.update(f(_, a)))) *> acc.get)

  /**
   * Gets current metrics snapshot.
   */
  def metrics(implicit trace: Trace): UIO[Metrics] =
    ZIO.succeedUnsafe { implicit u =>
      Metrics(internal.metrics.metricRegistry.snapshot())
    }

  /**
   * Returns a effect that will never produce anything. The moral equivalent of
   * `while(true) {}`, only without the wasted CPU cycles. Fibers that execute
   * this effect will be automatically garbage collected on the JVM when no
   * explicit references to them are held, because they cannot be reactivated.
   */
  def never(implicit trace: Trace): UIO[Nothing] =
    async[Any, Nothing, Nothing](ZIO.unitFn)

  /**
   * Returns an effect that succeeds with the `None` value.
   */
  val none: UIO[Option[Nothing]] =
    succeed(None)(Trace.empty)

  /**
   * Lifts an Option into a ZIO. If the option is empty it succeeds with Unit.
   * If the option is defined it fails with the content.
   */
  def noneOrFail[E](o: => Option[E])(implicit trace: Trace): IO[E, Unit] =
    getOrFailUnit(o).flip

  /**
   * Lifts an Option into a ZIO. If the option is empty it succeeds with Unit.
   * If the option is defined it fails with an error adapted with f.
   */
  def noneOrFailWith[E, O](o: => Option[O])(f: O => E)(implicit trace: Trace): IO[E, Unit] =
    getOrFailUnit(o).flip.mapError(f)

  /**
   * Returns an effect that will execute the specified effect fully on the
   * provided executor, before potentially returning to the previous executor.
   * See [[ZIO!.onExecutor]].
   */
  def onExecutor[R, E, A](executor: => Executor)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.descriptorWith { descriptor =>
      val oldExecutor = descriptor.executor
      val newExecutor = executor

      if (descriptor.isLocked && oldExecutor == newExecutor)
        zio
      else if (descriptor.isLocked)
        ZIO.acquireReleaseWith(ZIO.shift(newExecutor))(_ => ZIO.shift(oldExecutor))(_ => zio)
      else
        ZIO.acquireReleaseWith(ZIO.shift(newExecutor))(_ => ZIO.unshift)(_ => zio)
    }

  /**
   * Shifts execution to the specified executor and shifts it back to the
   * previous executor, if any, when the scope is closed.
   */
  def onExecutorScoped(executor: => Executor)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ZIO.descriptorWith { descriptor =>
      val oldExecutor = descriptor.executor
      val newExecutor = executor

      if (descriptor.isLocked && oldExecutor == newExecutor)
        Exit.unit
      else if (descriptor.isLocked)
        ZIO.acquireRelease(ZIO.shift(executor))(_ => ZIO.shift(descriptor.executor))
      else
        ZIO.acquireRelease(ZIO.shift(executor))(_ => ZIO.unshift)
    }

  /**
   * Applies the specified changes to the `FiberRef` values for the fiber
   * running this workflow.
   */
  def patchFiberRefs(patch: FiberRefs.Patch)(implicit trace: Trace): ZIO[Any, Nothing, Unit] =
    updateFiberRefs(patch)

  /**
   * Returns a new scoped workflow that runs finalizers added to the scope of
   * this workflow in parallel.
   */
  def parallelFinalizers[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.environmentWithZIO[R] { environment =>
      environment.getDynamic[Scope] match {
        case None => zio
        case Some(scope) =>
          scope.executionStrategy match {
            case ExecutionStrategy.Parallel => zio
            case _                          => scope.forkWith(ExecutionStrategy.Parallel).flatMap(_.extend[R](zio))
          }
      }
    }

  def parallelFinalizersMask[R, E, A](f: ZIO.FinalizersRestorer => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.environmentWithZIO[R] { environment =>
      environment.getDynamic[Scope] match {
        case None => f(ZIO.FinalizersRestorer.Identity)
        case Some(scope) =>
          scope.executionStrategy match {
            case ExecutionStrategy.Parallel     => ZIO.parallelFinalizers(f(ZIO.FinalizersRestorer.MakeParallel))
            case ExecutionStrategy.ParallelN(n) => ZIO.parallelFinalizers(f(ZIO.FinalizersRestorer.MakeParallelN(n)))
            case ExecutionStrategy.Sequential   => ZIO.parallelFinalizers(f(ZIO.FinalizersRestorer.MakeSequential))
          }
      }
    }

  /**
   * Retrieves the maximum number of fibers for parallel operators or `None` if
   * it is unbounded.
   */
  def parallelism(implicit trace: Trace): UIO[Option[Int]] =
    Parallelism.get

  /**
   * Retrieves the current maximum number of fibers for parallel operators and
   * uses it to run the specified effect.
   */
  def parallelismWith[R, E, A](f: Option[Int] => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    Parallelism.getWith(f)

  /**
   * Feeds elements of type `A` to a function `f` that returns an effect.
   * Collects all successes and failures in a tupled fashion.
   */
  def partition[R, E, A, B](
    in: => Iterable[A]
  )(f: A => ZIO[R, E, B])(implicit ev: CanFail[E], trace: Trace): ZIO[R, Nothing, (Iterable[E], Iterable[B])] =
    ZIO.suspendSucceed(ZIO.foreach(in)(f(_).either).map(partitionMap(_)(ZIO.identityFn)))

  /**
   * Feeds elements of type `A` to a function `f` that returns an effect.
   * Collects all successes and failures in parallel and returns the result as a
   * tuple.
   */
  def partitionPar[R, E, A, B](
    in: => Iterable[A]
  )(f: A => ZIO[R, E, B])(implicit ev: CanFail[E], trace: Trace): ZIO[R, Nothing, (Iterable[E], Iterable[B])] =
    ZIO.suspendSucceed(ZIO.foreachPar(in)(f(_).either).map(ZIO.partitionMap(_)(ZIO.identityFn)))

  /**
   * Given an environment `R`, returns a function that can supply the
   * environment to programs that require it, removing their need for any
   * specific environment.
   *
   * This is similar to dependency injection, and the `provide` function can be
   * thought of as `inject`.
   */
  def provideEnvironment[R, E, A](r: => ZEnvironment[R])(implicit trace: Trace): ZIO[R, E, A] => IO[E, A] =
    _.provideEnvironment(r)

  def provideLayer[RIn, E, ROut, RIn2, ROut2](layer: ZLayer[RIn, E, ROut])(
    zio: ZIO[ROut with RIn2, E, ROut2]
  )(implicit ev: EnvironmentTag[RIn2], tag: EnvironmentTag[ROut], trace: Trace): ZIO[RIn with RIn2, E, ROut2] =
    zio.provideSomeLayer[RIn with RIn2](ZLayer.environment[RIn2] ++ layer)

  /**
   * Races an `IO[E, A]` against zero or more other effects. Yields either the
   * first success or the last failure.
   */
  def raceAll[R, R1 <: R, E, A](
    zio: => ZIO[R, E, A],
    ios: => Iterable[ZIO[R1, E, A]]
  )(implicit trace: Trace): ZIO[R1, E, A] =
    ZIO.suspendSucceed(zio.raceAll(ios))

  /**
   * Returns an effect that races this effect with all the specified effects,
   * yielding the first result to complete, whether by success or failure. If
   * neither effect completes, then the composed effect will not complete.
   *
   * WARNING: The raced effect will safely interrupt the "losers", but will not
   * resume until the losers have been cleanly terminated. If early return is
   * desired, then instead of performing `ZIO.raceFirst(l, rs)`, perform
   * `ZIO.raceFirst(l.disconnect, rs.map(_.disconnect))`, which disconnects left
   * and rights interrupt signal, allowing a fast return, with interruption
   * performed in the background.
   */
  def raceFirst[R, R1 <: R, E, A](zio: ZIO[R, E, A], ios: Iterable[ZIO[R1, E, A]])(implicit
    trace: Trace
  ): ZIO[R1, E, A] =
    (zio.exit raceAll ios.map(_.exit)).unexit

  /**
   * Retreives the `Random` service for this workflow.
   */
  def random(implicit trace: Trace): UIO[Random] =
    ZIO.randomWith(ZIO.successFn)

  /**
   * Retrieves the `Random` service for this workflow and uses it to run the
   * specified workflow.
   */
  def randomWith[R, E, A](f: Random => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.get(Random.tag)))

  /**
   * Reduces an `Iterable[IO]` to a single `IO`, working sequentially.
   */
  def reduceAll[R, R1 <: R, E, A](a: => ZIO[R, E, A], as: => Iterable[ZIO[R1, E, A]])(
    f: (A, A) => A
  )(implicit trace: Trace): ZIO[R1, E, A] =
    ZIO.suspendSucceed(a.flatMap(ZIO.mergeAll(as)(_)(f)))

  /**
   * Reduces an `Iterable[IO]` to a single `IO`, working in parallel.
   */
  def reduceAllPar[R, R1 <: R, E, A](a0: => ZIO[R, E, A], as0: => Iterable[ZIO[R1, E, A]])(
    f: (A, A) => A
  )(implicit trace: Trace): ZIO[R1, E, A] =
    ZIO.suspendSucceed {
      val a  = a0
      val as = as0

      def prepend[Z](z: Z, zs: Iterable[Z]): Iterable[Z] =
        new Iterable[Z] {
          override def iterator: Iterator[Z] = Iterator(z) ++ zs.iterator
        }

      val all = prepend(a, as)
      mergeAllPar(all)(Option.empty[A])((acc, elem) => Some(acc.fold(elem)(f(_, elem)))).map(_.get)
    }

  // NOTE: Prefer using `Exit.failCause` which doesn't require the unused implicit `trace`
  def refailCause[E](cause: Cause[E])(implicit trace: Trace): ZIO[Any, E, Nothing] = Exit.Failure(cause)

  /**
   * Replicates the given effect `n` times. If 0 or negative numbers are given,
   * an empty `Iterable` will be returned. This method is more efficient than
   * using `List.fill` or similar methods, because the returned `Iterable`
   * consumes only a small amount of heap regardless of `n`.
   */
  def replicate[R, E, A](n: Int)(effect: ZIO[R, E, A])(implicit trace: Trace): Iterable[ZIO[R, E, A]] =
    new Iterable[ZIO[R, E, A]] {
      override def iterator: Iterator[ZIO[R, E, A]] = Iterator.range(0, n).map(_ => effect)
    }

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  def replicateZIO[R, E, A](n: => Int)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, Iterable[A]] =
    ZIO.suspendSucceed(ZIO.collectAll(ZIO.replicate(n)(effect)))

  /**
   * Performs this effect the specified number of times, discarding the results.
   */
  def replicateZIODiscard[R, E, A](n: => Int)(effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.collectAllDiscard(ZIO.replicate(n)(effect))

  /**
   * Returns an effect with the value on the right part.
   */
  def right[B](b: => B)(implicit trace: Trace): UIO[Either[Nothing, B]] =
    succeed(Right(b))

  /**
   * Returns an effect that accesses the runtime, which can be used to
   * (unsafely) execute tasks. This is useful for integration with legacy code
   * that must call back into ZIO code.
   */
  def runtime[R](implicit trace: Trace): URIO[R, Runtime[R]] =
    ZIO.withFiberRuntime[R, Nothing, Runtime[R]] { (state, status) =>
      val fiberRefs    = state.getFiberRefs()
      val environment  = fiberRefs.getOrDefault(FiberRef.currentEnvironment).asInstanceOf[ZEnvironment[R]]
      val runtimeFlags = status.runtimeFlags
      Exit.succeed(Runtime(environment, fiberRefs, runtimeFlags))
    }

  /**
   * Retrieves an effect that succeeds with the current runtime flags, which
   * govern behavior and features of the runtime system.
   */
  def runtimeFlags(implicit trace: Trace): ZIO[Any, Nothing, RuntimeFlags] =
    ZIO.withFiberRuntime[Any, Nothing, RuntimeFlags] { (_, status) =>
      Exit.succeed(status.runtimeFlags)
    }

  /**
   * Returns the current scope.
   */
  def scope(implicit trace: Trace): ZIO[Scope, Nothing, Scope] =
    ZIO.service[Scope]

  /**
   * Scopes all resources used in this effect to the lifetime of the effect,
   * ensuring that their finalizers are run as soon as this effect completes
   * execution, whether by success, failure, or interruption.
   *
   * {{{
   * ZIO.scoped {
   *   openFile(name).flatMap(useFile)
   * }
   * }}}
   */
  def scoped[R]: ScopedPartiallyApplied[R] =
    new ScopedPartiallyApplied[R]

  /**
   * Creates a scope, uses it to perform the specified effect, and closes the
   * scope as soon as the effect completes, whether by success, failure, or
   * interruption.
   */
  def scopedWith[R, E, A](f: Scope => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    Scope.make.flatMap(scope => f(scope).onExit(scope.close(_)))

  /**
   * Accesses the current scope and uses it to perform the specified effect.
   */
  def scopeWith[R, E, A](f: Scope => ZIO[R, E, A])(implicit trace: Trace): ZIO[R with Scope, E, A] =
    ZIO.serviceWithZIO[Scope](f)

  /**
   * Returns a new scoped workflow that runs finalizers added to the scope of
   * this workflow sequentially in the reverse of the order in which they were
   * added. Note that finalizers are run sequentially by default so this only
   * has meaning if used within a scope where finalizers are being run in
   * parallel.
   */
  def sequentialFinalizers[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.environmentWithZIO[R] { environment =>
      environment.getDynamic[Scope] match {
        case None => zio
        case Some(scope) =>
          scope.executionStrategy match {
            case ExecutionStrategy.Sequential => zio
            case _                            => scope.forkWith(ExecutionStrategy.Sequential).flatMap(_.extend[R](zio))
          }
      }
    }

  /**
   * Sets the `FiberRef` values for the fiber running this effect to the values
   * in the specified collection of `FiberRef` values.
   */
  def setFiberRefs(fiberRefs: => FiberRefs)(implicit trace: Trace): UIO[Unit] =
    ZIO.suspendSucceed(fiberRefs.setAll)

  /**
   * Sets a state in the environment to the specified value.
   */
  def setState[S: EnvironmentTag](s: => S)(implicit trace: Trace): ZIO[ZState[S], Nothing, Unit] =
    ZIO.serviceWithZIO(_.set(s))

  /**
   * Accesses the specified service in the environment of the effect.
   */
  def service[A: Tag](implicit trace: Trace): URIO[A, A] =
    serviceWith(identity)

  /**
   * Accesses the service corresponding to the specified key in the environment.
   */
  def serviceAt[Service]: ServiceAtPartiallyApplied[Service] =
    new ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified service in the environment of the effect.
   *
   * Especially useful for creating "accessor" methods on Services' companion
   * objects.
   *
   * {{{
   * def foo(int: Int) = ZIO.serviceWith[Foo](_.foo(int))
   * }}}
   */
  def serviceWith[Service]: ServiceWithPartiallyApplied[Service] =
    new ServiceWithPartiallyApplied[Service]

  /**
   * Effectfully accesses the specified service in the environment of the
   * effect.
   *
   * Especially useful for creating "accessor" methods on Services' companion
   * objects.
   *
   * {{{
   * def foo(int: Int) = ZIO.serviceWithZIO[Foo](_.foo(int))
   * }}}
   */
  def serviceWithZIO[Service]: ServiceWithZIOPartiallyApplied[Service] =
    new ServiceWithZIOPartiallyApplied[Service]

  /**
   * Returns an effect that shifts execution to the specified executor. This is
   * useful to specify a default executor that effects sequenced after this one
   * will be run on if they are not shifted somewhere else. It can also be used
   * to implement higher level operators to manage where an effect is run such
   * as [[ZIO!.onExecutor]] and [[ZIO!.onExecutionContext]].
   */
  def shift(executor: => Executor)(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberRuntime, _) =>
      val newExecutor = executor
      fiberRuntime.getFiberRef(FiberRef.overrideExecutor) match {
        case None =>
          fiberRuntime.setFiberRef(FiberRef.overrideExecutor, Some(newExecutor))
          fiberRuntime.getRunningExecutor() match {
            case Some(runningExecutor) if runningExecutor == newExecutor => Exit.unit
            case _                                                       => ZIO.yieldNow
          }
        case Some(overrideExecutor) =>
          if (overrideExecutor eq newExecutor) Exit.unit
          else {
            fiberRuntime.setFiberRef(FiberRef.overrideExecutor, Some(newExecutor))
            ZIO.yieldNow
          }
      }
    }

  /**
   * Returns an effect that suspends for the specified duration. This method is
   * asynchronous, and does not actually block the fiber executing the effect.
   */
  def sleep(duration: => Duration)(implicit trace: Trace): UIO[Unit] =
    Clock.sleep(duration)

  /**
   * Returns an effect with the optional value.
   */
  def some[A](a: => A)(implicit trace: Trace): UIO[Option[A]] =
    succeed(Some(a))

  /**
   * Provides a stateful ZIO workflow with its initial state, resulting in a
   * workflow that is ready to be run.
   *
   * {{{
   * ZIO.stateful(0) {
   *   for {
   *       _     <- ZIO.updateState[Int](_ + 1)
   *       state <- ZIO.getState[Int]
   *     } yield assertTrue(state == 1)
   *   }
   * }}}
   */
  def stateful[R]: StatefulPartiallyApplied[R] =
    new StatefulPartiallyApplied[R]

  /**
   * Provides a stateful ZIO workflow with its initial state, using the
   * specified patch type to combine updates to the state by different fibers in
   * a compositional way.
   */
  def statefulPatch[R]: StatefulPatchPartiallyApplied[R] =
    new StatefulPatchPartiallyApplied[R]

  def succeedBlockingUnsafe[A](a: Unsafe => A)(implicit trace: Trace): UIO[A] =
    ZIO.blocking(ZIO.succeedUnsafe(a))

  def succeedUnsafe[A](a: Unsafe => A)(implicit trace: Trace): UIO[A] =
    ZIO.succeed(a(Unsafe))

  /**
   * Returns a lazily constructed effect, whose construction may itself require
   * effects. When no environment is required (i.e., when R == Any) it is
   * conceptually equivalent to `flatten(effect(io))`.
   */
  def suspend[R, A](rio: => RIO[R, A])(implicit trace: Trace): RIO[R, A] =
    ZIO.suspendSucceed {
      try rio
      catch {
        case t: Throwable =>
          ZIO.isFatalWith { isFatal =>
            if (!isFatal(t)) Exit.Failure(Cause.fail(t))
            else throw t
          }
      }
    }

  /**
   * Returns a lazily constructed effect, whose construction may itself require
   * effects. The effect must not throw any exceptions. When no environment is
   * required (i.e., when R == Any) it is conceptually equivalent to
   * `flatten(succeed(zio))`. If you wonder if the effect throws exceptions, do
   * not use this method, use [[ZIO.suspend]].
   */
  def suspendSucceed[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.succeed(zio).flatMap(identityFn)

  def suspendSucceedUnsafe[R, E, A](zio: Unsafe => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.succeedUnsafe(zio).flatMap(identityFn)

  /**
   * Retrieves the `System` service for this workflow.
   */
  def system(implicit trace: Trace): UIO[System] =
    ZIO.systemWith(ZIO.successFn)

  /**
   * Retrieves the `System` service for this workflow and uses it to run the
   * specified workflow.
   */
  def systemWith[R, E, A](f: System => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.get(System.tag)))

  /**
   * Capture ZIO stack trace at the current point.
   */
  def stackTrace(implicit trace: Trace): UIO[StackTrace] =
    GenerateStackTrace(trace)

  /**
   * Tags each metric in this effect with the specific tag.
   */
  def tagged(key: => String, value: => String): Tagged =
    tagged(Set(MetricLabel(key, value)))

  /**
   * Tags each metric in this effect with the specific tag.
   */
  def tagged(tag: => MetricLabel, tags: MetricLabel*): Tagged =
    tagged(Set(tag) ++ tags.toSet)

  /**
   * Tags each metric in this effect with the specific tag.
   */
  def tagged(tags: => Set[MetricLabel]): Tagged =
    new Tagged(() => tags)

  def taggedScoped(key: => String, value: => String)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    taggedScoped(Set(MetricLabel(key, value)))

  def taggedScoped(tag: => MetricLabel, tags: MetricLabel*)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    taggedScoped(Set(tag) ++ tags.toSet)

  def taggedScoped(tags: => Set[MetricLabel])(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    FiberRef.currentTags.locallyScopedWith(_ ++ tags)

  /**
   * Retrieves the metric tags associated with the current scope.
   */
  def tags(implicit trace: Trace): ZIO[Any, Nothing, Set[MetricLabel]] =
    FiberRef.currentTags.get

  /**
   * Transplants specified effects so that when those effects fork other
   * effects, the forked effects will be governed by the scope of the fiber that
   * executes this effect.
   *
   * This can be used to "graft" deep grandchildren onto a higher-level scope,
   * effectively extending their lifespans into the parent scope.
   */
  def transplant[R, E, A](f: Grafter => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.withFiberRuntime[R, E, A] { (fiberState, _) =>
      f(Grafter(fiberState))
    }

  /**
   * An effect that succeeds with a unit value.
   */
  val unit: UIO[Unit] =
    succeed(())(Trace.empty)

  /**
   * Prefix form of `ZIO#uninterruptible`.
   */
  def uninterruptible[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio).uninterruptible

  /**
   * Makes the effect uninterruptible, but passes it a restore function that can
   * be used to restore the inherited interruptibility from whatever region the
   * effect is composed into.
   */
  def uninterruptibleMask[R, E, A](
    f: ZIO.InterruptibilityRestorer => ZIO[R, E, A]
  )(implicit trace: Trace): ZIO[R, E, A] =
    interruptionMasked(enable = false, f)

  /**
   * The moral equivalent of `if (!p) Some(exp) else None`
   */
  def unless[R, E, A](p: => Boolean)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, Option[A]] =
    suspendSucceed(if (p) Exit.none else zio.asSome)

  /**
   * The moral equivalent of `if (!p) { expr; () }`
   */
  def unlessDiscard[R, E](p: => Boolean)(zio: => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    suspendSucceed(if (p) Exit.unit else zio.unit)

  /**
   * The moral equivalent of `if (!p) Some(expr) else None` when `p` has
   * side-effects
   */
  def unlessZIO[R, E](p: => ZIO[R, E, Boolean]): ZIO.UnlessZIO[R, E] =
    new ZIO.UnlessZIO(() => p)

  /**
   * The moral equivalent of `if (!p) { expr; () }` when `p` has side-effects
   */
  def unlessZIODiscard[R, E](p: => ZIO[R, E, Boolean]): ZIO.UnlessZIODiscard[R, E] =
    new ZIO.UnlessZIODiscard(() => p)

  /**
   * The inverse operation `IO.sandboxed`
   *
   * Terminates with exceptions on the `Left` side of the `Either` error, if it
   * exists. Otherwise extracts the contained `IO[E, A]`
   */
  def unsandbox[R, E, A](v: => ZIO[R, Cause[E], A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(v.mapErrorCause(_.flatten))

  /**
   * Returns an effect indicating that execution is no longer required to be
   * performed on the current executor. The runtime may continue executing on
   * this executor for efficiency but will not automatically shift back to it
   * after completing an effect on another executor.
   */
  def unshift(implicit trace: Trace): UIO[Unit] =
    FiberRef.overrideExecutor.set(None)

  /**
   * Updates the `FiberRef` values for the fiber running this effect using the
   * specified function.
   */
  def updateFiberRefs(f: (FiberId.Runtime, FiberRefs) => FiberRefs)(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (state, _) =>
      state.setFiberRefs(f(state.id, state.getFiberRefs()))

      Exit.unit
    }

  /**
   * Updates the runtime flags. This may have a performance impact. For a
   * higher-performance variant, see `ZIO#withRuntimeFlags`.
   */
  def updateRuntimeFlags(patch: RuntimeFlags.Patch)(implicit trace: Trace): ZIO[Any, Nothing, Unit] =
    if (patch == RuntimeFlags.Patch.empty) Exit.unit
    else ZIO.UpdateRuntimeFlags(trace, patch)

  /**
   * Updates a state in the environment with the specified function.
   */
  def updateState[S: EnvironmentTag](f: S => S)(implicit trace: Trace): ZIO[ZState[S], Nothing, Unit] =
    ZIO.serviceWithZIO(_.update(f))

  /**
   * Scopes all resources acquired by `resource` to the lifetime of `use`
   * without effecting the scope of any resources acquired by `use`.
   */
  def using[R]: UsingPartiallyApplied[R] =
    new ZIO.UsingPartiallyApplied[R]

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors in error
   * channel or successes in success channel.
   *
   * This combinator is lossy meaning that if there are errors all successes
   * will be lost. To retain all information please use [[partition]].
   */
  def validate[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit
    bf: BuildFrom[Collection[A], B, Collection[B]],
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, ::[E], Collection[B]] =
    partition(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) Exit.succeed(bf.fromSpecific(in)(bs))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors in error
   * channel or successes in success channel.
   *
   * This combinator is lossy meaning that if there are errors all successes
   * will be lost. To retain all information please use [[partition]].
   */
  def validate[R, E, A, B](in: NonEmptyChunk[A])(
    f: A => ZIO[R, E, B]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R, ::[E], NonEmptyChunk[B]] =
    partition(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) Exit.succeed(NonEmptyChunk.nonEmpty(Chunk.fromIterable(bs)))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors, discarding
   * the successes.
   */
  def validateDiscard[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, ::[E], Unit] =
    partition(in)(f).flatMap { case (es, _) =>
      if (es.isEmpty) Exit.unit
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f `and accumulates, in parallel, all errors
   * in error channel or successes in success channel.
   *
   * This combinator is lossy meaning that if there are errors all successes
   * will be lost. To retain all information please use [[partitionPar]].
   */
  def validatePar[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit
    bf: BuildFrom[Collection[A], B, Collection[B]],
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, ::[E], Collection[B]] =
    partitionPar(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) Exit.succeed(bf.fromSpecific(in)(bs))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f `and accumulates, in parallel, all errors
   * in error channel or successes in success channel.
   *
   * This combinator is lossy meaning that if there are errors all successes
   * will be lost. To retain all information please use [[partitionPar]].
   */
  def validatePar[R, E, A, B](in: NonEmptyChunk[A])(
    f: A => ZIO[R, E, B]
  )(implicit ev: CanFail[E], trace: Trace): ZIO[R, ::[E], NonEmptyChunk[B]] =
    partitionPar(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) Exit.succeed(NonEmptyChunk.nonEmpty(Chunk.fromIterable(bs)))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` in parallel and accumulates all errors,
   * discarding the successes.
   */
  def validateParDiscard[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, ::[E], Unit] =
    partitionPar(in)(f).flatMap { case (es, _) =>
      if (es.isEmpty) Exit.unit
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` until it succeeds. Returns first success
   * or the accumulation of all errors.
   */
  def validateFirst[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit
    bf: BuildFrom[Collection[A], E, Collection[E]],
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, Collection[E], B] =
    ZIO.foreach(in)(f(_).flip).flip

  /**
   * Feeds elements of type `A` to `f`, in parallel, until it succeeds. Returns
   * first success or the accumulation of all errors.
   *
   * In case of success all other running fibers are terminated.
   */
  def validateFirstPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit
    bf: BuildFrom[Collection[A], E, Collection[E]],
    ev: CanFail[E],
    trace: Trace
  ): ZIO[R, Collection[E], B] =
    ZIO.foreachPar(in)(f(_).flip).flip

  /**
   * The moral equivalent of `if (p) Some(exp) else None`
   */
  def when[R, E, A](p: => Boolean)(zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, Option[A]] =
    suspendSucceed(if (p) zio.asSome else Exit.none)

  /**
   * The moral equivalent of `if (p) { expr; () }`
   */
  def whenDiscard[R, E](p: => Boolean)(zio: => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    suspendSucceed(if (p) zio.unit else Exit.unit)

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given
   * value, otherwise does nothing.
   */
  def whenCase[R, E, A, B](a: => A)(pf: PartialFunction[A, ZIO[R, E, B]])(implicit
    trace: Trace
  ): ZIO[R, E, Option[B]] =
    suspendSucceed(pf.andThen(_.asSome).applyOrElse(a, (_: A) => Exit.none))

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given
   * value, and discards the result, otherwise does nothing.
   */
  def whenCaseDiscard[R, E, A](a: => A)(pf: PartialFunction[A, ZIO[R, E, Any]])(implicit
    trace: Trace
  ): ZIO[R, E, Unit] =
    suspendSucceed(pf.andThen(_.unit).applyOrElse(a, (_: A) => Exit.unit))

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given
   * effectful value, otherwise does nothing.
   */
  def whenCaseZIO[R, E, A, B](a: => ZIO[R, E, A])(pf: PartialFunction[A, ZIO[R, E, B]])(implicit
    trace: Trace
  ): ZIO[R, E, Option[B]] =
    ZIO.suspendSucceed(a.flatMap(whenCase(_)(pf)))

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given
   * effectful value, and discards the value, otherwise does nothing.
   */
  def whenCaseZIODiscard[R, E, A](a: => ZIO[R, E, A])(pf: PartialFunction[A, ZIO[R, E, Any]])(implicit
    trace: Trace
  ): ZIO[R, E, Unit] =
    suspendSucceed(a.flatMap(whenCaseDiscard(_)(pf)))

  /**
   * The moral equivalent of `if (p) exp` when `p` has side-effects
   */
  def whenZIO[R, E](p: => ZIO[R, E, Boolean]): ZIO.WhenZIO[R, E] =
    new ZIO.WhenZIO(() => p)

  /**
   * The moral equivalent of `if (p) exp: Unit` when `p` has side-effects
   */
  def whenZIODiscard[R, E](p: => ZIO[R, E, Boolean]): ZIO.WhenZIODiscard[R, E] =
    new ZIO.WhenZIODiscard(() => p)

  /**
   * Locally installs a supervisor and an effect that succeeds with all the
   * children that have been forked in the returned effect.
   */
  def withChildren[R, E, A](
    get: UIO[Chunk[Fiber.Runtime[Any, Any]]] => ZIO[R, E, A]
  )(implicit trace: Trace): ZIO[R, E, A] =
    Supervisor.track(true).flatMap { supervisor =>
      // Filter out the fiber id of whoever is calling this:
      get(supervisor.value.flatMap(children => ZIO.descriptor.map(d => children.filter(_.id != d.id))))
        .supervised(supervisor)
    }

  /**
   * Executes the specified workflow with the specified implementation of the
   * clock service.
   */
  def withClock[R, E, A <: Clock, B](clock: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    DefaultServices.currentServices.locallyWith(_.add(clock))(zio)

  /**
   * Sets the implementation of the clock service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withClockScoped[A <: Clock](clock: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    DefaultServices.currentServices.locallyScopedWith(_.add(clock))

  /**
   * Executes the specified workflow with the specified configuration provider.
   */
  def withConfigProvider[R, E, A <: ConfigProvider, B](configProvider: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    DefaultServices.currentServices.locallyWith(_.add(configProvider))(zio)

  /**
   * Sets the configuration provider to the specified value and restores it to
   * its original value when the scope is closed.
   */
  def withConfigProviderScoped[A <: ConfigProvider](
    configProvider: => A
  )(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    DefaultServices.currentServices.locallyScopedWith(_.add(configProvider))

  /**
   * Executes the specified workflow with the specified implementation of the
   * console service.
   */
  def withConsole[R, E, A <: Console, B](console: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    DefaultServices.currentServices.locallyWith(_.add(console))(zio)

  /**
   * Sets the implementation of the console service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withConsoleScoped[A <: Console](console: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    DefaultServices.currentServices.locallyScopedWith(_.add(console))

  /**
   * Executed this workflow with the specified logger added.
   */
  def withLogger[R, E, A <: ZLogger[String, Any], B](logger: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    FiberRef.currentLoggers.locallyWith(_ + logger)(zio)

  /**
   * Adds the specified logger and removes it when the scope is closed.
   */
  def withLoggerScoped[A <: ZLogger[String, Any]](
    logger: => A
  )(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    FiberRef.currentLoggers.locallyScopedWith(_ + logger)

  /**
   * Runs the specified effect with the specified maximum number of fibers for
   * parallel operators.
   */
  def withParallelism[R, E, A](n: => Int)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(Parallelism.locally(Some(n))(zio))

  /**
   * Runs the specified effect with the specified maximum number of fibers for
   * parallel operators, but passes it a restore function that can be used to
   * restore the inherited parallelism from whatever region the effect is
   * composed into.
   */
  def withParallelismMask[R, E, A](n: => Int)(f: ZIO.ParallelismRestorer => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    Parallelism.getWith {
      case Some(n0) => Parallelism.locally(Some(n))(f(ParallelismRestorer.MakeParallel(n0)))
      case None     => Parallelism.locally(Some(n))(f(ParallelismRestorer.MakeParallelUnbounded))
    }

  /**
   * Runs the specified effect with an unbounded maximum number of fibers for
   * parallel operators.
   */
  def withParallelismUnbounded[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.suspendSucceed(Parallelism.locally(None)(zio))

  /**
   * Runs the specified effect with an unbounded maximum number of fibers for
   * parallel operators, but passes it a restore function that can be used to
   * restore the inherited parallelism from whatever region the effect is
   * composed into.
   */
  def withParallelismUnboundedMask[R, E, A](f: ZIO.ParallelismRestorer => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    Parallelism.getWith {
      case Some(n) => Parallelism.locally(None)(f(ParallelismRestorer.MakeParallel(n)))
      case None    => Parallelism.locally(None)(f(ParallelismRestorer.MakeParallelUnbounded))
    }

  /**
   * Executes the specified workflow with the specified implementation of the
   * random service.
   */
  def withRandom[R, E, A <: Random, B](random: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    DefaultServices.currentServices.locallyWith(_.add(random))(zio)

  /**
   * Sets the implementation of the random service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withRandomScoped[A <: Random](random: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    DefaultServices.currentServices.locallyScopedWith(_.add(random))

  def withRuntimeFlagsScoped(update: RuntimeFlags.Patch)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    if (update == RuntimeFlags.Patch.empty) {
      ZIO.unit
    } else {
      ZIO.uninterruptible {
        ZIO.runtimeFlags.flatMap { runtimeFlags =>
          val updatedRuntimeFlags = RuntimeFlags.Patch.patch(update)(runtimeFlags)
          val revertRuntimeFlags  = RuntimeFlags.diff(updatedRuntimeFlags, runtimeFlags)
          ZIO.updateRuntimeFlags(update) *>
            ZIO.addFinalizer(ZIO.updateRuntimeFlags(revertRuntimeFlags)).unit
        }
      }
    }

  /**
   * Executes the specified workflow with the specified implementation of the
   * system service.
   */
  def withSystem[R, E, A <: System, B](system: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    DefaultServices.currentServices.locallyWith(_.add(system))(zio)

  /**
   * Sets the implementation of the system service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withSystemScoped[A <: System](system: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    DefaultServices.currentServices.locallyScopedWith(_.add(system))

  /**
   * Returns an effect that yields to the runtime system, starting on a fresh
   * stack. Manual use of this method can improve fairness, at the cost of
   * overhead.
   */
  def yieldNow(implicit trace: Trace): UIO[Unit] = ZIO.YieldNow(trace, false)

  private[zio] def withFiberRuntime[R, E, A](
    onState: (Fiber.Runtime[E, A], Fiber.Status.Running) => ZIO[R, E, A]
  )(implicit trace: Trace): ZIO[R, E, A] =
    Stateful(trace, onState)

  private final val MaxIterationsPerYield = 512

  private val _IdentityFn: Any => Any = (a: Any) => a

  private val _FailFn: Any => Exit[Any, Nothing] = a => Exit.Failure(Cause.fail(a))

  private val _SuccessFn: Any => Exit[Nothing, Any] = a => Exit.Success(a)

  private val _SecondFn: (Any, Any) => Any = (_: Any, b: Any) => b

  private[zio] def secondFn[A]: (Any, A) => A = _SecondFn.asInstanceOf[(Any, A) => A]

  private[zio] def identityFn[A]: A => A = _IdentityFn.asInstanceOf[A => A]

  private[zio] def failFn[E]: E => Exit[E, Nothing] = _FailFn.asInstanceOf[E => Exit[E, Nothing]]

  private[zio] def successFn[A]: A => Exit[Nothing, A] = _SuccessFn.asInstanceOf[A => Exit[Nothing, A]]

  private[zio] def partitionMap[A, A1, A2](
    iterable: Iterable[A]
  )(f: A => Either[A1, A2]): (Iterable[A1], Iterable[A2]) =
    iterable.foldRight((List.empty[A1], List.empty[A2])) { case (a, (es, bs)) =>
      f(a).fold(
        e => (e :: es, bs),
        b => (es, b :: bs)
      )
    }

  private[zio] val unitFn: Any => Unit    = (_: Any) => ()
  private val unitZIOFn: Any => UIO[Unit] = (_: Any) => Exit.unit

  implicit final class ZIOAutoCloseableOps[R, E, A <: AutoCloseable](private val io: ZIO[R, E, A]) extends AnyVal {

    /**
     * Like `acquireReleaseWith`, safely wraps a use and release of a resource.
     * This resource will get automatically closed, because it implements
     * `AutoCloseable`.
     */
    def acquireReleaseWithAuto[R1 <: R, E1 >: E, B](use: A => ZIO[R1, E1, B])(implicit
      trace: Trace
    ): ZIO[R1, E1, B] =
      acquireReleaseWith(io)(a => ZIO.succeed(a.close()))(use)

    /**
     * Like `withFinalizer, add a finalizer from AutoClosable.
     */
    def withFinalizerAuto(implicit trace: Trace): ZIO[R with Scope, E, A] =
      ZIO.acquireRelease(io)(a => ZIO.succeed(a.close()))
  }

  implicit final class ZIOBooleanOps[R, E](private val self: ZIO[R, E, Boolean]) extends AnyVal {

    /**
     * Returns the logical conjunction of the `Boolean` value returned by this
     * effect and the `Boolean` value returned by the specified effect. This
     * operator has "short circuiting" behavior so if the value returned by this
     * effect is false the specified effect will not be evaluated.
     */
    final def &&[R1 <: R, E1 >: E](
      that: => ZIO[R1, E1, Boolean]
    )(implicit trace: Trace): ZIO[R1, E1, Boolean] =
      self.flatMap(a => if (a) that else Exit.`false`)

    /**
     * Returns the logical conjunction of the `Boolean` value returned by this
     * effect and the `Boolean` value returned by the specified effect. This
     * operator has "short circuiting" behavior so if the value returned by this
     * effect is true the specified effect will not be evaluated.
     */
    final def ||[R1 <: R, E1 >: E](
      that: => ZIO[R1, E1, Boolean]
    )(implicit trace: Trace): ZIO[R1, E1, Boolean] =
      self.flatMap(a => if (a) Exit.`true` else that)
  }

  implicit final class ZioRefineToOrDieOps[R, E <: Throwable, A](private val self: ZIO[R, E, A]) extends AnyVal {

    /**
     * Keeps some of the errors, and terminates the fiber with the rest.
     */
    def refineToOrDie[E1 <: E: ClassTag](implicit ev: CanFail[E], trace: Trace): ZIO[R, E1, A] =
      self.refineOrDie { case e: E1 => e }
  }

  final class ProvideSomeLayer[R0, -R, +E, +A](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: => ZLayer[R0, E1, R1]
    )(implicit
      ev: R0 with R1 <:< R,
      tagged: EnvironmentTag[R1],
      trace: Trace
    ): ZIO[R0, E1, A] =
      self.asInstanceOf[ZIO[R0 with R1, E, A]].provideLayer(ZLayer.environment[R0] ++ layer)
  }

  final class UpdateService[-R, +E, +A, M](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R with M](
      f: M => M
    )(implicit tag: Tag[M], trace: Trace): ZIO[R1, E, A] =
      self.provideSomeEnvironment(_.update(f))
  }

  final class UpdateServiceAt[-R, +E, +A, Service](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R with Map[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit tag: Tag[Map[Key, Service]], trace: Trace): ZIO[R1, E, A] =
      self.provideSomeEnvironment(_.updateAt(key)(f))
  }

  @implicitNotFound(
    "Pattern guards are only supported when the error type is a supertype of NoSuchElementException. However, your effect has ${E} for the error type."
  )
  abstract class CanFilter[+E] {
    def apply(t: NoSuchElementException): E
  }

  object CanFilter {
    implicit def canFilter[E >: NoSuchElementException]: CanFilter[E] =
      new CanFilter[E] {
        def apply(t: NoSuchElementException): E = t
      }
  }

  final class Grafter(private val scope: FiberScope) extends AnyVal { self =>
    def apply[R, E, A](zio: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      FiberRef.forkScopeOverride.locally(Some(scope))(zio)

    /**
     * Similar to [[apply]] but delays the transfer of children spawned by the
     * effect until the effect has completed, either by success or failure.
     *
     * This is more performant than [[apply]] as:
     *   1. it doesn't require updating [[FiberRef.forkScopeOverride]] which has
     *      a "complex" join
     *   1. only fibers that are still active by the time the effect completes
     *      are transferred.
     *
     * @see
     *   [[applyOnExitWith]] for variant that allows providing a callback
     *   function
     */
    private[zio] def applyOnExit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      applyOnExitWith(zio)(ZIO.unitFn)

    /**
     * More powerful variant of [[applyOnExit]] that allows providing a callback
     * function that is guaranteed to be invoked ''after'' the children have
     * been transferred.
     */
    private[zio] def applyOnExitWith[R, E, A](
      zio: ZIO[R, E, A]
    )(
      ensuring: Exit[E, A] => Unit
    )(implicit trace: Trace): ZIO[R, E, A] =
      ZIO.uninterruptibleMask { restore =>
        restore(zio).exitWith { exit =>
          ZIO.withFiberRuntime[Any, E, A] { (fiber, _) =>
            fiber.transferChildren(self.scope)
            ensuring(exit)
            exit
          }
        }
      }
  }

  object Grafter {

    private[zio] def apply(fiberState: Fiber.Runtime[?, ?])(implicit trace: Trace): Grafter = {
      val scopeOverride = fiberState.getFiberRefOrNull(FiberRef.forkScopeOverride)
      val scope =
        if ((scopeOverride eq null) || (scopeOverride eq None)) fiberState.scope
        else scopeOverride.get

      new Grafter(scope)
    }
  }

  final class IfZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, A](onTrue: => ZIO[R1, E1, A], onFalse: => ZIO[R1, E1, A])(implicit
      trace: Trace
    ): ZIO[R1, E1, A] =
      ZIO.suspendSucceed(b().flatMap(b => if (b) onTrue else onFalse))
  }

  final class UnlessZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, A](zio: => ZIO[R1, E1, A])(implicit trace: Trace): ZIO[R1, E1, Option[A]] =
      ZIO.suspendSucceed(b()).flatMap(b => if (b) Exit.none else zio.asSome)
  }

  final class UnlessZIODiscard[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E](zio: => ZIO[R1, E1, Any])(implicit trace: Trace): ZIO[R1, E1, Unit] =
      ZIO.suspendSucceed(b()).flatMap(b => if (b) Exit.unit else zio.unit)
  }

  final class WhenZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, A](zio: => ZIO[R1, E1, A])(implicit trace: Trace): ZIO[R1, E1, Option[A]] =
      ZIO.suspendSucceed(b()).flatMap(b => if (b) zio.asSome else Exit.none)
  }

  final class WhenZIODiscard[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E](zio: => ZIO[R1, E1, Any])(implicit trace: Trace): ZIO[R1, E1, Unit] =
      ZIO.suspendSucceed(b()).flatMap(b => if (b) zio.unit else Exit.unit)
  }

  final class TimeoutTo[-R, +E, +A, +B](self: ZIO[R, E, A], b: () => B) {
    def apply[B1 >: B](f: A => B1)(duration: => Duration)(implicit
      trace: Trace
    ): ZIO[R, E, B1] =
      ZIO.fiberIdWith { parentFiberId =>
        self.raceFibersWith[R, Nothing, E, Unit, B1](ZIO.sleep(duration).interruptible)(
          (winner, loser) =>
            winner.await.flatMap {
              case Exit.Success(a) =>
                winner.inheritAll *> loser.interruptAs(parentFiberId).as(f(a))
              case Exit.Failure(cause) =>
                winner.inheritAll *> loser.interruptAs(parentFiberId) *> Exit.failCause(cause)
            },
          (winner, loser) =>
            winner.await.flatMap {
              case _: Exit.Success[?] =>
                loser.inheritAll *> loser.interruptAs(parentFiberId).as(b())
              case Exit.Failure(cause) =>
                loser.inheritAll *> loser.interruptAs(parentFiberId) *> Exit.failCause(cause)
            },
          null,
          FiberScope.global
        )
      }
  }

  final class Acquire[-R, +E, +A](private val acquire: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1](release: A => URIO[R1, Any]): Release[R with R1, E, A] =
      new Release[R with R1, E, A](acquire, release)
  }
  final class Release[-R, +E, +A](acquire: () => ZIO[R, E, A], release: A => URIO[R, Any]) {
    def apply[R1 <: R, E1 >: E, B](use: A => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
      acquireReleaseExitWith(acquire())((a: A, _: Exit[E1, B]) => release(a))(use)
  }

  final class AcquireExit[-R, +E, +A](private val acquire: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, B](
      release: (A, Exit[E1, B]) => URIO[R1, Any]
    ): ReleaseExit[R1, E, E1, A, B] =
      new ReleaseExit(acquire, release)
  }
  final class ReleaseExit[-R, +E, E1, +A, B](
    acquire: () => ZIO[R, E, A],
    release: (A, Exit[E1, B]) => URIO[R, Any]
  ) {
    def apply[R1 <: R, E2 >: E <: E1, B1 <: B](use: A => ZIO[R1, E2, B1])(implicit
      trace: Trace
    ): ZIO[R1, E2, B1] =
      ZIO.uninterruptibleMask[R1, E2, B1](restore =>
        acquire().flatMap { a =>
          attemptOrDieZIO(restore(use(a))).exitWith { e =>
            attemptOrDieZIO(release(a, e)).foldCauseZIO(
              cause2 => Exit.failCause(e.foldExit(_ ++ cause2, _ => cause2)),
              _ => e
            )
          }
        }
      )
  }

  final class EnvironmentWithPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: ZEnvironment[R] => A)(implicit trace: Trace): URIO[R, A] =
      ZIO.environmentWithZIO(env => ZIO.succeed(f(env)))
  }

  final class EnvironmentWithZIOPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1 <: R, E, A](f: ZEnvironment[R] => ZIO[R1, E, A])(implicit trace: Trace): ZIO[R with R1, E, A] =
      FiberRef.currentEnvironment.getWith(env => f(env.asInstanceOf[ZEnvironment[R]]))
  }

  final class ScopedPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      Scope.make.flatMap(_.use[R](zio))
  }

  final class UsingPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[R1, E, A, B](
      resource: ZIO[R with Scope, E, A]
    )(use: A => ZIO[R1, E, B])(implicit trace: Trace): ZIO[R with R1, E, B] =
      ZIO.scopedWith(_.extend[R](resource).flatMap(use))
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit tag: EnvironmentTag[Map[Key, Service]], trace: Trace): URIO[Map[Key, Service], Option[Service]] =
      ZIO.environmentWith(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](
      f: Service => A
    )(implicit tagged: Tag[Service], trace: Trace): ZIO[Service, Nothing, A] =
      ZIO.serviceWithZIO(service => ZIO.succeed(f(service)))
  }

  final class ServiceWithZIOPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[R <: Service, E, A](
      f: Service => ZIO[R, E, A]
    )(implicit
      tagged: Tag[Service],
      trace: Trace
    ): ZIO[R with Service, E, A] = {
      val tag = tagged.tag
      FiberRef.currentEnvironment.getWith(environment => f(environment.unsafe.get(tag)(Unsafe)))
    }
  }

  final class StatefulPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[S, E, A](
      s: S
    )(zio: => ZIO[ZState[S] with R, E, A])(implicit tag: EnvironmentTag[S], trace: Trace): ZIO[R, E, A] =
      zio.provideSomeLayer[R](ZState.initial(s))
  }

  final class StatefulPatchPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[State, Patch, E, A](
      state: State,
      differ: Differ[State, Patch]
    )(zio: => ZIO[ZState[State] with R, E, A])(implicit
      tag: EnvironmentTag[State],
      trace: Trace
    ): ZIO[R, E, A] =
      zio.provideSomeLayer[R](ZState.initialPatch(state, differ))
  }

  final class GetStateWithPartiallyApplied[S](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: S => A)(implicit tag: EnvironmentTag[S], trace: Trace): ZIO[ZState[S], Nothing, A] =
      ZIO.serviceWithZIO(_.get.map(f))
  }

  final class LogSpan(val label: () => String) extends AnyVal {
    import zio.{LogSpan => ZioLogSpan}

    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      FiberRef.currentLogSpan.getWith { stack =>
        val instant = java.lang.System.currentTimeMillis()
        val logSpan = ZioLogSpan(label(), instant)

        FiberRef.currentLogSpan.locally(logSpan :: stack)(zio)
      }
  }

  final class LogAnnotate(val annotations: () => Set[LogAnnotation]) { self =>
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      FiberRef.currentLogAnnotations.locallyWith(_ ++ annotations().map { case LogAnnotation(key, value) =>
        key -> value
      })(zio)
  }

  final class Tagged(val tags: () => Set[MetricLabel]) { self =>
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      FiberRef.currentTags.locallyWith(_ ++ tags())(zio)
  }

  @inline
  private def succeedLeft[E, A]: E => UIO[Either[E, A]] =
    _succeedLeft.asInstanceOf[E => UIO[Either[E, A]]]

  private val _succeedLeft: Any => IO[Any, Either[Any, Any]] =
    e2 => Exit.succeed[Either[Any, Any]](Left(e2))

  @inline
  private def succeedRight[E, A]: A => UIO[Either[E, A]] =
    _succeedRight.asInstanceOf[A => UIO[Either[E, A]]]

  private val _succeedRight: Any => IO[Any, Either[Any, Any]] =
    a => Exit.succeed[Either[Any, Any]](Right(a))

  /**
   * A `ZIOConstructor[Input]` knows how to construct a `ZIO` value from an
   * input of type `Input`. This allows the type of the `ZIO` value constructed
   * to depend on `Input`. The constructed `ZIO` value is guaranteed not to
   * require any services not included in `Environment` or to be able to fail in
   * any ways not described by `Error`.
   */
  sealed trait ZIOConstructor[-Environment, +Error, In] {

    /**
     * The environment type of the `ZIO` value
     */
    type OutEnvironment >: Environment

    /**
     * The error type of the `ZIO` value.
     */
    type OutError <: Error

    /**
     * The success type the `ZIO` value.
     */
    type OutSuccess

    /**
     * Constructs a `ZIO` value from the specified input.
     */
    def make(input: => In)(implicit trace: Trace): ZIO[OutEnvironment, OutError, OutSuccess]
  }

  object ZIOConstructor extends ZIOConstructorLowPriority1 {

    /**
     * Constructs a `ZIO[Any, E, A]` from an `Either[Cause[E], A]`.
     */
    implicit def EitherCauseConstructor[E, A]: WithOut[Any, E, Either[Cause[E], A], Any, E, A] =
      new ZIOConstructor[Any, E, Either[Cause[E], A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Either[Cause[E], A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEitherCause(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from an `Either[Cause[E], A]`.
     */
    implicit def EitherCauseLeftConstructor[E, A]: WithOut[Any, E, Left[Cause[E], A], Any, E, A] =
      new ZIOConstructor[Any, E, Left[Cause[E], A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Left[Cause[E], A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEitherCause(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from an `Either[Cause[E], A]`.
     */
    implicit def EitherCauseRightConstructor[E, A]: WithOut[Any, E, Right[Cause[E], A], Any, E, A] =
      new ZIOConstructor[Any, E, Right[Cause[E], A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Right[Cause[E], A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEitherCause(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from a `Fiber[E, A].`
     */
    implicit def FiberConstructor[E, A]: WithOut[Any, E, Fiber[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Fiber[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Fiber[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromFiber(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from a `Fiber[E, A].`
     */
    implicit def FiberRuntimeConstructor[E, A]: WithOut[Any, E, Fiber.Runtime[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Fiber.Runtime[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Fiber.Runtime[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromFiber(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from a `Fiber[E, A].`
     */
    implicit def FiberSyntheticConstructor[E, A]: WithOut[Any, E, Fiber.Synthetic[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Fiber.Synthetic[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Fiber.Synthetic[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromFiber(input)
      }

    /**
     * Constructs a `ZIO[R, E, A]` from a `ZIO[R, E, Fiber[E, A]]`.
     */
    implicit def FiberZIOConstructor[R, E1 <: E3, E2 <: E3, E3, A]: WithOut[R, E3, ZIO[R, E1, Fiber[E2, A]], R, E3, A] =
      new ZIOConstructor[R, E3, ZIO[R, E1, Fiber[E2, A]]] {
        type OutEnvironment = R
        type OutError       = E3
        type OutSuccess     = A
        def make(input: => ZIO[R, E1, Fiber[E2, A]])(implicit trace: Trace): ZIO[R, E3, A] =
          ZIO.fromFiberZIO(input)
      }

    /**
     * Constructs a `ZIO[R, E, A]` from a `ZIO[R, E, Fiber[E, A]]`.
     */
    implicit def FiberZIORuntimeConstructor[R, E1 <: E3, E2 <: E3, E3, A]
      : WithOut[R, E3, ZIO[R, E1, Fiber.Runtime[E2, A]], R, E3, A] =
      new ZIOConstructor[R, E3, ZIO[R, E1, Fiber.Runtime[E2, A]]] {
        type OutEnvironment = R
        type OutError       = E3
        type OutSuccess     = A
        def make(input: => ZIO[R, E1, Fiber.Runtime[E2, A]])(implicit trace: Trace): ZIO[R, E3, A] =
          ZIO.fromFiberZIO(input)
      }

    /**
     * Constructs a `ZIO[R, E, A]` from a `ZIO[R, E, Fiber[E, A]]`.
     */
    implicit def FiberZIOSyntheticConstructor[R, E1 <: E3, E2 <: E3, E3, A]
      : WithOut[R, E3, ZIO[R, E1, Fiber.Synthetic[E2, A]], R, E3, A] =
      new ZIOConstructor[R, E3, ZIO[R, E1, Fiber.Synthetic[E2, A]]] {
        type OutEnvironment = R
        type OutError       = E3
        type OutSuccess     = A
        def make(input: => ZIO[R, E1, Fiber.Synthetic[E2, A]])(implicit trace: Trace): ZIO[R, E3, A] =
          ZIO.fromFiberZIO(input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a `Future[A]`.
     */
    implicit def FutureConstructor[A, FutureLike[A] <: scala.concurrent.Future[A]]
      : WithOut[Any, Throwable, FutureLike[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, FutureLike[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => FutureLike[A])(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.suspend(ZIO.fromFutureNow(input)(trace, Unsafe))
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a function `ExecutionContext
     * => Future[A]`.
     */
    implicit def FutureExecutionContextConstructor[A, FutureLike[A] <: scala.concurrent.Future[A]]
      : WithOut[Any, Throwable, scala.concurrent.ExecutionContext => FutureLike[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, scala.concurrent.ExecutionContext => FutureLike[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => (scala.concurrent.ExecutionContext => FutureLike[A]))(implicit
          trace: Trace
        ): ZIO[Any, Throwable, A] =
          ZIO.fromFuture(input)
      }

    /**
     * Constructs a `ZIO[Any, Option[Nothing], A]` from an `Option[A]`.
     */
    implicit def OptionConstructor[A]: WithOut[Any, Option[Nothing], Option[A], Any, Option[Nothing], A] =
      new ZIOConstructor[Any, Option[Nothing], Option[A]] {
        type OutEnvironment = Any
        type OutError       = Option[Nothing]
        type OutSuccess     = A
        def make(input: => Option[A])(implicit trace: Trace): ZIO[Any, Option[Nothing], A] =
          ZIO.fromOption(input)
      }

    /**
     * Constructs a `ZIO[Any, Option[Nothing], Nothing]` from a `None`.
     */
    implicit val OptionNoneConstructor: WithOut[Any, Option[Nothing], None.type, Any, Option[Nothing], Nothing] =
      new ZIOConstructor[Any, Option[Nothing], None.type] {
        type OutEnvironment = Any
        type OutError       = Option[Nothing]
        type OutSuccess     = Nothing
        def make(input: => None.type)(implicit trace: Trace): ZIO[Any, Option[Nothing], Nothing] =
          ZIO.fromOption(input)
      }

    /**
     * Constructs a `ZIO[Any, Option[Nothing], A]` from a `Some[A]`.
     */
    implicit def OptionSomeConstructor[A]: WithOut[Any, Option[Nothing], Some[A], Any, Option[Nothing], A] =
      new ZIOConstructor[Any, Option[Nothing], Some[A]] {
        type OutEnvironment = Any
        type OutError       = Option[Nothing]
        type OutSuccess     = A
        def make(input: => Some[A])(implicit trace: Trace): ZIO[Any, Option[Nothing], A] =
          ZIO.fromOption(input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a `Promise[A]`
     */
    implicit def PromiseScalaConstructor[A, PromiseLike[A] <: scala.concurrent.Promise[A]]
      : WithOut[Any, Throwable, PromiseLike[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, PromiseLike[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => PromiseLike[A])(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.fromPromiseScala(input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a `Try[A]`.
     */
    implicit def TryConstructor[A]: WithOut[Any, Throwable, scala.util.Try[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, scala.util.Try[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => scala.util.Try[A])(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.fromTry(input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a `Failure[A]`.
     */
    implicit def TryFailureConstructor[A]: WithOut[Any, Throwable, scala.util.Failure[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, scala.util.Failure[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => scala.util.Failure[A])(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.fromTry(input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a `Success[A]`.
     */
    implicit def TrySuccessConstructor[A]: WithOut[Any, Throwable, scala.util.Success[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, scala.util.Success[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => scala.util.Success[A])(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.fromTry(input)
      }
  }

  trait ZIOConstructorLowPriority1 extends ZIOConstructorLowPriority2 {

    /**
     * Constructs a `ZIO[Any, E, A]` from an `Either[E, A]`.
     */
    implicit def EitherConstructor[E, A]: WithOut[Any, E, Either[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Either[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Either[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEither(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]]` from an `Either[E, A]`.
     */
    implicit def EitherLeftConstructor[E, A]: WithOut[Any, E, Left[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Left[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Left[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEither(input)
      }

    /**
     * Constructs a `ZIO[Any, E, A]` from an `Either[E, A]`.
     */
    implicit def EitherRightConstructor[E, A]: WithOut[Any, E, Right[E, A], Any, E, A] =
      new ZIOConstructor[Any, E, Right[E, A]] {
        type OutEnvironment = Any
        type OutError       = E
        type OutSuccess     = A
        def make(input: => Right[E, A])(implicit trace: Trace): ZIO[Any, E, A] =
          ZIO.fromEither(input)
      }
  }

  trait ZIOConstructorLowPriority2 extends ZIOConstructorLowPriority3 {

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from an `A`.
     */
    implicit def AttemptConstructor[A]: WithOut[Any, Throwable, A, Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, A] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => A)(implicit trace: Trace): ZIO[Any, Throwable, A] =
          ZIO.attempt(input)
      }
  }

  trait ZIOConstructorLowPriority3 {

    /**
     * The type of the `ZIOConstructor` with the type of the `ZIO` value.
     */
    type WithOut[Environment, Error, In, OutEnvironment0, OutError0, OutSuccess0] =
      ZIOConstructor[Environment, Error, In] {
        type OutEnvironment = OutEnvironment0; type OutError = OutError0; type OutSuccess = OutSuccess0
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from an `A`.
     */
    implicit def SucceedConstructor[A]: WithOut[Any, Nothing, A, Any, Nothing, A] =
      new ZIOConstructor[Any, Nothing, A] {
        type OutEnvironment = Any
        type OutError       = Nothing
        type OutSuccess     = A
        def make(input: => A)(implicit trace: Trace): ZIO[Any, Nothing, A] =
          ZIO.succeed(input)
      }
  }

  private[zio] type Erased = ZIO[Any, Any, Any]

  private[zio] final case class FlatMap[R, E, A1, A2](
    trace: Trace,
    first: ZIO[R, E, A1],
    successK: A1 => ZIO[R, E, A2]
  ) extends Continuation
      with ZIO[R, E, A2]

  private[zio] sealed abstract class Continuation {
    def trace: Trace
  }
  object Continuation {
    def apply[R, E, A, B](f: A => ZIO[R, E, B])(implicit trace: Trace): Continuation =
      FlatMap[R, E, A, B](trace, null, f)
  }
  private[zio] case class FoldZIO[R, E1, E2, A1, A2](
    trace: Trace,
    first: ZIO[R, E1, A1],
    successK: A1 => ZIO[R, E2, A2],
    failureK: Cause[E1] => ZIO[R, E2, A2]
  ) extends Continuation
      with ZIO[R, E2, A2]
  private[zio] final case class Sync[A](trace: Trace, eval: () => A) extends ZIO[Any, Nothing, A]
  private[zio] final case class Async[R, E, A](
    trace: Trace,
    registerCallback: (ZIO[R, E, A] => Unit) => ZIO[R, E, A],
    blockingOn: () => FiberId
  ) extends ZIO[R, E, A]
  private[zio] final case class UpdateRuntimeFlags(trace: Trace, update: RuntimeFlags.Patch)
      extends Continuation
      with ZIO[Any, Nothing, Unit]

  private[zio] sealed trait UpdateRuntimeFlagsWithin[R, E, A] extends ZIO[R, E, A] {
    def update: RuntimeFlags.Patch
    def scope(oldRuntimeFlags: RuntimeFlags): ZIO[R, E, A]
  }
  private[zio] object UpdateRuntimeFlagsWithin extends UpdateRuntimeFlagsWithinPlatformSpecific {
    def apply[R, E, A](trace: Trace, update: RuntimeFlags.Patch, f: IntFunction[ZIO[R, E, A]]): DynamicNoBox[R, E, A] =
      DynamicNoBox(trace, update, f)

    @deprecated("Kept for bin-compat only", "2.1.7")
    final case class Interruptible[R, E, A](trace: Trace, effect: ZIO[R, E, A])
        extends UpdateRuntimeFlagsWithin[R, E, A] {
      def update: RuntimeFlags.Patch = RuntimeFlags.enableInterruption

      def scope(oldRuntimeFlags: RuntimeFlags): ZIO[R, E, A] = effect
    }
    @deprecated("Kept for bin-compat only", "2.1.7")
    final case class Uninterruptible[R, E, A](trace: Trace, effect: ZIO[R, E, A])
        extends UpdateRuntimeFlagsWithin[R, E, A] {
      def update: RuntimeFlags.Patch = RuntimeFlags.disableInterruption

      def scope(oldRuntimeFlags: RuntimeFlags): ZIO[R, E, A] = effect
    }
    @deprecated("Kept for bin-compat only", "2.1.7")
    final case class Dynamic[R, E, A](trace: Trace, update: RuntimeFlags.Patch, f: RuntimeFlags => ZIO[R, E, A])
        extends UpdateRuntimeFlagsWithin[R, E, A] {
      def scope(oldRuntimeFlags: RuntimeFlags): ZIO[R, E, A] = f(oldRuntimeFlags)
    }
    final case class DynamicNoBox[R, E, A](trace: Trace, update: RuntimeFlags.Patch, f: IntFunction[ZIO[R, E, A]])
        extends UpdateRuntimeFlagsWithin[R, E, A] {
      def scope(oldRuntimeFlags: RuntimeFlags): ZIO[R, E, A] = f(oldRuntimeFlags)
    }
  }
  private[zio] final case class GenerateStackTrace(trace: Trace) extends ZIO[Any, Nothing, StackTrace]
  private[zio] final case class Stateful[R, E, A](
    trace: Trace,
    onState: (Fiber.Runtime[E, A], Fiber.Status.Running) => ZIO[R, E, A]
  ) extends ZIO[R, E, A]
  private[zio] final case class WhileLoop[R, E, A](
    trace: Trace,
    check: () => Boolean,
    body: () => ZIO[R, E, A],
    process: A => Any
  ) extends ZIO[R, E, Unit]
  private[zio] final case class YieldNow(trace: Trace, forceAsync: Boolean) extends ZIO[Any, Nothing, Unit]

  sealed trait InterruptibilityRestorer {
    def apply[R, E, A](effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

    def isParentRegionInterruptible: Boolean

    final def isParentRegionUninterruptible: Boolean = !isParentRegionInterruptible

    final def parentInterruptStatus: InterruptStatus = InterruptStatus.fromBoolean(isParentRegionInterruptible)
  }
  object InterruptibilityRestorer {
    def apply(status: InterruptStatus): InterruptibilityRestorer =
      if (status.isInterruptible) MakeInterruptible
      else MakeUninterruptible

    def make(implicit trace: Trace): ZIO[Any, Nothing, InterruptibilityRestorer] =
      ZIO.checkInterruptible(status => ZIO.succeed(InterruptibilityRestorer(status)))

    case object MakeInterruptible extends InterruptibilityRestorer {
      def apply[R, E, A](effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.UpdateRuntimeFlagsWithin(trace, RuntimeFlags.enableInterruption, _ => effect)

      def isParentRegionInterruptible: Boolean = true
    }
    case object MakeUninterruptible extends InterruptibilityRestorer {
      def apply[R, E, A](effect: => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.UpdateRuntimeFlagsWithin(trace, RuntimeFlags.disableInterruption, _ => effect)

      def isParentRegionInterruptible: Boolean = false
    }
  }

  sealed trait ParallelismRestorer {
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  }

  object ParallelismRestorer {
    final case class MakeParallel(n: Int) extends ParallelismRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.withParallelism(n)
    }
    case object MakeParallelUnbounded extends ParallelismRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.withParallelismUnbounded
    }
  }

  sealed trait FinalizersRestorer {
    def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  }

  object FinalizersRestorer {
    def apply(executionStrategy: ExecutionStrategy): FinalizersRestorer =
      executionStrategy match {
        case ExecutionStrategy.Sequential =>
          FinalizersRestorer.MakeSequential
        case ExecutionStrategy.Parallel =>
          FinalizersRestorer.MakeParallel
        case ExecutionStrategy.ParallelN(n) =>
          FinalizersRestorer.MakeParallelN(n)
      }

    case object Identity extends FinalizersRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio
    }

    case object MakeParallel extends FinalizersRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.parallelFinalizers
    }

    final case class MakeParallelN(n: Int) extends FinalizersRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        ZIO.environmentWithZIO[R] { environment =>
          environment.getDynamic[Scope] match {
            case None => zio
            case Some(scope) =>
              scope.executionStrategy match {
                case ExecutionStrategy.ParallelN(n) => zio
                case _                              => scope.forkWith(ExecutionStrategy.ParallelN(n)).flatMap(_.extend[R](zio))
              }
          }
        }
    }

    case object MakeSequential extends FinalizersRestorer {
      def apply[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
        zio.sequentialFinalizers
    }
  }

  private[zio] val someFatal   = Some(LogLevel.Fatal)
  private[zio] val someError   = Some(LogLevel.Error)
  private[zio] val someWarning = Some(LogLevel.Warning)
  private[zio] val someInfo    = Some(LogLevel.Info)
  private[zio] val someDebug   = Some(LogLevel.Debug)
  private[zio] val someTrace   = Some(LogLevel.Trace)

  @deprecated("use succeed", "2.0.9")
  private[zio] def succeedNow[A](a: A): UIO[A] =
    succeed(a)(Trace.empty)

  private def collectAllParUnboundedDiscard[R, E, A](as: => Iterable[ZIO[R, E, A]])(implicit
    trace: Trace
  ): ZIO[R, E, Unit] =
    foreachParUnboundedDiscard(as)(ZIO.identityFn)

  private def foreachPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](n: => Int)(
    as: Collection[A]
  )(
    fn: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      val array = Array.ofDim[AnyRef](as.size)
      val zioFunction: ((A, Int)) => ZIO[R, E, Any] = { case (a, i) =>
        fn(a).flatMap(b => ZIO.succeed(array(i) = b.asInstanceOf[AnyRef]))
      }
      foreachParDiscard(n)(as.zipWithIndex)(zioFunction) *>
        ZIO.succeed(bf.fromSpecific(as)(array.asInstanceOf[Array[B]]))
    }

  private def foreachParDiscard[R, E, A](
    n: => Int
  )(as0: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      val as   = as0
      val size = as.size
      if (size == 0) Exit.unit
      else if (size == 1) f(as.head).unit
      else {

        def worker(queue: Queue[A]): ZIO[R, E, Unit] =
          queue.poll.flatMap {
            case Some(a) => f(a) *> worker(queue)
            case None    => Exit.unit
          }

        for {
          queue <- Queue.bounded[A](size)
          _     <- queue.offerAll(as)
          _     <- ZIO.collectAllParUnboundedDiscard(ZIO.replicate(n.min(size))(worker(queue)))
        } yield ()
      }
    }

  private def foreachParUnbounded[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], trace: Trace): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      val array = Array.ofDim[AnyRef](as.size)
      val zioFunction: ((A, Int)) => ZIO[R, E, Any] = { case (a, i) =>
        f(a).flatMap(b => ZIO.succeed(array(i) = b.asInstanceOf[AnyRef]))
      }
      foreachParUnboundedDiscard(as.zipWithIndex)(zioFunction) *>
        ZIO.succeed(bf.fromSpecific(as)(array.asInstanceOf[Array[B]]))
    }

  private def foreachParUnboundedDiscard[R, E, A](
    as0: => Iterable[A]
  )(f: A => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      val as   = as0
      val size = as.size
      if (size == 0) Exit.unit
      else if (size == 1) f(as.head).unit
      else {
        ZIO.uninterruptibleMask { restore =>
          val promise = Promise.unsafe.make[Unit, Unit](FiberId.None)(Unsafe)
          val ref     = new java.util.concurrent.atomic.AtomicInteger(size)
          ZIO.transplant { graft =>
            ZIO.foreach(as) { a =>
              graft
                .applyOnExitWith(restore(f(a))) {
                  case _: Exit.Success[?] => if (ref.decrementAndGet() == 0) promise.unsafe.done(Exit.unit)(Unsafe)
                  case _                  => promise.unsafe.done(Exit.failUnit)(Unsafe)
                }
                .forkDaemon
            }
          }.flatMap { fibers =>
            restore(promise.await).foldCauseZIO(
              cause =>
                ZIO
                  .foreachParUnbounded(fibers)(_.interrupt)
                  .flatMap(Exit.collectAllPar(_) match {
                    case Some(Exit.Failure(causes)) => Exit.failCause(cause.stripFailures && causes)
                    case _                          => Exit.failCause(cause.stripFailures)
                  }),
              _ => ZIO.foreachDiscard(fibers)(_.inheritAll)
            )
          }
        }
      }
    }
}

/**
 * An `Exit[E, A]` describes the result of executing an `IO` value. The result
 * is either succeeded with a value `A`, or failed with a `Cause[E]`.
 */
sealed trait Exit[+E, +A] extends ZIO[Any, E, A] { self =>
  import Exit._

  /**
   * Parallelly zips the this result with the specified result discarding the
   * first element of the tuple or else returns the failed `Cause[E1]`
   */
  final def &>[E1 >: E, B](that: Exit[E1, B]): Exit[E1, B] = zipRightWith(self, that)(_ && _)

  /**
   * Sequentially zips the this result with the specified result discarding the
   * first element of the tuple or else returns the failed `Cause[E1]`
   */
  final def *>[E1 >: E, B](that: Exit[E1, B]): Exit[E1, B] = zipRightWith(self, that)(_ ++ _)

  /**
   * Parallelly zips the this result with the specified result discarding the
   * second element of the tuple or else returns the failed `Cause[E1]`
   */
  final def <&[E1 >: E, B](that: Exit[E1, B]): Exit[E1, A] = zipRightWith(that, self)((l, r) => r && l)

  /**
   * Parallelly zips the this result with the specified result or else returns
   * the failed `Cause[E1]`
   */
  final def <&>[E1 >: E, B](that: Exit[E1, B])(implicit zippable: Zippable[A, B]): Exit[E1, zippable.Out] =
    zipWith(that)(zippable.zip(_, _), _ && _)

  /**
   * Sequentially zips the this result with the specified result discarding the
   * second element of the tuple or else returns the failed `Cause[E1]`
   */
  final def <*[E1 >: E, B](that: Exit[E1, B]): Exit[E1, A] = zipRightWith(that, self)((l, r) => r ++ l)

  /**
   * Sequentially zips the this result with the specified result or else returns
   * the failed `Cause[E1]`
   */
  final def <*>[E1 >: E, B](that: Exit[E1, B])(implicit zippable: Zippable[A, B]): Exit[E1, zippable.Out] =
    zipWith(that)(zippable.zip(_, _), _ ++ _)

  /**
   * Maps the success value of this effect to the specified constant value.
   */
  override final def as[B](b: => B)(implicit trace: Trace): ZIO[Any, E, B] =
    asExit(b)

  /**
   * Replaces the success value with the one provided.
   */
  final def asExit[B](b: B): Exit[E, B] = mapExit(_ => b)

  /**
   * Returns an option of the cause of failure.
   */
  final def causeOption: Option[Cause[E]] =
    self match {
      case Failure(cause) => Some(cause)
      case _              => None
    }

  private[zio] final def causeOrNull: Cause[E] =
    self match {
      case Failure(cause) => cause
      case _              => null.asInstanceOf[Cause[E]]
    }

  final def exists(p: A => Boolean): Boolean =
    foldExit(_ => false, p)

  override final def flatMap[R1, E1 >: E, B](k: A => ZIO[R1, E1, B])(implicit trace: Trace): ZIO[R1, E1, B] =
    self match {
      case Success(a)     => k(a)
      case e @ Failure(_) => e
    }

  /**
   * Flat maps over the value type.
   */
  final def flatMapExit[E1 >: E, A1](f: A => Exit[E1, A1]): Exit[E1, A1] =
    self match {
      case Success(a)     => f(a)
      case e @ Failure(_) => e
    }

  /**
   * Flat maps over the value type.
   */
  final def flatMapExitZIO[E1 >: E, R, E2, A1](f: A => ZIO[R, E2, Exit[E1, A1]]): ZIO[R, E2, Exit[E1, A1]] =
    self match {
      case Success(a)     => f(a)
      case e @ Failure(_) => Exit.succeed(e)
    }

  /**
   * Flattens an Exit of an Exit into a single Exit value.
   */
  final def flattenExit[E1 >: E, B](implicit ev: A <:< Exit[E1, B]): Exit[E1, B] =
    Exit.flatten(self.mapExit(ev))

  /**
   * Folds over the failure value or the success value to yield an effect that
   * does not fail, but succeeds with the value returned by the left or right
   * function passed to `fold`.
   */
  override final def fold[B](failure: E => B, success: A => B)(implicit ev: CanFail[E], trace: Trace): UIO[B] =
    foldZIO(e => Exit.succeed(failure(e)), a => Exit.succeed(success(a)))

  /**
   * A more powerful version of `fold` that allows recovering from any kind of
   * failure except external interruption.
   */
  override final def foldCause[B](failure: Cause[E] => B, success: A => B)(implicit trace: Trace): UIO[B] =
    Exit.succeed(foldExit(failure, success))

  /**
   * A more powerful version of `foldZIO` that allows recovering from any kind
   * of failure except external interruption.
   */
  override final def foldCauseZIO[R, E2, B](
    failure: Cause[E] => ZIO[R, E2, B],
    success: A => ZIO[R, E2, B]
  )(implicit trace: Trace): ZIO[R, E2, B] =
    foldExitZIO(failure, success)

  /**
   * Folds over the value or cause.
   */
  final def foldExit[Z](failed: Cause[E] => Z, completed: A => Z): Z =
    self match {
      case Success(v)     => completed(v)
      case Failure(cause) => failed(cause)
    }

  /**
   * Sequentially zips the this result with the specified result or else returns
   * the failed `Cause[E1]`
   */
  final def foldExitZIO[R, E1, B](failed: Cause[E] => ZIO[R, E1, B], completed: A => ZIO[R, E1, B])(implicit
    trace: Trace
  ): ZIO[R, E1, B] =
    self match {
      case Success(v)     => completed(v)
      case Failure(cause) => failed(cause)
    }

  /**
   * Applies the function `f` to the successful result of the `Exit` and returns
   * the result in a new `Exit`.
   */
  final def foreach[R, E1 >: E, B](f: A => ZIO[R, E1, B])(implicit trace: Trace): ZIO[R, Nothing, Exit[E1, B]] =
    foldExit(c => Exit.succeed(failCause(c)), a => f(a).exit)

  /**
   * Retrieves the `A` if succeeded, or else returns the specified default `A`.
   */
  final def getOrElse[A1 >: A](orElse: Cause[E] => A1): A1 = self match {
    case Success(value) => value
    case Failure(cause) => orElse(cause)
  }

  final def getOrThrow()(implicit ev: E <:< Throwable, unsafe: Unsafe): A =
    getOrElse(cause => throw cause.squashTrace)

  final def getOrThrowFiberFailure()(implicit unsafe: Unsafe): A =
    getOrElse(c => throw FiberFailure(c))

  /**
   * Determines if the result is a failure.
   */
  final def isFailure: Boolean = !isSuccess

  /**
   * Determines if the result is interrupted.
   */
  final def isInterrupted: Boolean = self match {
    case _: Success[?] => false
    case Failure(c)    => c.isInterrupted
  }

  final def isInterruptedOnly: Boolean = self match {
    case _: Success[?] => false
    case Failure(c)    => c.isInterruptedOnly
  }

  /**
   * Determines if the result is a success.
   */
  final def isSuccess: Boolean =
    self.isInstanceOf[Success[?]]

  /**
   * Returns an effect whose success is mapped by the specified `f` function.
   */
  override final def map[B](f: A => B)(implicit trace: Trace): ZIO[Any, E, B] =
    mapExit(f)

  /**
   * Maps over the value type.
   */
  final def mapExit[A1](f: A => A1): Exit[E, A1] =
    self match {
      case Success(v)     => Exit.succeed(f(v))
      case e @ Failure(_) => e
    }

  /**
   * Returns an effect whose failure and success channels have been mapped by
   * the specified pair of functions, `f` and `g`.
   */
  override final def mapBoth[E2, B](f: E => E2, g: A => B)(implicit ev: CanFail[E], trace: Trace): ZIO[Any, E2, B] =
    mapBothExit(f, g)

  /**
   * Maps over both the error and value type.
   */
  final def mapBothExit[E1, A1](f: E => E1, g: A => A1): Exit[E1, A1] =
    mapErrorExit(f).mapExit(g)

  /**
   * Returns an effect with its error channel mapped using the specified
   * function. This can be used to lift a "smaller" error into a "larger" error.
   */
  override final def mapError[E2](f: E => E2)(implicit ev: CanFail[E], trace: Trace): ZIO[Any, E2, A] =
    mapErrorExit(f)

  /**
   * Maps over the error type.
   */
  final def mapErrorExit[E1](f: E => E1): Exit[E1, A] =
    self match {
      case e: Success[A] => e
      case Failure(c)    => failCause(c.map(f))
    }

  /**
   * Returns an effect with its full cause of failure mapped using the specified
   * function. This can be used to transform errors while preserving the
   * original structure of `Cause`.
   *
   * @see
   *   [[absorb]], [[sandbox]], [[catchAllCause]] - other functions for dealing
   *   with defects
   */
  override final def mapErrorCause[E2](h: Cause[E] => Cause[E2])(implicit trace: Trace): ZIO[Any, E2, A] =
    mapErrorCauseExit(h)

  /**
   * Maps over the cause type.
   */
  final def mapErrorCauseExit[E1](f: Cause[E] => Cause[E1]): Exit[E1, A] =
    self match {
      case e: Success[A] => e
      case Failure(c)    => Failure(f(c))
    }

  /**
   * Executes this effect and returns its value, if it succeeds, but otherwise
   * fails with the specified error.
   */
  override final def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E], trace: Trace): ZIO[Any, E1, A] =
    orElseFailExit(e1)

  /**
   * Replaces the error value with the one provided.
   */
  final def orElseFailExit[E1](e1: => E1): Exit[E1, A] =
    mapErrorExit(_ => e1)

  /**
   * Converts the `Exit` to an `Either[Throwable, A]`, by wrapping the cause in
   * `FiberFailure` (if the result is failed).
   */
  final def toEither: Either[Throwable, A] = self match {
    case Success(value) => Right(value)
    case Failure(cause) => Left(FiberFailure(cause))
  }

  final def toTry(implicit ev: E <:< Throwable): scala.util.Try[A] =
    self match {
      case Success(value) => scala.util.Try(value)
      case Failure(cause) => scala.util.Failure(cause.squash)
    }

  final def trace: Trace = Trace.empty

  /**
   * Returns the effect resulting from mapping the success of this effect to
   * unit.
   */
  override final def unit(implicit trace: Trace): ZIO[Any, E, Unit] =
    unitExit

  /**
   * Discards the value.
   */
  final def unitExit: Exit[E, Unit] = asExit(())

  /**
   * Returns an untraced exit value.
   */
  final def untraced: Exit[E, A] = mapErrorCauseExit(_.untraced)

  /**
   * Named alias for `<*>`.
   */
  final def zip[E1 >: E, B](that: Exit[E1, B])(implicit zippable: Zippable[A, B]): Exit[E1, zippable.Out] =
    self <*> that

  /**
   * Named alias for `<*`.
   */
  final def zipLeft[E1 >: E, B](that: Exit[E1, B]): Exit[E1, A] = self <* that

  /**
   * Named alias for `<&>`.
   */
  final def zipPar[E1 >: E, B](that: Exit[E1, B])(implicit zippable: Zippable[A, B]): Exit[E1, zippable.Out] =
    self <&> that

  /**
   * Named alias for `<&`.
   */
  final def zipParLeft[E1 >: E, B](that: Exit[E1, B]): Exit[E1, A] = self <& that

  /**
   * Named alias for `&>`.
   */
  final def zipParRight[E1 >: E, B](that: Exit[E1, B]): Exit[E1, B] = self &> that

  /**
   * Named alias for `*>`.
   */
  final def zipRight[E1 >: E, B](that: Exit[E1, B]): Exit[E1, B] = self *> that

  /**
   * Zips this together with the specified result using the combination
   * functions.
   */
  final def zipWith[E1 >: E, B, C](that: Exit[E1, B])(
    f: (A, B) => C,
    g: (Cause[E], Cause[E1]) => Cause[E1]
  ): Exit[E1, C] =
    self match {
      case Success(l) =>
        that match {
          case Success(r)     => Exit.succeed(f(l, r))
          case f: Failure[E1] => f
        }
      case e @ Failure(c1) =>
        that match {
          case Failure(c2) => Exit.failCause(g(c1, c2))
          case _           => e
        }
    }

}

object Exit extends Serializable {

  final case class Success[+A](value: A)        extends Exit[Nothing, A]
  final case class Failure[+E](cause: Cause[E]) extends Exit[E, Nothing]

  def interrupt(id: FiberId): Exit[Nothing, Nothing] =
    failCause(Cause.interrupt(id))

  /**
   * Collects all Exits, and returns a List of the values if all Exits
   * succeeded, or combines the causes sequentially in case of failures. Returns
   * `None` in case the input iterable is empty
   *
   * @see
   *   [[collectAllParDiscard]] For a more performant variant that discard the
   *   successful values
   */
  def collectAll[E, A](exits: Iterable[Exit[E, A]]): Option[Exit[E, List[A]]] =
    collectAllWith(exits)(_ ++ _)

  /**
   * Collects all Exits, and succeeds with Unit if all Exits succeeded, or
   * combines the causes sequentially in case of failures.
   */
  def collectAllDiscard[E, A](exits: Iterable[Exit[E, A]]): Exit[E, Unit] =
    collectAllDiscardWith(exits)(_ ++ _)

  /**
   * Collects all Exits, and returns a List of the values if all Exits
   * succeeded, or combines the causes in parallel in case of failures. Returns
   * `None` in case the input iterable is empty
   *
   * @see
   *   [[collectAllParDiscard]] For a more performant variant that discard the
   *   successful values
   */
  def collectAllPar[E, A](exits: Iterable[Exit[E, A]]): Option[Exit[E, List[A]]] =
    collectAllWith(exits)(_ && _)

  /**
   * Collects all Exits, and succeeds with Unit if all Exits succeeded, or
   * combines the causes in parallel in case of failures.
   */
  def collectAllParDiscard[E, A](exits: Iterable[Exit[E, A]]): Exit[E, Unit] =
    collectAllDiscardWith(exits)(_ && _)

  private def collectAllWith[E, A](
    exits: Iterable[Exit[E, A]]
  )(
    combineError: (Cause[E], Cause[E]) => Cause[E]
  ): Option[Exit[E, List[A]]] =
    if (exits.isEmpty) None
    else {
      val builder = new ListBuffer[A]
      var cause   = null.asInstanceOf[Cause[E]]
      val it      = exits.iterator
      while (it.hasNext) {
        val head = it.next()
        head match {
          case Success(v) =>
            if (cause eq null) builder.append(v)
          case Failure(e) =>
            if (cause eq null) cause = e
            else cause = combineError(cause, e)
        }
      }
      if (cause eq null) Some(Success(builder.result()))
      else Some(Failure(cause))
    }

  private def collectAllDiscardWith[E, A](
    exits: Iterable[Exit[E, A]]
  )(
    combineError: (Cause[E], Cause[E]) => Cause[E]
  ): Exit[E, Unit] = {
    var cause = null.asInstanceOf[Cause[E]]
    val it    = exits.iterator
    while (it.hasNext) {
      val head = it.next()
      head match {
        case _: Success[?] => ()
        case Failure(e) =>
          if (cause eq null) cause = e
          else cause = combineError(cause, e)
      }
    }
    if (cause eq null) Exit.unit
    else Failure(cause)
  }

  def die(t: Throwable): Exit[Nothing, Nothing] =
    failCause(Cause.die(t))

  def fail[E](error: E): Exit[E, Nothing] =
    failCause(Cause.fail(error))

  def failCause[E](cause: Cause[E]): Exit[E, Nothing] =
    Failure(cause)

  def flatten[E, A](exit: Exit[E, Exit[E, A]]): Exit[E, A] =
    exit.flatMapExit(identity)

  def fromEither[E, A](e: Either[E, A]): Exit[E, A] =
    e.fold(fail, succeed)

  def fromOption[A](o: Option[A]): Exit[Unit, A] =
    o.fold[Exit[Unit, A]](fail(()))(succeed)

  def fromTry[A](t: scala.util.Try[A]): Exit[Throwable, A] =
    t match {
      case scala.util.Success(a) => succeed(a)
      case scala.util.Failure(t) => fail(t)
    }

  def succeed[A](a: A): Exit[Nothing, A] = Success(a)

  val unit: Exit[Nothing, Unit] = succeed(())

  private def zipRightWith[E, E1 >: E, A](left: Exit[E, Any], right: Exit[E1, A])(
    g: (Cause[E], Cause[E1]) => Cause[E1]
  ): Exit[E1, A] =
    left match {
      case _: Success[?] =>
        right match {
          case r: Success[A]  => r
          case f: Failure[E1] => f
        }
      case e @ Failure(c1) =>
        right match {
          case Failure(c2) => Exit.failCause(g(c1, c2))
          case _           => e
        }
    }

  /**
   * Optimized [[succeed]] for Boolean values that uses cached
   * `Exit.succeed(true)` and `Exit.succeed(false)` instances
   */
  private[zio] def boolean(b: Boolean): Exit[Nothing, Boolean] =
    if (b) Exit.`true` else Exit.`false`

  private[zio] val `true`: Exit[Nothing, Boolean]            = Success(true)
  private[zio] val `false`: Exit[Nothing, Boolean]           = Success(false)
  private[zio] val none: Exit[Nothing, Option[Nothing]]      = Success(None)
  private[zio] val emptyChunk: Exit[Nothing, Chunk[Nothing]] = Success(Chunk.empty)
  private[zio] val failNone: Exit[Option[Nothing], Nothing]  = Failure(Cause.fail(None))
  private[zio] val failUnit: Exit[Unit, Nothing]             = Failure(Cause.fail(()))

  private[zio] val empty: Exit[Nothing, Nothing] = Exit.failCause[Nothing](Cause.empty)
}
