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

import scala.reflect.ClassTag

object UIO {

  /**
   * @see See [[zio.ZIO.absolve]]
   */
  def absolve[A](v: UIO[Either[Nothing, A]]): UIO[A] =
    ZIO.absolve(v)

  /**
   * @see See acquireReleaseWith [[zio.ZIO]]
   */
  def acquireReleaseWith[A](acquire: UIO[A]): ZIO.BracketAcquire[Any, Nothing, A] =
    ZIO.acquireReleaseWith(acquire)

  /**
   * @see See acquireReleaseWith [[zio.ZIO]]
   */
  def acquireReleaseWith[A, B](acquire: UIO[A], release: A => UIO[Any], use: A => UIO[B]): UIO[B] =
    ZIO.acquireReleaseWith(acquire, release, use)

  /**
   * @see See acquireReleaseExitWith [[zio.ZIO]]
   */
  def acquireReleaseExitWith[A](acquire: UIO[A]): ZIO.BracketExitAcquire[Any, Nothing, A] =
    ZIO.acquireReleaseExitWith(acquire)

  /**
   * @see See acquireReleaseExitWith [[zio.ZIO]]
   */
  def acquireReleaseExitWith[A, B](
    acquire: UIO[A],
    release: (A, Exit[Nothing, B]) => UIO[Any],
    use: A => UIO[B]
  ): UIO[B] =
    ZIO.acquireReleaseExitWith(acquire, release, use)

  /**
   * @see See [[zio.ZIO.allowInterrupt]]
   */
  def allowInterrupt: UIO[Unit] =
    ZIO.allowInterrupt

  /**
   * @see See [[zio.ZIO.apply]]
   */
  def apply[A](a: => A): UIO[A] =
    ZIO.succeed(a)

  /**
   * @see See [[zio.ZIO.async]]
   */
  def async[A](register: (UIO[A] => Unit) => Any, blockingOn: Fiber.Id = Fiber.Id.None): UIO[A] =
    ZIO.async(register, blockingOn)

