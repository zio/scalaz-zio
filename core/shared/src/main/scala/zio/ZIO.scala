/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

import zio.internal.tracing.{ZIOFn, ZIOFn1, ZIOFn2}
import zio.internal.{Executor, Platform}
import zio.{TracingStatus => TracingS}

import java.io.IOException
import scala.annotation.implicitNotFound
import scala.collection.mutable.Builder
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

/**
 * A `ZIO[R, E, A]` value is an immutable value that lazily describes a
 * workflow or job. The workflow requires some environment `R`, and may fail
 * with an error of type `E`, or succeed with a value of type `A`.
 *
 * These lazy workflows, referred to as _effects_, can be informally thought
 * of as functions in the form:
 *
 * {{{
 * R => Either[E, A]
 * }}}
 *
 * ZIO effects model resourceful interaction with the outside world, including
 * synchronous, asynchronous, concurrent, and parallel interaction.
 *
 * ZIO effects use a fiber-based concurrency model, with built-in support for
 * scheduling, fine-grained interruption, structured concurrency, and high scalability.
 *
 * To run an effect, you need a `Runtime`, which is capable of executing effects.
 * Runtimes bundle a thread pool together with the environment that effects need.
 */
sealed trait ZIO[-R, +E, +A] extends Serializable with ZIOPlatformSpecific[R, E, A] with ZIOVersionSpecific[R, E, A] {
  self =>

  /**
   * Syntax for adding aspects.
   */
  final def @@[LowerR <: UpperR, UpperR <: R, LowerE >: E, UpperE >: LowerE, LowerA >: A, UpperA >: LowerA](
    aspect: => ZIOAspect[LowerR, UpperR, LowerE, UpperE, LowerA, UpperA]
  ): ZIO[UpperR, LowerE, LowerA] =
    ZIO.suspendSucceed(aspect(self))

  /**
   * A symbolic alias for `orDie`.
   */
  final def !(implicit ev1: E <:< Throwable, ev2: CanFail[E]): ZIO[R, Nothing, A] =
    self.orDie

  /**
   * Returns the logical conjunction of the `Boolean` value returned by this
   * effect and the `Boolean` value returned by the specified effect. This
   * operator has "short circuiting" behavior so if the value returned by this
   * effect is false the specified effect will not be evaluated.
   */
  final def &&[R1 <: R, E1 >: E](that: => ZIO[R1, E1, Boolean])(implicit ev: A <:< Boolean): ZIO[R1, E1, Boolean] =
    self.flatMap(a => if (ev(a)) that else ZIO.succeedNow(false))

