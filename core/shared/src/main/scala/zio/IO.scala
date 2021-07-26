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

import zio.internal.{Executor, Platform}

import java.io.IOException
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

object IO {

  /**
   * @see See [[zio.ZIO.absolve]]
   */
  def absolve[E, A](v: IO[E, Either[E, A]]): IO[E, A] =
    ZIO.absolve(v)

  /**
   * @see See acquireReleaseWith [[zio.ZIO]]
   */
  def acquireReleaseWith[E, A](acquire: IO[E, A]): BracketAcquire[E, A] =
    new BracketAcquire(acquire)

  /**
   * @see See acquireReleaseWith [[zio.ZIO]]
   */
  def acquireReleaseWith[E, A, B](acquire: IO[E, A], release: A => UIO[Any], use: A => IO[E, B]): IO[E, B] =
    ZIO.acquireReleaseWith(acquire, release, use)

  /**
   * @see See acquireReleaseExitWith [[zio.ZIO]]
   */
  def acquireReleaseExitWith[E, A](acquire: IO[E, A]): ZIO.BracketExitAcquire[Any, E, A] =
    ZIO.acquireReleaseExitWith(acquire)

  /**
   * @see See acquireReleaseExitWith [[zio.ZIO]]
   */
  def acquireReleaseExitWith[E, A, B](
    acquire: IO[E, A],
    release: (A, Exit[E, B]) => UIO[Any],
    use: A => IO[E, B]
  ): IO[E, B] =
    ZIO.acquireReleaseExitWith(acquire, release, use)

  /**
   * @see See [[zio.ZIO.allowInterrupt]]
   */
  def allowInterrupt: UIO[Unit] =
    ZIO.allowInterrupt

  /**
   * @see See [[zio.ZIO.apply]]
   */
  def apply[A](a: => A): Task[A] = ZIO.apply(a)

  /**
   * @see See [[zio.ZIO.async]]
   */
  def async[E, A](register: (IO[E, A] => Unit) => Any, blockingOn: Fiber.Id = Fiber.Id.None): IO[E, A] =
    ZIO.async(register, blockingOn)