  /**
   * @see See [[zio.ZIO.asyncMaybe]]
   */
  def asyncMaybe[A](
    register: (UIO[A] => Unit) => Option[UIO[A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): UIO[A] =
    ZIO.asyncMaybe(register, blockingOn)

  /**
   * @see See [[zio.ZIO.asyncZIO]]
   */
  def asyncZIO[A](register: (UIO[A] => Unit) => UIO[Any]): UIO[A] =
    ZIO.asyncZIO(register)

  /**
   * @see See [[zio.ZIO.asyncInterrupt]]
   */
  def asyncInterrupt[A](
    register: (UIO[A] => Unit) => Either[Canceler[Any], UIO[A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): UIO[A] =
    ZIO.asyncInterrupt(register, blockingOn)

  /**
   * @see See [[zio.ZIO.blocking]]
   */
  def blocking[A](zio: UIO[A]): UIO[A] =
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
  def bracket[A](acquire: UIO[A]): ZIO.BracketAcquire[Any, Nothing, A] =
    ZIO.bracket(acquire)

  /**
   * @see See bracket [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseWith", "2.0.0")
  def bracket[A, B](acquire: UIO[A], release: A => UIO[Any], use: A => UIO[B]): UIO[B] =
    ZIO.bracket(acquire, release, use)

  /**
   * @see See bracketExit [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[A](acquire: UIO[A]): ZIO.BracketExitAcquire[Any, Nothing, A] =
    ZIO.bracketExit(acquire)

  /**
   * @see See bracketExit [[zio.ZIO]]
   */
  @deprecated("use acquireReleaseExitWith", "2.0.0")
  def bracketExit[A, B](acquire: UIO[A], release: (A, Exit[Nothing, B]) => UIO[Any], use: A => UIO[B]): UIO[B] =
    ZIO.bracketExit(acquire, release, use)

  /**
   * @see See [[zio.ZIO.checkInterruptible]]
   */
  def checkInterruptible[A](f: InterruptStatus => UIO[A]): UIO[A] =
    ZIO.checkInterruptible(f)

  /**
   * @see See [[zio.ZIO.checkTraced]]
   */
  def checkTraced[A](f: TracingStatus => UIO[A]): UIO[A] =
    ZIO.checkTraced(f)

  /**
   * @see See [[zio.ZIO.collect]]
   */
  def collect[A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[Option[Nothing], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collect(in)(f)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def collectAll[A, Collection[+Element] <: Iterable[Element]](
    in: Collection[UIO[A]]
  )(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:Set*]]]
   */
  def collectAll[A](in: Set[UIO[A]]): UIO[Set[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:Array*]]]
   */
  def collectAll[A: ClassTag](in: Array[UIO[A]]): UIO[Array[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:Option*]]]
   */
  def collectAll[A](in: Option[UIO[A]]): UIO[Option[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll[R,E,A](in:zio\.NonEmptyChunk*]]]
   */
  def collectAll[A](in: NonEmptyChunk[UIO[A]]): UIO[NonEmptyChunk[A]] =
    ZIO.collectAll(in)

  /**
   * @see See [[[zio.ZIO.collectAll_[R,E,A](in:Iterable*]]]
   */
  @deprecated("use collectAllDiscard", "2.0.0")
  def collectAll_[A](in: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAll_(in)

  /**
   * @see See [[[zio.ZIO.collectAllDiscard[R,E,A](in:Iterable*]]]
   */
  def collectAllDiscard[A](in: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAllDiscard(in)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def collectAllPar[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[UIO[A]]
  )(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:Set*]]]
   */
  def collectAllPar[A](as: Set[UIO[A]]): UIO[Set[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:Array*]]]
   */
  def collectAllPar[A: ClassTag](as: Array[UIO[A]]): UIO[Array[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar[R,E,A](as:zio\.NonEmptyChunk*]]]
   */
  def collectAllPar[A](as: NonEmptyChunk[UIO[A]]): UIO[NonEmptyChunk[A]] =
    ZIO.collectAllPar(as)

  /**
   * @see See [[[zio.ZIO.collectAllPar_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use collectAllParDiscard", "2.0.0")
  def collectAllPar_[A](in: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAllPar_(in)

  /**
   * @see See [[[zio.ZIO.collectAllParDiscard[R,E,A](as:Iterable*]]]
   */
  def collectAllParDiscard[A](in: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAllParDiscard(in)

  /**
   * @see See [[zio.ZIO.collectAllParN]]
   */
  def collectAllParN[A, Collection[+Element] <: Iterable[Element]](
    n: Int
  )(as: Collection[UIO[A]])(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllParN(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllParN_]]
   */
  @deprecated("use collectAllParNDiscard", "2.0.0")
  def collectAllParN_[A](n: Int)(as: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAllParN_(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllParNDiscard]]
   */
  def collectAllParNDiscard[A](n: Int)(as: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.collectAllParNDiscard(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllSuccesses]]
   */
  def collectAllSuccesses[A, Collection[+Element] <: Iterable[Element]](
    in: Collection[UIO[A]]
  )(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccesses(in)

  /**
   * @see See [[zio.ZIO.collectAllSuccessesPar]]
   */
  def collectAllSuccessesPar[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[UIO[A]]
  )(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccessesPar(as)

  /**
   * @see See [[zio.ZIO.collectAllSuccessesParN]]
   */
  def collectAllSuccessesParN[A, Collection[+Element] <: Iterable[Element]](
    n: Int
  )(as: Collection[UIO[A]])(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.collectAllSuccessesParN(n)(as)

  /**
   * @see See [[zio.ZIO.collectAllWith]]
   */
  def collectAllWith[A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[UIO[A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[UIO[A]], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collectAllWith(in)(f)

  /**
   * @see See [[zio.ZIO.collectAllWithPar]]
   */
  def collectAllWithPar[A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[UIO[A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[UIO[A]], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collectAllWithPar(as)(f)

  /**
   * @see See [[zio.ZIO.collectAllWithParN]]
   */
  def collectAllWithParN[A, B, Collection[+Element] <: Iterable[Element]](n: Int)(
    as: Collection[UIO[A]]
  )(f: PartialFunction[A, B])(implicit bf: BuildFrom[Collection[UIO[A]], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collectAllWithParN(n)(as)(f)

  /**
   * @see See [[zio.ZIO.collectFirst]]
   */
  def collectFirst[A, B](as: Iterable[A])(f: A => UIO[Option[B]]): UIO[Option[B]] =
    ZIO.collectFirst(as)(f)

  /**
   * @see See [[zio.ZIO.collectPar]]
   */
  def collectPar[A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => IO[Option[Nothing], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collectPar(in)(f)

  /**
   * @see See [[zio.ZIO.collectParN]]
   */
  def collectParN[A, B, Collection[+Element] <: Iterable[Element]](n: Int)(
    in: Collection[A]
  )(f: A => IO[Option[Nothing], B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.collectParN(n)(in)(f)

  /**
   * @see See [[zio.ZIO.debug]]
   */
  def debug(value: => Any): UIO[Unit] =
    ZIO.debug(value)

  /**
   * @see See [[zio.ZIO.descriptor]]
   */
  def descriptor: UIO[Fiber.Descriptor] = ZIO.descriptor

  /**
   * @see See [[zio.ZIO.descriptorWith]]
   */
  def descriptorWith[A](f: Fiber.Descriptor => UIO[A]): UIO[A] =
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
  def done[A](r: => Exit[Nothing, A]): UIO[A] = ZIO.done(r)

  /**
   * @see See [[zio.ZIO.effectAsync]]
   */
  @deprecated("use async", "2.0.0")
  def effectAsync[A](register: (UIO[A] => Unit) => Any, blockingOn: Fiber.Id = Fiber.Id.None): UIO[A] =
    ZIO.effectAsync(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectAsyncMaybe]]
   */
  @deprecated("use asyncMaybe", "2.0.0")
  def effectAsyncMaybe[A](
    register: (UIO[A] => Unit) => Option[UIO[A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): UIO[A] =
    ZIO.effectAsyncMaybe(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectAsyncM]]
   */
  @deprecated("use asyncZIO", "2.0.0")
  def effectAsyncM[A](register: (UIO[A] => Unit) => UIO[Any]): UIO[A] =
    ZIO.effectAsyncM(register)

  /**
   * @see See [[zio.ZIO.effectAsyncInterrupt]]
   */
  @deprecated("use asyncInterrupt", "2.0.0")
  def effectAsyncInterrupt[A](
    register: (UIO[A] => Unit) => Either[Canceler[Any], UIO[A]],
    blockingOn: Fiber.Id = Fiber.Id.None
  ): UIO[A] =
    ZIO.effectAsyncInterrupt(register, blockingOn)

  /**
   * @see See [[zio.ZIO.effectSuspendTotal]]
   */
  @deprecated("use suspendSucceed", "2.0.0")
  def effectSuspendTotal[A](uio: => UIO[A]): UIO[A] =
    ZIO.effectSuspendTotal(uio)

  /**
   * @see See [[zio.ZIO.effectSuspendTotalWith]]
   */
  @deprecated("use suspendSucceedWith", "2.0.0")
  def effectSuspendTotalWith[A](p: (Platform, Fiber.Id) => UIO[A]): UIO[A] =
    ZIO.effectSuspendTotalWith(p)

  /**
   * @see See [[zio.ZIO.effectTotal]]
   */
  @deprecated("use succeed", "2.0.0")
  def effectTotal[A](effect: => A): UIO[A] =
    ZIO.succeed(effect)

  /**
   * @see See [[zio.ZIO.executor]]
   */
  def executor: UIO[Executor] =
    ZIO.executor

  /**
   * @see See [[zio.ZIO.exists]]
   */
  def exists[A](as: Iterable[A])(f: A => UIO[Boolean]): UIO[Boolean] =
    ZIO.exists(as)(f)

  /**
   * @see See [[zio.ZIO.failCause]]
   */
  def failCause(cause: => Cause[Nothing]): UIO[Nothing] =
    ZIO.failCause(cause)

  /**
   * @see [[zio.ZIO.failCauseWith]]
   */
  def failCauseWith(function: (() => ZTrace) => Cause[Nothing]): UIO[Nothing] =
    ZIO.failCauseWith(function)

  /**
   * @see [[zio.ZIO.fiberId]]
   */
  val fiberId: UIO[Fiber.Id] = ZIO.fiberId

  /**
   * @see [[zio.ZIO.filter[R,E,A,Collection*]]
   */
  def filter[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => UIO[Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.filter(as)(f)

  /**
   * @see [[[zio.ZIO.filter[R,E,A](as:Set*]]]
   */
  def filter[A](as: Set[A])(f: A => UIO[Boolean]): UIO[Set[A]] =
    ZIO.filter(as)(f)

  /**
   * @see [[zio.ZIO.filterPar[R,E,A,Collection*]]
   */
  def filterPar[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => UIO[Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.filterPar(as)(f)

  /**
   * @see [[[zio.ZIO.filterPar[R,E,A](as:Set*]]]
   */
  def filterPar[A](as: Set[A])(f: A => UIO[Boolean]): UIO[Set[A]] =
    ZIO.filterPar(as)(f)

  /**
   * @see [[zio.ZIO.filterNot[R,E,A,Collection*]]
   */
  def filterNot[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => UIO[Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.filterNot(as)(f)

  /**
   * @see [[[zio.ZIO.filterNot[R,E,A](as:Set*]]]
   */
  def filterNot[A](as: Set[A])(f: A => UIO[Boolean]): UIO[Set[A]] =
    ZIO.filterNot(as)(f)

  /**
   * @see [[zio.ZIO.filterNotPar[R,E,A,Collection*]]
   */
  def filterNotPar[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(f: A => UIO[Boolean])(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.filterNotPar(as)(f)

  /**
   * @see [[[zio.ZIO.filterNotPar[R,E,A](as:Set*]]]
   */
  def filterNotPar[A](as: Set[A])(f: A => UIO[Boolean]): UIO[Set[A]] =
    ZIO.filterNotPar(as)(f)

  /**
   * @see [[zio.ZIO.firstSuccessOf]]
   */
  def firstSuccessOf[A](uio: UIO[A], rest: Iterable[UIO[A]]): UIO[A] = ZIO.firstSuccessOf(uio, rest)

  /**
   * @see See [[zio.ZIO.flatten]]
   */
  def flatten[A](uio: UIO[UIO[A]]): UIO[A] =
    ZIO.flatten(uio)

  /**
   * @see See [[zio.ZIO.foldLeft]]
   */
  def foldLeft[S, A](in: Iterable[A])(zero: S)(f: (S, A) => UIO[S]): UIO[S] =
    ZIO.foldLeft(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.foldRight]]
   */
  def foldRight[S, A](in: Iterable[A])(zero: S)(f: (A, S) => UIO[S]): UIO[S] =
    ZIO.foldRight(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.forall]]
   */
  def forall[A](as: Iterable[A])(f: A => UIO[Boolean]): UIO[Boolean] =
    ZIO.forall(as)(f)

  /**
   * @see See [[zio.ZIO.forkAll]]
   */
  def forkAll[A, Collection[+Element] <: Iterable[Element]](
    as: Collection[UIO[A]]
  )(implicit bf: BuildFrom[Collection[UIO[A]], A, Collection[A]]): UIO[Fiber[Nothing, Collection[A]]] =
    ZIO.forkAll(as)

  /**
   * @see See [[zio.ZIO.forkAll_]]
   */
  @deprecated("use forkAllDiscard", "2.0.0")
  def forkAll_[A](as: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.forkAll_(as)

  /**
   * @see See [[zio.ZIO.forkAllDiscard]]
   */
  def forkAllDiscard[A](as: Iterable[UIO[A]]): UIO[Unit] =
    ZIO.forkAllDiscard(as)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def foreach[A, B, Collection[+Element] <: Iterable[Element]](
    in: Collection[A]
  )(f: A => UIO[B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:Set*]]]
   */
  def foreach[A, B](in: Set[A])(f: A => UIO[B]): UIO[Set[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:Array*]]]
   */
  def foreach[A, B: ClassTag](in: Array[A])(f: A => UIO[B]): UIO[Array[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,Key,Key2,Value,Value2](map:Map*]]]
   */
  def foreach[Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => UIO[(Key2, Value2)]): UIO[Map[Key2, Value2]] =
    ZIO.foreach(map)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:Option*]]]
   */
  def foreach[A, B](in: Option[A])(f: A => UIO[B]): UIO[Option[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach[R,E,A,B](in:zio\.NonEmptyChunk*]]]
   */
  def foreach[A, B](in: NonEmptyChunk[A])(f: A => UIO[B]): UIO[NonEmptyChunk[B]] =
    ZIO.foreach(in)(f)

  /**
   * @see See [[[zio.ZIO.foreach_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use foreachDiscard", "2.0.0")
  def foreach_[A](as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreach_(as)(f)

  /**
   * @see See [[[zio.ZIO.foreachDiscard[R,E,A](as:Iterable*]]]
   */
  def foreachDiscard[A](as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreachDiscard(as)(f)

  /**
   * @see See [[zio.ZIO.foreachExec]]
   */
  final def foreachExec[A, B, Collection[+Element] <: Iterable[Element]](as: Collection[A])(
    exec: ExecutionStrategy
  )(f: A => UIO[B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.foreachExec(as)(exec)(f)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B,Collection[+Element]<:Iterable[Element]]*]]]
   */
  def foreachPar[A, B, Collection[+Element] <: Iterable[Element]](
    as: Collection[A]
  )(fn: A => UIO[B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:Set*]]]
   */
  def foreachPar[A, B](as: Set[A])(fn: A => UIO[B]): UIO[Set[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:Array*]]]
   */
  def foreachPar[A, B: ClassTag](as: Array[A])(fn: A => UIO[B]): UIO[Array[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,Key,Key2,Value,Value2](map:Map*]]]
   */
  def foreachPar[Key, Key2, Value, Value2](
    map: Map[Key, Value]
  )(f: (Key, Value) => UIO[(Key2, Value2)]): UIO[Map[Key2, Value2]] =
    ZIO.foreachPar(map)(f)

  /**
   * @see See [[[zio.ZIO.foreachPar[R,E,A,B](as:zio\.NonEmptyChunk*]]]
   */
  def foreachPar[A, B](as: NonEmptyChunk[A])(fn: A => UIO[B]): UIO[NonEmptyChunk[B]] =
    ZIO.foreachPar(as)(fn)

  /**
   * @see See [[[zio.ZIO.foreachPar_[R,E,A](as:Iterable*]]]
   */
  @deprecated("use foreachParDiscard", "2.0.0")
  def foreachPar_[A](as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreachPar_(as)(f)

  /**
   * @see See [[[zio.ZIO.foreachParDiscard[R,E,A](as:Iterable*]]]
   */
  def foreachParDiscard[A](as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreachParDiscard(as)(f)

  /**
   * @see See [[zio.ZIO.foreachParN]]
   */
  def foreachParN[A, B, Collection[+Element] <: Iterable[Element]](
    n: Int
  )(as: Collection[A])(fn: A => UIO[B])(implicit bf: BuildFrom[Collection[A], B, Collection[B]]): UIO[Collection[B]] =
    ZIO.foreachParN(n)(as)(fn)

  /**
   * @see See [[zio.ZIO.foreachParN_]]
   */
  @deprecated("use foreachParNDiscard", "2.0.0")
  def foreachParN_[A](n: Int)(as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreachParN_(n)(as)(f)

  /**
   * @see See [[zio.ZIO.foreachParNDiscard]]
   */
  def foreachParNDiscard[A](n: Int)(as: Iterable[A])(f: A => UIO[Any]): UIO[Unit] =
    ZIO.foreachParNDiscard(n)(as)(f)

  /**
   * Constructs a `UIO` value of the appropriate type for the specified input.
   */
  def from[Input](input: => Input)(implicit
    constructor: ZIO.ZIOConstructor[Any, Nothing, Input]
  ): ZIO[constructor.OutEnvironment, constructor.OutError, constructor.OutSuccess] =
    constructor.make(input)

  /**
   * @see See [[zio.ZIO.fromEither]]
   */
  def fromEither[A](v: => Either[Nothing, A]): UIO[A] =
    ZIO.fromEither(v)

  /**
   * @see See [[zio.ZIO.fromFiber]]
   */
  def fromFiber[A](fiber: => Fiber[Nothing, A]): UIO[A] =
    ZIO.fromFiber(fiber)

  /**
   * @see See [[zio.ZIO.fromFiberM]]
   */
  @deprecated("use fromFiberZIO", "2.0.0")
  def fromFiberM[A](fiber: UIO[Fiber[Nothing, A]]): UIO[A] =
    ZIO.fromFiberM(fiber)

  /**
   * @see See [[zio.ZIO.fromFiberZIO]]
   */
  def fromFiberZIO[A](fiber: UIO[Fiber[Nothing, A]]): UIO[A] =
    ZIO.fromFiberZIO(fiber)

  /**
   * @see See [[zio.ZIO.halt]]
   */
  @deprecated("use failCause", "2.0.0")
  def halt(cause: => Cause[Nothing]): UIO[Nothing] =
    ZIO.halt(cause)

  /**
   * @see [[zio.ZIO.haltWith]]
   */
  @deprecated("use failCauseWith", "2.0.0")
  def haltWith(function: (() => ZTrace) => Cause[Nothing]): UIO[Nothing] =
    ZIO.haltWith(function)

  /**
   * @see [[zio.ZIO.ifM]]
   */
  @deprecated("use ifZIO", "2.0.0")
  def ifM(b: UIO[Boolean]): ZIO.IfZIO[Any, Nothing] =
    ZIO.ifM(b)

  /**
   * @see [[zio.ZIO.ifZIO]]
   */
  def ifZIO(b: UIO[Boolean]): ZIO.IfZIO[Any, Nothing] =
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
  def interruptible[A](uio: UIO[A]): UIO[A] =
    ZIO.interruptible(uio)

  /**
   * @see See [[zio.ZIO.interruptibleMask]]
   */
  def interruptibleMask[A](k: ZIO.InterruptStatusRestore => UIO[A]): UIO[A] =
    ZIO.interruptibleMask(k)

  /**
   * @see See [[zio.ZIO.iterate]]
   */
  def iterate[S](initial: S)(cont: S => Boolean)(body: S => UIO[S]): UIO[S] =
    ZIO.iterate(initial)(cont)(body)

  /**
   *  @see See [[zio.ZIO.left]]
   */
  def left[A](a: => A): UIO[Either[A, Nothing]] = ZIO.left(a)

  /**
   * @see See [[zio.ZIO.lock]]
   */
  def lock[A](executor: => Executor)(uio: UIO[A]): UIO[A] =
    ZIO.lock(executor)(uio)

  /**
   *  @see See [[zio.ZIO.loop]]
   */
  def loop[A, S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => UIO[A]): UIO[List[A]] =
    ZIO.loop(initial)(cont, inc)(body)

  /**
   *  @see See [[zio.ZIO.loop_]]
   */
  @deprecated("use loopDiscard", "2.0.0")
  def loop_[S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => UIO[Any]): UIO[Unit] =
    ZIO.loop_(initial)(cont, inc)(body)

  /**
   *  @see See [[zio.ZIO.loopDiscard]]
   */
  def loopDiscard[S](initial: S)(cont: S => Boolean, inc: S => S)(body: S => UIO[Any]): UIO[Unit] =
    ZIO.loopDiscard(initial)(cont, inc)(body)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C]*]]
   */
  @deprecated("use zip", "2.0.0")
  def mapN[A, B, C](uio1: UIO[A], uio2: UIO[B])(f: (A, B) => C): UIO[C] =
    ZIO.mapN(uio1, uio2)(f)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C,D]*]]
   */
  @deprecated("use zip", "2.0.0")
  def mapN[A, B, C, D](uio1: UIO[A], uio2: UIO[B], uio3: UIO[C])(f: (A, B, C) => D): UIO[D] =
    ZIO.mapN(uio1, uio2, uio3)(f)

  /**
   *  @see [[zio.ZIO.mapN[R,E,A,B,C,D,F]*]]
   */
  @deprecated("use zip", "2.0.0")
  def mapN[A, B, C, D, F](uio1: UIO[A], uio2: UIO[B], uio3: UIO[C], uio4: UIO[D])(f: (A, B, C, D) => F): UIO[F] =
    ZIO.mapN(uio1, uio2, uio3, uio4)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C]*]]
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[A, B, C](uio1: UIO[A], uio2: UIO[B])(f: (A, B) => C): UIO[C] =
    ZIO.mapParN(uio1, uio2)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C,D]*]]
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[A, B, C, D](uio1: UIO[A], uio2: UIO[B], uio3: UIO[C])(f: (A, B, C) => D): UIO[D] =
    ZIO.mapParN(uio1, uio2, uio3)(f)

  /**
   *  @see [[zio.ZIO.mapParN[R,E,A,B,C,D,F]*]]
   */
  @deprecated("use zipPar", "2.0.0")
  def mapParN[A, B, C, D, F](uio1: UIO[A], uio2: UIO[B], uio3: UIO[C], uio4: UIO[D])(
    f: (A, B, C, D) => F
  ): UIO[F] =
    ZIO.mapParN(uio1, uio2, uio3, uio4)(f)

  /**
   * @see See [[zio.ZIO.memoize]]
   */
  def memoize[A, B](f: A => UIO[B]): UIO[A => UIO[B]] =
    ZIO.memoize(f)

  /**
   * @see See [[zio.ZIO.mergeAll]]
   */
  def mergeAll[A, B](in: Iterable[UIO[A]])(zero: B)(f: (B, A) => B): UIO[B] =
    ZIO.mergeAll(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.mergeAllPar]]
   */
  def mergeAllPar[A, B](in: Iterable[UIO[A]])(zero: B)(f: (B, A) => B): UIO[B] =
    ZIO.mergeAllPar(in)(zero)(f)

  /**
   * @see See [[zio.ZIO.none]]
   */
  val none: UIO[Option[Nothing]] = ZIO.none

  /**
   * @see See [[zio.ZIO.never]]
   */
  val never: UIO[Nothing] = ZIO.never

  /**
   *  @see See [[zio.ZIO.not]]
   */
  def not(effect: UIO[Boolean]): UIO[Boolean] =
    ZIO.not(effect)

  /**
   * @see See [[zio.ZIO.raceAll]]
   */
  def raceAll[A](uio: UIO[A], uios: Iterable[UIO[A]]): UIO[A] =
    ZIO.raceAll(uio, uios)

  /**
   * @see See [[zio.ZIO.reduceAll]]
   */
  def reduceAll[A](a: UIO[A], as: Iterable[UIO[A]])(f: (A, A) => A): UIO[A] =
    ZIO.reduceAll(a, as)(f)

  /**
   * @see See [[zio.ZIO.reduceAllPar]]
   */
  def reduceAllPar[A](a: UIO[A], as: Iterable[UIO[A]])(f: (A, A) => A): UIO[A] =
    ZIO.reduceAllPar(a, as)(f)

  /**
   * @see See [[zio.ZIO.replicate]]
   */
  def replicate[A](n: Int)(effect: UIO[A]): Iterable[UIO[A]] =
    ZIO.replicate(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateM]]
   */
  @deprecated("use replicateZIO", "2.0.0")
  def replicateM[A](n: Int)(effect: UIO[A]): UIO[Iterable[A]] =
    ZIO.replicateM(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateM_]]
   */
  @deprecated("use replicateZIODiscard", "2.0.0")
  def replicateM_[A](n: Int)(effect: UIO[A]): UIO[Unit] =
    ZIO.replicateM_(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateZIO]]
   */
  def replicateZIO[A](n: Int)(effect: UIO[A]): UIO[Iterable[A]] =
    ZIO.replicateZIO(n)(effect)

  /**
   * @see See [[zio.ZIO.replicateZIODiscard]]
   */
  def replicateZIODiscard[A](n: Int)(effect: UIO[A]): UIO[Unit] =
    ZIO.replicateZIODiscard(n)(effect)

  /**
   * @see See [[zio.ZIO.reserve]]
   */
  def reserve[A, B](reservation: UIO[Reservation[Any, Nothing, A]])(use: A => UIO[B]): UIO[B] =
    ZIO.reserve(reservation)(use)

  /**
   *  @see [[zio.ZIO.right]]
   */
  def right[B](b: => B): UIO[Either[Nothing, B]] = ZIO.right(b)

  /**
   * @see See [[zio.ZIO.runtime]]
   */
  def runtime: UIO[Runtime[Any]] = ZIO.runtime

  /**
   *  @see [[zio.ZIO.some]]
   */
  def some[A](a: => A): UIO[Option[A]] = ZIO.some(a)

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
   * @see See [[zio.ZIO.suspendSucceed]]
   */
  def suspendSucceed[A](uio: => UIO[A]): UIO[A] =
    ZIO.suspendSucceed(uio)

  /**
   * @see See [[zio.ZIO.suspendSucceedWith]]
   */
  def suspendSucceedWith[A](p: (Platform, Fiber.Id) => UIO[A]): UIO[A] =
    ZIO.suspendSucceedWith(p)

  /**
   * @see See [[zio.ZIO.trace]]
   */
  def trace: UIO[ZTrace] = ZIO.trace

  /**
   * @see See [[zio.ZIO.traced]]
   */
  def traced[A](uio: UIO[A]): UIO[A] = ZIO.traced(uio)

  /**
   * @see See [[zio.ZIO.unit]]
   */
  val unit: UIO[Unit] = ZIO.unit

  /**
   * @see See [[zio.ZIO.interruptibleMask]]
   */
  def uninterruptible[A](uio: UIO[A]): UIO[A] =
    ZIO.uninterruptible(uio)

  /**
   * @see See [[zio.ZIO.uninterruptibleMask]]
   */
  def uninterruptibleMask[A](k: ZIO.InterruptStatusRestore => UIO[A]): UIO[A] =
    ZIO.uninterruptibleMask(k)

  /**
   * @see See [[zio.ZIO.unless]]
   */
  def unless(b: => Boolean)(zio: => UIO[Any]): UIO[Unit] =
    ZIO.unless(b)(zio)

  /**
   * @see See [[zio.ZIO.unlessM]]
   */
  @deprecated("use unlessZIO", "2.0.0")
  def unlessM(b: UIO[Boolean]): ZIO.UnlessZIO[Any, Nothing] =
    ZIO.unlessM(b)

  /**
   * @see See [[zio.ZIO.unlessZIO]]
   */
  def unlessZIO(b: UIO[Boolean]): ZIO.UnlessZIO[Any, Nothing] =
    ZIO.unlessZIO(b)

  /**
   * @see [[zio.ZIO.unsandbox]]
   */
  def unsandbox[A](v: IO[Cause[Nothing], A]): UIO[A] = ZIO.unsandbox(v)

  /**
   * @see See [[zio.ZIO.untraced]]
   */
  def untraced[A](uio: UIO[A]): UIO[A] = ZIO.untraced(uio)

  /**
   * @see See [[zio.ZIO.when]]
   */
  def when(b: => Boolean)(uio: => UIO[Any]): UIO[Unit] =
    ZIO.when(b)(uio)

  /**
   * @see See [[zio.ZIO.whenCase]]
   */
  def whenCase[A](a: => A)(pf: PartialFunction[A, UIO[Any]]): UIO[Unit] =
    ZIO.whenCase(a)(pf)

  /**
   * @see See [[zio.ZIO.whenCaseM]]
   */
  @deprecated("use whenCaseZIO", "2.0.0")
  def whenCaseM[A](a: UIO[A])(pf: PartialFunction[A, UIO[Any]]): UIO[Unit] =
    ZIO.whenCaseM(a)(pf)

  /**
   * @see See [[zio.ZIO.whenCaseZIO]]
   */
  def whenCaseZIO[A](a: UIO[A])(pf: PartialFunction[A, UIO[Any]]): UIO[Unit] =
    ZIO.whenCaseZIO(a)(pf)

  /**
   * @see See [[zio.ZIO.whenM]]
   */
  @deprecated("use whenZIO", "2.0.0")
  def whenM(b: UIO[Boolean]): ZIO.WhenZIO[Any, Nothing] =
    ZIO.whenM(b)

  /**
   * @see See [[zio.ZIO.whenZIO]]
   */
  def whenZIO(b: UIO[Boolean]): ZIO.WhenZIO[Any, Nothing] =
    ZIO.whenZIO(b)

  /**
   * @see See [[zio.ZIO.yieldNow]]
   */
  val yieldNow: UIO[Unit] = ZIO.yieldNow

  private[zio] def succeedNow[A](a: A): UIO[A] = ZIO.succeedNow(a)
}