  /**
   * Sequentially zips this effect with the specified effect, combining the
   * results into a tuple.
   */
  @deprecated("use zip", "2.0.0")
  final def &&&[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B]
  ): ZIO[R1, E1, zippable.Out] =
    self <*> that

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, returning result of provided effect. If either side fails,
   * then the other side will be interrupted.
   */
  final def &>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self.zipWithPar(that)((_, b) => b)

  /**
   * A variant of `flatMap` that ignores the value produced by this effect.
   */
  final def *>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self.flatMap(new ZIO.ZipRightFn(() => that))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, this effect result returned. If either side fails,
   * then the other side will be interrupted.
   */
  final def <&[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, A] =
    self.zipWithPar(that)((a, _) => a)

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results into a tuple. If either side fails,
   * then the other side will be interrupted.
   */
  final def <&>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B]
  ): ZIO[R1, E1, zippable.Out] =
    self.zipWithPar(that)((a, b) => zippable.zip(a, b))

  /**
   * Sequences the specified effect after this effect, but ignores the
   * value produced by the effect.
   */
  final def <*[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, A] =
    self.flatMap(new ZIO.ZipLeftFn(() => that))

  /**
   * Sequentially zips this effect with the specified effect, combining the
   * results into a tuple.
   */
  final def <*>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B]
  ): ZIO[R1, E1, zippable.Out] =
    self zip that

  /**
   * A symbolic alias for `orElseEither`.
   */
  final def <+>[R1 <: R, E1, B](that: => ZIO[R1, E1, B])(implicit ev: CanFail[E]): ZIO[R1, E1, Either[A, B]] =
    self.orElseEither(that)

  /**
   * Operator alias for `orElse`.
   */
  final def <>[R1 <: R, E2, A1 >: A](that: => ZIO[R1, E2, A1])(implicit ev: CanFail[E]): ZIO[R1, E2, A1] =
    orElse(that)

  /**
   * A symbolic alias for `raceEither`.
   */
  final def <|>[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, Either[A, B]] =
    self.raceEither(that)

  /**
   * Alias for `flatMap`.
   *
   * {{{
   * val parsed = readFile("foo.txt") >>= parseFile
   * }}}
   */
  @deprecated("use flatMap", "2.0.0")
  final def >>=[R1 <: R, E1 >: E, B](k: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    flatMap(k)

  /**
   * Returns the logical negation of the `Boolean` value returned by this
   * effect.
   */
  final def unary_![R1 <: R, E1 >: E](implicit ev: A <:< Boolean): ZIO[R1, E1, Boolean] =
    self.map(a => !ev(a))

  /**
   * Returns the logical conjunction of the `Boolean` value returned by this
   * effect and the `Boolean` value returned by the specified effect. This
   * operator has "short circuiting" behavior so if the value returned by this
   * effect is true the specified effect will not be evaluated.
   */
  final def ||[R1 <: R, E1 >: E](that: => ZIO[R1, E1, Boolean])(implicit ev: A <:< Boolean): ZIO[R1, E1, Boolean] =
    self.flatMap(a => if (ev(a)) ZIO.succeedNow(true) else that)

  /**
   * Returns an effect that submerges the error case of an `Either` into the
   * `ZIO`. The inverse operation of `ZIO.either`.
   */
  final def absolve[E1 >: E, B](implicit ev: A IsSubtypeOfOutput Either[E1, B]): ZIO[R, E1, B] =
    ZIO.absolve(self.map(ev))

  /**
   * Attempts to convert defects into a failure, throwing away all information
   * about the cause of the failure.
   */
  final def absorb(implicit ev: E IsSubtypeOfError Throwable): RIO[R, A] =
    absorbWith(ev)

  /**
   * Attempts to convert defects into a failure, throwing away all information
   * about the cause of the failure.
   */
  final def absorbWith(f: E => Throwable): RIO[R, A] =
    self.sandbox
      .foldZIO(
        cause => ZIO.fail(cause.squashWith(f)),
        ZIO.succeedNow
      )

  /**
   * Shorthand for the uncurried version of `ZIO.acquireReleaseWith`.
   */
  final def acquireReleaseWith[R1 <: R, E1 >: E, B](
    release: A => URIO[R1, Any],
    use: A => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    ZIO.acquireReleaseWith(self, release, use)

  /**
   * Shorthand for the curried version of `ZIO.acquireReleaseWith`.
   */
  final def acquireReleaseWith: ZIO.BracketAcquire[R, E, A] =
    ZIO.acquireReleaseWith(self)

  /**
   * A less powerful variant of `acquireReleaseWith` where the resource
   * acquired by this effect is not needed.
   */
  final def acquireRelease[R1 <: R, E1 >: E]: ZIO.BracketAcquire_[R1, E1] =
    new ZIO.BracketAcquire_(self)

  /**
   * Uncurried version. Doesn't offer curried syntax and has worse
   * type-inference characteristics, but it doesn't allocate intermediate
   * [[zio.ZIO.BracketAcquire_]] and [[zio.ZIO.BracketRelease_]] objects.
   */
  final def acquireRelease[R1 <: R, E1 >: E, B](
    release: => URIO[R1, Any],
    use: => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    ZIO.acquireReleaseWith(self, (_: A) => release, (_: A) => use)

  /**
   * Shorthand for the uncurried version of `ZIO.acquireReleaseExitWith`.
   */
  final def acquireReleaseExitWith[R1 <: R, E1 >: E, B](
    release: (A, Exit[E1, B]) => URIO[R1, Any],
    use: A => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    ZIO.acquireReleaseExitWith(self, release, use)

  /**
   * Shorthand for the curried version of `ZIO.acquireReleaseExitWith`.
   */
  final def acquireReleaseExitWith: ZIO.BracketExitAcquire[R, E, A] =
    ZIO.acquireReleaseExitWith(self)

  /**
   * Executes the release effect only if there was an error.
   */
  final def acquireReleaseOnErrorWith[R1 <: R, E1 >: E, B](
    release: A => URIO[R1, Any]
  )(use: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    ZIO.acquireReleaseExitWith(self)((a: A, eb: Exit[E1, B]) =>
      eb match {
        case Exit.Failure(_) => release(a)
        case _               => ZIO.unit
      }
    )(use)

  /**
   * Maps the success value of this effect to the specified constant value.
   */
  final def as[B](b: => B): ZIO[R, E, B] =
    self.flatMap(new ZIO.ConstZIOFn(() => b))

  /**
   * Maps the success value of this effect to a left value.
   */
  final def asLeft: ZIO[R, E, Either[A, Nothing]] =
    map(Left(_))

  /**
   * Maps the error value of this effect to a left value.
   */
  final def asLeftError: ZIO[R, Either[E, Nothing], A] =
    mapError(Left(_))

  /**
   * Maps the success value of this effect to a right value.
   */
  final def asRight: ZIO[R, E, Either[Nothing, A]] =
    map(Right(_))

  /**
   * Maps the error value of this effect to a right value.
   */
  final def asRightError: ZIO[R, Either[Nothing, E], A] =
    mapError(Right(_))

  /**
   * Maps the success value of this effect to a service.
   */
  @deprecated("use toLayer", "2.0.0")
  final def asService[A1 >: A: Tag]: ZIO[R, E, Has[A1]] =
    map(Has(_))

  /**
   * Maps the success value of this effect to an optional value.
   */
  final def asSome: ZIO[R, E, Option[A]] =
    map(Some(_))

  /**
   * Maps the error value of this effect to an optional value.
   */
  final def asSomeError: ZIO[R, Option[E], A] =
    mapError(Some(_))

  /**
   * Returns a new effect that will not succeed with its value before first
   * waiting for the end of all child fibers forked by the effect.
   */
  final def awaitAllChildren: ZIO[R, E, A] =
    ensuringChildren(Fiber.awaitAll(_))

  /**
   * Returns an effect whose failure and success channels have been mapped by
   * the specified pair of functions, `f` and `g`.
   */
  @deprecated("use mapBoth", "2.0.0")
  final def bimap[E2, B](f: E => E2, g: A => B)(implicit ev: CanFail[E]): ZIO[R, E2, B] =
    mapBoth(f, g)

  /**
   * Shorthand for the uncurried version of `ZIO.bracket`.
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  final def bracket[R1 <: R, E1 >: E, B](
    release: A => URIO[R1, Any],
    use: A => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    acquireReleaseWith(release, use)

  /**
   * Shorthand for the curried version of `ZIO.bracket`.
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  final def bracket: ZIO.BracketAcquire[R, E, A] =
    acquireReleaseWith

  /**
   * A less powerful variant of `bracket` where the resource acquired by this
   * effect is not needed.
   */
  @deprecated("use acquireRelease", "2.0.0")
  final def bracket_[R1 <: R, E1 >: E]: ZIO.BracketAcquire_[R1, E1] =
    acquireRelease

  /**
   * Uncurried version. Doesn't offer curried syntax and has worse
   * type-inference characteristics, but it doesn't allocate intermediate
   * [[zio.ZIO.BracketAcquire_]] and [[zio.ZIO.BracketRelease_]] objects.
   */
  @deprecated("use acquireRelease", "2.0.0")
  final def bracket_[R1 <: R, E1 >: E, B](
    release: => URIO[R1, Any],
    use: => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    acquireRelease(release, use)

  /**
   * Shorthand for the uncurried version of `ZIO.bracketExit`.
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  final def bracketExit[R1 <: R, E1 >: E, B](
    release: (A, Exit[E1, B]) => URIO[R1, Any],
    use: A => ZIO[R1, E1, B]
  ): ZIO[R1, E1, B] =
    acquireReleaseExitWith(release, use)

  /**
   * Shorthand for the curried version of `ZIO.bracketExit`.
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  final def bracketExit: ZIO.BracketExitAcquire[R, E, A] =
    acquireReleaseExitWith

  /**
   * Executes the release effect only if there was an error.
   */
  @deprecated("use acquireReleaseOnErrorWith", "2.0.0")
  final def bracketOnError[R1 <: R, E1 >: E, B](
    release: A => URIO[R1, Any]
  )(use: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    acquireReleaseOnErrorWith[R1, E1, B](release)(use)

  /**
   * Returns an effect that, if evaluated, will return the cached result of
   * this effect. Cached results will expire after `timeToLive` duration.
   */
  final def cached(timeToLive: => Duration): ZIO[R with Has[Clock], Nothing, IO[E, A]] =
    cachedInvalidate(timeToLive).map(_._1)

  /**
   * Returns an effect that, if evaluated, will return the cached result of
   * this effect. Cached results will expire after `timeToLive` duration. In
   * addition, returns an effect that can be used to invalidate the current
   * cached value before the `timeToLive` duration expires.
   */
  final def cachedInvalidate(timeToLive0: => Duration): ZIO[R with Has[Clock], Nothing, (IO[E, A], UIO[Unit])] =
    ZIO.suspendSucceed {
      val timeToLive = timeToLive0

      def compute(start: Long): ZIO[R with Has[Clock], Nothing, Option[(Long, Promise[E, A])]] =
        for {
          p <- Promise.make[E, A]
          _ <- self.intoPromise(p)
        } yield Some((start + timeToLive.toNanos, p))

      def get(cache: Ref.Synchronized[Option[(Long, Promise[E, A])]]): ZIO[R with Has[Clock], E, A] =
        ZIO.uninterruptibleMask { restore =>
          Clock.nanoTime.flatMap { time =>
            cache.updateSomeAndGetZIO {
              case None                              => compute(time)
              case Some((end, _)) if end - time <= 0 => compute(time)
            }.flatMap(a => restore(a.get._2.await))
          }
        }

      def invalidate(cache: Ref.Synchronized[Option[(Long, Promise[E, A])]]): UIO[Unit] =
        cache.set(None)

      for {
        r     <- ZIO.environment[R with Has[Clock]]
        cache <- Ref.Synchronized.make[Option[(Long, Promise[E, A])]](None)
      } yield (get(cache).provide(r), invalidate(cache))
    }

  /**
   * Recovers from all errors.
   *
   * {{{
   * openFile("config.json").catchAll(_ => IO.succeed(defaultConfig))
   * }}}
   */
  final def catchAll[R1 <: R, E2, A1 >: A](h: E => ZIO[R1, E2, A1])(implicit ev: CanFail[E]): ZIO[R1, E2, A1] =
    self.foldZIO[R1, E2, A1](h, new ZIO.SucceedFn(h))

  /**
   * A version of `catchAll` that gives you the (optional) trace of the error.
   */
  final def catchAllTrace[R1 <: R, E2, A1 >: A](
    h: ((E, Option[ZTrace])) => ZIO[R1, E2, A1]
  )(implicit ev: CanFail[E]): ZIO[R1, E2, A1] =
    self.foldTraceZIO[R1, E2, A1](h, new ZIO.SucceedFn(h))

  /**
   * Recovers from all errors with provided Cause.
   *
   * {{{
   * openFile("config.json").catchAllCause(_ => IO.succeed(defaultConfig))
   * }}}
   *
   * @see [[absorb]], [[sandbox]], [[mapErrorCause]] - other functions that can recover from defects
   */
  final def catchAllCause[R1 <: R, E2, A1 >: A](h: Cause[E] => ZIO[R1, E2, A1]): ZIO[R1, E2, A1] =
    self.foldCauseZIO[R1, E2, A1](h, new ZIO.SucceedFn(h))

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
  final def catchAllDefect[R1 <: R, E1 >: E, A1 >: A](h: Throwable => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    catchSomeDefect { case t => h(t) }

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
  )(implicit ev: CanFail[E]): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      c.failureOrCause.fold(t => pf.applyOrElse(t, (_: E) => ZIO.failCause(c)), ZIO.failCause(_))

    self.foldCauseZIO[R1, E1, A1](ZIOFn(pf)(tryRescue), new ZIO.SucceedFn(pf))
  }

  /**
   * A version of `catchSome` that gives you the (optional) trace of the error.
   */
  final def catchSomeTrace[R1 <: R, E1 >: E, A1 >: A](
    pf: PartialFunction[(E, Option[ZTrace]), ZIO[R1, E1, A1]]
  )(implicit ev: CanFail[E]): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      c.failureTraceOrCause.fold(t => pf.applyOrElse(t, (_: (E, Option[ZTrace])) => ZIO.failCause(c)), ZIO.failCause(_))

    self.foldCauseZIO[R1, E1, A1](ZIOFn(pf)(tryRescue), new ZIO.SucceedFn(pf))
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
  ): ZIO[R1, E1, A1] = {
    def tryRescue(c: Cause[E]): ZIO[R1, E1, A1] =
      pf.applyOrElse(c, (_: Cause[E]) => ZIO.failCause(c))

    self.foldCauseZIO[R1, E1, A1](ZIOFn(pf)(tryRescue), new ZIO.SucceedFn(pf))
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
  ): ZIO[R1, E1, A1] =
    unrefineWith(pf)(ZIO.fail(_)).catchAll(identity)

  /**
   * Returns an effect that succeeds with the cause of failure of this effect,
   * or `Cause.empty` if the effect did succeed.
   */
  final def cause: URIO[R, Cause[E]] =
    self.foldCause(c => c, _ => Cause.empty)

  /**
   * Fail with `e` if the supplied `PartialFunction` does not match, otherwise
   * succeed with the returned value.
   */
  final def collect[E1 >: E, B](e: => E1)(pf: PartialFunction[A, B]): ZIO[R, E1, B] =
    collectZIO(e)(pf.andThen(ZIO.succeedNow(_)))

  /**
   * Fail with `e` if the supplied `PartialFunction` does not match, otherwise
   * continue with the returned value.
   */
  @deprecated("use collectZIO", "2.0.0")
  final def collectM[R1 <: R, E1 >: E, B](e: => E1)(pf: PartialFunction[A, ZIO[R1, E1, B]]): ZIO[R1, E1, B] =
    collectZIO(e)(pf)

  /**
   * Fail with `e` if the supplied `PartialFunction` does not match, otherwise
   * continue with the returned value.
   */
  final def collectZIO[R1 <: R, E1 >: E, B](e: => E1)(pf: PartialFunction[A, ZIO[R1, E1, B]]): ZIO[R1, E1, B] =
    self.flatMap(v => pf.applyOrElse[A, ZIO[R1, E1, B]](v, _ => ZIO.fail(e)))

  /**
   * Taps the effect, printing the result of calling `.toString` on the value
   */
  final def debug: ZIO[R, E, A] =
    self.tapBoth(
      error => UIO(println(s"<FAIL> $error")),
      value => UIO(println(value))
    )

  /**
   * Taps the effect, printing the result of calling `.toString` on the value.
   * Prefixes the output with the given message.
   */
  final def debug(prefix: => String): ZIO[R, E, A] =
    self.tapBoth(
      error => UIO(println(s"<FAIL> $prefix: $error")),
      value => UIO(println(s"$prefix: $value"))
    )

  /**
   * Returns an effect that is delayed from this effect by the specified
   * [[zio.Duration]].
   */
  final def delay(duration: => Duration): ZIO[R with Has[Clock], E, A] =
    Clock.sleep(duration) *> self

  /**
   * Returns an effect whose interruption will be disconnected from the
   * fiber's own interruption, being performed in the background without
   * slowing down the fiber's interruption.
   *
   * This method is useful to create "fast interrupting" effects. For
   * example, if you call this on an acquire release effect, then even if the
   * effect is "stuck" in acquire or release, its interruption will return
   * immediately, while the acquire / release are performed in the
   * background.
   *
   * See timeout and race for other applications.
   */
  final def disconnect: ZIO[R, E, A] =
    ZIO.uninterruptibleMask(restore =>
      for {
        id    <- ZIO.fiberId
        fiber <- restore(self).forkDaemon
        a     <- restore(fiber.join).onInterrupt(fiber.interruptAs(id).forkDaemon)
      } yield a
    )

  /**
   * Returns an effect whose failure and success have been lifted into an
   * `Either`.The resulting effect cannot fail, because the failure case has
   * been exposed as part of the `Either` success case.
   *
   * This method is useful for recovering from `ZIO` effects that may fail.
   *
   * The error parameter of the returned `ZIO` is `Nothing`, since it is
   * guaranteed the `ZIO` effect does not model failure.
   */
  final def either(implicit ev: CanFail[E]): URIO[R, Either[E, A]] =
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
  final def ensuring[R1 <: R](finalizer: => URIO[R1, Any]): ZIO[R1, E, A] =
    new ZIO.Ensuring(self, () => finalizer)

  /**
   * Acts on the children of this fiber (collected into a single fiber),
   * guaranteeing the specified callback will be invoked, whether or not
   * this effect succeeds.
   */
  final def ensuringChild[R1 <: R](f: Fiber[Any, Iterable[Any]] => ZIO[R1, Nothing, Any]): ZIO[R1, E, A] =
    ensuringChildren(children => f(Fiber.collectAll(children)))

  /**
   * Acts on the children of this fiber, guaranteeing the specified callback
   * will be invoked, whether or not this effect succeeds.
   */
  def ensuringChildren[R1 <: R](children: Chunk[Fiber.Runtime[Any, Any]] => ZIO[R1, Nothing, Any]): ZIO[R1, E, A] =
    Supervisor
      .track(true)
      .flatMap(supervisor => self.supervised(supervisor).ensuring(supervisor.value.flatMap(children)))

  /**
   * Returns an effect that ignores errors and runs repeatedly until it eventually succeeds.
   */
  final def eventually(implicit ev: CanFail[E]): URIO[R, A] =
    self <> ZIO.yieldNow *> eventually

  /**
   * Returns an effect that semantically runs the effect on a fiber,
   * producing an [[zio.Exit]] for the completion value of the fiber.
   */
  final def exit: URIO[R, Exit[E, A]] =
    new ZIO.Fold[R, E, Nothing, A, Exit[E, A]](
      self,
      cause => ZIO.succeedNow(Exit.failCause(cause)),
      success => ZIO.succeedNow(Exit.succeed(success))
    )

  /**
   * Maps this effect to the default exit codes.
   */
  final def exitCode: URIO[R, ExitCode] =
    self.foldCause(
      _ => ExitCode.failure,
      _ => ExitCode.success
    )

  /**
   * Dies with specified `Throwable` if the predicate fails.
   */
  final def filterOrDie(p: A => Boolean)(t: => Throwable): ZIO[R, E, A] =
    self.filterOrElse(p)(ZIO.die(t))

  /**
   * Dies with a [[java.lang.RuntimeException]] having the specified text message
   * if the predicate fails.
   */
  final def filterOrDieMessage(p: A => Boolean)(message: => String): ZIO[R, E, A] =
    self.filterOrElse(p)(ZIO.dieMessage(message))

  /**
   * Supplies `zio` if the predicate fails.
   */
  final def filterOrElse[R1 <: R, E1 >: E, A1 >: A](p: A => Boolean)(zio: => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    filterOrElseWith[R1, E1, A1](p)(_ => zio)

  /**
   * Supplies `zio` if the predicate fails.
   */
  @deprecated("use filterOrElse", "2.0.0")
  final def filterOrElse_[R1 <: R, E1 >: E, A1 >: A](p: A => Boolean)(zio: => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    filterOrElse[R1, E1, A1](p)(zio)

  /**
   * Applies `f` if the predicate fails.
   */
  final def filterOrElseWith[R1 <: R, E1 >: E, A1 >: A](p: A => Boolean)(f: A => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    self.flatMap {
      case v if !p(v) => f(v)
      case v          => ZIO.succeedNow(v)
    }

  /**
   * Fails with `e` if the predicate fails.
   */
  final def filterOrFail[E1 >: E](p: A => Boolean)(e: => E1): ZIO[R, E1, A] =
    filterOrElse[R, E1, A](p)(ZIO.fail(e))

  /**
   * Returns an effect that runs this effect and in case of failure,
   * runs each of the specified effects in order until one of them succeeds.
   */
  final def firstSuccessOf[R1 <: R, E1 >: E, A1 >: A](rest: => Iterable[ZIO[R1, E1, A1]]): ZIO[R1, E1, A1] =
    ZIO.firstSuccessOf(self, rest)

  /**
   * Returns an effect that models the execution of this effect, followed by
   * the passing of its value to the specified continuation function `k`,
   * followed by the effect that it returns.
   *
   * {{{
   * val parsed = readFile("foo.txt").flatMap(file => parseFile(file))
   * }}}
   */
  def flatMap[R1 <: R, E1 >: E, B](k: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    new ZIO.FlatMap(self, k)

  /**
   * Creates a composite effect that represents this effect followed by another
   * one that may depend on the error produced by this one.
   *
   * {{{
   * val parsed = readFile("foo.txt").flatMapError(error => logErrorToFile(error))
   * }}}
   */
  final def flatMapError[R1 <: R, E2](f: E => URIO[R1, E2])(implicit ev: CanFail[E]): ZIO[R1, E2, A] =
    flipWith(_ flatMap f)

  /**
   * Returns an effect that performs the outer effect first, followed by the
   * inner effect, yielding the value of the inner effect.
   *
   * This method can be used to "flatten" nested effects.
   */
  final def flatten[R1 <: R, E1 >: E, B](implicit ev1: A IsSubtypeOfOutput ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self.flatMap(a => ev1(a))

  /**
   * Returns an effect that swaps the error/success cases. This allows you to
   * use all methods on the error channel, possibly before flipping back.
   */
  final def flip: ZIO[R, A, E] =
    self.foldZIO(ZIO.succeedNow, ZIO.fail(_))

  /**
   *  Swaps the error/value parameters, applies the function `f` and flips the parameters back
   */
  final def flipWith[R1, A1, E1](f: ZIO[R, A, E] => ZIO[R1, A1, E1]): ZIO[R1, E1, A1] =
    f(self.flip).flip

  /**
   * Folds over the failure value or the success value to yield an effect that
   * does not fail, but succeeds with the value returned by the left or right
   * function passed to `fold`.
   */
  final def fold[B](failure: E => B, success: A => B)(implicit ev: CanFail[E]): URIO[R, B] =
    foldZIO(new ZIO.MapFn(failure), new ZIO.MapFn(success))

  /**
   * A more powerful version of `fold` that allows recovering from any kind of failure except interruptions.
   */
  final def foldCause[B](failure: Cause[E] => B, success: A => B): URIO[R, B] =
    foldCauseZIO(new ZIO.MapFn(failure), new ZIO.MapFn(success))

  /**
   * A more powerful version of `fold` that allows recovering from any kind of failure except interruptions.
   */
  @deprecated("use foldCauseZIO", "2.0.0")
  final def foldCauseM[R1 <: R, E2, B](
    failure: Cause[E] => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  ): ZIO[R1, E2, B] =
    foldCauseZIO(failure, success)

  /**
   * A more powerful version of `foldZIO` that allows recovering from any kind of failure except interruptions.
   */
  final def foldCauseZIO[R1 <: R, E2, B](
    failure: Cause[E] => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  ): ZIO[R1, E2, B] =
    new ZIO.Fold(self, failure, success)

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
  @deprecated("use foldZIO", "2.0.0")
  final def foldM[R1 <: R, E2, B](failure: E => ZIO[R1, E2, B], success: A => ZIO[R1, E2, B])(implicit
    ev: CanFail[E]
  ): ZIO[R1, E2, B] =
    foldZIO(failure, success)

  /**
   * A version of `foldM` that gives you the (optional) trace of the error.
   */
  @deprecated("use foldTraceZIO", "2.0.0")
  final def foldTraceM[R1 <: R, E2, B](
    failure: ((E, Option[ZTrace])) => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  )(implicit
    ev: CanFail[E]
  ): ZIO[R1, E2, B] =
    foldTraceZIO(failure, success)

  /**
   * A version of `foldZIO` that gives you the (optional) trace of the error.
   */
  final def foldTraceZIO[R1 <: R, E2, B](
    failure: ((E, Option[ZTrace])) => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  )(implicit
    ev: CanFail[E]
  ): ZIO[R1, E2, B] =
    foldCauseZIO(new ZIO.FoldCauseZIOFailureTraceFn(failure), success)

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
    ev: CanFail[E]
  ): ZIO[R1, E2, B] =
    foldCauseZIO(new ZIO.FoldCauseZIOFailureFn(failure), success)

  /**
   * Repeats this effect forever (until the first error). For more sophisticated
   * schedules, see the `repeat` method.
   */
  final def forever: ZIO[R, E, Nothing] =
    self *> ZIO.yieldNow *> forever

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
  final def fork: URIO[R, Fiber.Runtime[E, A]] =
    new ZIO.Fork(self, () => None, None)

  final def forkIn(scope: => ZScope[Exit[Any, Any]]): URIO[R, Fiber.Runtime[E, A]] =
    new ZIO.Fork(self, () => Some(scope), None)

  /**
   * Forks the effect into a new independent fiber, with the specified name.
   */
  final def forkAs(name: => String): URIO[R, Fiber.Runtime[E, A]] =
    ZIO.uninterruptibleMask(restore => (Fiber.fiberName.set(Some(name)) *> restore(self)).fork)

  /**
   * Forks the effect into a new fiber attached to the global scope. Because the
   * new fiber is attached to the global scope, when the fiber executing the
   * returned effect terminates, the forked fiber will continue running.
   */
  final def forkDaemon: URIO[R, Fiber.Runtime[E, A]] =
    forkIn(ZScope.global)

  /**
   * Forks an effect that will be executed without unhandled failures being
   * reported. This is useful for implementing combinators that handle failures
   * themselves.
   */
  final def forkInternal: ZIO[R, Nothing, Fiber.Runtime[E, A]] =
    new ZIO.Fork(self, () => None, Some(_ => ()))

  /**
   * Forks the fiber in a [[ZManaged]]. Using the [[ZManaged]] value will
   * execute the effect in the fiber, while ensuring its interruption when
   * the effect supplied to [[ZManaged#use]] completes.
   */
  final def forkManaged: ZManaged[R, Nothing, Fiber.Runtime[E, A]] =
    self.toManaged.fork

  /**
   * Forks an effect that will be executed on the specified `ExecutionContext`.
   */
  final def forkOn(ec: => ExecutionContext): ZIO[R, E, Fiber.Runtime[E, A]] =
    self.onExecutionContext(ec).fork

  /**
   * Like fork but handles an error with the provided handler.
   */
  final def forkWithErrorHandler(handler: E => UIO[Unit]): URIO[R, Fiber.Runtime[E, A]] =
    onError(new ZIO.FoldCauseZIOFailureFn(handler)).fork

  /**
   * Unwraps the optional error, defaulting to the provided value.
   */
  final def flattenErrorOption[E1, E2 <: E1](default: => E2)(implicit
    ev: E IsSubtypeOfError Option[E1]
  ): ZIO[R, E1, A] =
    self.mapError(e => ev(e).getOrElse(default))

  /**
   * Unwraps the optional success of this effect, but can fail with an None value.
   */
  @deprecated("use some", "2.0.0")
  final def get[B](implicit
    ev1: E IsSubtypeOfError Nothing,
    ev2: A IsSubtypeOfOutput Option[B]
  ): ZIO[R, Option[Nothing], B] =
    ZIO.absolve(self.mapError(ev1)(CanFail).map(ev2(_).toRight(None)))

  /**
   * Returns a successful effect with the head of the list if the list is
   * non-empty or fails with the error `None` if the list is empty.
   */
  final def head[B](implicit ev: A IsSubtypeOfOutput List[B]): ZIO[R, Option[E], B] =
    self.foldZIO(
      e => ZIO.fail(Some(e)),
      a => ev(a).headOption.fold[ZIO[R, Option[E], B]](ZIO.fail(None))(ZIO.succeedNow)
    )

  /**
   * Returns a new effect that ignores the success or failure of this effect.
   */
  final def ignore: URIO[R, Unit] =
    self.fold(ZIO.unitFn, ZIO.unitFn)

  /**
   * Returns a new effect whose scope will be extended by the specified scope.
   * This means any finalizers associated with the effect will not be executed
   * until the specified scope is closed.
   */
  final def in(scope: => ZScope[Any]): ZIO[R, E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(self).forkDaemon.flatMap { fiber =>
        scope.extend(fiber.scope) *> restore(fiber.join).onInterrupt(ids =>
          ids.headOption.fold(fiber.interrupt)(id => fiber.interruptAs(id))
        )
      }
    }

  /**
   * Returns a new effect that will not succeed with its value before first
   * interrupting all child fibers forked by the effect.
   */
  final def interruptAllChildren: ZIO[R, E, A] =
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
   * exactly what you are doing. Instead, you should use [[ZIO.uninterruptibleMask]].
   */
  final def interruptible: ZIO[R, E, A] =
    interruptStatus(InterruptStatus.Interruptible)

  /**
   * Switches the interrupt status for this effect. If `true` is used, then the
   * effect becomes interruptible (the default), while if `false` is used, then
   * the effect becomes uninterruptible. These changes are compositional, so
   * they only affect regions of the effect.
   */
  final def interruptStatus(flag: => InterruptStatus): ZIO[R, E, A] =
    new ZIO.InterruptStatus(self, () => flag)

  /**
   * Returns an effect that keeps or breaks a promise based on the result of
   * this effect. Synchronizes interruption, so if this effect is interrupted,
   * the specified promise will be interrupted, too.
   */
  final def intoPromise[E1 >: E, A1 >: A](p: => Promise[E1, A1]): URIO[R, Boolean] =
    ZIO.uninterruptibleMask(restore => restore(self).exit.flatMap(p.done(_)))

  /**
   * Returns whether this effect is a failure.
   */
  final def isFailure: URIO[R, Boolean] =
    fold(_ => true, _ => false)

  /**
   * Returns whether this effect is a success.
   */
  final def isSuccess: URIO[R, Boolean] =
    fold(_ => false, _ => true)

  /**
   * "Zooms in" on the value in the `Left` side of an `Either`, moving the
   * possibility that the value is a `Right` to the error channel.
   */
  final def left[B, C](implicit ev: A IsSubtypeOfOutput Either[B, C]): ZIO[R, Either[E, C], B] =
    self.foldZIO(
      e => ZIO.fail(Left(e)),
      a => ev(a).fold(b => ZIO.succeedNow(b), c => ZIO.fail(Right(c)))
    )

  /**
   * Returns an effect which is guaranteed to be executed on the specified
   * executor. The specified effect will always run on the specified executor,
   * even in the presence of asynchronous boundaries.
   *
   * This is useful when an effect must be executed somewhere, for example:
   * on a UI thread, inside a client library's thread pool, inside a blocking
   * thread pool, inside a low-latency thread pool, or elsewhere.
   *
   * The `lock` function composes with the innermost `lock` taking priority.
   * Use of this method does not alter the execution semantics of other effects
   * composed with this one, making it easy to compositionally reason about
   * where effects are running.
   */
  @deprecated("use onExecutor", "2.0.0")
  final def lock(executor: => Executor): ZIO[R, E, A] =
    onExecutor(executor)

  /**
   * Executes the effect on the specified `ExecutionContext` and then shifts back
   * to the default one.
   */
  @deprecated("use onExecutionContext", "2.0.0")
  final def lockExecutionContext(ec: => ExecutionContext): ZIO[R, E, A] =
    onExecutionContext(ec)

  /**
   * Returns an effect whose success is mapped by the specified `f` function.
   */
  def map[B](f: A => B): ZIO[R, E, B] =
    new ZIO.FlatMap(self, new ZIO.MapFn(f))

  /**
   * Returns an effect whose success is mapped by the specified side effecting
   * `f` function, translating any thrown exceptions into typed failed effects.
   */
  final def mapAttempt[B](f: A => B)(implicit ev: E IsSubtypeOfError Throwable): RIO[R, B] =
    foldZIO(e => ZIO.fail(ev(e)), a => ZIO.attempt(f(a)))

  /**
   * Returns an effect whose failure and success channels have been mapped by
   * the specified pair of functions, `f` and `g`.
   */
  final def mapBoth[E2, B](f: E => E2, g: A => B)(implicit ev: CanFail[E]): ZIO[R, E2, B] =
    foldZIO(e => ZIO.fail(f(e)), a => ZIO.succeedNow(g(a)))

  /**
   * Returns an effect whose success is mapped by the specified side effecting
   * `f` function, translating any thrown exceptions into typed failed effects.
   */
  @deprecated("use mapAttempt", "2.0.0")
  final def mapEffect[B](f: A => B)(implicit ev: E IsSubtypeOfError Throwable): RIO[R, B] =
    mapAttempt(f)

  /**
   * Returns an effect with its error channel mapped using the specified
   * function. This can be used to lift a "smaller" error into a "larger"
   * error.
   */
  final def mapError[E2](f: E => E2)(implicit ev: CanFail[E]): ZIO[R, E2, A] =
    self.foldCauseZIO(new ZIO.MapErrorFn(f), new ZIO.SucceedFn(f))

  /**
   * Returns an effect with its full cause of failure mapped using the
   * specified function. This can be used to transform errors while
   * preserving the original structure of `Cause`.
   *
   * @see [[absorb]], [[sandbox]], [[catchAllCause]] - other functions for dealing with defects
   */
  final def mapErrorCause[E2](h: Cause[E] => Cause[E2]): ZIO[R, E2, A] =
    self.foldCauseZIO(new ZIO.MapErrorCauseFn(h), new ZIO.SucceedFn(h))

  /**
   * Returns an effect that, if evaluated, will return the lazily computed result
   * of this effect.
   */
  final def memoize: UIO[ZIO[R, E, A]] =
    for {
      promise  <- Promise.make[E, A]
      complete <- self.intoPromise(promise).once
    } yield complete *> promise.await

  /**
   * Returns a new effect where the error channel has been merged into the
   * success channel to their common combined type.
   */
  final def merge[A1 >: A](implicit ev1: E IsSubtypeOfError A1, ev2: CanFail[E]): URIO[R, A1] =
    self.foldZIO(e => ZIO.succeedNow(ev1(e)), ZIO.succeedNow)

  /**
   * Returns a new effect where boolean value of this effect is negated.
   */
  final def negate(implicit ev: A IsSubtypeOfOutput Boolean): ZIO[R, E, Boolean] =
    map(result => !ev(result))

  /**
   * Requires the option produced by this value to be `None`.
   */
  final def none[B](implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R, Option[E], Unit] =
    self.foldZIO(
      e => ZIO.fail(Some(e)),
      a => ev(a).fold[ZIO[R, Option[E], Unit]](ZIO.succeedNow(()))(_ => ZIO.fail(None))
    )

  /**
   * Executes the effect on the specified `ExecutionContext` and then shifts back
   * to the default one.
   */
  @deprecated("use lockExecutionContext", "2.0.0")
  final def on(ec: => ExecutionContext): ZIO[R, E, A] =
    self.onExecutionContext(ec)

  /**
   * Returns an effect that will be executed at most once, even if it is
   * evaluated multiple times.
   */
  final def once: UIO[ZIO[R, E, Unit]] =
    Ref.make(true).map(ref => self.whenZIO(ref.getAndSet(false)))

  /**
   * Runs the specified effect if this effect fails, providing the error to the
   * effect if it exists. The provided effect will not be interrupted.
   */
  final def onError[R1 <: R](cleanup: Cause[E] => URIO[R1, Any]): ZIO[R1, E, A] =
    onExit {
      case Exit.Success(_)     => UIO.unit
      case Exit.Failure(cause) => cleanup(cause)
    }

  /**
   * Returns an effect which is guaranteed to be executed on the specified
   * executor. The specified effect will always run on the specified executor,
   * even in the presence of asynchronous boundaries.
   *
   * This is useful when an effect must be executed somewhere, for example:
   * on a UI thread, inside a client library's thread pool, inside a blocking
   * thread pool, inside a low-latency thread pool, or elsewhere.
   *
   * The `onExecutor` function composes with the innermost `onExecutor` taking
   * priority. Use of this method does not alter the execution semantics of
   * other effects composed with this one, making it easy to compositionally
   * reason about where effects are running.
   */
  final def onExecutor(executor: => Executor): ZIO[R, E, A] =
    ZIO.onExecutor(executor)(self)

  /**
   * Executes the effect on the specified `ExecutionContext` and then shifts back
   * to the default one.
   */
  final def onExecutionContext(ec: => ExecutionContext): ZIO[R, E, A] =
    self.onExecutor(Executor.fromExecutionContext(Int.MaxValue)(ec))

  /**
   * Ensures that a cleanup functions runs, whether this effect succeeds,
   * fails, or is interrupted.
   */
  final def onExit[R1 <: R](cleanup: Exit[E, A] => URIO[R1, Any]): ZIO[R1, E, A] =
    ZIO.acquireReleaseExitWith(ZIO.unit)((_, exit: Exit[E, A]) => cleanup(exit))(_ => self)

  /**
   * Runs the specified effect if this effect is interrupted.
   */
  final def onInterrupt[R1 <: R](cleanup: => URIO[R1, Any]): ZIO[R1, E, A] =
    onInterrupt(_ => cleanup)

  /**
   * Calls the specified function, and runs the effect it returns, if this
   * effect is interrupted.
   */
  final def onInterrupt[R1 <: R](cleanup: Set[FiberId] => URIO[R1, Any]): ZIO[R1, E, A] =
    ZIO.uninterruptibleMask { restore =>
      restore(self).foldCauseZIO(
        cause => if (cause.isInterrupted) cleanup(cause.interruptors) *> ZIO.failCause(cause) else ZIO.failCause(cause),
        a => ZIO.succeedNow(a)
      )
    }

  /**
   * Runs this effect on the specified platform, restoring the old platform
   * when it completes execution.
   */
  final def onPlatform(platform: => Platform): ZIO[R, E, A] =
    ZIO.onPlatform(platform)(self)

  /**
   * Runs the specified effect if this effect is terminated, either because of
   * a defect or because of interruption.
   */
  final def onTermination[R1 <: R](cleanup: Cause[Nothing] => URIO[R1, Any]): ZIO[R1, E, A] =
    ZIO.acquireReleaseExitWith(ZIO.unit)((_, eb: Exit[E, A]) =>
      eb match {
        case Exit.Failure(cause) => cause.failureOrCause.fold(_ => ZIO.unit, cleanup)
        case _                   => ZIO.unit
      }
    )(_ => self)

  /**
   * Executes this effect, skipping the error but returning optionally the success.
   */
  final def option(implicit ev: CanFail[E]): URIO[R, Option[A]] =
    self.foldZIO(_ => IO.succeedNow(None), a => IO.succeedNow(Some(a)))

  /**
   * Converts an option on errors into an option on values.
   */
  @deprecated("use unoption", "2.0.0")
  final def optional[E1](implicit ev: E IsSubtypeOfError Option[E1]): ZIO[R, E1, Option[A]] =
    self.foldZIO(
      e => ev(e).fold[ZIO[R, E1, Option[A]]](ZIO.succeedNow(Option.empty[A]))(ZIO.fail(_)),
      a => ZIO.succeedNow(Some(a))
    )

  /**
   * Translates effect failure into death of the fiber, making all failures unchecked and
   * not a part of the type of the effect.
   */
  final def orDie(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E]): URIO[R, A] =
    orDieWith(ev1)

  /**
   * Keeps none of the errors, and terminates the fiber with them, using
   * the specified function to convert the `E` into a `Throwable`.
   */
  final def orDieWith(f: E => Throwable)(implicit ev: CanFail[E]): URIO[R, A] =
    self.foldZIO(e => ZIO.die(f(e)), ZIO.succeedNow)

  /**
   * Unearth the unchecked failure of the effect. (opposite of `orDie`)
   * {{{
   *  val f0: Task[Unit] = ZIO.fail(new Exception("failing")).unit
   *  val f1: UIO[Unit]  = f0.orDie
   *  val f2: Task[Unit] = f1.resurrect
   * }}}
   */
  final def resurrect(implicit ev1: E IsSubtypeOfError Throwable): RIO[R, A] =
    self.unrefineWith({ case e => e })(ev1)

  /**
   * Executes this effect and returns its value, if it succeeds, but
   * otherwise executes the specified effect.
   */
  final def orElse[R1 <: R, E2, A1 >: A](that: => ZIO[R1, E2, A1])(implicit ev: CanFail[E]): ZIO[R1, E2, A1] =
    tryOrElse(that, new ZIO.SucceedFn(() => that))

  /**
   * Returns an effect that will produce the value of this effect, unless it
   * fails, in which case, it will produce the value of the specified effect.
   */
  final def orElseEither[R1 <: R, E2, B](that: => ZIO[R1, E2, B])(implicit ev: CanFail[E]): ZIO[R1, E2, Either[A, B]] =
    tryOrElse(that.map(Right(_)), ZIO.succeedLeft)

  /**
   * Executes this effect and returns its value, if it succeeds, but
   * otherwise fails with the specified error.
   */
  final def orElseFail[E1](e1: => E1)(implicit ev: CanFail[E]): ZIO[R, E1, A] =
    orElse(ZIO.fail(e1))

  /**
   * Returns an effect that will produce the value of this effect, unless it
   * fails with the `None` value, in which case it will produce the value of
   * the specified effect.
   */
  final def orElseOptional[R1 <: R, E1, A1 >: A](
    that: => ZIO[R1, Option[E1], A1]
  )(implicit ev: E IsSubtypeOfError Option[E1]): ZIO[R1, Option[E1], A1] =
    catchAll(ev(_).fold(that)(e => ZIO.fail(Some(e))))

  /**
   * Executes this effect and returns its value, if it succeeds, but
   * otherwise succeeds with the specified value.
   */
  final def orElseSucceed[A1 >: A](a1: => A1)(implicit ev: CanFail[E]): URIO[R, A1] =
    orElse(ZIO.succeedNow(a1))

  /**
   * Returns a new effect that will utilize the specified scope to supervise
   * any fibers forked within the original effect.
   */
  final def overrideForkScope(scope: => ZScope[Exit[Any, Any]]): ZIO[R, E, A] =
    new ZIO.OverrideForkScope(self, () => Some(scope))

  /**
   * Exposes all parallel errors in a single call
   */
  final def parallelErrors[E1 >: E]: ZIO[R, ::[E1], A] =
    self.foldCauseZIO(
      cause =>
        cause.failures match {
          case Nil            => ZIO.failCause(cause.asInstanceOf[Cause[Nothing]])
          case ::(head, tail) => ZIO.fail(::(head, tail))
        },
      ZIO.succeedNow
    )

  /**
   * Provides the `ZIO` effect with its required environment, which eliminates
   * its dependency on `R`.
   */
  final def provide(r: => R)(implicit ev: NeedsEnv[R]): IO[E, A] =
    ZIO.provide(r)(self)

  /**
   * Provides the part of the environment that is not part of the `ZEnv`,
   * leaving an effect that only depends on the `ZEnv`.
   *
   * {{{
   * val zio: ZIO[ZEnv with Logging, Nothing, Unit] = ???
   *
   * val loggingLayer: ZLayer[Any, Nothing, Logging] = ???
   *
   * val zio2 = zio.provideCustomLayer(loggingLayer)
   * }}}
   */
  final def provideCustomLayer[E1 >: E, R1](
    layer: => ZLayer[ZEnv, E1, R1]
  )(implicit ev1: ZEnv with R1 <:< R, ev2: Has.Union[ZEnv, R1], tagged: Tag[R1]): ZIO[ZEnv, E1, A] =
    provideSomeLayer[ZEnv](layer)

  /**
   * Provides a layer to the ZIO effect, which translates it to another level.
   */
  final def provideLayer[E1 >: E, R0, R1](layer: => ZLayer[R0, E1, R1])(implicit ev: R1 <:< R): ZIO[R0, E1, A] =
    ZIO.suspendSucceed(layer.build.map(ev).use(r => self.provide(r)))

  /**
   * Provides some of the environment required to run this effect when the
   * environment is not a subtype of `Has[_]`. This is useful primarily for
   * implementing operators that are polymorphic in the environment type. When
   * your environment is a subtype of `Has[_]` use [[zio.ZIO.injectSome]]
   */
  final def provideSome[R0](f: R0 => R)(implicit ev: NeedsEnv[R]): ZIO[R0, E, A] =
    ZIO.accessZIO(r0 => self.provide(f(r0)))

  /**
   * Splits the environment into two parts, providing one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val zio: ZIO[Has[Clock] with Has[Random], Nothing, Unit] = ???
   *
   * val clockLayer: ZLayer[Any, Nothing, Has[Clock]] = ???
   *
   * val zio2 = zio.provideSomeLayer[Has[Random]](clockLayer)
   * }}}
   */
  final def provideSomeLayer[R0]: ZIO.ProvideSomeLayer[R0, R, E, A] =
    new ZIO.ProvideSomeLayer[R0, R, E, A](self)

  /**
   * Returns a new effect that will utilize the default scope (fiber scope) to
   * supervise any fibers forked within the original effect.
   */
  final def resetForkScope: ZIO[R, E, A] =
    new ZIO.OverrideForkScope(self, () => None)

  /**
   * Returns an effect that races this effect with the specified effect,
   * returning the first successful `A` from the faster side. If one effect
   * succeeds, the other will be interrupted. If neither succeeds, then the
   * effect will fail with some error.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l race r`, perform
   * `l.disconnect race r.disconnect`, which disconnects left and right
   * interruption signals, allowing a fast return, with interruption performed
   * in the background.
   *
   * Note that if the `race` is embedded into an uninterruptible region, then
   * because the loser cannot be interrupted, it will be allowed to continue
   * executing in the background, without delaying the return of the race.
   */
  final def race[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    ZIO.descriptorWith { descriptor =>
      val parentFiberId = descriptor.id
      def maybeDisconnect[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
        ZIO.uninterruptibleMask(interruptible => interruptible.force(zio))
      (maybeDisconnect(self) raceWith maybeDisconnect(that))(
        (exit, right) =>
          exit.foldZIO[Any, E1, A1](
            cause => right.join mapErrorCause (cause && _),
            a => (right interruptAs parentFiberId) as a
          ),
        (exit, left) =>
          exit.foldZIO[Any, E1, A1](
            cause => left.join mapErrorCause (_ && cause),
            a => (left interruptAs parentFiberId) as a
          )
      )
    }.refailWithTrace

  /**
   * Returns an effect that races this effect with all the specified effects,
   * yielding the value of the first effect to succeed with a value.
   * Losers of the race will be interrupted immediately
   */
  final def raceAll[R1 <: R, E1 >: E, A1 >: A](ios: => Iterable[ZIO[R1, E1, A1]]): ZIO[R1, E1, A1] = {
    def arbiter[E1, A1](
      fibers: List[Fiber[E1, A1]],
      winner: Fiber[E1, A1],
      promise: Promise[E1, (A1, Fiber[E1, A1])],
      fails: Ref[Int]
    )(res: Exit[E1, A1]): URIO[R1, Any] =
      res.foldZIO[R1, Nothing, Unit](
        e => ZIO.flatten(fails.modify((c: Int) => (if (c == 0) promise.failCause(e).unit else ZIO.unit) -> (c - 1))),
        a =>
          promise
            .succeed(a -> winner)
            .flatMap(set =>
              if (set) fibers.foldLeft(IO.unit)((io, f) => if (f eq winner) io else io <* f.interrupt)
              else ZIO.unit
            )
      )

    (for {
      done  <- Promise.make[E1, (A1, Fiber[E1, A1])]
      fails <- Ref.make[Int](ios.size)
      c <- ZIO.uninterruptibleMask { restore =>
             for {
               head <- ZIO.interruptible(self).fork
               tail <- ZIO.foreach(ios)(io => ZIO.interruptible(io).fork)
               fs    = head :: tail.toList
               _ <- fs.foldLeft[ZIO[R1, E1, Any]](ZIO.unit) { case (io, f) =>
                      io *> f.await.flatMap(arbiter(fs, f, done, fails)).fork
                    }

               inheritRefs = { (res: (A1, Fiber[E1, A1])) => res._2.inheritRefs.as(res._1) }

               c <- restore(done.await.flatMap(inheritRefs))
                      .onInterrupt(fs.foldLeft(IO.unit)((io, f) => io <* f.interrupt))
             } yield c
           }
    } yield c).refailWithTrace
  }

  /**
   * Returns an effect that races this effect with the specified effect,
   * yielding the first result to complete, whether by success or failure. If
   * neither effect completes, then the composed effect will not complete.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l raceFirst r`, perform
   * `l.disconnect raceFirst r.disconnect`, which disconnects left and right
   * interrupt signal, allowing a fast return, with interruption performed
   * in the background.
   */
  final def raceFirst[R1 <: R, E1 >: E, A1 >: A](that: => ZIO[R1, E1, A1]): ZIO[R1, E1, A1] =
    (self.exit race that.exit).flatMap(ZIO.done(_)).refailWithTrace

  /**
   * Returns an effect that races this effect with the specified effect,
   * yielding the first result to succeed. If neither effect succeeds, then the
   * composed effect will fail with some error.
   *
   * WARNING: The raced effect will safely interrupt the "loser", but will not
   * resume until the loser has been cleanly terminated. If early return is
   * desired, then instead of performing `l raceEither r`, perform
   * `l.disconnect raceEither r.disconnect`, which disconnects left and right
   * interrupt signal, allowing the earliest possible return.
   */
  final def raceEither[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, Either[A, B]] =
    self.map(Left(_)) race that.map(Right(_))

  /**
   * Returns an effect that races this effect with the specified effect, calling
   * the specified finisher as soon as one result or the other has been computed.
   */
  final def raceWith[R1 <: R, E1, E2, B, C](that: => ZIO[R1, E1, B])(
    leftDone: (Exit[E, A], Fiber[E1, B]) => ZIO[R1, E2, C],
    rightDone: (Exit[E1, B], Fiber[E, A]) => ZIO[R1, E2, C],
    scope: => Option[ZScope[Exit[Any, Any]]] = None
  ): ZIO[R1, E2, C] =
    new ZIO.RaceWith[R1, E, E1, E2, A, B, C](
      () => self,
      () => that,
      (exit, fiber) => leftDone(exit, fiber),
      (exit, fiber) => rightDone(exit, fiber),
      () => scope
    )

  /**
   * Attach a wrapping trace pointing to this location in case of error.
   *
   * Useful when joining fibers to make the resulting trace mention
   * the `join` point, otherwise only the traces of joined fibers are
   * included.
   *
   * {{{
   *   for {
   *     badFiber <- UIO(1 / 0).fork
   *     _ <- badFiber.join.refailWithTrace
   *   } yield ()
   * }}}
   */
  final def refailWithTrace: ZIO[R, E, A] =
    foldCauseZIO(c => ZIO.failCauseWith(trace => Cause.traced(c, trace())), ZIO.succeedNow)

  /**
   * Keeps some of the errors, and terminates the fiber with the rest
   */
  final def refineOrDie[E1](
    pf: PartialFunction[E, E1]
  )(implicit ev1: E IsSubtypeOfError Throwable, ev2: CanFail[E]): ZIO[R, E1, A] =
    refineOrDieWith(pf)(ev1)

  /**
   * Keeps some of the errors, and terminates the fiber with the rest, using
   * the specified function to convert the `E` into a `Throwable`.
   */
  final def refineOrDieWith[E1](pf: PartialFunction[E, E1])(f: E => Throwable)(implicit ev: CanFail[E]): ZIO[R, E1, A] =
    self catchAll (err => (pf lift err).fold[ZIO[R, E1, A]](ZIO.die(f(err)))(ZIO.fail(_)))

  /**
   * Fail with the returned value if the `PartialFunction` matches, otherwise
   * continue with our held value.
   */
  final def reject[E1 >: E](pf: PartialFunction[A, E1]): ZIO[R, E1, A] =
    rejectZIO(pf.andThen(ZIO.fail(_)))

  /**
   * Continue with the returned computation if the `PartialFunction` matches,
   * translating the successful match into a failure, otherwise continue with
   * our held value.
   */
  @deprecated("use rejectZIO", "2.0.0")
  final def rejectM[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, E1]]): ZIO[R1, E1, A] =
    rejectZIO(pf)

  /**
   * Continue with the returned computation if the `PartialFunction` matches,
   * translating the successful match into a failure, otherwise continue with
   * our held value.
   */
  final def rejectZIO[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, E1]]): ZIO[R1, E1, A] =
    self.flatMap { v =>
      pf.andThen[ZIO[R1, E1, A]](_.flatMap(ZIO.fail(_)))
        .applyOrElse[A, ZIO[R1, E1, A]](v, ZIO.succeedNow)
    }

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure. Scheduled recurrences are in addition
   * to the first execution, so that `io.repeat(Schedule.once)` yields an
   * effect that executes `io`, and then if that succeeds, executes `io` an
   * additional time.
   */
  final def repeat[R1 <: R, B](schedule: => Schedule[R1, A, B]): ZIO[R1 with Has[Clock], E, B] =
    repeatOrElse[R1, E, B](schedule, (e, _) => ZIO.fail(e))

  /**
   * Repeats this effect the specified number of times.
   */
  final def repeatN(n: => Int): ZIO[R, E, A] =
    ZIO.suspendSucceed {

      def loop(n: Int): ZIO[R, E, A] =
        self.flatMap(a => if (n <= 0) ZIO.succeedNow(a) else ZIO.yieldNow *> loop(n - 1))

      loop(n)
    }

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure, at which point, the failure value
   * and schedule output are passed to the specified handler.
   *
   * Scheduled recurrences are in addition to the first execution, so that
   * `io.repeat(Schedule.once)` yields an effect that executes `io`, and then
   * if that succeeds, executes `io` an additional time.
   */
  final def repeatOrElse[R1 <: R, E2, B](
    schedule: => Schedule[R1, A, B],
    orElse: (E, Option[B]) => ZIO[R1, E2, B]
  ): ZIO[R1 with Has[Clock], E2, B] =
    repeatOrElseEither[R1, B, E2, B](schedule, orElse).map(_.merge)

  /**
   * Returns a new effect that repeats this effect according to the specified
   * schedule or until the first failure, at which point, the failure value
   * and schedule output are passed to the specified handler.
   *
   * Scheduled recurrences are in addition to the first execution, so that
   * `io.repeat(Schedule.once)` yields an effect that executes `io`, and then
   * if that succeeds, executes `io` an additional time.
   */
  final def repeatOrElseEither[R1 <: R, B, E2, C](
    schedule: => Schedule[R1, A, B],
    orElse: (E, Option[B]) => ZIO[R1, E2, C]
  ): ZIO[R1 with Has[Clock], E2, Either[C, B]] =
    Clock.repeatOrElseEither(self)(schedule, orElse)

  /**
   * Repeats this effect until its value satisfies the specified predicate
   * or until the first failure.
   */
  final def repeatUntil(f: A => Boolean): ZIO[R, E, A] =
    repeatUntilZIO(a => ZIO.succeed(f(a)))

  /**
   * Repeats this effect until its value is equal to the specified value
   * or until the first failure.
   */
  final def repeatUntilEquals[A1 >: A](a: => A1): ZIO[R, E, A1] =
    repeatUntil(_ == a)

  /**
   * Repeats this effect until its value satisfies the specified effectful predicate
   * or until the first failure.
   */
  @deprecated("use repeatUntilZIO", "2.0.0")
  final def repeatUntilM[R1 <: R](f: A => URIO[R1, Boolean]): ZIO[R1, E, A] =
    repeatUntilZIO(f)

  /**
   * Repeats this effect until its value satisfies the specified effectful predicate
   * or until the first failure.
   */
  final def repeatUntilZIO[R1 <: R](f: A => URIO[R1, Boolean]): ZIO[R1, E, A] =
    self.flatMap(a => f(a).flatMap(b => if (b) ZIO.succeedNow(a) else ZIO.yieldNow *> repeatUntilZIO(f)))

  /**
   * Repeats this effect while its value satisfies the specified predicate
   * or until the first failure.
   */
  final def repeatWhile(f: A => Boolean): ZIO[R, E, A] =
    repeatWhileZIO(a => ZIO.succeed(f(a)))

  /**
   * Repeats this effect for as long as its value is equal to the specified value
   * or until the first failure.
   */
  final def repeatWhileEquals[A1 >: A](a: => A1): ZIO[R, E, A1] =
    repeatWhile(_ == a)

  /**
   * Repeats this effect while its value satisfies the specified effectful predicate
   * or until the first failure.
   */
  @deprecated("use repeatWhileZIO", "2.0.0")
  final def repeatWhileM[R1 <: R](f: A => URIO[R1, Boolean]): ZIO[R1, E, A] =
    repeatWhileZIO(f)

  /**
   * Repeats this effect while its value satisfies the specified effectful predicate
   * or until the first failure.
   */
  final def repeatWhileZIO[R1 <: R](f: A => URIO[R1, Boolean]): ZIO[R1, E, A] =
    repeatUntilZIO(e => f(e).map(!_))

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  @deprecated("use replicateZIO", "2.0.0")
  final def replicateM(n: => Int): ZIO[R, E, Iterable[A]] =
    replicateZIO(n)

  /**
   * Performs this effect the specified number of times, discarding the
   * results.
   */
  @deprecated("use replicateZIODiscard", "2.0.0")
  final def replicateM_(n: => Int): ZIO[R, E, Unit] =
    replicateZIODiscard(n)

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  final def replicateZIO(n: => Int): ZIO[R, E, Iterable[A]] =
    ZIO.replicateZIO(n)(self)

  /**
   * Performs this effect the specified number of times, discarding the
   * results.
   */
  final def replicateZIODiscard(n: => Int): ZIO[R, E, Unit] =
    ZIO.replicateZIODiscard(n)(self)

  /**
   * Retries with the specified retry policy.
   * Retries are done following the failure of the original `io` (up to a fixed maximum with
   * `once` or `recurs` for example), so that that `io.retry(Schedule.once)` means
   * "execute `io` and in case of failure, try again once".
   */
  final def retry[R1 <: R, S](policy: => Schedule[R1, E, S])(implicit ev: CanFail[E]): ZIO[R1 with Has[Clock], E, A] =
    Clock.retry(self)(policy)

  /**
   * Retries this effect the specified number of times.
   */
  final def retryN(n: => Int)(implicit ev: CanFail[E]): ZIO[R, E, A] =
    ZIO.suspendSucceed {

      def loop(n: Int): ZIO[R, E, A] =
        self.catchAll(e => if (n <= 0) ZIO.fail(e) else ZIO.yieldNow *> loop(n - 1))

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
  )(implicit ev: CanFail[E]): ZIO[R1 with Has[Clock], E1, A1] =
    Clock.retryOrElse[R, R1, E, E1, A, A1, S](self)(policy, orElse)

  /**
   * Returns an effect that retries this effect with the specified schedule when it fails, until
   * the schedule is done, then both the value produced by the schedule together with the last
   * error are passed to the specified recovery function.
   */
  final def retryOrElseEither[R1 <: R, Out, E1, B](
    schedule: => Schedule[R1, E, Out],
    orElse: (E, Out) => ZIO[R1, E1, B]
  )(implicit ev: CanFail[E]): ZIO[R1 with Has[Clock], E1, Either[B, A]] =
    Clock.retryOrElseEither(self)(schedule, orElse)

  /**
   * Retries this effect until its error satisfies the specified predicate.
   */
  final def retryUntil(f: E => Boolean)(implicit ev: CanFail[E]): ZIO[R, E, A] =
    retryUntilZIO(e => ZIO.succeed(f(e)))

  /**
   * Retries this effect until its error is equal to the specified error.
   */
  final def retryUntilEquals[E1 >: E](e: => E1)(implicit ev: CanFail[E1]): ZIO[R, E1, A] =
    retryUntil(_ == e)

  /**
   * Retries this effect until its error satisfies the specified effectful predicate.
   */
  @deprecated("use retryUntilZIO", "2.0.0")
  final def retryUntilM[R1 <: R](f: E => URIO[R1, Boolean])(implicit ev: CanFail[E]): ZIO[R1, E, A] =
    retryUntilZIO(f)

  /**
   * Retries this effect until its error satisfies the specified effectful predicate.
   */
  final def retryUntilZIO[R1 <: R](f: E => URIO[R1, Boolean])(implicit ev: CanFail[E]): ZIO[R1, E, A] =
    self.catchAll(e => f(e).flatMap(b => if (b) ZIO.fail(e) else ZIO.yieldNow *> retryUntilZIO(f)))

  /**
   * Retries this effect while its error satisfies the specified predicate.
   */
  final def retryWhile(f: E => Boolean)(implicit ev: CanFail[E]): ZIO[R, E, A] =
    retryWhileZIO(e => ZIO.succeed(f(e)))

  /**
   * Retries this effect for as long as its error is equal to the specified error.
   */
  final def retryWhileEquals[E1 >: E](e: => E1)(implicit ev: CanFail[E1]): ZIO[R, E1, A] =
    retryWhile(_ == e)

  /**
   * Retries this effect while its error satisfies the specified effectful predicate.
   */
  @deprecated("use retryWhileZIO", "2.0.0")
  final def retryWhileM[R1 <: R](f: E => URIO[R1, Boolean])(implicit ev: CanFail[E]): ZIO[R1, E, A] =
    retryWhileZIO(f)

  /**
   * Retries this effect while its error satisfies the specified effectful predicate.
   */
  final def retryWhileZIO[R1 <: R](f: E => URIO[R1, Boolean])(implicit ev: CanFail[E]): ZIO[R1, E, A] =
    retryUntilZIO(e => f(e).map(!_))

  /**
   * "Zooms in" on the value in the `Right` side of an `Either`, moving the
   * possibility that the value is a `Left` to the error channel.
   */
  final def right[B, C](implicit ev: A IsSubtypeOfOutput Either[B, C]): ZIO[R, Either[B, E], C] =
    self.foldZIO(
      e => ZIO.fail(Right(e)),
      a => ev(a).fold(b => ZIO.fail(Left(b)), c => ZIO.succeedNow(c))
    )

  /**
   * Returns an effect that semantically runs the effect on a fiber,
   * producing an [[zio.Exit]] for the completion value of the fiber.
   */
  @deprecated("use exit", "2.0.0")
  final def run: URIO[R, Exit[E, A]] =
    exit

  /**
   * Exposes the full cause of failure of this effect.
   *
   * {{{
   * final case class DomainError()
   *
   * val veryBadIO: IO[DomainError, Unit] =
   *   IO.succeed(5 / 0) *> IO.fail(DomainError())
   *
   * val caught: IO[DomainError, Unit] =
   *   veryBadIO.sandbox.mapError(_.untraced).catchAll {
   *     case Cause.Die(_: ArithmeticException) =>
   *       // Caught defect: divided by zero!
   *       IO.unit
   *     case Cause.Fail(_) =>
   *       // Caught error: DomainError!
   *       IO.unit
   *     case cause =>
   *       // Caught unknown defects, shouldn't recover!
   *       IO.failCause(cause)
   *   }
   * }}}
   */
  final def sandbox: ZIO[R, Cause[E], A] =
    foldCauseZIO(ZIO.fail(_), ZIO.succeedNow)

  /**
   * Runs this effect according to the specified schedule.
   *
   * See [[scheduleFrom]] for a variant that allows the schedule's decision to
   * depend on the result of this effect.
   */
  final def schedule[R1 <: R, B](schedule: => Schedule[R1, Any, B]): ZIO[R1 with Has[Clock], E, B] =
    Clock.schedule(self)(schedule)

  /**
   * Runs this effect according to the specified schedule starting from the
   * specified input value.
   */
  final def scheduleFrom[R1 <: R, A1 >: A, B](a: => A1)(
    schedule: => Schedule[R1, A1, B]
  ): ZIO[R1 with Has[Clock], E, B] =
    Clock.scheduleFrom[R, R1, E, A, A1, B](self)(a)(schedule)

  /**
   * Converts an option on values into an option on errors.
   */
  final def some[B](implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R, Option[E], B] =
    self.foldZIO(
      e => ZIO.fail(Some(e)),
      a => ev(a).fold[ZIO[R, Option[E], B]](ZIO.fail(Option.empty[E]))(ZIO.succeedNow)
    )

  /**
   * Extracts the optional value, or returns the given 'default'.
   */
  final def someOrElse[B](default: => B)(implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R, E, B] =
    map(a => ev(a).getOrElse(default))

  /**
   * Extracts the optional value, or executes the effect 'default'.
   */
  @deprecated("use someOrElseZIO", "2.0.0")
  final def someOrElseM[B, R1 <: R, E1 >: E](
    default: => ZIO[R1, E1, B]
  )(implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R1, E1, B] =
    someOrElseZIO(default)

  /**
   * Extracts the optional value, or executes the effect 'default'.
   */
  final def someOrElseZIO[B, R1 <: R, E1 >: E](
    default: => ZIO[R1, E1, B]
  )(implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R1, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => ZIO.succeedNow(value)
      case None        => default
    })

  /**
   * Extracts the optional value, or fails with the given error 'e'.
   */
  final def someOrFail[B, E1 >: E](e: => E1)(implicit ev: A IsSubtypeOfOutput Option[B]): ZIO[R, E1, B] =
    self.flatMap(ev(_) match {
      case Some(value) => ZIO.succeedNow(value)
      case None        => ZIO.fail(e)
    })

  /**
   * Extracts the optional value, or fails with a [[java.util.NoSuchElementException]]
   */
  final def someOrFailException[B, E1 >: E](implicit
    ev: A IsSubtypeOfOutput Option[B],
    ev2: NoSuchElementException <:< E1
  ): ZIO[R, E1, B] =
    self.foldZIO(
      e => ZIO.fail(e),
      ev(_) match {
        case Some(value) => ZIO.succeedNow(value)
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
   *   IO.succeed(5 / 0) *> IO.fail(DomainError())
   *
   * val caught: IO[DomainError, Unit] =
   *   veryBadIO.sandboxWith(_.catchSome {
   *     case Cause.Die(_: ArithmeticException)=>
   *       // Caught defect: divided by zero!
   *       IO.succeed(0)
   *   })
   * }}}
   *
   * Using `sandboxWith` with `catchSome` is better than using
   * `io.sandbox.catchAll` with a partial match, because in
   * the latter, if the match fails, the original defects will
   * be lost and replaced by a `MatchError`
   */
  final def sandboxWith[R1 <: R, E2, B](f: ZIO[R1, Cause[E], A] => ZIO[R1, Cause[E2], B]): ZIO[R1, E2, B] =
    ZIO.unsandbox(f(self.sandbox))

  /**
   * Summarizes a effect by computing some value before and after execution, and
   * then combining the values to produce a summary, together with the result of
   * execution.
   */
  final def summarized[R1 <: R, E1 >: E, B, C](summary0: => ZIO[R1, E1, B])(f: (B, B) => C): ZIO[R1, E1, (C, A)] =
    ZIO.suspendSucceed {
      val summary = summary0

      for {
        start <- summary
        value <- self
        end   <- summary
      } yield (f(start, end), value)
    }

  /**
   * Returns an effect with the behavior of this one, but where all child
   * fibers forked in the effect are reported to the specified supervisor.
   */
  final def supervised(supervisor: => Supervisor[Any]): ZIO[R, E, A] =
    new ZIO.Supervise(self, () => supervisor)

  /**
   * An integer that identifies the term in the `ZIO` sum type to which this
   * instance belongs (e.g. `IO.Tags.Succeed`).
   */
  def tag: Int

  /**
   * Returns an effect that effectfully "peeks" at the success of this effect.
   *
   * {{{
   * readFile("data.json").tap(printLine)
   * }}}
   */
  final def tap[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, Any]): ZIO[R1, E1, A] =
    self.flatMap(new ZIO.TapFn(f))

  /**
   * Returns an effect that effectfully "peeks" at the failure or success of
   * this effect.
   * {{{
   * readFile("data.json").tapBoth(logError(_), logData(_))
   * }}}
   */
  final def tapBoth[R1 <: R, E1 >: E](f: E => ZIO[R1, E1, Any], g: A => ZIO[R1, E1, Any])(implicit
    ev: CanFail[E]
  ): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapErrorRefailFn(f), new ZIO.TapFn(g))

  /**
   * Returns an effect that effectually "peeks" at the cause of the failure of
   * this effect.
   * {{{
   * readFile("data.json").tapCause(logCause(_))
   * }}}
   */
  @deprecated("use tapErrorCause", "2.0.0")
  final def tapCause[R1 <: R, E1 >: E](f: Cause[E] => ZIO[R1, E1, Any]): ZIO[R1, E1, A] =
    tapErrorCause(f)

  /**
   * Returns an effect that effectually "peeks" at the defect of this effect.
   */
  final def tapDefect[R1 <: R, E1 >: E](f: Cause[Nothing] => ZIO[R1, E1, Any]): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapDefectRefailFn(f), ZIO.succeedNow)

  /**
   * Returns an effect that effectfully "peeks" at the result of this effect.
   * {{{
   * readFile("data.json").tapEither(result => log(result.fold("Error: " + _, "Success: " + _)))
   * }}}
   */
  final def tapEither[R1 <: R, E1 >: E](f: Either[E, A] => ZIO[R1, E1, Any])(implicit
    ev: CanFail[E]
  ): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapErrorRefailFn(e => f(Left(e))), new ZIO.TapFn(a => f(Right(a))))

  /**
   * Returns an effect that effectfully "peeks" at the failure of this effect.
   * {{{
   * readFile("data.json").tapError(logError(_))
   * }}}
   */
  final def tapError[R1 <: R, E1 >: E](f: E => ZIO[R1, E1, Any])(implicit ev: CanFail[E]): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapErrorRefailFn(f), ZIO.succeedNow)

  /**
   * Returns an effect that effectually "peeks" at the cause of the failure of
   * this effect.
   * {{{
   * readFile("data.json").tapErrorCause(logCause(_))
   * }}}
   */
  final def tapErrorCause[R1 <: R, E1 >: E](f: Cause[E] => ZIO[R1, E1, Any]): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapErrorCauseRefailFn(f), ZIO.succeedNow)

  /**
   * A version of `tapError` that gives you the (optional) trace of the error.
   */
  final def tapErrorTrace[R1 <: R, E1 >: E](
    f: ((E, Option[ZTrace])) => ZIO[R1, E1, Any]
  )(implicit ev: CanFail[E]): ZIO[R1, E1, A] =
    self.foldCauseZIO(new ZIO.TapErrorTraceRefailFn(f), ZIO.succeedNow)

  /**
   * Returns an effect that effectfully "peeks" at the success of this effect.
   * If the partial function isn't defined at the input, the result is equivalent to the original effect.
   *
   * {{{
   * readFile("data.json").tapSome {
   *   case content if content.nonEmpty => putStrLn(content)
   * }
   * }}}
   */
  final def tapSome[R1 <: R, E1 >: E](f: PartialFunction[A, ZIO[R1, E1, Any]]): ZIO[R1, E1, A] =
    self.tap(f.applyOrElse(_, (_: A) => ZIO.unit))

  /**
   * Returns a new effect that executes this one and times the execution.
   */
  final def timed: ZIO[R with Has[Clock], E, (Duration, A)] =
    timedWith(Clock.nanoTime)

  /**
   * A more powerful variation of `timed` that allows specifying the Clock.
   */
  final def timedWith[R1 <: R, E1 >: E](nanoTime: => ZIO[R1, E1, Long]): ZIO[R1, E1, (Duration, A)] =
    summarized(nanoTime)((start, end) => Duration.fromNanos(end - start))

  /**
   * Returns an effect that will timeout this effect, returning `None` if the
   * timeout elapses before the effect has produced a value; and returning
   * `Some` of the produced value otherwise.
   *
   * If the timeout elapses without producing a value, the running effect
   * will be safely interrupted.
   *
   * WARNING: The effect returned by this method will not itself return until
   * the underlying effect is actually interrupted. This leads to more
   * predictable resource utilization. If early return is desired, then
   * instead of using `effect.timeout(d)`, use `effect.disconnect.timeout(d)`,
   * which first disconnects the effect's interruption signal before performing
   * the timeout, resulting in earliest possible return, before an underlying
   * effect has been successfully interrupted.
   */
  final def timeout(d: => Duration): ZIO[R with Has[Clock], E, Option[A]] = timeoutTo(None)(Some(_))(d)

  /**
   * The same as [[timeout]], but instead of producing a `None` in the event
   * of timeout, it will produce the specified error.
   */
  final def timeoutFail[E1 >: E](e: => E1)(d: => Duration): ZIO[R with Has[Clock], E1, A] =
    ZIO.flatten(timeoutTo(ZIO.fail(e))(ZIO.succeedNow)(d))

  /**
   * The same as [[timeout]], but instead of producing a `None` in the event
   * of timeout, it will produce the specified failure.
   */
  final def timeoutFailCause[E1 >: E](cause: => Cause[E1])(d: => Duration): ZIO[R with Has[Clock], E1, A] =
    ZIO.flatten(timeoutTo(ZIO.failCause(cause))(ZIO.succeedNow)(d))

  /**
   * The same as [[timeout]], but instead of producing a `None` in the event
   * of timeout, it will produce the specified failure.
   */
  @deprecated("use timeoutFailCause", "2.0.0")
  final def timeoutHalt[E1 >: E](cause: => Cause[E1])(d: => Duration): ZIO[R with Has[Clock], E1, A] =
    timeoutFailCause(cause)(d)

  /**
   * Returns an effect that will timeout this effect, returning either the
   * default value if the timeout elapses before the effect has produced a
   * value; and or returning the result of applying the function `f` to the
   * success value of the effect.
   *
   * If the timeout elapses without producing a value, the running effect
   * will be safely interrupted
   *
   * {{{
   * IO.succeed(1).timeoutTo(None)(Some(_))(1.second)
   * }}}
   */
  final def timeoutTo[B](b: => B): ZIO.TimeoutTo[R, E, A, B] =
    new ZIO.TimeoutTo(self, () => b)

  /**
   * Returns an effect that keeps or breaks a promise based on the result of
   * this effect. Synchronizes interruption, so if this effect is interrupted,
   * the specified promise will be interrupted, too.
   */
  @deprecated("use intoPromise", "2.0.0")
  final def to[E1 >: E, A1 >: A](p: => Promise[E1, A1]): URIO[R, Boolean] =
    intoPromise(p)

  /**
   * Converts the effect into a [[scala.concurrent.Future]].
   */
  final def toFuture(implicit ev2: E IsSubtypeOfError Throwable): URIO[R, CancelableFuture[A]] =
    self toFutureWith ev2

  /**
   * Converts the effect into a [[scala.concurrent.Future]].
   */
  final def toFutureWith(f: E => Throwable): URIO[R, CancelableFuture[A]] =
    self.fork.flatMap(_.toFutureWith(f))

  /**
   * Constructs a layer from this effect.
   */
  final def toLayer[A1 >: A](implicit ev: Tag[A1]): ZLayer[R, E, Has[A1]] =
    ZLayer.fromZIO(self)

  /**
   * Constructs a layer from this effect, which must return one or more
   * services.
   */
  final def toLayerMany[A1 >: A](implicit ev: Tag[A1]): ZLayer[R, E, A1] =
    ZLayer.fromZIOMany(self)

  /**
   * Converts this ZIO to [[zio.ZManaged]] with no release action. It will be
   * performed interruptibly.
   */
  final def toManaged: ZManaged[R, E, A] =
    ZManaged.fromZIO(self)

  /**
   * Converts this ZIO to [[zio.Managed]]. This ZIO and the provided release
   * action will be performed uninterruptibly.
   */
  final def toManagedWith[R1 <: R](release: A => URIO[R1, Any]): ZManaged[R1, E, A] =
    ZManaged.acquireReleaseWith(self)(release)

  /**
   * Converts this ZIO to [[zio.ZManaged]] with no release action. It will be
   * performed interruptibly.
   */
  @deprecated("use toManaged", "2.0.0")
  final def toManaged_ : ZManaged[R, E, A] =
    self.toManaged

  /**
   * Enables ZIO tracing for this effect. Because this is the default, this
   * operation only has an additional meaning if the effect is located within
   * an `untraced` section, or the current fiber has been spawned by a parent
   * inside an `untraced` section.
   */
  final def traced: ZIO[R, E, A] =
    tracingStatus(TracingStatus.Traced)

  /**
   * Toggles ZIO tracing support for this effect. If `true` is used, then the
   * effect will accumulate traces, while if `false` is used, then tracing
   * is disabled. These changes are compositional, so they only affect regions
   * of the effect.
   */
  final def tracingStatus(flag: => TracingStatus): ZIO[R, E, A] =
    new ZIO.TracingStatus(self, () => flag)

  /**
   * When this effect succeeds with a cause, then this method returns a new
   * effect that either fails with the cause that this effect succeeded with,
   * or succeeds with unit, depending on whether the cause is empty.
   *
   * This operation is the opposite of [[cause]].
   */
  final def uncause[E1 >: E](implicit ev: A IsSubtypeOfOutput Cause[E1]): ZIO[R, E1, Unit] =
    self.flatMap { a =>
      val cause = ev(a)

      if (cause.isEmpty) ZIO.unit
      else ZIO.failCause(cause)
    }

  /**
   * Performs this effect uninterruptibly. This will prevent the effect from
   * being terminated externally, but the effect may fail for internal reasons
   * (e.g. an uncaught error) or terminate due to defect.
   *
   * Uninterruptible effects may recover from all failure causes (including
   * interruption of an inner effect that has been made interruptible).
   */
  final def uninterruptible: ZIO[R, E, A] =
    interruptStatus(InterruptStatus.Uninterruptible)

  /**
   * Returns the effect resulting from mapping the success of this effect to unit.
   */
  final def unit: ZIO[R, E, Unit] =
    as(())

  /**
   * Converts a `ZIO[R, Either[E, B], A]` into a `ZIO[R, E, Either[A, B]]`. The
   * inverse of `left`.
   */
  final def unleft[E1, B](implicit ev: E IsSubtypeOfError Either[E1, B]): ZIO[R, E1, Either[A, B]] =
    self.foldZIO(
      e => ev(e).fold(e1 => ZIO.fail(e1), b => ZIO.succeedNow(Right(b))),
      a => ZIO.succeedNow(Left(a))
    )

  /**
   * The moral equivalent of `if (!p) exp`
   */
  final def unless(b: => Boolean): ZIO[R, E, Unit] =
    ZIO.unless(b)(self)

  /**
   * The moral equivalent of `if (!p) exp` when `p` has side-effects
   */
  @deprecated("use unlessZIO", "2.0.0")
  final def unlessM[R1 <: R, E1 >: E](b: => ZIO[R1, E1, Boolean]): ZIO[R1, E1, Unit] =
    unlessZIO(b)

  /**
   * The moral equivalent of `if (!p) exp` when `p` has side-effects
   */
  final def unlessZIO[R1 <: R, E1 >: E](b: => ZIO[R1, E1, Boolean]): ZIO[R1, E1, Unit] =
    ZIO.unlessZIO(b)(self)

  /**
   * Converts an option on errors into an option on values.
   */
  final def unoption[E1](implicit ev: E IsSubtypeOfError Option[E1]): ZIO[R, E1, Option[A]] =
    self.foldZIO(
      e => ev(e).fold[ZIO[R, E1, Option[A]]](ZIO.succeedNow(Option.empty[A]))(ZIO.fail(_)),
      a => ZIO.succeedNow(Some(a))
    )

  /**
   * Takes some fiber failures and converts them into errors.
   */
  final def unrefine[E1 >: E](pf: PartialFunction[Throwable, E1]): ZIO[R, E1, A] =
    unrefineWith(pf)(identity)

  /**
   * Takes some fiber failures and converts them into errors.
   */
  final def unrefineTo[E1 >: E: ClassTag]: ZIO[R, E1, A] =
    unrefine { case e: E1 => e }

  /**
   * Takes some fiber failures and converts them into errors, using the
   * specified function to convert the `E` into an `E1`.
   */
  final def unrefineWith[E1](pf: PartialFunction[Throwable, E1])(f: E => E1): ZIO[R, E1, A] =
    catchAllCause { cause =>
      cause.find {
        case Cause.Die(t) if pf.isDefinedAt(t) => pf(t)
      }.fold(ZIO.failCause(cause.map(f)))(ZIO.fail(_))
    }

  /**
   * Converts a `ZIO[R, Either[B, E], A]` into a `ZIO[R, E, Either[B, A]]`. The
   * inverse of `right`.
   */
  final def unright[E1, B](implicit ev: E IsSubtypeOfError Either[B, E1]): ZIO[R, E1, Either[B, A]] =
    self.foldZIO(
      e => ev(e).fold(b => ZIO.succeedNow(Left(b)), e1 => ZIO.fail(e1)),
      a => ZIO.succeedNow(Right(a))
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

  /**
   * The inverse operation to `sandbox`. Submerges the full cause of failure.
   */
  final def unsandbox[E1](implicit ev: E IsSubtypeOfError Cause[E1]): ZIO[R, E1, A] =
    ZIO.unsandbox(self.mapError(ev))

  /**
   * Disables ZIO tracing facilities for the duration of the effect.
   *
   * Note: ZIO tracing is cached, as such after the first iteration
   * it has a negligible effect on performance of hot-spots (Additional
   * hash map lookup per flatMap). As such, using `untraced` sections
   * is not guaranteed to result in a noticeable performance increase.
   */
  final def untraced: ZIO[R, E, A] =
    tracingStatus(TracingStatus.Untraced)

  /**
   * Sequentially zips the this result with the specified result. Combines both
   * `Cause[E1]` when both effects fail.
   */
  final def validate[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    validateWith(that)((_, _))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel. Combines both Cause[E1]` when both effects fail.
   */
  final def validatePar[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, (A, B)] =
    validateWithPar(that)((_, _))

  /**
   * Sequentially zips this effect with the specified effect using the
   * specified combiner function. Combines the causes in case both effect fail.
   */
  final def validateWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C): ZIO[R1, E1, C] =
    self.exit.zipWith(that.exit)(_.zipWith(_)(f, _ ++ _)).flatMap(ZIO.done(_))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results with the specified `f` function. If
   * both sides fail, then the cause will be combined.
   */
  final def validateWithPar[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C): ZIO[R1, E1, C] =
    self.exit.zipWithPar(that.exit)(_.zipWith(_)(f, _ && _)).flatMap(ZIO.done(_))

  /**
   * The moral equivalent of `if (p) exp`
   */
  final def when(b: => Boolean): ZIO[R, E, Unit] =
    ZIO.when(b)(self)

  /**
   * The moral equivalent of `if (p) exp` when `p` has side-effects
   */
  @deprecated("use whenZIO", "2.0.0")
  final def whenM[R1 <: R, E1 >: E](
    b: => ZIO[R1, E1, Boolean]
  ): ZIO[R1, E1, Unit] =
    whenZIO(b)

  /**
   * The moral equivalent of `if (p) exp` when `p` has side-effects
   */
  final def whenZIO[R1 <: R, E1 >: E](
    b: => ZIO[R1, E1, Boolean]
  ): ZIO[R1, E1, Unit] =
    ZIO.whenZIO(b)(self)

  /**
   * A named alias for `&&&` or `<*>`.
   */
  final def zip[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B]
  ): ZIO[R1, E1, zippable.Out] =
    self.zipWith(that)((a, b) => zippable.zip(a, b))

  /**
   * A named alias for `<*`.
   */
  final def zipLeft[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, A] =
    self <* that

  /**
   * A named alias for `<&>`.
   */
  final def zipPar[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B])(implicit
    zippable: Zippable[A, B]
  ): ZIO[R1, E1, zippable.Out] =
    self <&> that

  /**
   * A named alias for `<&`.
   */
  final def zipParLeft[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, A] =
    self <& that

  /**
   * A named alias for `&>`.
   */
  final def zipParRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self &> that

  /**
   * A named alias for `*>`.
   */
  final def zipRight[R1 <: R, E1 >: E, B](that: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
    self *> that

  /**
   * Sequentially zips this effect with the specified effect using the
   * specified combiner function.
   */
  final def zipWith[R1 <: R, E1 >: E, B, C](that: => ZIO[R1, E1, B])(f: (A, B) => C): ZIO[R1, E1, C] =
    self.flatMap(a => that.map(ZIOFn(f)(b => f(a, b))))

  /**
   * Returns an effect that executes both this effect and the specified effect,
   * in parallel, combining their results with the specified `f` function. If
   * either side fails, then the other side will be interrupted.
   */
  final def zipWithPar[R1 <: R, E1 >: E, B, C](
    that: => ZIO[R1, E1, B]
  )(f: (A, B) => C): ZIO[R1, E1, C] = {
    def coordinate[A, B](
      fiberId: FiberId,
      f: (A, B) => C,
      leftWinner: Boolean
    )(winner: Exit[E1, A], loser: Fiber[E1, B]): ZIO[R1, E1, C] =
      winner match {
        case Exit.Success(a) => loser.inheritRefs *> loser.join.map(f(a, _))
        case Exit.Failure(cause) =>
          loser.interruptAs(fiberId).flatMap {
            case Exit.Success(_) => ZIO.failCause(cause)
            case Exit.Failure(loserCause) =>
              if (leftWinner) ZIO.failCause(cause && loserCause)
              else ZIO.failCause(loserCause && cause)
          }
      }

    val g = (b: B, a: A) => f(a, b)

    ZIO.transplant { graft =>
      ZIO.descriptorWith { d =>
        (graft(self) raceWith graft(that))(coordinate(d.id, f, true), coordinate(d.id, g, false))
      }
    }
  }

  private[this] final def tryOrElse[R1 <: R, E2, B](
    that: => ZIO[R1, E2, B],
    success: A => ZIO[R1, E2, B]
  ): ZIO[R1, E2, B] =
    new ZIO.Fold[R1, E, E2, A, B](
      self,
      ZIOFn(() => that) { cause =>
        cause.keepDefects match {
          case None    => that
          case Some(c) => ZIO.failCause(c)
        }
      },
      success
    )
}

object ZIO extends ZIOCompanionPlatformSpecific {

  /**
   * Submerges the error case of an `Either` into the `ZIO`. The inverse
   * operation of `IO.either`.
   */
  def absolve[R, E, A](v: => ZIO[R, E, Either[E, A]]): ZIO[R, E, A] =
    suspendSucceed(v).flatMap(fromEither(_))

  /**
   * Accesses the environment of the effect.
   * {{{
   * val portNumber = effect.access(_.config.portNumber)
   * }}}
   */
  def access[R]: ZIO.AccessPartiallyApplied[R] =
    new ZIO.AccessPartiallyApplied[R]

  /**
   * Effectfully accesses the environment of the effect.
   */
  @deprecated("use accessZIO", "2.0.0")
  def accessM[R]: ZIO.AccessZIOPartiallyApplied[R] =
    accessZIO

  /**
   * Effectfully accesses the environment of the effect.
   */
  def accessZIO[R]: ZIO.AccessZIOPartiallyApplied[R] =
    new ZIO.AccessZIOPartiallyApplied[R]

  /**
   * When this effect represents acquisition of a resource (for example,
   * opening a file, launching a thread, etc.), `acquireReleaseWith` can be
   * used to ensure the acquisition is not interrupted and the resource is
   * always released.
   *
   * The function does two things:
   *
   * 1. Ensures this effect, which acquires the resource, will not be
   * interrupted. Of course, acquisition may fail for internal reasons (an
   * uncaught exception).
   * 2. Ensures the `release` effect will not be interrupted, and will be
   * executed so long as this effect successfully acquires the resource.
   *
   * In between acquisition and release of the resource, the `use` effect is
   * executed.
   *
   * If the `release` effect fails, then the entire effect will fail even
   * if the `use` effect succeeds. If this fail-fast behavior is not desired,
   * errors produced by the `release` effect can be caught and ignored.
   *
   * {{{
   * openFile("data.json").acquireReleaseWith(closeFile) { file =>
   *   for {
   *     header <- readHeader(file)
   *     ...
   *   } yield result
   * }
   * }}}
   */
  def acquireReleaseWith[R, E, A](acquire: => ZIO[R, E, A]): ZIO.BracketAcquire[R, E, A] =
    new ZIO.BracketAcquire[R, E, A](() => acquire)

  /**
   * Uncurried version. Doesn't offer curried syntax and have worse type-inference
   * characteristics, but guarantees no extra allocations of intermediate
   * [[zio.ZIO.BracketAcquire]] and [[zio.ZIO.BracketRelease]] objects.
   */
  def acquireReleaseWith[R, E, A, B](
    acquire: => ZIO[R, E, A],
    release: A => URIO[R, Any],
    use: A => ZIO[R, E, B]
  ): ZIO[R, E, B] =
    acquireReleaseExitWith(acquire, new ZIO.BracketReleaseFn(release): (A, Exit[E, B]) => URIO[R, Any], use)

  /**
   * Acquires a resource, uses the resource, and then releases the resource.
   * Neither the acquisition nor the release will be interrupted, and the
   * resource is guaranteed to be released, so long as the `acquire` effect
   * succeeds. If `use` fails, then after release, the returned effect will fail
   * with the same error.
   */
  def acquireReleaseExitWith[R, E, A](acquire: => ZIO[R, E, A]): ZIO.BracketExitAcquire[R, E, A] =
    new ZIO.BracketExitAcquire(() => acquire)

  /**
   * Uncurried version. Doesn't offer curried syntax and has worse type-inference
   * characteristics, but guarantees no extra allocations of intermediate
   * [[zio.ZIO.BracketExitAcquire]] and [[zio.ZIO.BracketExitRelease]] objects.
   */
  def acquireReleaseExitWith[R, E, A, B](
    acquire: => ZIO[R, E, A],
    release: (A, Exit[E, B]) => URIO[R, Any],
    use: A => ZIO[R, E, B]
  ): ZIO[R, E, B] =
    ZIO.uninterruptibleMask[R, E, B](restore =>
      acquire.flatMap(ZIOFn(traceAs = use) { a =>
        ZIO
          .suspendSucceed(restore(use(a)))
          .exit
          .flatMap(ZIOFn(traceAs = release) { e =>
            ZIO
              .suspendSucceed(release(a, e))
              .foldCauseZIO(
                cause2 => ZIO.failCause(e.fold(_ ++ cause2, _ => cause2)),
                _ => ZIO.done(e)
              )
          })
      })
    )

  /**
   * Makes an explicit check to see if the fiber has been interrupted, and if
   * so, performs self-interruption
   */
  def allowInterrupt: UIO[Unit] =
    descriptorWith(d => if (d.interrupters.nonEmpty) interrupt else ZIO.unit)

  /**
   * Imports an asynchronous side-effect into a pure `ZIO` value. See
   * `asyncMaybe` for the more expressive variant of this function that
   * can return a value synchronously.
   *
   * The callback function `ZIO[R, E, A] => Any` must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  def async[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Any,
    blockingOn: => FiberId = FiberId.None
  ): ZIO[R, E, A] =
    asyncMaybe(
      ZIOFn(register) { (callback: ZIO[R, E, A] => Unit) =>
        register(callback)

        None
      },
      blockingOn
    )

  /**
   * Imports an asynchronous side-effect into a ZIO effect. The side-effect
   * has the option of returning the value synchronously, which is useful in
   * cases where it cannot be determined if the effect is synchronous or
   * asynchronous until the side-effect is actually executed. The effect also
   * has the option of returning a canceler, which will be used by the runtime
   * to cancel the asynchronous effect if the fiber executing the effect is
   * interrupted.
   *
   * If the register function returns a value synchronously, then the callback
   * function `ZIO[R, E, A] => Any` must not be called. Otherwise the callback
   * function must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  def asyncInterrupt[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Either[Canceler[R], ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  ): ZIO[R, E, A] =
    new EffectAsync(register, () => blockingOn)

  /**
   * Imports an asynchronous effect into a pure `ZIO` value. This formulation is
   * necessary when the effect is itself expressed in terms of `ZIO`.
   */
  def asyncZIO[R, E, A](
    register: (ZIO[R, E, A] => Unit) => ZIO[R, E, Any]
  ): ZIO[R, E, A] =
    for {
      p <- Promise.make[E, A]
      r <- ZIO.runtime[R]
      a <- ZIO.uninterruptibleMask { restore =>
             val f = register(k => r.unsafeRunAsync(k.intoPromise(p)))

             restore(f.catchAllCause(p.failCause)).fork *> restore(p.await)
           }
    } yield a

  /**
   * Imports an asynchronous effect into a pure `ZIO` value, possibly returning
   * the value synchronously.
   *
   * If the register function returns a value synchronously, then the callback
   * function `ZIO[R, E, A] => Any` must not be called. Otherwise the callback
   * function must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  def asyncMaybe[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Option[ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  ): ZIO[R, E, A] =
    asyncInterrupt(
      register(_) match {
        case None      => Left(ZIO.unit)
        case Some(now) => Right(now)
      },
      blockingOn
    )

  /**
   * Imports a synchronous side-effect into a pure `ZIO` value, translating any
   * thrown exceptions into typed failed effects creating with `ZIO.fail`.
   *
   * {{{
   * def printLine(line: String): Task[Unit] = Task.attempt(println(line))
   * }}}
   */
  def attempt[A](effect: => A): Task[A] =
    effectTotalWith { (platform, _) =>
      try effect
      catch {
        case t: Throwable if !platform.fatal(t) => throw new ZioError(Exit.fail(t))
      }
    }

  /**
   * Imports a synchronous effect that does blocking IO into a pure value.
   */
  def attemptBlocking[A](effect: => A): Task[A] =
    blocking(ZIO.attempt(effect))

  /**
   * Imports a synchronous effect that does blocking IO into a pure value,
   * with a custom cancel effect.
   *
   * If the returned `ZIO` is interrupted, the blocked thread running the
   * synchronous effect will be interrupted via the cancel effect.
   */
  def attemptBlockingCancelable[A](effect: => A)(cancel: => UIO[Unit]): Task[A] =
    blocking(ZIO.attempt(effect)).fork.flatMap(_.join).onInterrupt(cancel)

  /**
   * Imports a synchronous effect that does blocking IO into a pure value,
   * refining the error type to `[[java.io.IOException]]`.
   */
  def attemptBlockingIO[A](effect: => A): IO[IOException, A] =
    attemptBlocking(effect).refineToOrDie[IOException]

  /**
   * Imports a synchronous effect that does blocking IO into a pure value.
   *
   * If the returned `ZIO` is interrupted, the blocked thread running the
   * synchronous effect will be interrupted via `Thread.interrupt`.
   *
   * Note that this adds significant overhead. For performance sensitive
   * applications consider using `attemptBlocking` or
   * `attemptBlockingCancelable`.
   */
  def attemptBlockingInterrupt[A](effect: => A): Task[A] =
    // Reference user's lambda for the tracer
    ZIOFn.recordTrace(() => effect) {
      ZIO.suspendSucceed {
        import java.util.concurrent.atomic.AtomicReference
        import java.util.concurrent.locks.ReentrantLock

        import zio.internal.OneShot

        val lock   = new ReentrantLock()
        val thread = new AtomicReference[Option[Thread]](None)
        val begin  = OneShot.make[Unit]
        val end    = OneShot.make[Unit]

        def withMutex[B](b: => B): B =
          try {
            lock.lock(); b
          } finally lock.unlock()

        val interruptThread: UIO[Unit] =
          ZIO.succeed {
            begin.get()

            var looping = true
            var n       = 0L
            val base    = 2L
            while (looping) {
              withMutex(thread.get match {
                case None         => looping = false; ()
                case Some(thread) => thread.interrupt()
              })

              if (looping) {
                n += 1
                Thread.sleep(math.min(50, base * n))
              }
            }

            end.get()
          }

        blocking(
          ZIO.uninterruptibleMask(restore =>
            for {
              fiber <- ZIO.suspend {
                         val current = Some(Thread.currentThread)

                         withMutex(thread.set(current))

                         begin.set(())

                         try {
                           val a = effect

                           ZIO.succeedNow(a)
                         } catch {
                           case _: InterruptedException =>
                             Thread.interrupted // Clear interrupt status
                             ZIO.interrupt
                           case t: Throwable =>
                             ZIO.fail(t)
                         } finally {
                           withMutex { thread.set(None); end.set(()) }
                         }
                       }.forkDaemon
              a <- restore(fiber.join.refailWithTrace).ensuring(interruptThread)
            } yield a
          )
        )
      }
    }

  /**
   * Locks the specified effect to the blocking thread pool.
   */
  def blocking[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.blockingExecutor.flatMap(zio.onExecutor(_))

  /**
   * Retrieves the executor for all blocking tasks.
   */
  def blockingExecutor: UIO[Executor] =
    ZIO.suspendSucceedWith((platform, _) => ZIO.succeedNow(platform.blockingExecutor))

  /**
   * When this effect represents acquisition of a resource (for example,
   * opening a file, launching a thread, etc.), `bracket` can be used to ensure
   * the acquisition is not interrupted and the resource is always released.
   *
   * The function does two things:
   *
   * 1. Ensures this effect, which acquires the resource, will not be
   * interrupted. Of course, acquisition may fail for internal reasons (an
   * uncaught exception).
   * 2. Ensures the `release` effect will not be interrupted, and will be
   * executed so long as this effect successfully acquires the resource.
   *
   * In between acquisition and release of the resource, the `use` effect is
   * executed.
   *
   * If the `release` effect fails, then the entire effect will fail even
   * if the `use` effect succeeds. If this fail-fast behavior is not desired,
   * errors produced by the `release` effect can be caught and ignored.
   *
   * {{{
   * openFile("data.json").bracket(closeFile) { file =>
   *   for {
   *     header <- readHeader(file)
   *     ...
   *   } yield result
   * }
   * }}}
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[R, E, A](acquire: => ZIO[R, E, A]): ZIO.BracketAcquire[R, E, A] =
    acquireReleaseWith(acquire)

  /**
   * Uncurried version. Doesn't offer curried syntax and have worse type-inference
   * characteristics, but guarantees no extra allocations of intermediate
   * [[zio.ZIO.BracketAcquire]] and [[zio.ZIO.BracketRelease]] objects.
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[R, E, A, B](
    acquire: => ZIO[R, E, A],
    release: A => URIO[R, Any],
    use: A => ZIO[R, E, B]
  ): ZIO[R, E, B] =
    acquireReleaseWith(acquire, release, use)

  /**
   * Acquires a resource, uses the resource, and then releases the resource.
   * Neither the acquisition nor the release will be interrupted, and the
   * resource is guaranteed to be released, so long as the `acquire` effect
   * succeeds. If `use` fails, then after release, the returned effect will fail
   * with the same error.
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[R, E, A](acquire: => ZIO[R, E, A]): ZIO.BracketExitAcquire[R, E, A] =
    acquireReleaseExitWith(acquire)

  /**
   * Uncurried version. Doesn't offer curried syntax and has worse type-inference
   * characteristics, but guarantees no extra allocations of intermediate
   * [[zio.ZIO.BracketExitAcquire]] and [[zio.ZIO.BracketExitRelease]] objects.
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[R, E, A, B](
    acquire: => ZIO[R, E, A],
    release: (A, Exit[E, B]) => URIO[R, Any],
    use: A => ZIO[R, E, B]
  ): ZIO[R, E, B] =
    acquireReleaseExitWith(acquire, release, use)

  /**
   * Checks the interrupt status, and produces the effect returned by the
   * specified callback.
   */
  def checkInterruptible[R, E, A](f: zio.InterruptStatus => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.CheckInterrupt(f)

  /**
   * Checks the ZIO Tracing status, and produces the effect returned by the
   * specified callback.
   */
  def checkTraced[R, E, A](f: TracingS => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.CheckTracing(f)

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel version, see `collectPar`.
   */
  def collect[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => ZIO[R, Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    foreach[R, E, A, Option[B], Iterable](in)(a => f(a).unoption).map(_.flatten).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel version, see `collectPar`.
   */
  def collect[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, Option[E], (Key2, Value2)]): ZIO[R, E, Map[Key2, Value2]] =
    foreach[R, E, (Key, Value), Option[(Key2, Value2)], Iterable](map)(f.tupled(_).unoption).map(_.flatten.toMap)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A](in: Set[ZIO[R, E, A]]): ZIO[R, E, Set[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A: ClassTag](in: Array[ZIO[R, E, A]]): ZIO[R, E, Array[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate effect if present, and return its result as `Option[A]`.
   */
  def collectAll[R, E, A](in: Option[ZIO[R, E, A]]): ZIO[R, E, Option[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and collect the
   * results. For a parallel version, see `collectAllPar`.
   */
  def collectAll[R, E, A](in: NonEmptyChunk[ZIO[R, E, A]]): ZIO[R, E, NonEmptyChunk[A]] =
    foreach(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure from left to right, and discard the
   * results. For a parallel version, see `collectAllPar_`.
   */
  @deprecated("use collectAllDiscard", "2.0.0")
  def collectAll_[R, E, A](in: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    collectAllDiscard(in)

  /**
   * Evaluate each effect in the structure from left to right, and discard the
   * results. For a parallel version, see `collectAllParDiscard`.
   */
  def collectAllDiscard[R, E, A](in: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    foreachDiscard(in)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the
   * results. For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the
   * results. For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A](as: Set[ZIO[R, E, A]]): ZIO[R, E, Set[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the
   * results. For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A: ClassTag](as: Array[ZIO[R, E, A]]): ZIO[R, E, Array[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the
   * results. For a sequential version, see `collectAll`.
   */
  def collectAllPar[R, E, A](as: NonEmptyChunk[ZIO[R, E, A]]): ZIO[R, E, NonEmptyChunk[A]] =
    foreachPar(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and discard the
   * results. For a sequential version, see `collectAll_`.
   */
  @deprecated("use collectAllParDiscard", "2.0.0")
  def collectAllPar_[R, E, A](as: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    collectAllParDiscard(as)

  /**
   * Evaluate each effect in the structure in parallel, and discard the
   * results. For a sequential version, see `collectAllDiscard`.
   */
  def collectAllParDiscard[R, E, A](as: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    foreachParDiscard(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and collect the
   * results. For a sequential version, see `collectAll`.
   *
   * Unlike `collectAllPar`, this method will use at most `n` fibers.
   */
  def collectAllParN[R, E, A, Collection[+Element] <: Iterable[Element]](n: => Int)(
    as: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    foreachParN(n)(as)(ZIO.identityFn)

  /**
   * Evaluate each effect in the structure in parallel, and discard the
   * results. For a sequential version, see `collectAll_`.
   *
   * Unlike `collectAllPar_`, this method will use at most `n` fibers.
   */
  @deprecated("use collectAllParNDiscard", "2.0.0")
  def collectAllParN_[R, E, A](n: => Int)(as: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    collectAllParNDiscard(n)(as)

  /**
   * Evaluate each effect in the structure in parallel, and discard the
   * results. For a sequential version, see `collectAllDiscard`.
   *
   * Unlike `collectAllParDiscard`, this method will use at most `n` fibers.
   */
  def collectAllParNDiscard[R, E, A](n: => Int)(as: => Iterable[ZIO[R, E, A]]): ZIO[R, E, Unit] =
    foreachParNDiscard(n)(as)(ZIO.identityFn)

  /**
   * Evaluate and run each effect in the structure and collect discarding failed ones.
   */
  def collectAllSuccesses[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): URIO[R, Collection[A]] =
    collectAllWith(in.map(_.exit)) { case zio.Exit.Success(a) => a }.map(bf.fromSpecific(in))

  /**
   * Evaluate and run each effect in the structure in parallel, and collect discarding failed ones.
   */
  def collectAllSuccessesPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): URIO[R, Collection[A]] =
    collectAllWithPar(in.map(_.exit)) { case zio.Exit.Success(a) => a }.map(bf.fromSpecific(in))

  /**
   * Evaluate and run each effect in the structure in parallel, and collect discarding failed ones.
   *
   * Unlike `collectAllSuccessesPar`, this method will use at most up to `n` fibers.
   */
  def collectAllSuccessesParN[R, E, A, Collection[+Element] <: Iterable[Element]](n: => Int)(
    in: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): URIO[R, Collection[A]] =
    collectAllWithParN(n)(in.map(_.exit)) { case zio.Exit.Success(a) => a }.map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure with `collectAll`, and collect
   * the results with given partial function.
   */
  def collectAllWith[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[ZIO[R, E, A]])(
    f: PartialFunction[A, B]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.collectAll[R, E, A, Iterable](in).map(_.collect(f)).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure with `collectAllPar`, and collect
   * the results with given partial function.
   */
  def collectAllWithPar[R, E, A, U, Collection[+Element] <: Iterable[Element]](in: Collection[ZIO[R, E, A]])(
    f: PartialFunction[A, U]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], U, Collection[U]]): ZIO[R, E, Collection[U]] =
    ZIO.collectAllPar[R, E, A, Iterable](in).map(_.collect(f)).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure with `collectAllPar`, and collect
   * the results with given partial function.
   *
   * Unlike `collectAllWithPar`, this method will use at most up to `n` fibers.
   */
  def collectAllWithParN[R, E, A, B, Collection[+Element] <: Iterable[Element]](n: => Int)(
    in: Collection[ZIO[R, E, A]]
  )(
    f: PartialFunction[A, B]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.collectAllParN[R, E, A, Iterable](n)(in).map(_.collect(f)).map(bf.fromSpecific(in))

  /**
   * Collects the first element of the `Iterable[A]` for which the effectual
   * function `f` returns `Some`.
   */
  def collectFirst[R, E, A, B](as: => Iterable[A])(f: A => ZIO[R, E, Option[B]]): ZIO[R, E, Option[B]] =
    succeed(as.iterator).flatMap { iterator =>
      def loop: ZIO[R, E, Option[B]] =
        if (iterator.hasNext) f(iterator.next()).flatMap(_.fold(loop)(some(_)))
        else none
      loop
    }

  /**
   * Evaluate each effect in the structure in parallel, collecting the
   * the successful values and discarding the empty cases.
   */
  def collectPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => ZIO[R, Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    foreachPar[R, E, A, Option[B], Iterable](in)(a => f(a).unoption).map(_.flatten).map(bf.fromSpecific(in))

  /**
   * Evaluate each effect in the structure from left to right, collecting the
   * the successful values and discarding the empty cases. For a parallel version, see `collectPar`.
   */
  def collectPar[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, Option[E], (Key2, Value2)]): ZIO[R, E, Map[Key2, Value2]] =
    foreachPar[R, E, (Key, Value), Option[(Key2, Value2)], Iterable](map)(f.tupled(_).unoption).map(_.flatten.toMap)

  /**
   * Evaluate each effect in the structure in parallel, collecting the
   * the successful values and discarding the empty cases.
   *
   * Unlike `collectPar`, this method will use at most up to `n` fibers.
   */
  def collectParN[R, E, A, B, Collection[+Element] <: Iterable[Element]](n: => Int)(
    in: Collection[A]
  )(f: A => ZIO[R, Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    foreachParN[R, E, A, Option[B], Iterable](n)(in)(a => f(a).unoption).map(_.flatten).map(bf.fromSpecific(in))

  /**
   * Similar to Either.cond, evaluate the predicate,
   * return the given A as success if predicate returns true, and the given E as error otherwise
   *
   * For effectful conditionals, see [[ZIO.ifZIO]]
   */
  def cond[E, A](predicate: => Boolean, result: => A, error: => E): IO[E, A] =
    ZIO.suspendSucceed(if (predicate) ZIO.succeedNow(result) else ZIO.fail(error))

  /**
   * Prints the specified message to the console for debugging purposes.
   */
  def debug(value: => Any): UIO[Unit] =
    ZIO.succeed(println(value))

  /**
   * Returns information about the current fiber, such as its identity.
   */
  def descriptor: UIO[Fiber.Descriptor] =
    descriptorWith(succeedNow)

  /**
   * Constructs an effect based on information about the current fiber, such as
   * its identity.
   */
  def descriptorWith[R, E, A](f: Fiber.Descriptor => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.Descriptor(f)

  /**
   * Returns an effect that dies with the specified `Throwable`.
   * This method can be used for terminating a fiber because a defect has been
   * detected in the code.
   */
  def die(t: => Throwable): UIO[Nothing] =
    failCauseWith(trace => Cause.Traced(Cause.Die(t), trace()))

  /**
   * Returns an effect that dies with a [[java.lang.RuntimeException]] having the
   * specified text message. This method can be used for terminating a fiber
   * because a defect has been detected in the code.
   */
  def dieMessage(message: => String): UIO[Nothing] =
    die(new RuntimeException(message))

  /**
   * Returns an effect from a [[zio.Exit]] value.
   */
  def done[E, A](r: => Exit[E, A]): IO[E, A] =
    ZIO.suspendSucceed {
      r match {
        case Exit.Success(b)     => succeedNow(b)
        case Exit.Failure(cause) => failCause(cause)
      }
    }

  /**
   * Imports a synchronous side-effect into a pure `ZIO` value, translating any
   * thrown exceptions into typed failed effects creating with `ZIO.fail`.
   *
   * {{{
   * def printLine(line: String): Task[Unit] = Task.effect(println(line))
   * }}}
   */
  @deprecated("use attempt", "2.0.0")
  def effect[A](effect: => A): Task[A] =
    attempt(effect)

  /**
   * Imports an asynchronous side-effect into a pure `ZIO` value. See
   * `effectAsyncMaybe` for the more expressive variant of this function that
   * can return a value synchronously.
   *
   * The callback function `ZIO[R, E, A] => Any` must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  @deprecated("use async", "2.0.0")
  def effectAsync[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Any,
    blockingOn: => FiberId = null
  ): ZIO[R, E, A] =
    async(register, blockingOn)

  /**
   * Imports an asynchronous side-effect into a ZIO effect. The side-effect
   * has the option of returning the value synchronously, which is useful in
   * cases where it cannot be determined if the effect is synchronous or
   * asynchronous until the side-effect is actually executed. The effect also
   * has the option of returning a canceler, which will be used by the runtime
   * to cancel the asynchronous effect if the fiber executing the effect is
   * interrupted.
   *
   * If the register function returns a value synchronously, then the callback
   * function `ZIO[R, E, A] => Any` must not be called. Otherwise the callback
   * function must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  @deprecated("use asyncInterrupt", "2.0.0")
  def effectAsyncInterrupt[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Either[Canceler[R], ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  ): ZIO[R, E, A] =
    asyncInterrupt(register, blockingOn)

  /**
   * Imports an asynchronous effect into a pure `ZIO` value. This formulation is
   * necessary when the effect is itself expressed in terms of `ZIO`.
   */
  @deprecated("use asyncZIO", "2.0.0")
  def effectAsyncM[R, E, A](
    register: (ZIO[R, E, A] => Unit) => ZIO[R, E, Any]
  ): ZIO[R, E, A] =
    asyncZIO(register)

  /**
   * Imports an asynchronous effect into a pure `ZIO` value, possibly returning
   * the value synchronously.
   *
   * If the register function returns a value synchronously, then the callback
   * function `ZIO[R, E, A] => Any` must not be called. Otherwise the callback
   * function must be called at most once.
   *
   * The list of fibers, that may complete the async callback, is used to
   * provide better diagnostics.
   */
  @deprecated("use asyncMaybe", "2.0.0")
  def effectAsyncMaybe[R, E, A](
    register: (ZIO[R, E, A] => Unit) => Option[ZIO[R, E, A]],
    blockingOn: => FiberId = FiberId.None
  ): ZIO[R, E, A] =
    asyncMaybe(register, blockingOn)

  /**
   * Imports a synchronous effect that does blocking IO into a pure value.
   */
  @deprecated("use attemptBlocking", "2.0.0")
  def effectBlocking[A](effect: => A): Task[A] =
    blocking(ZIO.attempt(effect))

  /**
   * Imports a synchronous effect that does blocking IO into a pure value,
   * with a custom cancel effect.
   *
   * If the returned `ZIO` is interrupted, the blocked thread running the
   * synchronous effect will be interrupted via the cancel effect.
   */
  @deprecated("use attemptBlockingCancelable", "2.0.0")
  def effectBlockingCancelable[A](effect: => A)(cancel: => UIO[Unit]): Task[A] =
    attemptBlockingCancelable(effect)(cancel)

  /**
   * Imports a synchronous effect that does blocking IO into a pure value,
   * refining the error type to `[[java.io.IOException]]`.
   */
  @deprecated("use attemptBlockingIO", "2.0.0")
  def effectBlockingIO[A](effect: => A): IO[IOException, A] =
    attemptBlockingIO(effect)

  /**
   * Imports a synchronous effect that does blocking IO into a pure value.
   *
   * If the returned `ZIO` is interrupted, the blocked thread running the
   * synchronous effect will be interrupted via `Thread.interrupt`.
   *
   * Note that this adds significant overhead. For performance sensitive
   * applications consider using `effectBlocking` or
   * `effectBlockingCancel`.
   */
  @deprecated("use attemptBlockingInterrupt", "2.0.0")
  def effectBlockingInterrupt[A](effect: => A): Task[A] =
    attemptBlockingInterrupt(effect)

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * When no environment is required (i.e., when R == Any) it is conceptually equivalent to `flatten(effect(io))`.
   */
  @deprecated("use suspend", "2.0.0")
  def effectSuspend[R, A](rio: => RIO[R, A]): RIO[R, A] =
    suspend(rio)

  /**
   * Returns a lazily constructed effect, whose construction may itself require
   * effects. The effect must not throw any exceptions. When no environment is required (i.e., when R == Any)
   * it is conceptually equivalent to `flatten(effectTotal(zio))`. If you wonder if the effect throws exceptions,
   * do not use this method, use [[Task.effectSuspend]] or [[ZIO.effectSuspend]].
   */
  @deprecated("use suspendSucceed", "2.0.0")
  def effectSuspendTotal[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    suspendSucceed(zio)

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * The effect must not throw any exceptions. When no environment is required (i.e., when R == Any)
   * it is conceptually equivalent to `flatten(effectTotal(zio))`. If you wonder if the effect throws exceptions,
   * do not use this method, use [[Task.effectSuspend]] or [[ZIO.effectSuspend]].
   */
  @deprecated("use suspendSucceedWith", "2.0.0")
  def effectSuspendTotalWith[R, E, A](f: (Platform, FiberId) => ZIO[R, E, A]): ZIO[R, E, A] =
    suspendSucceedWith(f)

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * When no environment is required (i.e., when R == Any) it is conceptually equivalent to `flatten(effect(io))`.
   */
  @deprecated("use suspendWith", "2.0.0")
  def effectSuspendWith[R, A](f: (Platform, FiberId) => RIO[R, A]): RIO[R, A] =
    suspendWith(f)

  /**
   * Captures a synchronous computation into a lazy effect, which can be
   * executed later and repeatedly. The synchronous computation must not throw
   * any exceptions. If you are unsure, then don't use this method, use
   * [[Task.effect]], [[IO.effect]], or [[ZIO.effect]].
   *
   * {{{
   * val nanoTime: UIO[Long] = IO.succeed(System.nanoTime())
   * }}}
   */
  @deprecated("use succeed", "2.0.0")
  def effectTotal[A](effect: => A): UIO[A] =
    succeed(effect)

  /**
   * The same as [[ZIO.effectTotal]], but also provides access to the
   * underlying Platform and fiber id.
   */
  def effectTotalWith[A](effect: (Platform, FiberId) => A): UIO[A] =
    new ZIO.EffectWith(effect)

  /**
   * Accesses the whole environment of the effect.
   */
  def environment[R]: URIO[R, R] =
    access(r => r)

  /**
   * Retrieves the executor for this effect.
   */
  def executor: UIO[Executor] =
    ZIO.descriptorWith(descriptor => ZIO.succeedNow(descriptor.executor))

  /**
   * Determines whether any element of the `Iterable[A]` satisfies the
   * effectual predicate `f`.
   */
  def exists[R, E, A](as: => Iterable[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Boolean] =
    succeed(as.iterator).flatMap { iterator =>
      def loop: ZIO[R, E, Boolean] =
        if (iterator.hasNext) f(iterator.next()).flatMap(b => if (b) succeedNow(b) else loop)
        else succeedNow(false)
      loop
    }

  /**
   * Returns an effect that models failure with the specified error.
   * The moral equivalent of `throw` for pure code.
   */
  def fail[E](error: => E): IO[E, Nothing] =
    failCauseWith(trace => Cause.Traced(Cause.Fail(error), trace()))

  /**
   * Returns an effect that models failure with the specified `Cause`.
   */
  def failCause[E](cause: => Cause[E]): IO[E, Nothing] =
    new ZIO.Fail(_ => cause)

  /**
   * Returns an effect that models failure with the specified `Cause`.
   *
   * This version takes in a lazily-evaluated trace that can be attached to the `Cause`
   * via `Cause.Traced`.
   */
  def failCauseWith[E](function: (() => ZTrace) => Cause[E]): IO[E, Nothing] =
    new ZIO.Fail(function)

  /**
   * Returns the `FiberId` of the fiber executing the effect that calls this method.
   */
  val fiberId: UIO[FiberId] =
    ZIO.descriptor.map(_.id)

  /**
   * Filters the collection using the specified effectual predicate.
   */
  def filter[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => ZIO[R, E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    as.foldLeft[ZIO[R, E, Builder[A, Collection[A]]]](ZIO.succeed(bf.newBuilder(as))) { (zio, a) =>
      zio.zipWith(f(a))((as, p) => if (p) as += a else as)
    }.map(_.result())

  /**
   * Filters the Set[A] using the specified effectual predicate.
   */
  def filter[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Set[A]] =
    filter[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection in parallel using the specified effectual
   * predicate.
   *
   * See [[[zio.ZIO.filter[R,E,A,Collection*]]] for a sequential version.
   */
  def filterPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => ZIO[R, E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    ZIO
      .foreachPar[R, E, A, Option[A], Iterable](as)(a => f(a).map(if (_) Some(a) else None))
      .map(_.flatten)
      .map(bf.fromSpecific(as))

  /**
   * Filters the Set[A] in parallel using the specified effectual predicate.
   *
   * See [[[zio.ZIO.filter[R,E,A,Collection*]]] for a sequential version.
   */
  def filterPar[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Set[A]] =
    filterPar[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection using the specified effectual predicate, removing
   * all elements that satisfy the predicate.
   */
  def filterNot[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => ZIO[R, E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    filter(as)(f(_).map(!_))

  /**
   * Filters the Set[A] using the specified effectual predicate, removing
   * all elements that satisfy the predicate.
   */
  def filterNot[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Set[A]] =
    filterNot[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Filters the collection in parallel using the specified effectual
   * predicate, emoving all elements that satisfy the predicate.
   *
   * See [[[zio.ZIO.filterNot[R,E,A,Collection*]]] for a sequential version.
   */
  def filterNotPar[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => ZIO[R, E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): ZIO[R, E, Collection[A]] =
    filterPar(as)(f(_).map(!_))

  /**
   * Filters the Set[A] in parallel using the specified effectual predicate,
   * removing all elements that satisfy the predicate.
   *
   * See [[[zio.ZIO.filterNot[R,E,A](as:Set*]]] for a sequential version.
   */
  def filterNotPar[R, E, A](as: Set[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Set[A]] =
    filterNotPar[R, E, A, Iterable](as)(f).map(_.toSet)

  /**
   * Returns an effect that runs the first effect and in case of failure,
   * runs each of the specified effects in order until one of them succeeds.
   */
  def firstSuccessOf[R, R1 <: R, E, A](
    zio: => ZIO[R, E, A],
    rest: => Iterable[ZIO[R1, E, A]]
  ): ZIO[R1, E, A] =
    ZIO.suspendSucceed(rest.foldLeft[ZIO[R1, E, A]](zio)(_ orElse _).refailWithTrace)

  /**
   * Returns an effect that first executes the outer effect, and then executes
   * the inner effect, returning the value from the inner effect, and effectively
   * flattening a nested effect.
   */
  def flatten[R, E, A](zio: => ZIO[R, E, ZIO[R, E, A]]): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio.flatMap(ZIO.identityFn))

  /**
   * Folds an Iterable[A] using an effectual function f, working sequentially from left to right.
   */
  def foldLeft[R, E, S, A](
    in: => Iterable[A]
  )(zero: => S)(f: (S, A) => ZIO[R, E, S]): ZIO[R, E, S] =
    ZIO.suspendSucceed(in.foldLeft[ZIO[R, E, S]](IO.succeedNow(zero))((acc, el) => acc.flatMap(f(_, el))))

  /**
   * Folds an Iterable[A] using an effectual function f, working sequentially from right to left.
   */
  def foldRight[R, E, S, A](
    in: => Iterable[A]
  )(zero: => S)(f: (A, S) => ZIO[R, E, S]): ZIO[R, E, S] =
    ZIO.suspendSucceed(in.foldRight(IO.succeedNow(zero): ZIO[R, E, S])((el, acc) => acc.flatMap(f(el, _))))

  /**
   * Determines whether all elements of the `Iterable[A]` satisfy the effectual
   * predicate `f`.
   */
  def forall[R, E, A](as: => Iterable[A])(f: A => ZIO[R, E, Boolean]): ZIO[R, E, Boolean] =
    succeed(as.iterator).flatMap { iterator =>
      def loop: ZIO[R, E, Boolean] =
        if (iterator.hasNext) f(iterator.next()).flatMap(b => if (b) loop else succeedNow(b))
        else succeedNow(true)
      loop
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` and
   * returns the results in a new `Collection[B]`.
   *
   * For a parallel version of this method, see `foreachPar`.
   * If you do not need the results, see `foreach_` for a more efficient implementation.
   */
  def foreach[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      val iterator = in.iterator
      val builder  = bf.newBuilder(in)

      lazy val recurse: B => ZIO[R, E, Collection[B]] = { b =>
        builder += b
        loop()
      }

      def loop(): ZIO[R, E, Collection[B]] =
        if (iterator.hasNext) f(iterator.next()).flatMap(recurse)
        else ZIO.succeedNow(builder.result())

      loop()
    }

  /**
   * Applies the function `f` to each element of the `Set[A]` and
   * returns the results in a new `Set[B]`.
   *
   * For a parallel version of this method, see `foreachPar`.
   * If you do not need the results, see `foreachDiscard` for a more efficient
   * implementation.
   */
  final def foreach[R, E, A, B](in: Set[A])(f: A => ZIO[R, E, B]): ZIO[R, E, Set[B]] =
    foreach[R, E, A, B, Iterable](in)(f).map(_.toSet)

  /**
   * Applies the function `f` to each element of the `Array[A]` and
   * returns the results in a new `Array[B]`.
   *
   * For a parallel version of this method, see `foreachPar`.
   * If you do not need the results, see `foreachDiscard` for a more efficient
   * implementation.
   */
  final def foreach[R, E, A, B: ClassTag](in: Array[A])(f: A => ZIO[R, E, B]): ZIO[R, E, Array[B]] =
    foreach[R, E, A, B, Iterable](in)(f).map(_.toArray)

  /**
   * Applies the function `f` to each element of the `Map[Key, Value]` and
   * returns the results in a new `Map[Key2, Value2]`.
   *
   * For a parallel version of this method, see `foreachPar`. If you do not
   * need the results, see `foreachDiscard` for a more efficient
   * implementation.
   */
  def foreach[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, E, (Key2, Value2)]): ZIO[R, E, Map[Key2, Value2]] =
    foreach[R, E, (Key, Value), (Key2, Value2), Iterable](map)(f.tupled).map(_.toMap)

  /**
   * Applies the function `f` if the argument is non-empty and
   * returns the results in a new `Option[B]`.
   */
  final def foreach[R, E, A, B](in: Option[A])(f: A => ZIO[R, E, B]): ZIO[R, E, Option[B]] =
    in.fold[ZIO[R, E, Option[B]]](none)(f(_).map(Some(_)))

  /**
   * Applies the function `f` to each element of the `NonEmptyChunk[A]` and
   * returns the results in a new `NonEmptyChunk[B]`.
   *
   * For a parallel version of this method, see `foreachPar`.
   * If you do not need the results, see `foreachDiscard` for a more efficient
   * implementation.
   */
  final def foreach[R, E, A, B](in: NonEmptyChunk[A])(f: A => ZIO[R, E, B]): ZIO[R, E, NonEmptyChunk[B]] =
    in.mapZIO(f)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects sequentially.
   *
   * Equivalent to `foreach(as)(f).unit`, but without the cost of building
   * the list of results.
   */
  @deprecated("use foreachDiscard", "2.0.0")
  def foreach_[R, E, A](as: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    foreachDiscard(as)(f)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects sequentially.
   *
   * Equivalent to `foreach(as)(f).unit`, but without the cost of building
   * the list of results.
   */
  def foreachDiscard[R, E, A](as: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    ZIO.succeed(as.iterator).flatMap { i =>
      lazy val recurse: Any => ZIO[R, E, Unit] = _ => loop()

      def loop(): ZIO[R, E, Unit] =
        if (i.hasNext) f(i.next()).flatMap(recurse)
        else ZIO.unit

      loop()
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` and returns
   * the result in a new `Collection[B]` using the specified execution strategy.
   */
  final def foreachExec[R, E, A, B, Collection[+Element] <: Iterable[Element]](as: Collection[A])(
    exec: => ExecutionStrategy
  )(f: A => ZIO[R, E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      exec match {
        case ExecutionStrategy.Parallel     => ZIO.foreachPar(as)(f)
        case ExecutionStrategy.ParallelN(n) => ZIO.foreachParN(n)(as)(f)
        case ExecutionStrategy.Sequential   => ZIO.foreach(as)(f)
      }
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` in parallel,
   * and returns the results in a new `Collection[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  def foreachPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => ZIO[R, E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      val array = Array.ofDim[AnyRef](as.size)
      val zioFunction: ZIOFn1[(A, Int), ZIO[R, E, Any]] =
        ZIOFn(f) { case (a, i) =>
          f(a).flatMap(b => succeedNow(array(i) = b.asInstanceOf[AnyRef]))
        }
      foreachParDiscard(as.zipWithIndex)(zioFunction) *>
        succeedNow(bf.fromSpecific(as)(array.asInstanceOf[Array[B]]))
    }

  /**
   * Applies the function `f` to each element of the `Set[A]` in parallel,
   * and returns the results in a new `Set[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B](as: Set[A])(fn: A => ZIO[R, E, B]): ZIO[R, E, Set[B]] =
    foreachPar[R, E, A, B, Iterable](as)(fn).map(_.toSet)

  /**
   * Applies the function `f` to each element of the `Array[A]` in parallel,
   * and returns the results in a new `Array[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B: ClassTag](as: Array[A])(f: A => ZIO[R, E, B]): ZIO[R, E, Array[B]] =
    foreachPar[R, E, A, B, Iterable](as)(f).map(_.toArray)

  /**
   * Applies the function `f` to each element of the `Map[Key, Value]` in
   * parallel and returns the results in a new `Map[Key2, Value2]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  def foreachPar[R, E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => ZIO[R, E, (Key2, Value2)]): ZIO[R, E, Map[Key2, Value2]] =
    foreachPar[R, E, (Key, Value), (Key2, Value2), Iterable](map)(f.tupled).map(_.toMap)

  /**
   * Applies the function `f` to each element of the `NonEmptyChunk[A]` in parallel,
   * and returns the results in a new `NonEmptyChunk[B]`.
   *
   * For a sequential version of this method, see `foreach`.
   */
  final def foreachPar[R, E, A, B](as: NonEmptyChunk[A])(fn: A => ZIO[R, E, B]): ZIO[R, E, NonEmptyChunk[B]] =
    as.mapZIOPar(fn)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects in parallel, discarding the results.
   *
   * For a sequential version of this method, see `foreach_`.
   *
   * Optimized to avoid keeping full tree of effects, so that method could be
   * able to handle large input sequences.
   * Behaves almost like this code:
   *
   * {{{
   * as.foldLeft(ZIO.unit) { (acc, a) => acc.zipParLeft(f(a)) }
   * }}}
   *
   * Additionally, interrupts all effects on any failure.
   */
  @deprecated("use foreachParDiscard", "2.0.0")
  def foreachPar_[R, E, A](as: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    foreachParDiscard(as)(f)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects in parallel, discarding the results.
   *
   * For a sequential version of this method, see `foreachDiscard`.
   *
   * Optimized to avoid keeping full tree of effects, so that method could be
   * able to handle large input sequences.
   * Behaves almost like this code:
   *
   * {{{
   * as.foldLeft(ZIO.unit) { (acc, a) => acc.zipParLeft(f(a)) }
   * }}}
   *
   * Additionally, interrupts all effects on any failure.
   */
  def foreachParDiscard[R, E, A](as0: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      val as = as0
      if (as.isEmpty) ZIO.unit
      else {
        val size = as.size
        ZIO.uninterruptibleMask { restore =>
          val promise = Promise.unsafeMake[Unit, Unit](FiberId.None)
          val ref     = new java.util.concurrent.atomic.AtomicInteger(0)
          ZIO.transplant { graft =>
            ZIO.foreach(as) { a =>
              graft {
                restore(ZIO.suspendSucceed(f(a))).foldCauseZIO(
                  cause => promise.fail(()) *> ZIO.failCause(cause),
                  _ =>
                    if (ref.incrementAndGet == size) {
                      promise.unsafeDone(ZIO.unit)
                      ZIO.unit
                    } else {
                      ZIO.unit
                    }
                )
              }.forkDaemon
            }
          }.flatMap { fibers =>
            restore(promise.await).foldCauseZIO(
              cause =>
                ZIO
                  .foreachPar(fibers)(_.interrupt)
                  .flatMap(Exit.collectAllPar(_) match {
                    case Some(Exit.Failure(causes)) => ZIO.failCause(cause.stripFailures && causes)
                    case _                          => ZIO.failCause(cause.stripFailures)
                  }),
              _ => ZIO.foreachDiscard(fibers)(_.inheritRefs)
            )
          }
        }
      }
    }

  /**
   * Applies the function `f` to each element of the `Collection[A]` in parallel,
   * and returns the results in a new `Collection[B]`.
   *
   * Unlike `foreachPar`, this method will use at most up to `n` fibers.
   */
  def foreachParN[R, E, A, B, Collection[+Element] <: Iterable[Element]](n0: => Int)(
    as: Collection[A]
  )(fn: A => ZIO[R, E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): ZIO[R, E, Collection[B]] =
    ZIO.suspendSucceed {
      val n = n0
      if (n < 1) ZIO.dieMessage(s"Unexpected nonpositive value `$n` passed to foreachParN.")
      else {
        val size = as.size
        if (size == 0) ZIO.succeedNow(bf.newBuilder(as).result())
        else {

          def worker(queue: Queue[(A, Int)], array: Array[AnyRef]): ZIO[R, E, Unit] =
            queue.poll.flatMap {
              case Some((a, n)) =>
                fn(a).tap(b => ZIO.succeed(array(n) = b.asInstanceOf[AnyRef])) *> worker(queue, array)
              case None => ZIO.unit
            }

          for {
            array <- ZIO.succeed(Array.ofDim[AnyRef](size))
            queue <- Queue.bounded[(A, Int)](size)
            _     <- queue.offerAll(as.zipWithIndex)
            _     <- ZIO.collectAllParDiscard(ZIO.replicate(n)(worker(queue, array)))
          } yield bf.fromSpecific(as)(array.asInstanceOf[Array[B]])
        }.refailWithTrace
      }
    }

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects in parallel, discarding the results.
   *
   * Unlike `foreachPar_`, this method will use at most up to `n` fibers.
   */
  @deprecated("use foreachParNDiscard", "2.0.0")
  def foreachParN_[R, E, A](n: => Int)(as: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    foreachParNDiscard(n)(as)(f)

  /**
   * Applies the function `f` to each element of the `Iterable[A]` and runs
   * produced effects in parallel, discarding the results.
   *
   * Unlike `foreachParDiscard`, this method will use at most up to `n` fibers.
   */
  def foreachParNDiscard[R, E, A](n: => Int)(as0: => Iterable[A])(f: A => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {
      val as   = as0
      val size = as.size
      if (size == 0) ZIO.unit
      else {

        def worker(queue: Queue[A]): ZIO[R, E, Unit] =
          queue.poll.flatMap {
            case Some(a) => f(a) *> worker(queue)
            case None    => ZIO.unit
          }

        for {
          queue <- Queue.bounded[A](size)
          _     <- queue.offerAll(as)
          _     <- ZIO.collectAllParDiscard(ZIO.replicate(n)(worker(queue)))
        } yield ()
      }.refailWithTrace
    }

  /**
   * Returns an effect that forks all of the specified values, and returns a
   * composite fiber that produces a list of their results, in order.
   */
  def forkAll[R, E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[ZIO[R, E, A]]
  )(implicit bf: BuildFrom[Collection[ZIO[R, E, A]], A, Collection[A]]): URIO[R, Fiber[E, Collection[A]]] =
    ZIO
      .foreach[R, Nothing, ZIO[R, E, A], Fiber[E, A], Iterable](as)(_.fork)
      .map(Fiber.collectAll[E, A, Iterable](_).map(bf.fromSpecific(as)))

  /**
   * Returns an effect that forks all of the specified values, and returns a
   * composite fiber that produces unit. This version is faster than [[forkAll]]
   * in cases where the results of the forked fibers are not needed.
   */
  @deprecated("use forkAllDiscard", "2.0.0")
  def forkAll_[R, E, A](as: => Iterable[ZIO[R, E, A]]): URIO[R, Unit] =
    forkAllDiscard(as)

  /**
   * Returns an effect that forks all of the specified values, and returns a
   * composite fiber that produces unit. This version is faster than [[forkAll]]
   * in cases where the results of the forked fibers are not needed.
   */
  def forkAllDiscard[R, E, A](as: => Iterable[ZIO[R, E, A]]): URIO[R, Unit] =
    ZIO.suspendSucceed(as.foldRight[URIO[R, Unit]](ZIO.unit)(_.fork *> _))

  /**
   * Constructs a `ZIO` value of the appropriate type for the specified input.
   */
  def from[Input](input: => Input)(implicit
    constructor: ZIOConstructor[Nothing, Any, Input]
  ): ZIO[constructor.OutEnvironment, constructor.OutError, constructor.OutSuccess] =
    constructor.make(input)

  /**
   * Lifts an `Either` into a `ZIO` value.
   */
  def fromEither[E, A](v: => Either[E, A]): IO[E, A] =
    succeed(v).flatMap(_.fold(fail(_), succeedNow))

  /**
   * Lifts an `Either` into a `ZIO` value.
   */
  def fromEitherCause[E, A](v: => Either[Cause[E], A]): IO[E, A] =
    succeed(v).flatMap(_.fold(failCause(_), succeedNow))

  /**
   * Creates a `ZIO` value that represents the exit value of the specified
   * fiber.
   */
  def fromFiber[E, A](fiber: => Fiber[E, A]): IO[E, A] =
    succeed(fiber).flatMap(_.join)

  /**
   * Creates a `ZIO` value that represents the exit value of the specified
   * fiber.
   */
  @deprecated("use fromFiberZIO", "2.0.0")
  def fromFiberM[E, A](fiber: IO[E, Fiber[E, A]]): IO[E, A] =
    fromFiberZIO(fiber)

  /**
   * Creates a `ZIO` value that represents the exit value of the specified
   * fiber.
   */
  def fromFiberZIO[R, E, A](fiber: => ZIO[R, E, Fiber[E, A]]): ZIO[R, E, A] =
    ZIO.suspendSucceed(fiber.flatMap(_.join))

  /**
   * Lifts a function `R => A` into a `URIO[R, A]`.
   */
  @deprecated("use access", "2.0.0")
  def fromFunction[R, A](f: R => A): URIO[R, A] =
    access(f)

  /**
   * Lifts an effectful function whose effect requires no environment into
   * an effect that requires the input to the function.
   */
  @deprecated("use accessZIO", "2.0.0")
  def fromFunctionM[R, E, A](f: R => IO[E, A]): ZIO[R, E, A] =
    accessZIO(f)

  /**
   * Imports a function that creates a [[scala.concurrent.Future]] from an
   * [[scala.concurrent.ExecutionContext]] into a `ZIO`.
   */
  def fromFuture[A](make: ExecutionContext => scala.concurrent.Future[A]): Task[A] =
    Task.descriptorWith { d =>
      val ec = d.executor.asExecutionContext
      ZIO.attempt(make(ec)).flatMap { f =>
        val canceler: UIO[Unit] = f match {
          case cancelable: CancelableFuture[A] =>
            UIO.suspendSucceed(if (f.isCompleted) ZIO.unit else ZIO.fromFuture(_ => cancelable.cancel()).ignore)
          case _ => ZIO.unit
        }

        f.value
          .fold(
            Task.asyncInterrupt { (k: Task[A] => Unit) =>
              f.onComplete {
                case Success(a) => k(Task.succeedNow(a))
                case Failure(t) => k(Task.fail(t))
              }(ec)

              Left(canceler)
            }
          )(Task.fromTry(_))
      }
    }

  /**
   * Imports a [[scala.concurrent.Promise]] we generate a future from promise,
   * and we pass to [fromFuture] to transform into Task[A]
   */
  def fromPromiseScala[A](promise: => scala.concurrent.Promise[A]): Task[A] =
    ZIO.fromFuture(_ => promise.future)

  /**
   * Imports a function that creates a [[scala.concurrent.Future]] from an
   * [[scala.concurrent.ExecutionContext]] into a `ZIO`. The provided
   * `ExecutionContext` will interrupt the `Future` between asynchronous
   * operations such as `map` and `flatMap` if this effect is interrupted. Note
   * that no attempt will be made to interrupt a `Future` blocking on a
   * synchronous operation and that the `Future` must be created using the
   * provided `ExecutionContext`.
   */
  def fromFutureInterrupt[A](make: ExecutionContext => scala.concurrent.Future[A]): Task[A] =
    Task.descriptorWith { d =>
      val ec          = d.executor.asExecutionContext
      val interrupted = new java.util.concurrent.atomic.AtomicBoolean(false)
      val latch       = scala.concurrent.Promise[Unit]()
      val interruptibleEC = new scala.concurrent.ExecutionContext {
        def execute(runnable: Runnable): Unit =
          if (!interrupted.get) ec.execute(runnable)
          else {
            val _ = latch.success(())
          }
        def reportFailure(cause: Throwable): Unit =
          ec.reportFailure(cause)
      }
      attempt(make(interruptibleEC)).flatMap { f =>
        f.value
          .fold(
            Task.async { (cb: Task[A] => Any) =>
              f.onComplete {
                case Success(a) => latch.success(()); cb(Task.succeedNow(a))
                case Failure(t) => latch.success(()); cb(Task.fail(t))
              }(interruptibleEC)
            }
          )(Task.fromTry(_))
      }.onInterrupt(
        Task.succeed(interrupted.set(true)) *> Task.fromFuture(_ => latch.future).orDie
      )
    }

  /**
   * Lifts an `Option` into a `ZIO` but preserves the error as an option in the error channel, making it easier to compose
   * in some scenarios.
   */
  def fromOption[A](v: => Option[A]): IO[Option[Nothing], A] =
    succeed(v).flatMap(_.fold[IO[Option[Nothing], A]](fail(None))(succeedNow))

  /**
   * Lifts a `Try` into a `ZIO`.
   */
  def fromTry[A](value: => scala.util.Try[A]): Task[A] =
    attempt(value).flatMap {
      case scala.util.Success(v) => ZIO.succeedNow(v)
      case scala.util.Failure(t) => ZIO.fail(t)
    }

  /**
   * Retrieves the scope that will be used to supervise forked effects.
   */
  def forkScope: UIO[ZScope[Exit[Any, Any]]] =
    new ZIO.GetForkScope(ZIO.succeed(_))

  /**
   * Captures the fork scope, before overriding it with the specified new
   * scope, passing a function that allows restoring the fork scope to
   * what it was originally.
   */
  def forkScopeMask[R, E, A](newScope: => ZScope[Exit[Any, Any]])(f: ForkScopeRestore => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.forkScopeWith(scope => f(new ForkScopeRestore(scope)).overrideForkScope(newScope))

  /**
   * Retrieves the scope that will be used to supervise forked effects.
   */
  def forkScopeWith[R, E, A](f: ZScope[Exit[Any, Any]] => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.GetForkScope(f)

  /**
   * Lifts an Option into a ZIO, if the option is not defined it fails with NoSuchElementException.
   */
  final def getOrFail[A](v: => Option[A]): Task[A] =
    getOrFailWith(new NoSuchElementException("None.get"))(v)

  /**
   * Lifts an Option into a IO, if the option is not defined it fails with Unit.
   */
  final def getOrFailUnit[A](v: => Option[A]): IO[Unit, A] =
    getOrFailWith(())(v)

  /**
   * Lifts an Option into a ZIO. If the option is not defined, fail with the `e` value.
   */
  final def getOrFailWith[E, A](e: => E)(v: => Option[A]): IO[E, A] =
    suspendSucceed(v match {
      case None    => IO.fail(e)
      case Some(v) => ZIO.succeedNow(v)
    })

  /**
   * Gets a state from the environment.
   */
  def getState[S: Tag]: ZIO[Has[ZState[S]], Nothing, S] =
    ZIO.serviceWith(_.get)

  /**
   * Gets a state from the environment and uses it to run the specified
   * function.
   */
  def getStateWith[S]: ZIO.GetStateWithPartiallyApplied[S] =
    new ZIO.GetStateWithPartiallyApplied[S]

  /**
   * Returns an effect that models failure with the specified `Cause`.
   */
  @deprecated("use failCause", "2.0.0")
  def halt[E](cause: => Cause[E]): IO[E, Nothing] =
    failCause(cause)

  /**
   * Returns an effect that models failure with the specified `Cause`.
   *
   * This version takes in a lazily-evaluated trace that can be attached to the `Cause`
   * via `Cause.Traced`.
   */
  @deprecated("use failCauseWith", "2.0.0")
  def haltWith[E](function: (() => ZTrace) => Cause[E]): IO[E, Nothing] =
    failCauseWith(function)

  /**
   * Runs `onTrue` if the result of `b` is `true` and `onFalse` otherwise.
   */
  @deprecated("use ifZIO", "2.0.0")
  def ifM[R, E](b: => ZIO[R, E, Boolean]): ZIO.IfZIO[R, E] =
    ifZIO(b)

  /**
   * Runs `onTrue` if the result of `b` is `true` and `onFalse` otherwise.
   */
  def ifZIO[R, E](b: => ZIO[R, E, Boolean]): ZIO.IfZIO[R, E] =
    new ZIO.IfZIO(() => b)

  /**
   * Like [[never]], but fibers that running this effect won't be garbage
   * collected unless interrupted.
   */
  val infinity: URIO[Has[Clock], Nothing] =
    ZIO.sleep(Duration.fromNanos(Long.MaxValue)) *> ZIO.never

  /**
   * Returns an effect that is interrupted as if by the fiber calling this
   * method.
   */
  lazy val interrupt: UIO[Nothing] =
    ZIO.fiberId.flatMap(fiberId => interruptAs(fiberId))

  /**
   * Returns an effect that is interrupted as if by the specified fiber.
   */
  def interruptAs(fiberId: => FiberId): UIO[Nothing] =
    failCauseWith(trace => Cause.Traced(Cause.interrupt(fiberId), trace()))

  /**
   * Prefix form of `ZIO#interruptible`.
   */
  def interruptible[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    zio.interruptible

  /**
   * Makes the effect interruptible, but passes it a restore function that
   * can be used to restore the inherited interruptibility from whatever region
   * the effect is composed into.
   */
  def interruptibleMask[R, E, A](
    k: ZIO.InterruptStatusRestore => ZIO[R, E, A]
  ): ZIO[R, E, A] =
    checkInterruptible(flag => k(ZIO.InterruptStatusRestore(flag)).interruptible)

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
  def iterate[R, E, S](initial: => S)(cont: S => Boolean)(body: S => ZIO[R, E, S]): ZIO[R, E, S] =
    ZIO.suspendSucceed {

      def loop(initial: S): ZIO[R, E, S] =
        if (cont(initial)) body(initial).flatMap(loop)
        else ZIO.succeedNow(initial)

      loop(initial)
    }

  /**
   *  Returns an effect with the value on the left part.
   */
  def left[A](a: => A): UIO[Either[A, Nothing]] =
    succeed(Left(a))

  /**
   * Returns an effect that will execute the specified effect fully on the
   * provided executor, before returning to the default executor. See
   * [[ZIO!.lock]].
   */
  @deprecated("use onExecutor", "2.0.0")
  def lock[R, E, A](executor: => Executor)(zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    onExecutor(executor)(zio)

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
  def loop[R, E, A, S](initial: => S)(cont: S => Boolean, inc: S => S)(body: S => ZIO[R, E, A]): ZIO[R, E, List[A]] =
    ZIO.suspendSucceed {

      def loop(initial: S): ZIO[R, E, List[A]] =
        if (cont(initial))
          body(initial).flatMap(a => loop(inc(initial)).map(as => a :: as))
        else
          ZIO.succeedNow(List.empty[A])

      loop(initial)
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
  @deprecated("use loopDiscard", "2.0.0")
  def loop_[R, E, S](initial: => S)(cont: S => Boolean, inc: S => S)(body: S => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    loopDiscard(initial)(cont, inc)(body)

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
  def loopDiscard[R, E, S](initial: => S)(cont: S => Boolean, inc: S => S)(body: S => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    ZIO.suspendSucceed {

      def loop(initial: S): ZIO[R, E, Unit] =
        if (cont(initial)) body(initial) *> loop(inc(initial))
        else ZIO.unit

      loop(initial)
    }

  /**
   * Returns a new effect where boolean value of this effect is negated.
   */
  def not[R, E](effect: => ZIO[R, E, Boolean]): ZIO[R, E, Boolean] =
    ZIO.suspendSucceed(effect.negate)

  /**
   * Sequentially zips the specified effects. Specialized version of mapN.
   */
  @deprecated("use zip", "2.0.0")
  def tupled[R, E, A, B](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B]): ZIO[R, E, (A, B)] =
    mapN(zio1, zio2)((_, _))

  /**
   * Sequentially zips the specified effects. Specialized version of mapN.
   */
  @deprecated("use zip", "2.0.0")
  def tupled[R, E, A, B, C](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B], zio3: => ZIO[R, E, C]): ZIO[R, E, (A, B, C)] =
    mapN(zio1, zio2, zio3)((_, _, _))

  /**
   * Sequentially zips the specified effects. Specialized version of mapN.
   */
  @deprecated("use zip", "2.0.0")
  def tupled[R, E, A, B, C, D](
    zio1: => ZIO[R, E, A],
    zio2: => ZIO[R, E, B],
    zio3: => ZIO[R, E, C],
    zio4: => ZIO[R, E, D]
  ): ZIO[R, E, (A, B, C, D)] =
    mapN(zio1, zio2, zio3, zio4)((_, _, _, _))

  /**
   * Zips the specified effects in parallel. Specialized version of mapParN.
   */
  @deprecated("use zipPar", "2.0.0")
  def tupledPar[R, E, A, B](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B]): ZIO[R, E, (A, B)] =
    mapParN(zio1, zio2)((_, _))

  /**
   * Zips the specified effects in parallel. Specialized version of mapParN.
   */
  @deprecated("use zipPar", "2.0.0")
  def tupledPar[R, E, A, B, C](
    zio1: => ZIO[R, E, A],
    zio2: => ZIO[R, E, B],
    zio3: => ZIO[R, E, C]
  ): ZIO[R, E, (A, B, C)] =
    mapParN(zio1, zio2, zio3)((_, _, _))

  /**
   * Zips the specified effects in parallel. Specialized version of mapParN.
   */
  @deprecated("use zipPar", "2.0.0")
  def tupledPar[R, E, A, B, C, D](
    zio1: => ZIO[R, E, A],
    zio2: => ZIO[R, E, B],
    zio3: => ZIO[R, E, C],
    zio4: => ZIO[R, E, D]
  ): ZIO[R, E, (A, B, C, D)] =
    mapParN(zio1, zio2, zio3, zio4)((_, _, _, _))

  /**
   * Logs the specified message at the current log level.
   */
  def log(message: => String): UIO[Unit] = new Logged(() => message)

  /**
   * Logs the specified cause as an error.
   */
  def logCause(cause: => Cause[Any]): UIO[Unit] = new Logged(() => cause.prettyPrint, someError)

  /**
   * Logs the specified message at the debug log level.
   */
  def logDebug(message: => String): UIO[Unit] = new Logged(() => message, someDebug)

  /**
   * Logs the specified message at the error log level.
   */
  def logError(message: => String): UIO[Unit] = new Logged(() => message, someError)

  /**
   * Logs the specified message at the fatal log level.
   */
  def logFatal(message: => String): UIO[Unit] = new Logged(() => message, someFatal)

  /**
   * Logs the specified message at the informational log level.
   */
  def logInfo(message: => String): UIO[Unit] = new Logged(() => message, someInfo)

  def logLevel(level: LogLevel): LogLevel = level

  /**
   * Adjusts the label for the current logging span.
   * {{{
   * ZIO.logSpan("parsing") { parseRequest(req) }
   * }}}
   */
  def logSpan(label: => String): LogSpan = new LogSpan(() => label)

  /**
   * Logs the specified message at the warning log level.
   */
  def logWarning(message: => String): UIO[Unit] = new Logged(() => message, someWarning)

  /**
   * Sequentially zips the specified effects using the specified combiner
   * function.
   */
  @deprecated("use zip", "2.0.0")
  def mapN[R, E, A, B, C](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B])(f: (A, B) => C): ZIO[R, E, C] =
    ZIO.suspendSucceed(zio1.zipWith(zio2)(f))

  /**
   * Sequentially zips the specified effects using the specified combiner
   * function.
   */
  @deprecated("use zip", "2.0.0")
  def mapN[R, E, A, B, C, D](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B], zio3: => ZIO[R, E, C])(
    f: (A, B, C) => D
  ): ZIO[R, E, D] =
    ZIO.suspendSucceed {
      for {
        a <- zio1
        b <- zio2
        c <- zio3
      } yield f(a, b, c)
    }

  /**
   * Sequentially zips the specified effects using the specified combiner
   * function.
   */
  @deprecated("use zip", "2.0.0")
  def mapN[R, E, A, B, C, D, F](
    zio1: => ZIO[R, E, A],
    zio2: => ZIO[R, E, B],
    zio3: => ZIO[R, E, C],
    zio4: => ZIO[R, E, D]
  )(
    f: (A, B, C, D) => F
  ): ZIO[R, E, F] =
    ZIO.suspendSucceed {
      for {
        a <- zio1
        b <- zio2
        c <- zio3
        d <- zio4
      } yield f(a, b, c, d)
    }

  /**
   * Returns an effect that executes the specified effects in parallel,
   * combining their results with the specified `f` function. If any effect
   * fails, then the other effects will be interrupted.
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[R, E, A, B, C](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B])(f: (A, B) => C): ZIO[R, E, C] =
    ZIO.suspendSucceed(zio1.zipWithPar(zio2)(f))

  /**
   * Returns an effect that executes the specified effects in parallel,
   * combining their results with the specified `f` function. If any effect
   * fails, then the other effects will be interrupted.
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[R, E, A, B, C, D](zio1: => ZIO[R, E, A], zio2: => ZIO[R, E, B], zio3: => ZIO[R, E, C])(
    f: (A, B, C) => D
  ): ZIO[R, E, D] =
    ZIO.suspendSucceed((zio1 <&> zio2 <&> zio3).map(f.tupled))

  /**
   * Returns an effect that executes the specified effects in parallel,
   * combining their results with the specified `f` function. If any effect
   * fails, then the other effects will be interrupted.
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[R, E, A, B, C, D, F](
    zio1: => ZIO[R, E, A],
    zio2: => ZIO[R, E, B],
    zio3: => ZIO[R, E, C],
    zio4: => ZIO[R, E, D]
  )(f: (A, B, C, D) => F): ZIO[R, E, F] =
    ZIO.suspendSucceed((zio1 <&> zio2 <&> zio3 <&> zio4).map(f.tupled))

  /**
   * Returns a memoized version of the specified effectual function.
   */
  def memoize[R, E, A, B](f: A => ZIO[R, E, B]): UIO[A => ZIO[R, E, B]] =
    Ref.Synchronized.make(Map.empty[A, Promise[E, B]]).map { ref => a =>
      for {
        promise <- ref.modifyZIO { map =>
                     map.get(a) match {
                       case Some(promise) => ZIO.succeedNow((promise, map))
                       case None =>
                         for {
                           promise <- Promise.make[E, B]
                           _       <- f(a).intoPromise(promise).fork
                         } yield (promise, map + (a -> promise))
                     }
                   }
        b <- promise.await
      } yield b
    }

  /**
   * Merges an `Iterable[IO]` to a single IO, working sequentially.
   */
  def mergeAll[R, E, A, B](
    in: => Iterable[ZIO[R, E, A]]
  )(zero: => B)(f: (B, A) => B): ZIO[R, E, B] =
    ZIO.suspendSucceed(in.foldLeft[ZIO[R, E, B]](succeedNow(zero))(_.zipWith(_)(f)))

  /**
   * Merges an `Iterable[IO]` to a single IO, working in parallel.
   *
   * Due to the parallel nature of this combinator, `f` must be both:
   * - commutative: `f(a, b) == f(b, a)`
   * - associative: `f(a, f(b, c)) == f(f(a, b), c)`
   *
   * It's unsafe to execute side effects inside `f`, as `f` may be executed
   * more than once for some of `in` elements during effect execution.
   */
  def mergeAllPar[R, E, A, B](
    in: => Iterable[ZIO[R, E, A]]
  )(zero: => B)(f: (B, A) => B): ZIO[R, E, B] =
    Ref.make(zero).flatMap(acc => foreachParDiscard(in)(_.flatMap(a => acc.update(f(_, a)))) *> acc.get)

  /**
   * Merges an `Iterable[IO]` to a single IO, working in with up to `n` fibers in parallel.
   *
   * Due to the parallel nature of this combinator, `f` must be both:
   * - commutative: `f(a, b) == f(b, a)`
   * - associative: `f(a, f(b, c)) == f(f(a, b), c)`
   *
   * It's unsafe to execute side effects inside `f`, as `f` may be executed
   * more than once for some of `in` elements during effect execution.
   */
  def mergeAllParN[R, E, A, B](n: => Int)(
    in: => Iterable[ZIO[R, E, A]]
  )(zero: => B)(f: (B, A) => B): ZIO[R, E, B] =
    Ref.make(zero).flatMap(acc => foreachParNDiscard(n)(in)(_.flatMap(a => acc.update(f(_, a)))) *> acc.get)

  /**
   * Returns an effect with the empty value.
   */
  lazy val none: UIO[Option[Nothing]] = succeedNow(None)

  /**
   * Lifts an Option into a IO.
   * If the option is empty it succeeds with Unit.
   * If the option is defined it fails with the content.
   */
  def noneOrFail[E](o: => Option[E]): IO[E, Unit] =
    getOrFailUnit(o).flip

  /**
   * Lifts an Option into a IO.
   * If the option is empty it succeeds with Unit.
   * If the option is defined it fails with an error adapted with f.
   */
  def noneOrFailWith[E, O](o: => Option[O])(f: O => E): IO[E, Unit] =
    getOrFailUnit(o).flip.mapError(f)

  /**
   * Returns an effect that will execute the specified effect fully on the
   * provided executor, before returning to the default executor. See
   * [[ZIO!.onExecutor]].
   */
  def onExecutor[R, E, A](executor: => Executor)(zio: ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.descriptorWith { descriptor =>
      if (descriptor.isLocked) ZIO.shift(executor).acquireRelease(ZIO.shift(descriptor.executor), zio)
      else ZIO.shift(executor).acquireRelease(ZIO.unshift, zio)
    }

  /**
   * Feeds elements of type `A` to a function `f` that returns an effect.
   * Collects all successes and failures in a tupled fashion.
   */
  def partition[R, E, A, B](
    in: => Iterable[A]
  )(f: A => ZIO[R, E, B])(implicit ev: CanFail[E]): ZIO[R, Nothing, (Iterable[E], Iterable[B])] =
    ZIO.suspendSucceed(ZIO.foreach(in)(f(_).either).map(partitionMap(_)(ZIO.identityFn)))

  /**
   * Feeds elements of type `A` to a function `f` that returns an effect.
   * Collects all successes and failures in parallel and returns the result as
   * a tuple.
   */
  def partitionPar[R, E, A, B](
    in: => Iterable[A]
  )(f: A => ZIO[R, E, B])(implicit ev: CanFail[E]): ZIO[R, Nothing, (Iterable[E], Iterable[B])] =
    ZIO.suspendSucceed(ZIO.foreachPar(in)(f(_).either).map(ZIO.partitionMap(_)(ZIO.identityFn)))

  /**
   * Feeds elements of type `A` to a function `f` that returns an effect.
   * Collects all successes and failures in parallel and returns the result as
   * a tuple.
   *
   * Unlike [[partitionPar]], this method will use at most up to `n` fibers.
   */
  def partitionParN[R, E, A, B](
    n: => Int
  )(in: => Iterable[A])(f: A => ZIO[R, E, B])(implicit ev: CanFail[E]): ZIO[R, Nothing, (Iterable[E], Iterable[B])] =
    ZIO.suspendSucceed(ZIO.foreachParN(n)(in)(f(_).either).map(ZIO.partitionMap(_)(ZIO.identityFn)))

  /**
   * Retrieves the platform that this effect is running on.
   */
  val platform: UIO[Platform] =
    ZIO.suspendSucceedWith((platform, _) => ZIO.succeedNow(platform))

  /**
   * Given an environment `R`, returns a function that can supply the
   * environment to programs that require it, removing their need for any
   * specific environment.
   *
   * This is similar to dependency injection, and the `provide` function can be
   * thought of as `inject`.
   */
  def provide[R, E, A](r: => R): ZIO[R, E, A] => IO[E, A] =
    (zio: ZIO[R, E, A]) => new ZIO.Provide(() => r, zio)

  /**
   * Returns a effect that will never produce anything. The moral equivalent of
   * `while(true) {}`, only without the wasted CPU cycles. Fibers that suspended
   * running this effect are automatically garbage collected on the JVM,
   * because they cannot be reactivated.
   */
  lazy val never: UIO[Nothing] =
    async[Any, Nothing, Nothing](_ => ())

  /**
   * Runs the specified effect on the specified platform, restoring the old
   * platform when it completes execution.
   */
  def onPlatform[R, E, A](platform: => Platform)(zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.platform.flatMap { currentPlatform =>
      ZIO.setPlatform(platform).acquireRelease(ZIO.setPlatform(currentPlatform), zio)
    }

  /**
   * Races an `IO[E, A]` against zero or more other effects. Yields either the
   * first success or the last failure.
   */
  def raceAll[R, R1 <: R, E, A](
    zio: => ZIO[R, E, A],
    ios: => Iterable[ZIO[R1, E, A]]
  ): ZIO[R1, E, A] =
    ZIO.suspendSucceed(zio.raceAll(ios))

  /**
   * Reduces an `Iterable[IO]` to a single `IO`, working sequentially.
   */
  def reduceAll[R, R1 <: R, E, A](a: => ZIO[R, E, A], as: => Iterable[ZIO[R1, E, A]])(
    f: (A, A) => A
  ): ZIO[R1, E, A] =
    ZIO.suspendSucceed(as.foldLeft[ZIO[R1, E, A]](a)(_.zipWith(_)(f)))

  /**
   * Reduces an `Iterable[IO]` to a single `IO`, working in parallel.
   */
  def reduceAllPar[R, R1 <: R, E, A](a0: => ZIO[R, E, A], as0: => Iterable[ZIO[R1, E, A]])(
    f: (A, A) => A
  ): ZIO[R1, E, A] =
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

  /**
   * Reduces an `Iterable[IO]` to a single `IO`, working in up to `n` fibers in parallel.
   */
  def reduceAllParN[R, R1 <: R, E, A](n: => Int)(a0: => ZIO[R, E, A], as0: => Iterable[ZIO[R1, E, A]])(
    f: (A, A) => A
  ): ZIO[R1, E, A] =
    ZIO.suspendSucceed {
      val a  = a0
      val as = as0

      def prepend[Z](z: => Z, zs: => Iterable[Z]): Iterable[Z] =
        new Iterable[Z] {
          override def iterator: Iterator[Z] = Iterator(z) ++ zs.iterator
        }

      val all = prepend(a, as)
      mergeAllParN(n)(all)(Option.empty[A])((acc, elem) => Some(acc.fold(elem)(f(_, elem)))).map(_.get)
    }

  /**
   * Replicates the given effect `n` times. If 0 or negative numbers are given,
   * an empty `Iterable` will be returned. This method is more efficient than
   * using `List.fill` or similar methods, because the returned `Iterable`
   * consumes only a small amount of heap regardless of `n`.
   */
  def replicate[R, E, A](n: Int)(effect: ZIO[R, E, A]): Iterable[ZIO[R, E, A]] =
    new Iterable[ZIO[R, E, A]] {
      override def iterator: Iterator[ZIO[R, E, A]] = Iterator.range(0, n).map(_ => effect)
    }

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  @deprecated("use replicateZIO", "2.0.0")
  def replicateM[R, E, A](n: => Int)(effect: => ZIO[R, E, A]): ZIO[R, E, Iterable[A]] =
    replicateZIO(n)(effect)

  /**
   * Performs this effect the specified number of times, discarding the
   * results.
   */
  @deprecated("use replicateZIODiscard", "2.0.0")
  def replicateM_[R, E, A](n: => Int)(effect: => ZIO[R, E, A]): ZIO[R, E, Unit] =
    replicateZIODiscard(n)(effect)

  /**
   * Performs this effect the specified number of times and collects the
   * results.
   */
  def replicateZIO[R, E, A](n: => Int)(effect: => ZIO[R, E, A]): ZIO[R, E, Iterable[A]] =
    ZIO.suspendSucceed(ZIO.collectAll(ZIO.replicate(n)(effect)))

  /**
   * Performs this effect the specified number of times, discarding the
   * results.
   */
  def replicateZIODiscard[R, E, A](n: => Int)(effect: => ZIO[R, E, A]): ZIO[R, E, Unit] =
    ZIO.collectAllDiscard(ZIO.replicate(n)(effect))

  /**
   * Requires that the given `ZIO[R, E, Option[A]]` contain a value. If there is no
   * value, then the specified error will be raised.
   */
  @deprecated("use someOrFail", "2.0.0")
  def require[R, E, A](error: => E): ZIO[R, E, Option[A]] => ZIO[R, E, A] =
    (io: ZIO[R, E, Option[A]]) => io.flatMap(_.fold[ZIO[R, E, A]](fail[E](error))(succeedNow))

  /**
   * Acquires a resource, uses the resource, and then releases the resource.
   * However, unlike `acquireReleaseWith`, the separation of these phases
   * allows the acquisition to be interruptible.
   *
   * Useful for concurrent data structures and other cases where the
   * 'deallocator' can tell if the allocation succeeded or not just by
   * inspecting internal / external state.
   */
  def reserve[R, E, A, B](reservation: => ZIO[R, E, Reservation[R, E, A]])(use: A => ZIO[R, E, B]): ZIO[R, E, B] =
    ZManaged.fromReservationZIO(reservation).use(use)

  /**
   *  Returns an effect with the value on the right part.
   */
  def right[B](b: => B): UIO[Either[Nothing, B]] =
    succeed(Right(b))

  /**
   * Returns an effect that accesses the runtime, which can be used to
   * (unsafely) execute tasks. This is useful for integration with legacy
   * code that must call back into ZIO code.
   */
  def runtime[R]: URIO[R, Runtime[R]] =
    for {
      environment <- environment[R]
      platform    <- suspendSucceedWith((p, _) => ZIO.succeedNow(p))
      executor    <- executor
    } yield Runtime(environment, platform.copy(executor = executor))

  /**
   * Passes the fiber's scope to the specified function, which creates an effect
   * that will be returned from this method.
   */
  def scopeWith[R, E, A](f: ZScope[Exit[Any, Any]] => ZIO[R, E, A]): ZIO[R, E, A] =
    descriptorWith(d => f(d.scope))

  /**
   * Sets a state in the environment to the specified value.
   */
  def setState[S: Tag](s: => S): ZIO[Has[ZState[S]], Nothing, Unit] =
    ZIO.serviceWith(_.set(s))

  /**
   * Sets the platform to the specified value.
   */
  def setPlatform(platform: => Platform): UIO[Unit] =
    new ZIO.SetPlatform(() => platform)

  /**
   * Accesses the specified service in the environment of the effect.
   */
  def service[A: Tag]: URIO[Has[A], A] =
    ZIO.access(_.get[A])

  /**
   * Accesses the service corresponding to the specified key in the
   * environment.
   */
  def serviceAt[Service]: ServiceAtPartiallyApplied[Service] =
    new ServiceAtPartiallyApplied[Service]

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[A: Tag, B: Tag]: URIO[Has[A] with Has[B], (A, B)] =
    ZIO.access(r => (r.get[A], r.get[B]))

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[A: Tag, B: Tag, C: Tag]: URIO[Has[A] with Has[B] with Has[C], (A, B, C)] =
    ZIO.access(r => (r.get[A], r.get[B], r.get[C]))

  /**
   * Accesses the specified services in the environment of the effect.
   */
  @deprecated("use service", "2.0.0")
  def services[A: Tag, B: Tag, C: Tag, D: Tag]: URIO[Has[A] with Has[B] with Has[C] with Has[D], (A, B, C, D)] =
    ZIO.access(r => (r.get[A], r.get[B], r.get[C], r.get[D]))

  /**
   * Effectfully accesses the specified service in the environment of the effect.
   *
   * Especially useful for creating "accessor" methods on Services' companion objects.
   *
   * {{{
   * def foo(int: Int) = ZIO.serviceWith[Foo](_.foo(int))
   * }}}
   */
  def serviceWith[Service]: ServiceWithPartiallyApplied[Service] =
    new ServiceWithPartiallyApplied[Service]

  /**
   * Returns an effect that shifts execution to the specified executor. This
   * is useful to specify a default executor that effects sequenced after this
   * one will be run on if they are not shifted somewhere else. It can also be
   * used to implement higher level operators to manage where an effect is run
   * such as [[ZIO!.onExecutor]] and [[ZIO!.onExecutionContext]].
   */
  def shift(executor: => Executor): UIO[Unit] =
    new ZIO.Shift(() => executor)

  /**
   * Returns an effect that suspends for the specified duration. This method is
   * asynchronous, and does not actually block the fiber executing the effect.
   */
  def sleep(duration: => Duration): URIO[Has[Clock], Unit] =
    Clock.sleep(duration)

  /**
   *  Returns an effect with the optional value.
   */
  def some[A](a: => A): UIO[Option[A]] =
    succeed(Some(a))

  /**
   * Returns an effect that models success with the specified value.
   */
  def succeed[A](a: => A): UIO[A] =
    new ZIO.EffectTotal(() => a)

  /**
   * Returns a synchronous effect that does blocking and succeeds with the
   * specified value.
   */
  def succeedBlocking[A](a: => A): UIO[A] =
    blocking(ZIO.succeedNow(a))

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * When no environment is required (i.e., when R == Any) it is conceptually equivalent to `flatten(effect(io))`.
   */
  def suspend[R, A](rio: => RIO[R, A]): RIO[R, A] =
    suspendSucceedWith { (platform, _) =>
      try rio
      catch {
        case t: Throwable if !platform.fatal(t) => throw new ZioError(Exit.fail(t))
      }
    }

  /**
   * Returns a lazily constructed effect, whose construction may itself require
   * effects. The effect must not throw any exceptions. When no environment is required (i.e., when R == Any)
   * it is conceptually equivalent to `flatten(succeed(zio))`. If you wonder if the effect throws exceptions,
   * do not use this method, use [[Task.suspend]] or [[ZIO.suspend]].
   */
  def suspendSucceed[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.Suspend(() => zio)

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * The effect must not throw any exceptions. When no environment is required (i.e., when R == Any)
   * it is conceptually equivalent to `flatten(succeed(zio))`. If you wonder if the effect throws exceptions,
   * do not use this method, use [[Task.suspend]] or [[ZIO.suspend]].
   */
  def suspendSucceedWith[R, E, A](f: (Platform, FiberId) => ZIO[R, E, A]): ZIO[R, E, A] =
    new ZIO.SuspendWith(f)

  /**
   * Returns a lazily constructed effect, whose construction may itself require effects.
   * When no environment is required (i.e., when R == Any) it is conceptually equivalent to `flatten(effect(io))`.
   */
  def suspendWith[R, A](f: (Platform, FiberId) => RIO[R, A]): RIO[R, A] =
    suspendSucceedWith((platform, fiberId) =>
      try f(platform, fiberId)
      catch {
        case t: Throwable if !platform.fatal(t) => throw new ZioError(Exit.fail(t))
      }
    )

  /**
   * Capture ZIO trace at the current point
   */
  def trace: UIO[ZTrace] =
    ZIO.Trace

  /**
   * Prefix form of `ZIO#traced`.
   */
  def traced[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    zio.traced

  /**
   * Transplants specified effects so that when those effects fork other
   * effects, the forked effects will be governed by the scope of the
   * fiber that executes this effect.
   *
   * This can be used to "graft" deep grandchildren onto a higher-level
   * scope, effectively extending their lifespans into the parent scope.
   */
  def transplant[R, E, A](f: Grafter => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.forkScopeWith(scope => f(new Grafter(scope)))

  /**
   * An effect that succeeds with a unit value.
   */
  lazy val unit: UIO[Unit] =
    succeedNow(())

  /**
   * Prefix form of `ZIO#uninterruptible`.
   */
  def uninterruptible[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio.uninterruptible)

  /**
   * Makes the effect uninterruptible, but passes it a restore function that
   * can be used to restore the inherited interruptibility from whatever region
   * the effect is composed into.
   */
  def uninterruptibleMask[R, E, A](
    k: ZIO.InterruptStatusRestore => ZIO[R, E, A]
  ): ZIO[R, E, A] =
    checkInterruptible(flag => k(ZIO.InterruptStatusRestore(flag)).uninterruptible)

  /**
   * The moral equivalent of `if (!p) exp`
   */
  def unless[R, E](b: => Boolean)(zio: => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    suspendSucceed(if (b) unit else zio.unit)

  /**
   * The moral equivalent of `if (!p) exp` when `p` has side-effects
   */
  @deprecated("use unlessZIO", "2.0.0")
  def unlessM[R, E](b: => ZIO[R, E, Boolean]): ZIO.UnlessZIO[R, E] =
    unlessZIO(b)

  /**
   * The moral equivalent of `if (!p) exp` when `p` has side-effects
   */
  def unlessZIO[R, E](b: => ZIO[R, E, Boolean]): ZIO.UnlessZIO[R, E] =
    new ZIO.UnlessZIO(() => b)

  /**
   * The inverse operation `IO.sandboxed`
   *
   * Terminates with exceptions on the `Left` side of the `Either` error, if it
   * exists. Otherwise extracts the contained `IO[E, A]`
   */
  def unsandbox[R, E, A](v: => ZIO[R, Cause[E], A]): ZIO[R, E, A] =
    ZIO.suspendSucceed(v.mapErrorCause(_.flatten))

  /**
   * Returns an effect indicating that execution is no longer required to be
   * performed on the current executor. The runtime may continue executing on
   * this executor for efficiency but will not automatically shift back to it
   * after completing an effect on another executor.
   */
  def unshift: UIO[Unit] =
    new ZIO.Shift(() => null)

  /**
   * Prefix form of `ZIO#untraced`.
   */
  def untraced[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
    ZIO.suspendSucceed(zio.untraced)

  /**
   * Updates a state in the environment with the specified function.
   */
  def updateState[S: Tag](f: S => S): ZIO[Has[ZState[S]], Nothing, Unit] =
    ZIO.serviceWith(_.update(f))

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors in error
   * channel or successes in success channel.
   *
   * This combinator is lossy meaning that if there are errors all successes
   * will be lost. To retain all information please use [[partition]].
   */
  def validate[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], ev: CanFail[E]): ZIO[R, ::[E], Collection[B]] =
    partition(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) ZIO.succeedNow(bf.fromSpecific(in)(bs))
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
  )(implicit ev: CanFail[E]): ZIO[R, ::[E], NonEmptyChunk[B]] =
    partition(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) ZIO.succeedNow(NonEmptyChunk.nonEmpty(Chunk.fromIterable(bs)))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors, discarding
   * the successes.
   */
  @deprecated("use validateDiscard", "2.0.0")
  def validate_[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit ev: CanFail[E]): ZIO[R, ::[E], Unit] =
    validateDiscard(in)(f)

  /**
   * Feeds elements of type `A` to `f` and accumulates all errors, discarding
   * the successes.
   */
  def validateDiscard[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit
    ev: CanFail[E]
  ): ZIO[R, ::[E], Unit] =
    partition(in)(f).flatMap { case (es, _) =>
      if (es.isEmpty) ZIO.unit
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
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], ev: CanFail[E]): ZIO[R, ::[E], Collection[B]] =
    partitionPar(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) ZIO.succeedNow(bf.fromSpecific(in)(bs))
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
  )(implicit ev: CanFail[E]): ZIO[R, ::[E], NonEmptyChunk[B]] =
    partitionPar(in)(f).flatMap { case (es, bs) =>
      if (es.isEmpty) ZIO.succeedNow(NonEmptyChunk.nonEmpty(Chunk.fromIterable(bs)))
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` in parallel and accumulates all errors,
   * discarding the successes.
   */
  @deprecated("use validateParDiscard", "2.0.0")
  def validatePar_[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit ev: CanFail[E]): ZIO[R, ::[E], Unit] =
    validateParDiscard(in)(f)

  /**
   * Feeds elements of type `A` to `f` in parallel and accumulates all errors,
   * discarding the successes.
   */
  def validateParDiscard[R, E, A](in: => Iterable[A])(f: A => ZIO[R, E, Any])(implicit
    ev: CanFail[E]
  ): ZIO[R, ::[E], Unit] =
    partitionPar(in)(f).flatMap { case (es, _) =>
      if (es.isEmpty) ZIO.unit
      else ZIO.fail(::(es.head, es.tail.toList))
    }

  /**
   * Feeds elements of type `A` to `f` until it succeeds. Returns first success
   * or the accumulation of all errors.
   */
  def validateFirst[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], E, Collection[E]], ev: CanFail[E]): ZIO[R, Collection[E], B] =
    ZIO.foreach(in)(f(_).flip).flip

  /**
   * Feeds elements of type `A` to `f`, in parallel, until it succeeds. Returns
   * first success or the accumulation of all errors.
   *
   * In case of success all other running fibers are terminated.
   */
  def validateFirstPar[R, E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => ZIO[R, E, B]
  )(implicit bf: BuildFrom[Collection[A], E, Collection[E]], ev: CanFail[E]): ZIO[R, Collection[E], B] =
    ZIO.foreachPar(in)(f(_).flip).flip

  /**
   * The moral equivalent of `if (p) exp`
   */
  def when[R, E](b: => Boolean)(zio: => ZIO[R, E, Any]): ZIO[R, E, Unit] =
    suspendSucceed(if (b) zio.unit else unit)

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given value, otherwise does nothing.
   */
  def whenCase[R, E, A](a: => A)(pf: PartialFunction[A, ZIO[R, E, Any]]): ZIO[R, E, Unit] =
    suspendSucceed(pf.applyOrElse(a, (_: A) => unit).unit)

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given effectful value, otherwise does nothing.
   */
  @deprecated("use whenCaseZIO", "2.0.0")
  def whenCaseM[R, E, A](a: => ZIO[R, E, A])(pf: PartialFunction[A, ZIO[R, E, Any]]): ZIO[R, E, Unit] =
    whenCaseZIO(a)(pf)

  /**
   * Runs an effect when the supplied `PartialFunction` matches for the given effectful value, otherwise does nothing.
   */
  def whenCaseZIO[R, E, A](a: => ZIO[R, E, A])(pf: PartialFunction[A, ZIO[R, E, Any]]): ZIO[R, E, Unit] =
    ZIO.suspendSucceed(a.flatMap(whenCase(_)(pf)))

  /**
   * The moral equivalent of `if (p) exp` when `p` has side-effects
   */
  @deprecated("use whenZIO", "2.0.0")
  def whenM[R, E](b: => ZIO[R, E, Boolean]): ZIO.WhenZIO[R, E] =
    whenZIO(b)

  /**
   * The moral equivalent of `if (p) exp` when `p` has side-effects
   */
  def whenZIO[R, E](b: => ZIO[R, E, Boolean]): ZIO.WhenZIO[R, E] =
    new ZIO.WhenZIO(() => b)

  /**
   * Locally installs a supervisor and an effect that succeeds with all the
   * children that have been forked in the returned effect.
   */
  def withChildren[R, E, A](get: UIO[Chunk[Fiber.Runtime[Any, Any]]] => ZIO[R, E, A]): ZIO[R, E, A] =
    Supervisor.track(true).flatMap { supervisor =>
      // Filter out the fiber id of whoever is calling this:
      get(supervisor.value.flatMap(children => ZIO.descriptor.map(d => children.filter(_.id != d.id))))
        .supervised(supervisor)
    }

  /**
   * Returns an effect that yields to the runtime system, starting on a fresh
   * stack. Manual use of this method can improve fairness, at the cost of
   * overhead.
   */
  lazy val yieldNow: UIO[Unit] = ZIO.Yield

  def apply[A](a: => A): Task[A] = attempt(a)

  private lazy val _IdentityFn: Any => Any = (a: Any) => a

  private[zio] def identityFn[A]: A => A = _IdentityFn.asInstanceOf[A => A]

  private[zio] def partitionMap[A, A1, A2](
    iterable: Iterable[A]
  )(f: A => Either[A1, A2]): (Iterable[A1], Iterable[A2]) =
    iterable.foldRight((List.empty[A1], List.empty[A2])) { case (a, (es, bs)) =>
      f(a).fold(
        e => (e :: es, bs),
        b => (es, b :: bs)
      )
    }

  private[zio] val unitFn: Any => Unit = (_: Any) => ()

  implicit final class ZIOAutoCloseableOps[R, E, A <: AutoCloseable](private val io: ZIO[R, E, A]) extends AnyVal {

    /**
     * Like `acquireReleaseWith`, safely wraps a use and release of a resource.
     * This resource will get automatically closed, because it implements `AutoCloseable`.
     */
    def acquireReleaseWithAuto[R1 <: R, E1 >: E, B](use: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
      // TODO: Dotty doesn't infer this properly: io.bracket[R1, E1](a => UIO(a.close()))(use)
      acquireReleaseWith(io)(a => UIO(a.close()))(use)

    /**
     * Like `bracket`, safely wraps a use and release of a resource.
     * This resource will get automatically closed, because it implements `AutoCloseable`.
     */
    @deprecated("use acquireReleaseWithAuto", "2.0.0")
    def bracketAuto[R1 <: R, E1 >: E, B](use: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
      acquireReleaseWithAuto(use)

    /**
     * Converts this ZIO value to a ZManaged value. See [[ZManaged.fromAutoCloseable]].
     */
    def toManagedAuto: ZManaged[R, E, A] =
      ZManaged.fromAutoCloseable(io)
  }

  implicit final class ZioRefineToOrDieOps[R, E <: Throwable, A](private val self: ZIO[R, E, A]) extends AnyVal {

    /**
     * Keeps some of the errors, and terminates the fiber with the rest.
     */
    def refineToOrDie[E1 <: E: ClassTag](implicit ev: CanFail[E]): ZIO[R, E1, A] =
      self.refineOrDie { case e: E1 => e }
  }

  final class ProvideSomeLayer[R0, -R, +E, +A](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[E1 >: E, R1](
      layer: => ZLayer[R0, E1, R1]
    )(implicit ev1: R0 with R1 <:< R, ev2: Has.Union[R0, R1], tagged: Tag[R1]): ZIO[R0, E1, A] =
      self.provideLayer[E1, R0, R0 with R1](ZLayer.environment[R0] ++ layer)
  }

  final class UpdateService[-R, +E, +A, M](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R with Has[M]](f: M => M)(implicit ev: Has.IsHas[R1], tag: Tag[M]): ZIO[R1, E, A] =
      self.provideSome(ev.update(_, f))
  }

  final class UpdateServiceAt[-R, +E, +A, Service](private val self: ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R with HasMany[Key, Service], Key](key: => Key)(
      f: Service => Service
    )(implicit ev: Has.IsHas[R1], tag: Tag[Map[Key, Service]]): ZIO[R1, E, A] =
      self.provideSome(ev.updateAt(_, key, f))
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

  implicit final class ZIOWithFilterOps[R, E, A](private val self: ZIO[R, E, A]) extends AnyVal {

    /**
     * Enables to check conditions in the value produced by ZIO
     * If the condition is not satisfied, it fails with NoSuchElementException
     * this provide the syntax sugar in for-comprehension:
     * for {
     *   (i, j) <- io1
     *   positive <- io2 if positive > 0
     *  } yield ()
     */
    def withFilter(predicate: A => Boolean)(implicit ev: CanFilter[E]): ZIO[R, E, A] =
      self.flatMap { a =>
        if (predicate(a)) ZIO.succeedNow(a)
        else ZIO.fail(ev(new NoSuchElementException("The value doesn't satisfy the predicate")))
      }
  }

  final class Grafter(private val scope: ZScope[Exit[Any, Any]]) extends AnyVal {
    def apply[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
      new ZIO.OverrideForkScope(zio, () => Some(scope))
  }

  final class InterruptStatusRestore private (private val flag: zio.InterruptStatus) extends AnyVal {
    def apply[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
      zio.interruptStatus(flag)

    /**
     * Returns a new effect that, if the parent region is uninterruptible, can
     * be interrupted in the background instantaneously. If the parent region
     * is interruptible, then the effect can be interrupted normally, in the
     * foreground.
     */
    def force[R, E, A](zio: => ZIO[R, E, A]): ZIO[R, E, A] =
      if (flag == _root_.zio.InterruptStatus.Uninterruptible) zio.uninterruptible.disconnect.interruptible
      else zio.interruptStatus(flag)
  }
  object InterruptStatusRestore {
    val restoreInterruptible   = new InterruptStatusRestore(zio.InterruptStatus.Interruptible)
    val restoreUninterruptible = new InterruptStatusRestore(zio.InterruptStatus.Uninterruptible)

    def apply(flag: zio.InterruptStatus): InterruptStatusRestore =
      if (flag eq zio.InterruptStatus.Interruptible) restoreInterruptible else restoreUninterruptible
  }

  final class IfZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, A](onTrue: => ZIO[R1, E1, A], onFalse: => ZIO[R1, E1, A]): ZIO[R1, E1, A] =
      ZIO.suspendSucceed(b().flatMap(b => if (b) onTrue else onFalse))
  }

  final class UnlessZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E](zio: => ZIO[R1, E1, Any]): ZIO[R1, E1, Unit] =
      ZIO.suspendSucceed(b().flatMap(b => if (b) unit else zio.unit))
  }

  final class WhenZIO[R, E](private val b: () => ZIO[R, E, Boolean]) extends AnyVal {
    def apply[R1 <: R, E1 >: E](zio: => ZIO[R1, E1, Any]): ZIO[R1, E1, Unit] =
      ZIO.suspendSucceed(b()).flatMap(b => if (b) zio.unit else unit)
  }

  final class TimeoutTo[-R, +E, +A, +B](self: ZIO[R, E, A], b: () => B) {
    def apply[B1 >: B](f: A => B1)(duration: => Duration): ZIO[R with Has[Clock], E, B1] =
      (self map f) raceFirst (ZIO.sleep(duration).interruptible as b())
  }

  final class BracketAcquire_[-R, +E](private val acquire: ZIO[R, E, Any]) extends AnyVal {
    def apply[R1 <: R](
      release: => URIO[R1, Any]
    ): BracketRelease_[R1, E] =
      new BracketRelease_(acquire, release)
  }
  final class BracketRelease_[-R, +E](acquire: ZIO[R, E, Any], release: URIO[R, Any]) {
    def apply[R1 <: R, E1 >: E, B](use: => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
      ZIO.acquireReleaseWith(acquire, (_: Any) => release, (_: Any) => use)
  }

  final class BracketAcquire[-R, +E, +A](private val acquire: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1](release: A => URIO[R1, Any]): BracketRelease[R with R1, E, A] =
      new BracketRelease[R with R1, E, A](acquire, release)
  }
  final class BracketRelease[-R, +E, +A](acquire: () => ZIO[R, E, A], release: A => URIO[R, Any]) {
    def apply[R1 <: R, E1 >: E, B](use: A => ZIO[R1, E1, B]): ZIO[R1, E1, B] =
      ZIO.acquireReleaseWith(acquire(), release, use)
  }

  final class BracketExitAcquire[-R, +E, +A](private val acquire: () => ZIO[R, E, A]) extends AnyVal {
    def apply[R1 <: R, E1 >: E, B](
      release: (A, Exit[E1, B]) => URIO[R1, Any]
    ): BracketExitRelease[R1, E, E1, A, B] =
      new BracketExitRelease(acquire, release)
  }
  final class BracketExitRelease[-R, +E, E1, +A, B](
    acquire: () => ZIO[R, E, A],
    release: (A, Exit[E1, B]) => URIO[R, Any]
  ) {
    def apply[R1 <: R, E2 >: E <: E1, B1 <: B](use: A => ZIO[R1, E2, B1]): ZIO[R1, E2, B1] =
      ZIO.acquireReleaseExitWith(acquire(), release, use)
  }

  final class AccessPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: R => A): URIO[R, A] =
      new ZIO.Read(r => succeedNow(f(r)))
  }

  final class AccessZIOPartiallyApplied[R](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](f: R => ZIO[R, E, A]): ZIO[R, E, A] =
      new ZIO.Read(f)
  }

  final class ServiceAtPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[Key](
      key: => Key
    )(implicit tag: Tag[Map[Key, Service]]): URIO[HasMany[Key, Service], Option[Service]] =
      ZIO.access(_.getAt(key))
  }

  final class ServiceWithPartiallyApplied[Service](private val dummy: Boolean = true) extends AnyVal {
    def apply[E, A](f: Service => ZIO[Has[Service], E, A])(implicit tag: Tag[Service]): ZIO[Has[Service], E, A] =
      new ZIO.Read(r => f(r.get))
  }

  final class GetStateWithPartiallyApplied[S](private val dummy: Boolean = true) extends AnyVal {
    def apply[A](f: S => A)(implicit tag: Tag[S]): ZIO[Has[ZState[S]], Nothing, A] =
      ZIO.serviceWith(_.get.map(f))
  }

  final class LogSpan(val label: () => String) extends AnyVal {
    import zio.{LogSpan => ZioLogSpan}

    def apply[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
      FiberRef.currentLogSpan.get.flatMap { stack =>
        val instant = java.lang.System.currentTimeMillis()
        val logSpan = ZioLogSpan(label(), instant)

        FiberRef.currentLogSpan.locally(logSpan :: stack)(zio)
      }
  }

  @inline
  private def succeedLeft[E, A]: E => UIO[Either[E, A]] =
    _succeedLeft.asInstanceOf[E => UIO[Either[E, A]]]

  private val _succeedLeft: Any => IO[Any, Either[Any, Any]] =
    e2 => succeedNow[Either[Any, Any]](Left(e2))

  @inline
  private def succeedRight[E, A]: A => UIO[Either[E, A]] =
    _succeedRight.asInstanceOf[A => UIO[Either[E, A]]]

  private val _succeedRight: Any => IO[Any, Either[Any, Any]] =
    a => succeedNow[Either[Any, Any]](Right(a))

  final class ZipLeftFn[R, E, A, B](override val underlying: () => ZIO[R, E, A]) extends ZIOFn1[B, ZIO[R, E, B]] {
    def apply(a: B): ZIO[R, E, B] =
      underlying().as(a)
  }

  final class ZipRightFn[R, E, A, B](override val underlying: () => ZIO[R, E, B]) extends ZIOFn1[A, ZIO[R, E, B]] {
    def apply(a: A): ZIO[R, E, B] = {
      val _ = a
      underlying()
    }
  }

  final class TapFn[R, E, A](override val underlying: A => ZIO[R, E, Any]) extends ZIOFn1[A, ZIO[R, E, A]] {
    def apply(a: A): ZIO[R, E, A] =
      underlying(a).as(a)
  }

  final class MapFn[R, E, A, B](override val underlying: A => B) extends ZIOFn1[A, ZIO[R, E, B]] {
    def apply(a: A): ZIO[R, E, B] =
      new ZIO.Succeed(underlying(a))
  }

  final class ConstZIOFn[R, E, A, B](override val underlying: () => B) extends ZIOFn1[A, ZIO[R, E, B]] {
    def apply(a: A): ZIO[R, E, B] = {
      val _ = a
      new ZIO.Succeed(underlying())
    }
  }

  final class ForkScopeRestore(private val scope: ZScope[Exit[Any, Any]]) extends AnyVal {
    def apply[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
      zio.overrideForkScope(scope)
  }

  final class ConstFn[A, B](override val underlying: () => B) extends ZIOFn1[A, B] {
    def apply(a: A): B = {
      val _ = a
      underlying()
    }
  }

  final class BracketReleaseFn[R, E, A, B](override val underlying: A => URIO[R, Any])
      extends ZIOFn2[A, Exit[E, B], URIO[R, Any]] {
    override def apply(a: A, exit: Exit[E, B]): URIO[R, Any] = {
      val _ = exit
      underlying(a)
    }
  }

  final class SucceedFn[R, E, A](override val underlying: AnyRef) extends ZIOFn1[A, ZIO[R, E, A]] {
    def apply(a: A): ZIO[R, E, A] = new ZIO.Succeed(a)
  }

  final class MapErrorFn[R, E, E2, A](override val underlying: E => E2) extends ZIOFn1[Cause[E], ZIO[R, E2, Nothing]] {
    def apply(a: Cause[E]): ZIO[R, E2, Nothing] =
      ZIO.failCause(a.map(underlying))
  }

  final class MapErrorCauseFn[R, E, E2, A](override val underlying: Cause[E] => Cause[E2])
      extends ZIOFn1[Cause[E], ZIO[R, E2, Nothing]] {
    def apply(a: Cause[E]): ZIO[R, E2, Nothing] =
      ZIO.failCause(underlying(a))
  }

  final class FoldCauseZIOFailureFn[R, E, E2, A](override val underlying: E => ZIO[R, E2, A])
      extends ZIOFn1[Cause[E], ZIO[R, E2, A]] {
    def apply(c: Cause[E]): ZIO[R, E2, A] =
      c.failureOrCause.fold(underlying, ZIO.failCause(_))
  }

  final class FoldCauseZIOFailureTraceFn[R, E, E2, A](override val underlying: ((E, Option[ZTrace])) => ZIO[R, E2, A])
      extends ZIOFn1[Cause[E], ZIO[R, E2, A]] {
    def apply(c: Cause[E]): ZIO[R, E2, A] =
      c.failureTraceOrCause.fold(underlying, ZIO.failCause(_))
  }

  final class TapErrorCauseRefailFn[R, E, E1 >: E, A](override val underlying: Cause[E] => ZIO[R, E1, Any])
      extends ZIOFn1[Cause[E], ZIO[R, E1, Nothing]] {
    def apply(c: Cause[E]): ZIO[R, E1, Nothing] =
      underlying(c) *> ZIO.failCause(c)
  }

  final class TapDefectRefailFn[R, E, E1 >: E](override val underlying: Cause[Nothing] => ZIO[R, E, Any])
      extends ZIOFn1[Cause[E], ZIO[R, E1, Nothing]] {
    def apply(c: Cause[E]): ZIO[R, E1, Nothing] =
      underlying(c.stripFailures) *> ZIO.failCause(c)
  }

  final class TapErrorRefailFn[R, E, E1 >: E, A](override val underlying: E => ZIO[R, E1, Any])
      extends ZIOFn1[Cause[E], ZIO[R, E1, Nothing]] {
    def apply(c: Cause[E]): ZIO[R, E1, Nothing] =
      c.failureOrCause.fold(underlying(_) *> ZIO.failCause(c), _ => ZIO.failCause(c))
  }

  final class TapErrorTraceRefailFn[R, E, E1 >: E, A](
    override val underlying: ((E, Option[ZTrace])) => ZIO[R, E1, Any]
  ) extends ZIOFn1[Cause[E], ZIO[R, E1, Nothing]] {
    def apply(c: Cause[E]): ZIO[R, E1, Nothing] =
      c.failureTraceOrCause.fold(underlying(_) *> ZIO.failCause(c), _ => ZIO.failCause(c))
  }

  /**
   * A `ZIOConstructor[Input]` knows how to construct a `ZIO` value from an
   * input of type `Input`. This allows the type of the `ZIO` value constructed
   * to depend on `Input`. The constructed `ZIO` value is guaranteed not to
   * require any services not included in `Environment` or to be able to fail
   * in any ways not described by `Error`.
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
    def make(input: => In): ZIO[OutEnvironment, OutError, OutSuccess]
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
        def make(input: => Either[Cause[E], A]): ZIO[Any, E, A] =
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
        def make(input: => Left[Cause[E], A]): ZIO[Any, E, A] =
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
        def make(input: => Right[Cause[E], A]): ZIO[Any, E, A] =
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
        def make(input: => Fiber[E, A]): ZIO[Any, E, A] =
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
        def make(input: => Fiber.Runtime[E, A]): ZIO[Any, E, A] =
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
        def make(input: => Fiber.Synthetic[E, A]): ZIO[Any, E, A] =
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
        def make(input: => ZIO[R, E1, Fiber[E2, A]]): ZIO[R, E3, A] =
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
        def make(input: => ZIO[R, E1, Fiber.Runtime[E2, A]]): ZIO[R, E3, A] =
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
        def make(input: => ZIO[R, E1, Fiber.Synthetic[E2, A]]): ZIO[R, E3, A] =
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
        def make(input: => FutureLike[A]): ZIO[Any, Throwable, A] =
          ZIO.fromFuture(_ => input)
      }

    /**
     * Constructs a `ZIO[Any, Throwable, A]` from a function
     * `ExecutionContext => Future[A]`.
     */
    implicit def FutureExecutionContextConstructor[A, FutureLike[A] <: scala.concurrent.Future[A]]
      : WithOut[Any, Throwable, scala.concurrent.ExecutionContext => FutureLike[A], Any, Throwable, A] =
      new ZIOConstructor[Any, Throwable, scala.concurrent.ExecutionContext => FutureLike[A]] {
        type OutEnvironment = Any
        type OutError       = Throwable
        type OutSuccess     = A
        def make(input: => (scala.concurrent.ExecutionContext => FutureLike[A])): ZIO[Any, Throwable, A] =
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
        def make(input: => Option[A]): ZIO[Any, Option[Nothing], A] =
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
        def make(input: => None.type): ZIO[Any, Option[Nothing], Nothing] =
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
        def make(input: => Some[A]): ZIO[Any, Option[Nothing], A] =
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
        def make(input: => PromiseLike[A]): ZIO[Any, Throwable, A] =
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
        def make(input: => scala.util.Try[A]): ZIO[Any, Throwable, A] =
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
        def make(input: => scala.util.Failure[A]): ZIO[Any, Throwable, A] =
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
        def make(input: => scala.util.Success[A]): ZIO[Any, Throwable, A] =
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
        def make(input: => Either[E, A]): ZIO[Any, E, A] =
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
        def make(input: => Left[E, A]): ZIO[Any, E, A] =
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
        def make(input: => Right[E, A]): ZIO[Any, E, A] =
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
        def make(input: => A): ZIO[Any, Throwable, A] =
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
        def make(input: => A): ZIO[Any, Nothing, A] =
          ZIO.succeed(input)
      }
  }

  private[zio] object Tags {
    final val FlatMap           = 0
    final val Succeed           = 1
    final val Fail              = 3
    final val Fold              = 4
    final val Ensuring          = 5
    final val EffectTotal       = 6
    final val EffectWith        = 7
    final val Suspend           = 8
    final val SuspendWith       = 9
    final val EffectAsync       = 10
    final val InterruptStatus   = 11
    final val CheckInterrupt    = 12
    final val Fork              = 13
    final val Descriptor        = 14
    final val Shift             = 15
    final val Yield             = 16
    final val Access            = 17
    final val Provide           = 18
    final val FiberRefNew       = 19
    final val FiberRefModify    = 20
    final val Trace             = 21
    final val TracingStatus     = 22
    final val CheckTracing      = 23
    final val RaceWith          = 24
    final val Supervise         = 25
    final val GetForkScope      = 26
    final val OverrideForkScope = 27
    final val Logged            = 28
    final val FiberRefGetAll    = 29
    final val FiberRefLocally   = 30
    final val FiberRefDelete    = 31
    final val SetPlatform       = 32
  }

  private[zio] final case class ZioError[E, A](exit: Exit[E, A]) extends Throwable with NoStackTrace

  private[zio] final class FlatMap[R, E, A0, A](val zio: ZIO[R, E, A0], val k: A0 => ZIO[R, E, A])
      extends ZIO[R, E, A] {
    override def tag = Tags.FlatMap
  }

  private[zio] final class Succeed[A](val value: A) extends UIO[A] {
    override def tag = Tags.Succeed
  }

  private[zio] final class EffectTotal[A](val effect: () => A) extends UIO[A] {
    override def tag = Tags.EffectTotal
  }

  private[zio] final class EffectWith[A](val effect: (Platform, FiberId) => A) extends UIO[A] {
    override def tag = Tags.EffectWith
  }

  private[zio] final class Suspend[R, E, A](val make: () => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.Suspend
  }

  private[zio] final class SuspendWith[R, E, A](val make: (Platform, FiberId) => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.SuspendWith
  }

  private[zio] final class EffectAsync[R, E, A](
    val register: (ZIO[R, E, A] => Unit) => Either[Canceler[R], ZIO[R, E, A]],
    val blockingOn: () => FiberId
  ) extends ZIO[R, E, A] {
    override def tag = Tags.EffectAsync
  }

  private[zio] final class Fold[R, E, E2, A, B](
    val value: ZIO[R, E, A],
    val failure: Cause[E] => ZIO[R, E2, B],
    val success: A => ZIO[R, E2, B]
  ) extends ZIOFn1[A, ZIO[R, E2, B]]
      with ZIO[R, E2, B]
      with Function[A, ZIO[R, E2, B]] {

    override def tag = Tags.Fold

    override def underlying = success

    def apply(v: A): ZIO[R, E2, B] = success(v)
  }

  private[zio] final class Fork[R, E, A](
    val value: ZIO[R, E, A],
    val scope: () => Option[ZScope[Exit[Any, Any]]],
    val reportFailure: Option[Cause[Any] => Unit]
  ) extends URIO[R, Fiber.Runtime[E, A]] {
    override def tag = Tags.Fork
  }

  private[zio] final class InterruptStatus[R, E, A](val zio: ZIO[R, E, A], val flag: () => _root_.zio.InterruptStatus)
      extends ZIO[R, E, A] {
    override def tag = Tags.InterruptStatus
  }

  private[zio] final class CheckInterrupt[R, E, A](val k: zio.InterruptStatus => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.CheckInterrupt
  }

  private[zio] final class Fail[E](val fill: (() => ZTrace) => Cause[E]) extends IO[E, Nothing] { self =>
    override def tag = Tags.Fail

    override def map[B](f: Nothing => B): IO[E, Nothing] =
      self

    override def flatMap[R1 <: Any, E1 >: E, B](k: Nothing => ZIO[R1, E1, B]): ZIO[R1, E1, Nothing] =
      self
  }

  private[zio] final class Descriptor[R, E, A](val k: Fiber.Descriptor => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.Descriptor
  }

  private[zio] final class Shift(val executor: () => Executor) extends UIO[Unit] {
    override def tag = Tags.Shift
  }

  private[zio] object Yield extends UIO[Unit] {
    override def tag = Tags.Yield
  }

  private[zio] final class Read[R, E, A](val k: R => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.Access
  }

  private[zio] final class Provide[R, E, A](val r: () => R, val zio: ZIO[R, E, A]) extends IO[E, A] {
    override def tag = Tags.Provide
  }

  private[zio] final class FiberRefGetAll[R, E, A](val make: Map[ZFiberRef.Runtime[_], Any] => ZIO[R, E, A])
      extends ZIO[R, E, A] {
    override def tag = Tags.FiberRefGetAll
  }

  private[zio] final class FiberRefModify[A, B](val fiberRef: FiberRef.Runtime[A], val f: A => (B, A)) extends UIO[B] {
    override def tag = Tags.FiberRefModify
  }

  private[zio] final class FiberRefLocally[V, R, E, A](
    val localValue: V,
    val fiberRef: FiberRef.Runtime[V],
    val zio: ZIO[R, E, A]
  ) extends ZIO[R, E, A] {
    override def tag = Tags.FiberRefLocally
  }

  private[zio] final class FiberRefDelete(
    val fiberRef: FiberRef.Runtime[_]
  ) extends UIO[Unit] {
    override def tag = Tags.FiberRefDelete
  }

  private[zio] object Trace extends UIO[ZTrace] {
    override def tag = Tags.Trace
  }

  private[zio] final class TracingStatus[R, E, A](val zio: ZIO[R, E, A], val flag: () => TracingS)
      extends ZIO[R, E, A] {
    override def tag = Tags.TracingStatus
  }

  private[zio] final class CheckTracing[R, E, A](val k: TracingS => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.CheckTracing
  }

  private[zio] final class RaceWith[R, EL, ER, E, A, B, C](
    val left: () => ZIO[R, EL, A],
    val right: () => ZIO[R, ER, B],
    val leftWins: (Exit[EL, A], Fiber[ER, B]) => ZIO[R, E, C],
    val rightWins: (Exit[ER, B], Fiber[EL, A]) => ZIO[R, E, C],
    val scope: () => Option[ZScope[Exit[Any, Any]]]
  ) extends ZIO[R, E, C] {
    override def tag: Int = Tags.RaceWith
  }

  private[zio] final class Supervise[R, E, A](val zio: ZIO[R, E, A], val supervisor: () => Supervisor[Any])
      extends ZIO[R, E, A] {
    override def tag = Tags.Supervise
  }

  private[zio] final class GetForkScope[R, E, A](val f: ZScope[Exit[Any, Any]] => ZIO[R, E, A]) extends ZIO[R, E, A] {
    override def tag = Tags.GetForkScope
  }

  private[zio] final class OverrideForkScope[R, E, A](
    val zio: ZIO[R, E, A],
    val forkScope: () => Option[ZScope[Exit[Any, Any]]]
  ) extends ZIO[R, E, A] {
    override def tag = Tags.OverrideForkScope
  }

  private[zio] final class Ensuring[R, E, A](
    val zio: ZIO[R, E, A],
    val finalizer: () => ZIO[R, Nothing, Any]
  ) extends ZIO[R, E, A] {
    override def tag = Tags.Ensuring
  }

  private[zio] final class Logged(
    val message: () => String,
    val overrideLogLevel: Option[LogLevel] = None,
    val overrideRef1: FiberRef.Runtime[_] = null,
    val overrideValue1: AnyRef = null
  ) extends ZIO[Any, Nothing, Unit] {
    override def tag = Tags.Logged
  }

  private[zio] final class SetPlatform(val platform: () => Platform) extends UIO[Unit] {
    override def tag = Tags.SetPlatform
  }

  private[zio] val someFatal   = Some(LogLevel.Fatal)
  private[zio] val someError   = Some(LogLevel.Error)
  private[zio] val someWarning = Some(LogLevel.Warning)
  private[zio] val someInfo    = Some(LogLevel.Info)
  private[zio] val someDebug   = Some(LogLevel.Debug)

  private[zio] def succeedNow[A](a: A): UIO[A] = new ZIO.Succeed(a)
}