  /**
   * @see See [[zio.ZIO.asyncInterrupt]]
   */
  def asyncInterrupt[E, A](
    register: (IO[E, A] => Unit) => Either[Canceler[Any], IO[E, A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): IO[E, A] =
    ZIO.asyncInterrupt(register, blockingOn)

  /**
   * @see See [[zio.ZIO.asyncZIO]]
   */
  def asyncZIO[E, A](register: (IO[E, A] => Unit) => IO[E, Any]): IO[E, A] =
    ZIO.asyncZIO(register)

  /**
   * @see See [[zio.ZIO.asyncMaybe]]
   */
  def asyncMaybe[E, A](
    register: (IO[E, A] => Unit) => Option[IO[E, A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): IO[E, A] =
    ZIO.asyncMaybe(register, blockingOn)

  /**
   * @see See [[zio.ZIO.attempt]]
   */
  def attempt[A](effect: => A): Task[A] =
    ZIO.attempt(effect)

  /**
   * @see See [[zio.ZIO.attemptBlocking]]
   */
  def attemptBlocking[A](effect: => A): Task[A] =
    ZIO.attemptBlocking(effect)

  /**
   * @see See [[zio.ZIO.attemptBlockingCancelable]]
   */
  def attemptBlockingCancelable[A](effect: => A)(cancel: UIO[Unit]): Task[A] =
    ZIO.attemptBlockingCancelable(effect)(cancel)

  /**
   * @see See [[zio.ZIO.attemptBlockingIO]]
   */
  def attemptBlockingIO[A](effect: => A): IO[IOException, A] =
    ZIO.attemptBlockingIO(effect)

  /**
   * @see See [[zio.ZIO.attemptBlockingInterrupt]]
   */
  def attemptBlockingInterrupt[A](effect: => A): Task[A] =
    ZIO.attemptBlockingInterrupt(effect)

  /**
   * @see See [[zio.ZIO.blocking]]
   */
  def blocking[E, A](zio: IO[E, A]): IO[E, A] =
    ZIO.blocking(zio)

  /**
   * @see See [[zio.ZIO.blockingExecutor]]
   */
  def blockingExecutor: UIO[Executor] =
    ZIO.blockingExecutor

  /**
   * @see See bracket [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[E, A](acquire: IO[E, A]): BracketAcquire[E, A] =
    acquireReleaseWith(acquire)

  /**
   * @see See bracket [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[E, A, B](acquire: IO[E, A], release: A => UIO[Any], use: A => IO[E, B]): IO[E, B] =
    ZIO.bracket(acquire, release, use)

  /**
   * @see See bracketExit [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[E, A](acquire: IO[E, A]): ZIO.BracketExitAcquire[Any, E, A] =
    ZIO.bracketExit(acquire)

  /**
   * @see See bracketExit [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[E, A, B](
    acquire: IO[E, A],
    release: (A, Exit[E, B]) => UIO[Any],
    use: A => IO[E, B]
  ): IO[E, B] =
    ZIO.bracketExit(acquire, release, use)

  /**
   * @see See [[zio.ZIO.checkInterruptible]]
   */
  def checkInterruptible[E, A](f: InterruptStatus => IO[E, A]): IO[E, A] =
    ZIO.checkInterruptible(f)

  /**
   * @see See [[zio.ZIO.checkTraced]]
   */
  def checkTraced[E, A](f: TracingStatus => IO[E, A]): IO[E, A] =
    ZIO.checkTraced(f)

  /**
   * @see See [[zio.ZIO.collect]]
   */
  def collect[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collect(in)(f)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def collectAll[E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[IO[E, A]]
  )(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:Set*]]]
   */
  def collectAll[E, A](in: Set[IO[E, A]]): IO[E, Set[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:Array*]]]
   */
  def collectAll[E, A: ClassTag](in: Array[IO[E, A]]): IO[E, Array[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:zio\.NonEmptyChunk*]]]
   */
  def collectAll[E, A](in: NonEmptyChunk[IO[E, A]]): IO[E, NonEmptyChunk[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll_[R,E,A](in:Iterable*]]]
   */
  @deprecated("use collectAllDiscard", "2.0.0")
  def collectAll_[E, A](in: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAll_(in)

  /**
   * @see See [[[zio.ZIO.collectAllDiscard[R,E,A](in:Iterable*]]]
   */
  def collectAllDiscard[E, A](in: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAllDiscard(in)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def collectAllPar[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[IO[E, A]]
  )(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:Set*]]]
   */
  def collectAllPar[E, A](as: Set[IO[E, A]]): IO[E, Set[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:Array*]]]
   */
  def collectAllPar[E, A: ClassTag](as: Array[IO[E, A]]): IO[E, Array[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:zio\.NonEmptyChunk*]]]
   */
  def collectAllPar[E, A](as: NonEmptyChunk[IO[E, A]]): IO[E, NonEmptyChunk[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use collectAllParDiscard", "2.0.0")
  def collectAllPar_[E, A](in: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAllPar_(in)

  /**
   * @see See [[[zio.ZIO.collectAllParDiscard[R,E,A](as:Iterable*]]]
   */
  def collectAllParDiscard[E, A](in: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAllParDiscard(in)

  /**
   * @see See [[zio.ZIO.collectAllParN]]
   */
  def collectAllParN[E, A, Collection[+Element] <: Iterable[Element]](
    n: Int
  )(as: Collection[IO[E, A]])(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.collectAllParN(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllParN_]]
   */
  @deprecated("use collectAllParNDiscard", "2.0.0")
  def collectAllParN_[E, A](n: Int)(as: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAllParN_(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllParNDiscard]]
   */
  def collectAllParNDiscard[E, A](n: Int)(as: Iterable[IO[E, A]]): IO[E, Unit] =
    ZIO.collectAllParNDiscard(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllSuccesses]]
   */
  def collectAllSuccesses[E, A, Collection[+Element] <: Iterable[Element]](
    in: Collection[IO[E, A]]
  )(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccesses(in)

  /**
   * @see See [[zio.ZIO.collectAllSuccessesPar]]
   */
  def collectAllSuccessesPar[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[IO[E, A]]
  )(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccessesPar(as)

  /**
   * @see See [[zio.ZIO.collectAllSuccessesParN]]
   */
  def collectAllSuccessesParN[E, A, Collection[+Element] <: Iterable[Element]](
    n: Int
  )(as: Collection[IO[E, A]])(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccessesParN(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllWith]]
   */
  def collectAllWith[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[IO[E, A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[IO[E, A]], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collectAllWith(in)(f)

  /**
   * @see See [[zio.ZIO.collectAllWithPar]]
   */
  def collectAllWithPar[E, A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[IO[E, A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[IO[E, A]], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collectAllWithPar(as)(f)

  /**
   * @see See [[zio.ZIO.collectAllWithParN]]
   */
  def collectAllWithParN[E, A, B, Collection[+Element] <: Iterable[Element]](n: Int)(
    as: Collection[IO[E, A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[IO[E, A]], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collectAllWithParN(n)(as)(f)

  /**
   * @see See [[zio.ZIO.collectFirst]]
   */
  def collectFirst[E, A, B](as: Iterable[A])(f: A => IO[E, Option[B]]): IO[E, Option[B]] =
    ZIO.collectFirst(as)(f)

  /**
   * @see See [[zio.ZIO.collectPar]]
   */
  def collectPar[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collectPar(in)(f)

  /**
   * @see See [[zio.ZIO.collectParN]]
   */
  def collectParN[E, A, B, Collection[+Element] <: Iterable[Element]](n: Int)(
    in: Collection[A]
  )(f: A => IO[Option[E], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.collectParN(n)(in)(f)

  /**
   * @see See [[zio.ZIO.cond]]
   */
  def cond[E, A](predicate: Boolean, result: => A, error: => E): IO[E, A] =
    ZIO.cond(predicate, result, error)

  /**
   * @see See [[zio.ZIO.debug]]
   */
  def debug(value: Any): UIO[Unit] =
    ZIO.debug(value)

  /**
   * @see See [[zio.ZIO.descriptor]]
   */
  def descriptor: UIO[Fiber.Descriptor] = ZIO.descriptor

  /**
   * @see See [[zio.ZIO.descriptorWith]]
   */
  def descriptorWith[E, A](f: Fiber.Descriptor => IO[E, A]): IO[E, A] =
    ZIO.descriptorWith(f)

  /**
   * @see See [[zio.ZIO.die]]
   */
  def die(t: => Throwable): UIO[Nothing] = ZIO.die(t)

  /**
   * @see See [[zio.ZIO.dieMessage]]
   */
  def dieMessage(message: => String): UIO[Nothing] = ZIO.dieMessage(message)

  /**
   * @see See [[zio.ZIO.done]]
   */
  def done[E, A](r: => Exit[E, A]): IO[E, A] = ZIO.done(r)

  /**
   * @see See [[zio.ZIO.effect]]
   */
  @deprecated("use attempt", "2.0.0")
  def effect[A](effect: => A): Task[A] =
    ZIO.effect(effect)

  /**
   * @see See [[zio.ZIO.effectAsync]]
   */
  @deprecated("use async", "2.0.0")
  def effectAsync[E, A](register: (IO[E, A] => Unit) => Any, blockingOn: Fiber.Id = Fiber.Id.None): IO[E, A] =
    ZIO.effectAsync(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectAsyncInterrupt]]
   */
  @deprecated("use asyncInterrupt", "2.0.0")
  def effectAsyncInterrupt[E, A](
    register: (IO[E, A] => Unit) => Either[Canceler[Any], IO[E, A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): IO[E, A] =
    ZIO.effectAsyncInterrupt(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectAsyncM]]
   */
  @deprecated("use asyncZIO", "2.0.0")
  def effectAsyncM[E, A](register: (IO[E, A] => Unit) => IO[E, Any]): IO[E, A] =
    ZIO.effectAsyncM(register)

  /**
   * @see See [[zio.ZIO.effectAsyncMaybe]]
   */
  @deprecated("use asyncMaybe", "2.0.0")
  def effectAsyncMaybe[E, A](
    register: (IO[E, A] => Unit) => Option[IO[E, A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): IO[E, A] =
    ZIO.effectAsyncMaybe(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectBlocking]]
   */
  @deprecated("use attemptBlocking", "2.0.0")
  def effectBlocking[A](effect: => A): Task[A] =
    ZIO.effectBlocking(effect)

  /**
   * @see See [[zio.ZIO.effectBlockingCancelable]]
   */
  @deprecated("use attemptBlockingCancelable", "2.0.0")
  def effectBlockingCancelable[A](effect: => A)(cancel: UIO[Unit]): Task[A] =
    ZIO.effectBlockingCancelable(effect)(cancel)

  /**
   * @see See [[zio.ZIO.effectBlockingIO]]
   */
  @deprecated("use attemptBlockingIO", "2.0.0")
  def effectBlockingIO[A](effect: => A): IO[IOException, A] =
    ZIO.effectBlockingIO(effect)

  /**
   * @see See [[zio.ZIO.effectBlockingInterrupt]]
   */
  @deprecated("use attemptBlockingInterrupt", "2.0.0")
  def effectBlockingInterrupt[A](effect: => A): Task[A] =
    ZIO.effectBlockingInterrupt(effect)

  /**
   * @see [[zio.ZIO.effectSuspend]]
   */
  @deprecated("use suspend", "2.0.0")
  def effectSuspend[A](io: => IO[Throwable, A]): IO[Throwable, A] =
    ZIO.effectSuspend(io)

  /**
   * @see [[zio.ZIO.effectSuspendWith]]
   */
  @deprecated("use suspendWith", "2.0.0")
  def effectSuspendWith[A](p: (Platform, Fiber.Id) => IO[Throwable, A]): IO[Throwable, A] =
    ZIO.effectSuspendWith(p)

  /**
   * @see See [[zio.ZIO.effectSuspendTotal]]
   */
  @deprecated("use suspendSucceed", "2.0.0")
  def effectSuspendTotal[E, A](io: => IO[E, A]): IO[E, A] =
    ZIO.effectSuspendTotal(io)

  /**
   * @see See [[zio.ZIO.effectSuspendTotalWith]]
   */
  @deprecated("use suspendSucceedWith", "2.0.0")
  def effectSuspendTotalWith[E, A](p: (Platform, Fiber.Id) => IO[E, A]): IO[E, A] =
    ZIO.effectSuspendTotalWith(p)

  /**
   * @see See [[zio.ZIO.effectTotal]]
   */
  @deprecated("use succeed", "2.0.0")
  def effectTotal[A](effect: => A): UIO[A] =
    ZIO.effectTotal(effect)

  /**
   * @see See [[zio.ZIO.executor]]
   */
  def executor: UIO[Executor] =
    ZIO.executor

  /**
   * @see See [[zio.ZIO.exists]]
   */
  def exists[E, A](as: Iterable[A])(f: A => IO[E, Boolean]): IO[E, Boolean] =
    ZIO.exists(as)(f)

  /**
   * @see See [[zio.ZIO.fail]]
   */
  def fail[E](error: => E): IO[E, Nothing] = ZIO.fail(error)

  /**
   * @see See [[zio.ZIO.failCause]]
   */
  def failCause[E](cause: => Cause[E]): IO[E, Nothing] =
    ZIO.failCause(cause)

  /**
   * @see See [[zio.ZIO.failCauseWith]]
   */
  def failCauseWith[E](function: (() => ZTrace) => Cause[E]): IO[E, Nothing] =
    ZIO.failCauseWith(function)

  /**
   * @see [[zio.ZIO.fiberId]]
   */
  val fiberId: UIO[Fiber.Id] = ZIO.fiberId

  /**
   * @see [[zio.ZIO.filter[R,E,A,Collection*]]
   */
  def filter[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => IO[E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.filter(as)(f)

  /**
   * @see [[[zio.ZIO.filter[R,E,A](as:Set*]]]
   */
  def filter[E, A](as: Set[A])(f: A => IO[E, Boolean]): IO[E, Set[A]] =
    ZIO.filter(as)(f)

  /**
   * @see [[zio.ZIO.filterPar[R,E,A,Collection*]]
   */
  def filterPar[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => IO[E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.filterPar(as)(f)

  /**
   * @see [[[zio.ZIO.filterPar[R,E,A](as:Set*]]]
   */
  def filterPar[E, A](as: Set[A])(f: A => IO[E, Boolean]): IO[E, Set[A]] =
    ZIO.filterPar(as)(f)

  /**
   * @see [[zio.ZIO.filterNot[R,E,A,Collection*]]
   */
  def filterNot[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => IO[E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.filterNot(as)(f)

  /**
   * @see [[[zio.ZIO.filterNot[R,E,A](as:Set*]]]
   */
  def filterNot[E, A](as: Set[A])(f: A => IO[E, Boolean]): IO[E, Set[A]] =
    ZIO.filterNot(as)(f)

  /**
   * @see [[zio.ZIO.filterNotPar[R,E,A,Collection*]]
   */
  def filterNotPar[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => IO[E, Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): IO[E, Collection[A]] =
    ZIO.filterNotPar(as)(f)

  /**
   * @see [[[zio.ZIO.filterNotPar[R,E,A](as:Set*]]]
   */
  def filterNotPar[E, A](as: Set[A])(f: A => IO[E, Boolean]): IO[E, Set[A]] =
    ZIO.filterNotPar(as)(f)

  /**
   * @see See [[zio.ZIO.firstSuccessOf]]
   */
  def firstSuccessOf[E, A](
    io: IO[E, A],
    rest: Iterable[IO[E, A]]
  ): IO[E, A] = ZIO.firstSuccessOf(io, rest)

  /**
   * @see See [[zio.ZIO.flatten]]
   */
  def flatten[E, A](io: IO[E, IO[E, A]]): IO[E, A] =
    ZIO.flatten(io)

  /**
   * @see See [[zio.ZIO.foldLeft]]
   */
  def foldLeft[E, S, A](in: Iterable[A])(zero: S)(f: (S, A) => IO[E, S]): IO[E, S] =
    ZIO.foldLeft(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.foldRight]]
   */
  def foldRight[E, S, A](in: Iterable[A])(zero: S)(f: (A, S) => IO[E, S]): IO[E, S] =
    ZIO.foldRight(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.forall]]
   */
  def forall[E, A](as: Iterable[A])(f: A => IO[E, Boolean]): IO[E, Boolean] =
    ZIO.forall(as)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def foreach[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:Set*]]]
   */
  def foreach[E, A, B](in: Set[A])(f: A => IO[E, B]): IO[E, Set[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:Array*]]]
   */
  def foreach[E, A, B: ClassTag](in: Array[A])(f: A => IO[E, B]): IO[E, Array[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,Key,Key2,Value,Value2](map:Map*]]]
   */
  def foreach[E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => IO[E, (Key2, Value2)]): IO[E, Map[Key2, Value2]] =
    ZIO.foreach(map)(f)

  /**
   * @see See [[zio.ZIO.foreach[R,E,A,B](in:Option*]]]
   */
  def foreach[E, A, B](in: Option[A])(f: A => IO[E, B]): IO[E, Option[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:zio\.NonEmptyChunk*]]]
   */
  def foreach[E, A, B](in: NonEmptyChunk[A])(f: A => IO[E, B]): IO[E, NonEmptyChunk[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[zio.ZIO.foreachExec]]
   */
  final def foreachExec[E, A, B, Collection[+Element] <: Iterable[Element]](as: Collection[A])(
    exec: ExecutionStrategy
  )(f: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.foreachExec(as)(exec)(f)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def foreachPar[E, A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(fn: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:Set*]]]
   */
  def foreachPar[E, A, B](as: Set[A])(fn: A => IO[E, B]): IO[E, Set[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:Array*]]]
   */
  def foreachPar[E, A, B: ClassTag](as: Array[A])(fn: A => IO[E, B]): IO[E, Array[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,Key,Key2,Value,Value2](map:Map*]]]
   */
  def foreachPar[E, Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => IO[E, (Key2, Value2)]): IO[E, Map[Key2, Value2]] =
    ZIO.foreachPar(map)(f)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:zio\.NonEmptyChunk*]]]
   */
  def foreachPar[E, A, B](as: NonEmptyChunk[A])(fn: A => IO[E, B]): IO[E, NonEmptyChunk[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[zio.ZIO.foreachParN]]
   */
  def foreachParN[E, A, B, Collection[+Element] <: Iterable[Element]](n: Int)(
    as: Collection[A]
  )(fn: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): IO[E, Collection[B]] =
    ZIO.foreachParN(n)(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreach_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use foreachDiscard", "2.0.0")
  def foreach_[E, A](as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreach_(as)(f)

  /**
   * @see See [[[zio.ZIO.foreachDiscard[R,E,A](as:Iterable*]]]
   */
  def foreachDiscard[E, A](as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreachDiscard(as)(f)

  /**
   * @see See [[[zio.ZIO.foreachPar_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use foreachParDiscard", "2.0.0")
  def foreachPar_[E, A, B](as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreachPar_(as)(f)

  /**
   * @see See [[[zio.ZIO.foreachParDiscard[R,E,A](as:Iterable*]]]
   */
  def foreachParDiscard[E, A, B](as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreachParDiscard(as)(f)

  /**
   * @see See [[zio.ZIO.foreachParN_]]
   */
  @deprecated("use foreachParNDiscard", "2.0.0")
  def foreachParN_[E, A, B](n: Int)(as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreachParN_(n)(as)(f)

  /**
   * @see See [[zio.ZIO.foreachParNDiscard]]
   */
  def foreachParNDiscard[E, A, B](n: Int)(as: Iterable[A])(f: A => IO[E, Any]): IO[E, Unit] =
    ZIO.foreachParNDiscard(n)(as)(f)

  /**
   * @see See [[zio.ZIO.forkAll]]
   */
  def forkAll[E, A, Collection[+Element] <: Iterable[Element]](
    as: Collection[IO[E, A]]
  )(implicit bf: BuildFrom[Collection[IO[E, A]], A, Collection[A]]): UIO[Fiber[E, Collection[A]]] =
    ZIO.forkAll(as)

  /**
   * @see See [[zio.ZIO.forkAll_]]
   */
  @deprecated("use forkAllDiscard", "2.0.0")
  def forkAll_[E, A](as: Iterable[IO[E, A]]): UIO[Unit] =
    ZIO.forkAll_(as)

  /**
   * @see See [[zio.ZIO.forkAllDiscard]]
   */
  def forkAllDiscard[E, A](as: Iterable[IO[E, A]]): UIO[Unit] =
    ZIO.forkAllDiscard(as)

  /**
   * Constructs a `IO` value of the appropriate type for the specified input.
   */
  def from[Input](input: => Input)(implicit
    constructor: ZIO.ZIOConstructor[Any, Any, Input]
  ): ZIO[constructor.OutEnvironment, constructor.OutError, constructor.OutSuccess] =
    constructor.make(input)

  /**
   * @see See [[zio.ZIO.fromEither]]
   */
  def fromEither[E, A](v: => Either[E, A]): IO[E, A] =
    ZIO.fromEither(v)

  /**
   * @see See [[zio.ZIO.fromFiber]]
   */
  def fromFiber[E, A](fiber: => Fiber[E, A]): IO[E, A] =
    ZIO.fromFiber(fiber)

  /**
   * @see See [[zio.ZIO.fromFiberM]]
   */
  @deprecated("use fromFiberZIO", "2.0.0")
  def fromFiberM[E, A](fiber: IO[E, Fiber[E, A]]): IO[E, A] =
    ZIO.fromFiberM(fiber)

  /**
   * @see See [[zio.ZIO.fromFiberZIO]]
   */
  def fromFiberZIO[E, A](fiber: IO[E, Fiber[E, A]]): IO[E, A] =
    ZIO.fromFiberZIO(fiber)

  /**
   * @see [[zio.ZIO.fromFunction]]
   */
  def fromFunction[A](f: Any => A): IO[Nothing, A] = ZIO.fromFunction(f)

  /**
   * @see [[zio.ZIO.fromFunctionEither]]
   */
  def fromFunctionEither[E, A](f: Any => Either[E, A]): IO[E, A] =
    ZIO.fromFunctionEither(f)

  /**
   * @see [[zio.ZIO.fromFunctionFuture]]
   */
  def fromFunctionFuture[A](f: Any => scala.concurrent.Future[A]): Task[A] =
    ZIO.fromFunctionFuture(f)

  /**
   * @see [[zio.ZIO.fromFunctionM]]
   */
  @deprecated("use fromFunctionZIO", "2.0.0")
  def fromFunctionM[E, A](f: Any => IO[E, A]): IO[E, A] =
    ZIO.fromFunctionM(f)

  /**
   * @see [[zio.ZIO.fromFunctionZIO]]
   */
  def fromFunctionZIO[E, A](f: Any => IO[E, A]): IO[E, A] =
    ZIO.fromFunctionZIO(f)

  /**
   * @see See [[zio.ZIO.fromFuture]]
   */
  def fromFuture[A](make: ExecutionContext => scala.concurrent.Future[A]): Task[A] =
    ZIO.fromFuture(make)

  /**
   * @see See [[zio.ZIO.fromFutureInterrupt]]
   */
  def fromFutureInterrupt[A](make: ExecutionContext => scala.concurrent.Future[A]): Task[A] =
    ZIO.fromFutureInterrupt(make)

  /**
   * @see See [[zio.ZIO.fromOption]]
   */
  def fromOption[A](v: => Option[A]): IO[Option[Nothing], A] = ZIO.fromOption(v)

  /**
   * @see See [[zio.ZIO.getOrFailUnit]]
   */
  def getOrFailUnit[A](v: => Option[A]): IO[Unit, A] = ZIO.getOrFailUnit(v)

  /**
   * @see See [[zio.ZIO.fromTry]]
   */
  def fromTry[A](value: => scala.util.Try[A]): Task[A] =
    ZIO.fromTry(value)

  /**
   * @see See [[zio.ZIO.halt]]
   */
  @deprecated("use failCause", "2.0.0")
  def halt[E](cause: => Cause[E]): IO[E, Nothing] =
    ZIO.halt(cause)

  /**
   * @see See [[zio.ZIO.haltWith]]
   */
  @deprecated("use failCauseWith", "2.0.0")
  def haltWith[E](function: (() => ZTrace) => Cause[E]): IO[E, Nothing] =
    ZIO.haltWith(function)

  /**
   * @see [[zio.ZIO.identity]]
   */
  def identity: IO[Nothing, Any] = ZIO.identity

  /**
   * @see [[zio.ZIO.ifM]]
   */
  @deprecated("use ifZIO", "2.0.0")
  def ifM[E](b: IO[E, Boolean]): ZIO.IfZIO[Any, E] =
    ZIO.ifM(b)

  /**
   * @see [[zio.ZIO.ifZIO]]
   */
  def ifZIO[E](b: IO[E, Boolean]): ZIO.IfZIO[Any, E] =
    ZIO.ifZIO(b)

  /**
   * @see See [[zio.ZIO.interrupt]]
   */
  val interrupt: UIO[Nothing] = ZIO.interrupt

  /**
   * @see See [[zio.ZIO.interruptAs]]
   */
  def interruptAs(fiberId: => Fiber.Id): UIO[Nothing] = ZIO.interruptAs(fiberId)

  /**
   * @see See [[zio.ZIO.interruptible]]
   */
  def interruptible[E, A](io: IO[E, A]): IO[E, A] =
    ZIO.interruptible(io)

  /**
   * @see See [[zio.ZIO.interruptibleMask]]
   */
  def interruptibleMask[E, A](k: ZIO.InterruptStatusRestore => IO[E, A]): IO[E, A] =
    ZIO.interruptibleMask(k)

  /**
   * @see See [[zio.ZIO.iterate]]
   */
  def iterate[E, S](initial: S)(cont: S => Boolean)(body: S => IO[E, S]): IO[E, S] =
    ZIO.iterate(initial)(cont)(body)

  /**
   *  @see See [[zio.ZIO.left]]
   */
  def left[E, A](a: => A): IO[E, Either[A, Nothing]] = ZIO.left(a)

  /**
   * @see See [[zio.ZIO.lock]]
   */
  def lock[E, A](executor: => Executor)(io: IO[E, A]): IO[E, A] =
    ZIO.lock(executor)(io)

  /**
   *  @see See [[zio.ZIO.loop]]
   */
  def loop[E, A, S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => IO[E, A]): IO[E, List[A]] =
    ZIO.loop(initial)(cont, inc)(body)

  /**
   *  @see See [[zio.ZIO.loop_]]
   */
  @deprecated("use loopDiscard", "2.0.0")
  def loop_[E, S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => IO[E, Any]): IO[E, Unit] =
    ZIO.loop_(initial)(cont, inc)(body)

  /**
   *  @see See [[zio.ZIO.loopDiscard]]
   */
  def loopDiscard[E, S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => IO[E, Any]): IO[E, Unit] =
    ZIO.loopDiscard(initial)(cont, inc)(body)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C]*]]
   */
  def mapN[E, A, B, C](io1: IO[E, A], io2: IO[E, B])(f: (A, B) => C): IO[E, C] =
    ZIO.mapN(io1, io2)(f)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C,D]*]]
   */
  def mapN[E, A, B, C, D](io1: IO[E, A], io2: IO[E, B], io3: IO[E, C])(f: (A, B, C) => D): IO[E, D] =
    ZIO.mapN(io1, io2, io3)(f)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C,D,F]*]]
   */
  def mapN[E, A, B, C, D, F](io1: IO[E, A], io2: IO[E, B], io3: IO[E, C], io4: IO[E, D])(
    f: (A, B, C, D) => F
  ): IO[E, F] =
    ZIO.mapN(io1, io2, io3, io4)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C]*]]
   */
  def mapParN[E, A, B, C](io1: IO[E, A], io2: IO[E, B])(f: (A, B) => C): IO[E, C] =
    ZIO.mapParN(io1, io2)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C,D]*]]
   */
  def mapParN[E, A, B, C, D](io1: IO[E, A], io2: IO[E, B], io3: IO[E, C])(f: (A, B, C) => D): IO[E, D] =
    ZIO.mapParN(io1, io2, io3)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C,D,F]*]]
   */
  def mapParN[E, A, B, C, D, F](io1: IO[E, A], io2: IO[E, B], io3: IO[E, C], io4: IO[E, D])(
    f: (A, B, C, D) => F
  ): IO[E, F] =
    ZIO.mapParN(io1, io2, io3, io4)(f)

  /**
   * @see See [[zio.ZIO.memoize]]
   */
  def memoize[E, A, B](f: A => IO[E, B]): UIO[A => IO[E, B]] =
    ZIO.memoize(f)

  /**
   * @see See [[zio.ZIO.mergeAll]]
   */
  def mergeAll[E, A, B](in: Iterable[IO[E, A]])(zero: B)(f: (B, A) => B): IO[E, B] =
    ZIO.mergeAll(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.mergeAllPar]]
   */
  def mergeAllPar[E, A, B](in: Iterable[IO[E, A]])(zero: B)(f: (B, A) => B): IO[E, B] =
    ZIO.mergeAllPar(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.never]]
   */
  val never: UIO[Nothing] = ZIO.never

  /**
   * @see See [[zio.ZIO.none]]
   */
  val none: UIO[Option[Nothing]] = ZIO.none

  /**
   * @see See [[zio.ZIO.noneOrFail]]
   */
  def noneOrFail[E](o: Option[E]): IO[E, Unit] =
    ZIO.noneOrFail(o)

  /**
   * @see See [[zio.ZIO.noneOrFailWith]]
   */
  def noneOrFailWith[E, O](o: Option[O])(f: O => E): IO[E, Unit] =
    ZIO.noneOrFailWith(o)(f)

  /**
   *  @see See [[zio.ZIO.not]]
   */
  def not[E](effect: IO[E, Boolean]): IO[E, Boolean] =
    ZIO.not(effect)

  /**
   * @see See [[zio.ZIO.partition]]
   */
  def partition[E, A, B](in: Iterable[A])(f: A => IO[E, B])(implicit ev: CanFail[E]): UIO[(Iterable[E], Iterable[B])] =
    ZIO.partition(in)(f)

  /**
   * @see See [[zio.ZIO.partitionPar]]
   */
  def partitionPar[E, A, B](
    in: Iterable[A]
  )(f: A => IO[E, B])(implicit ev: CanFail[E]): UIO[(Iterable[E], Iterable[B])] =
    ZIO.partitionPar(in)(f)

  /**
   * @see See [[zio.ZIO.partitionParN]]
   */
  def partitionParN[E, A, B](
    n: Int
  )(in: Iterable[A])(f: A => IO[E, B])(implicit ev: CanFail[E]): UIO[(Iterable[E], Iterable[B])] =
    ZIO.partitionParN(n)(in)(f)

  /**
   * @see See [[zio.ZIO.raceAll]]
   */
  def raceAll[E, A](io: IO[E, A], ios: Iterable[IO[E, A]]): IO[E, A] = ZIO.raceAll(io, ios)

  /**
   * @see See [[zio.ZIO.reduceAll]]
   */
  def reduceAll[E, A](a: IO[E, A], as: Iterable[IO[E, A]])(f: (A, A) => A): IO[E, A] =
    ZIO.reduceAll(a, as)(f)

  /**
   * @see See [[zio.ZIO.reduceAllPar]]
   */
  def reduceAllPar[E, A](a: IO[E, A], as: Iterable[IO[E, A]])(f: (A, A) => A): IO[E, A] =
    ZIO.reduceAllPar(a, as)(f)

  /**
   * @see See [[zio.ZIO.replicate]]
   */
  def replicate[E, A](n: Int)(effect: IO[E, A]): Iterable[IO[E, A]] =
    ZIO.replicate(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateM]]
   */
  @deprecated("use replicateZIO", "2.0.0")
  def replicateM[E, A](n: Int)(effect: IO[E, A]): IO[E, Iterable[A]] =
    ZIO.replicateM(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateM_]]
   */
  @deprecated("use replicateZIODiscard", "2.0.0")
  def replicateM_[E, A](n: Int)(effect: IO[E, A]): IO[E, Unit] =
    ZIO.replicateM_(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateZIO]]
   */
  def replicateZIO[E, A](n: Int)(effect: IO[E, A]): IO[E, Iterable[A]] =
    ZIO.replicateZIO(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateZIODiscard]]
   */
  def replicateZIODiscard[E, A](n: Int)(effect: IO[E, A]): IO[E, Unit] =
    ZIO.replicateZIODiscard(n)(effect)

  /**
   * @see See [[zio.ZIO.require]]
   */
  def require[E, A](error: => E): IO[E, Option[A]] => IO[E, A] =
    ZIO.require[Any, E, A](error)

  /**
   * @see See [[zio.ZIO.reserve]]
   */
  def reserve[E, A, B](reservation: IO[E, Reservation[Any, E, A]])(use: A => IO[E, B]): IO[E, B] =
    ZIO.reserve(reservation)(use)

  /**
   *  @see [[zio.ZIO.right]]
   */
  def right[E, B](b: => B): IO[E, Either[Nothing, B]] = ZIO.right(b)

  /**
   * @see See [[zio.ZIO.runtime]]
   */
  def runtime: UIO[Runtime[Any]] = ZIO.runtime

  /**
   *  @see [[zio.ZIO.some]]
   */
  def some[E, A](a: => A): IO[E, Option[A]] = ZIO.some(a)

  /**
   * @see See [[zio.ZIO.succeed]]
   */
  def succeed[A](a: => A): UIO[A] = ZIO.succeed(a)

  /**
   * @see See [[zio.ZIO.succeedBlocking]]
   */
  def succeedBlocking[A](a: => A): UIO[A] =
    ZIO.succeedBlocking(a)

  /**
   * @see [[zio.ZIO.suspend]]
   */
  def suspend[A](io: => IO[Throwable, A]): IO[Throwable, A] =
    ZIO.suspend(io)

  /**
   * @see [[zio.ZIO.suspendWith]]
   */
  def suspendWith[A](p: (Platform, Fiber.Id) => IO[Throwable, A]): IO[Throwable, A] =
    ZIO.suspendWith(p)

  /**
   * @see See [[zio.ZIO.suspendSucceed]]
   */
  def suspendSucceed[E, A](io: => IO[E, A]): IO[E, A] =
    ZIO.suspendSucceed(io)

  /**
   * @see See [[zio.ZIO.suspendSucceedWith]]
   */
  def suspendSucceedWith[E, A](p: (Platform, Fiber.Id) => IO[E, A]): IO[E, A] =
    ZIO.suspendSucceedWith(p)

  /**
   * @see See [[zio.ZIO.trace]]
   */
  def trace: UIO[ZTrace] = ZIO.trace

  /**
   * @see See [[zio.ZIO.traced]]
   */
  def traced[E, A](zio: IO[E, A]): IO[E, A] = ZIO.traced(zio)

  /**
   * @see See [[zio.ZIO.unit]]
   */
  val unit: UIO[Unit] = ZIO.unit

  /**
   * @see See [[zio.ZIO.uninterruptible]]
   */
  def uninterruptible[E, A](io: IO[E, A]): IO[E, A] =
    ZIO.uninterruptible(io)

  /**
   * @see See [[zio.ZIO.uninterruptibleMask]]
   */
  def uninterruptibleMask[E, A](k: ZIO.InterruptStatusRestore => IO[E, A]): IO[E, A] =
    ZIO.uninterruptibleMask(k)

  /**
   * @see See [[zio.ZIO.unless]]
   */
  def unless[E](b: => Boolean)(zio: => IO[E, Any]): IO[E, Unit] =
    ZIO.unless(b)(zio)

  /**
   * @see See [[zio.ZIO.unlessM]]
   */
  @deprecated("use unlessZIO", "2.0.0")
  def unlessM[E](b: IO[E, Boolean]): ZIO.UnlessZIO[Any, E] =
    ZIO.unlessM(b)

  /**
   * @see See [[zio.ZIO.unlessZIO]]
   */
  def unlessZIO[E](b: IO[E, Boolean]): ZIO.UnlessZIO[Any, E] =
    ZIO.unlessZIO(b)

  /**
   * @see See [[zio.ZIO.unsandbox]]
   */
  def unsandbox[E, A](v: IO[Cause[E], A]): IO[E, A] = ZIO.unsandbox(v)

  /**
   * @see See [[zio.ZIO.untraced]]
   */
  def untraced[E, A](zio: IO[E, A]): IO[E, A] = ZIO.untraced(zio)

  /**
   * @see See [[[zio.ZIO.validate[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def validate[E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => IO[E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], ev: CanFail[E]): IO[::[E], Collection[B]] =
    ZIO.validate(in)(f)

  /**
   * @see See [[[zio.ZIO.validate[R,E,A,B](in:zio\.NonEmptyChunk*]]]
   */
  def validate[E, A, B](in: NonEmptyChunk[A])(
    f: A => IO[E, B]
  )(implicit ev: CanFail[E]): IO[::[E], NonEmptyChunk[B]] =
    ZIO.validate(in)(f)

  /**
   * @see See [[zio.ZIO.validate_]]
   */
  @deprecated("use validateDiscard", "2.0.0")
  def validate_[E, A](in: Iterable[A])(f: A => IO[E, Any])(implicit ev: CanFail[E]): IO[::[E], Unit] =
    ZIO.validate_(in)(f)

  /**
   * @see See [[zio.ZIO.validateDiscard]]
   */
  def validateDiscard[E, A](in: Iterable[A])(f: A => IO[E, Any])(implicit ev: CanFail[E]): IO[::[E], Unit] =
    ZIO.validateDiscard(in)(f)

  /**
   * @see See [[[zio.ZIO.validatePar[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def validatePar[E, A, B, Collection[+Element] <: Iterable[Element]](in: Collection[A])(
    f: A => IO[E, B]
  )(implicit bf: BuildFrom[Collection[A], B, Collection[B]], ev: CanFail[E]): IO[::[E], Collection[B]] =
    ZIO.validatePar(in)(f)

  /**
   * @see See [[[zio.ZIO.validatePar[R,E,A,B](in:zio\.NonEmptyChunk*]]]
   */
  def validatePar[E, A, B](in: NonEmptyChunk[A])(
    f: A => IO[E, B]
  )(implicit ev: CanFail[E]): IO[::[E], NonEmptyChunk[B]] =
    ZIO.validatePar(in)(f)

  /**
   * @see See [[zio.ZIO.validatePar_]]
   */
  @deprecated("use validateParDiscard", "2.0.0")
  def validatePar_[E, A](in: Iterable[A])(f: A => IO[E, Any])(implicit ev: CanFail[E]): IO[::[E], Unit] =
    ZIO.validatePar_(in)(f)

  /**
   * @see See [[zio.ZIO.validateParDiscard]]
   */
  def validateParDiscard[E, A](in: Iterable[A])(f: A => IO[E, Any])(implicit ev: CanFail[E]): IO[::[E], Unit] =
    ZIO.validateParDiscard(in)(f)

  /**
   * @see See [[zio.ZIO.validateFirst]]
   */
  def validateFirst[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], E, Collection[E]], ev: CanFail[E]): IO[Collection[E], B] =
    ZIO.validateFirst(in)(f)

  /**
   * @see See [[zio.ZIO.validateFirstPar]]
   */
  def validateFirstPar[E, A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[E, B])(implicit bf: BuildFrom[Collection[A], E, Collection[E]], ev: CanFail[E]): IO[Collection[E], B] =
    ZIO.validateFirstPar(in)(f)

  /**
   * @see See [[zio.ZIO.when]]
   */
  def when[E](b: => Boolean)(io: => IO[E, Any]): IO[E, Unit] =
    ZIO.when(b)(io)

  /**
   * @see See [[zio.ZIO.whenCase]]
   */
  def whenCase[E, A](a: => A)(pf: PartialFunction[A, IO[E, Any]]): IO[E, Unit] =
    ZIO.whenCase(a)(pf)

  /**
   * @see See [[zio.ZIO.whenCaseM]]
   */
  @deprecated("use whenCaseZIO", "2.0.0")
  def whenCaseM[E, A](a: IO[E, A])(pf: PartialFunction[A, IO[E, Any]]): IO[E, Unit] =
    ZIO.whenCaseM(a)(pf)

  /**
   * @see See [[zio.ZIO.whenCaseZIO]]
   */
  def whenCaseZIO[E, A](a: IO[E, A])(pf: PartialFunction[A, IO[E, Any]]): IO[E, Unit] =
    ZIO.whenCaseZIO(a)(pf)

  /**
   * @see See [[zio.ZIO.whenM]]
   */
  @deprecated("use whenZIO", "2.0.0")
  def whenM[E](b: IO[E, Boolean]): ZIO.WhenZIO[Any, E] =
    ZIO.whenM(b)

  /**
   * @see See [[zio.ZIO.whenZIO]]
   */
  def whenZIO[E](b: IO[E, Boolean]): ZIO.WhenZIO[Any, E] =
    ZIO.whenZIO(b)

  /**
   * @see See [[zio.ZIO.yieldNow]]
   */
  val yieldNow: UIO[Unit] = ZIO.yieldNow

  class BracketAcquire_[E](private val acquire: IO[E, Any]) extends AnyVal {
    def apply(release: IO[Nothing, Any]): BracketRelease_[E] =
      new BracketRelease_(acquire, release)
  }
  class BracketRelease_[E](acquire: IO[E, Any], release: IO[Nothing, Any]) {
    def apply[E1 >: E, B](use: IO[E1, B]): IO[E1, B] =
      ZIO.acquireReleaseWith(acquire, (_: Any) => release, (_: Any) => use)
  }

  class BracketAcquire[E, A](private val acquire: IO[E, A]) extends AnyVal {
    def apply(release: A => IO[Nothing, Any]): BracketRelease[E, A] =
      new BracketRelease[E, A](acquire, release)
  }
  class BracketRelease[E, A](acquire: IO[E, A], release: A => IO[Nothing, Any]) {
    def apply[E1 >: E, B](use: A => IO[E1, B]): IO[E1, B] =
      ZIO.acquireReleaseWith(acquire, release, use)
  }

  private[zio] def succeedNow[A](a: A): UIO[A] = ZIO.succeedNow(a)
}
