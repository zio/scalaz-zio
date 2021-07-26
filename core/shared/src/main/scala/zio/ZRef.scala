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

import com.github.ghik.silencer.silent
import zio.stm.STM

import java.util.concurrent.atomic.AtomicReference

/**
 * A `ZRef[RA, RB, EA, EB, A, B]` is a polymorphic, purely functional
 * description of a mutable reference. The fundamental operations of a `ZRef`
 * are `set` and `get`. `set` takes a value of type `A` and sets the reference
 * to a new value, requiring an environment of type `RA` and potentially
 * failing with an error of type `EA`. `get` gets the current value of the
 * reference and returns a value of type `B`, requiring an environment of type
 * `RB` and potentially failing with an error of type `EB`.
 *
 * When the error and value types of the `ZRef` are unified, that is, it is a
 * `ZRef[R, R, E, E, A, A]`, the `ZRef` also supports atomic `modify` and
 * `update` operations. All operations are guaranteed to be safe for concurrent
 * access.
 *
 * By default, `ZRef` is implemented in terms of compare and swap operations
 * for maximum performance and does not support performing effects within
 * update operations. If you need to perform effects within update operations
 * you can create a `ZRef.Synchronized`, a specialized type of `ZRef` that
 * supports performing effects within update operations at some cost to
 * performance. In this case writes will semantically block other writers,
 * while multiple readers can read simultaneously.
 *
 * `ZRef.Synchronized` also supports composing multiple `ZRef.Synchronized`
 * values together to form a single `ZRef.Synchronized` value that can be
 * atomically updated using the `zip` operator. In this case reads and writes
 * will semantically block other readers and writers.
 *
 * NOTE: While `ZRef` provides the functional equivalent of a mutable
 * reference, the value inside the `ZRef` should normally be immutable since
 * compare and swap operations are not safe for mutable values that do not
 * support concurrent access. If you do need to use a mutable value
 * `ZRef.Synchronized` will guarantee that access to the value is properly
 * synchronized.
 */
sealed abstract class ZRef[-RA, -RB, +EA, +EB, -A, +B] extends Serializable { self =>

  /**
   * Folds over the error and value types of the `ZRef`. This is a highly
   * polymorphic method that is capable of arbitrarily transforming the error
   * and value types of the `ZRef`. For most use cases one of the more specific
   * combinators implemented in terms of `fold` will be more ergonomic but this
   * method is extremely useful for implementing new combinators.
   */
  def fold[EC, ED, C, D](
    ea: EA => EC,
    eb: EB => ED,
    ca: C => Either[EC, A],
    bd: B => Either[ED, D]
  ): ZRef[RA, RB, EC, ED, C, D]

  /**
   * Folds over the error and value types of the `ZRef`, allowing access to
   * the state in transforming the `set` value. This is a more powerful version
   * of `fold` but requires unifying the error types.
   */
  def foldAll[EC, ED, C, D](
    ea: EA => EC,
    eb: EB => ED,
    ec: EB => EC,
    ca: C => B => Either[EC, A],
    bd: B => Either[ED, D]
  ): ZRef[RA with RB, RB, EC, ED, C, D]

  /**
   * Reads the value from the `ZRef`.
   */
  def get: ZIO[RB, EB, B]

  /**
   * Writes a new value to the `ZRef`, with a guarantee of immediate
   * consistency (at some cost to performance).
   */
  def set(a: A): ZIO[RA, EA, Unit]

  /**
   * Writes a new value to the `ZRef` without providing a guarantee of
   * immediate consistency.
   */
  def setAsync(a: A): ZIO[RA, EA, Unit]

  /**
   * Maps and filters the `get` value of the `ZRef` with the specified partial
   * function, returning a `ZRef` with a `get` value that succeeds with the
   * result of the partial function if it is defined or else fails with `None`.
   */
  def collect[C](pf: PartialFunction[B, C]): ZRef[RA, RB, EA, Option[EB], A, C] =
    fold(identity, Some(_), Right(_), pf.lift(_).toRight(None))

  /**
   * Transforms the `set` value of the `ZRef` with the specified function.
   */
  def contramap[C](f: C => A): ZRef[RA, RB, EA, EB, C, B] =
    contramapEither(c => Right(f(c)))

  /**
   * Transforms the `set` value of the `ZRef` with the specified fallible
   * function.
   */
  def contramapEither[EC >: EA, C](f: C => Either[EC, A]): ZRef[RA, RB, EC, EB, C, B] =
    dimapEither(f, Right(_))

  /**
   * Transforms both the `set` and `get` values of the `ZRef` with the
   * specified functions.
   */
  def dimap[C, D](f: C => A, g: B => D): ZRef[RA, RB, EA, EB, C, D] =
    dimapEither(c => Right(f(c)), b => Right(g(b)))

  /**
   * Transforms both the `set` and `get` values of the `ZRef` with the
   * specified fallible functions.
   */
  def dimapEither[EC >: EA, ED >: EB, C, D](
    f: C => Either[EC, A],
    g: B => Either[ED, D]
  ): ZRef[RA, RB, EC, ED, C, D] =
    fold(identity, identity, f, g)

  /**
   * Transforms both the `set` and `get` errors of the `ZRef` with the
   * specified functions.
   */
  def dimapError[EC, ED](f: EA => EC, g: EB => ED): ZRef[RA, RB, EC, ED, A, B] =
    fold(f, g, Right(_), Right(_))

  /**
   * Filters the `set` value of the `ZRef` with the specified predicate,
   * returning a `ZRef` with a `set` value that succeeds if the predicate is
   * satisfied or else fails with `None`.
   */
  def filterInput[A1 <: A](f: A1 => Boolean): ZRef[RA, RB, Option[EA], EB, A1, B] =
    fold(Some(_), identity, a => if (f(a)) Right(a) else Left(None), Right(_))

  /**
   * Filters the `get` value of the `ZRef` with the specified predicate,
   * returning a `ZRef` with a `get` value that succeeds if the predicate is
   * satisfied or else fails with `None`.
   */
  def filterOutput(f: B => Boolean): ZRef[RA, RB, EA, Option[EB], A, B] =
    fold(identity, Some(_), Right(_), b => if (f(b)) Right(b) else Left(None))

  /**
   * Transforms the `get` value of the `ZRef` with the specified function.
   */
  def map[C](f: B => C): ZRef[RA, RB, EA, EB, A, C] =
    mapEither(b => Right(f(b)))

  /**
   * Transforms the `get` value of the `ZRef` with the specified fallible
   * function.
   */
  def mapEither[EC >: EB, C](f: B => Either[EC, C]): ZRef[RA, RB, EA, EC, A, C] =
    dimapEither(Right(_), f)

  /**
   * Returns a read only view of the `ZRef`.
   */
  def readOnly: ZRef[RA, RB, EA, EB, Nothing, B] =
    self

  /**
   * Returns a write only view of the `ZRef`.
   */
  def writeOnly: ZRef[RA, RB, EA, Unit, A, Nothing] =
    fold(identity, _ => (), Right(_), _ => Left(()))
}

object ZRef extends Serializable {

  /**
   * Creates a new `ZRef` with the specified value.
   */
  def make[A](a: A): UIO[Ref[A]] =
    UIO.succeed(unsafeMake(a))

  private[zio] def unsafeMake[A](a: A): Ref.Atomic[A] =
    Atomic(new AtomicReference(a))

  /**
   * Creates a new managed `ZRef` with the specified value
   */
  def makeManaged[A](a: A): Managed[Nothing, Ref[A]] =
    make(a).toManaged

  implicit class UnifiedSyntax[-R, +E, A](private val self: ZRef[R, R, E, E, A, A]) extends AnyVal {

    /**
     * Atomically writes the specified value to the `ZRef`, returning the value
     * immediately before modification.
     */
    def getAndSet(a: A): ZIO[R, E, A] =
      self match {
        case atomic: Atomic[A] => atomic.getAndSet(a)
        case derived           => derived.modify(v => (v, a))
      }

    /**
     * Atomically modifies the `ZRef` with the specified function, returning
     * the value immediately before modification.
     */
    def getAndUpdate(f: A => A): ZIO[R, E, A] =
      self match {
        case atomic: Atomic[A] => atomic.getAndUpdate(f)
        case derived           => derived.modify(v => (v, f(v)))
      }

    /**
     * Atomically modifies the `ZRef` with the specified partial function,
     * returning the value immediately before modification. If the function is
     * undefined on the current value it doesn't change it.
     */
    def getAndUpdateSome(pf: PartialFunction[A, A]): ZIO[R, E, A] =
      self match {
        case atomic: Atomic[A] => atomic.getAndUpdateSome(pf)
        case derived =>
          derived.modify { v =>
            val result = pf.applyOrElse[A, A](v, identity)
            (v, result)
          }
      }

    /**
     * Atomically modifies the `ZRef` with the specified function, which
     * computes a return value for the modification. This is a more powerful
     * version of `update`.
     */
    @silent("unreachable code")
    def modify[B](f: A => (B, A)): ZIO[R, E, B] =
      self match {
        case atomic: Atomic[A] => atomic.modify(f)
        case derived: Derived[E, E, A, A] =>
          derived.value.modify { s =>
            derived.getEither(s) match {
              case Left(e) => (Left(e), s)
              case Right(a1) => {
                val (b, a2) = f(a1)
                derived.setEither(a2) match {
                  case Left(e)  => (Left(e), s)
                  case Right(s) => (Right(b), s)
                }
              }
            }
          }.absolve
        case derivedAll: DerivedAll[E, E, A, A] =>
          derivedAll.value.modify { s =>
            derivedAll.getEither(s) match {
              case Left(e) => (Left(e), s)
              case Right(a1) => {
                val (b, a2) = f(a1)
                derivedAll.setEither(a2)(s) match {
                  case Left(e)  => (Left(e), s)
                  case Right(s) => (Right(b), s)
                }
              }
            }
          }.absolve
        case zRef: Synchronized[R, R, E, E, A, A] =>
          zRef.modifyZIO(a => ZIO.succeedNow(f(a)))
      }

    /**
     * Atomically modifies the `ZRef` with the specified partial function,
     * which computes a return value for the modification if the function is
     * defined on the current value otherwise it returns a default value. This
     * is a more powerful version of `updateSome`.
     */
    def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)]): ZIO[R, E, B] =
      self match {
        case atomic: Atomic[A] => atomic.modifySome(default)(pf)
        case derived =>
          derived.modify(v => pf.applyOrElse[A, (B, A)](v, _ => (default, v)))
      }

    /**
     * Atomically modifies the `ZRef` with the specified function.
     */
    def update(f: A => A): ZIO[R, E, Unit] =
      self match {
        case atomic: Atomic[A] => atomic.update(f)
        case derived           => derived.modify(v => ((), f(v)))
      }

    /**
     * Atomically modifies the `ZRef` with the specified function and returns
     * the updated value.
     */
    def updateAndGet(f: A => A): ZIO[R, E, A] =
      self match {
        case atomic: Atomic[A] => atomic.updateAndGet(f)
        case derived =>
          derived.modify { v =>
            val result = f(v)
            (result, result)
          }
      }

    /**
     * Atomically modifies the `ZRef` with the specified partial function. If
     * the function is undefined on the current value it doesn't change it.
     */
    def updateSome(pf: PartialFunction[A, A]): ZIO[R, E, Unit] =
      self match {
        case atomic: Atomic[A] => atomic.updateSome(pf)
        case derived =>
          derived.modify { v =>
            val result = pf.applyOrElse[A, A](v, identity)
            ((), result)
          }
      }

    /**
     * Atomically modifies the `ZRef` with the specified partial function. If
     * the function is undefined on the current value it returns the old value
     * without changing it.
     */
    def updateSomeAndGet(pf: PartialFunction[A, A]): ZIO[R, E, A] =
      self match {
        case atomic: Atomic[A] => atomic.updateSomeAndGet(pf)
        case derived =>
          derived.modify { v =>
            val result = pf.applyOrElse[A, A](v, identity)
            (result, result)
          }
      }
  }

  /**
   * A `ZRef.Synchronized[RA, RB, EA, EB, A, B]` is a polymorphic, purely
   * functional description of a mutable reference. The fundamental operations
   * of a `ZRef.Synchronized` are `set` and `get`. `set` takes a value of type
   * `A` and sets the reference to a new value, requiring an environment of
   * type `RA` and potentially failing with an error of type `EA`. `get` gets
   * the current value of the reference and returns a value of type `B`,
   * requiring an environment of type `RB` and potentially failing with an
   * error of type `EB`.
   *
   * When the error and value types of the `ZRef.Synchronized` are unified,
   * that is, it is a `ZRef.Synchronized[R, R, E, E, A, A]`, the
   * `ZRef.Synchronized` also supports atomic `modify` and `update` operations.
   *
   * Unlike an ordinary `ZRef`, a `ZRef.Synchronized` allows performing effects
   * within update operations, at some cost to performance. Writes will
   * semantically block other writers, while multiple readers can read
   * simultaneously.
   *
   * `ZRef.Synchronized` also supports composing multiple `ZRef.Synchronized`
   * values together to form a single `ZRef.Synchronized` value that can be
   * atomically updated using the `zip` operator. In this case reads and writes
   * will semantically block other
   * readers and writers.
   */
  sealed abstract class Synchronized[-RA, -RB, +EA, +EB, -A, +B] extends ZRef[RA, RB, EA, EB, A, B] { self =>

    protected def semaphores: Set[Semaphore]

    protected def unsafeGet: ZIO[RB, EB, B]

    protected def unsafeSet(a: A): ZIO[RA, EA, Unit]

    protected def unsafeSetAsync(a: A): ZIO[RA, EA, Unit]

    /**
     * A symbolic alias for `zip`.
     */
    final def <*>[RA1 <: RA, RB1 <: RB, EA1 >: EA, EB1 >: EB, A2, B2](
      that: Synchronized[RA1, RB1, EA1, EB1, A2, B2]
    ): Synchronized[RA1, RB1, EA1, EB1, (A, A2), (B, B2)] =
      self zip that

    /**
     * Maps and filters the `get` value of the `ZRef.Synchronized` with the
     * specified partial function, returning a `ZRef.Synchronized` with a `get`
     * value that succeeds with the result of the partial function if it is
     * defined or else fails with `None`.
     */
    override final def collect[C](pf: PartialFunction[B, C]): Synchronized[RA, RB, EA, Option[EB], A, C] =
      collectZIO(pf.andThen(ZIO.succeedNow(_)))

    /**
     * Maps and filters the `get` value of the `ZRefM` with the specified
     * effectual partial function, returning a `ZRefM` with a `get` value that
     * succeeds with the result of the partial function if it is defined or else
     * fails with `None`.
     */
    @deprecated("use collectZIO", "2.0.0")
    final def collectM[RC <: RB, EC >: EB, C](
      pf: PartialFunction[B, ZIO[RC, EC, C]]
    ): ZRefM[RA, RC, EA, Option[EC], A, C] =
      collectZIO(pf)

    /**
     * Maps and filters the `get` value of the `ZRef.Synchronized` with the
     * specified effectual partial function, returning a `ZRef.Synchronized`
     * with a `get` value that succeeds with the result of the partial function
     * if it is defined or else fails with `None`.
     */
    final def collectZIO[RC <: RB, EC >: EB, C](
      pf: PartialFunction[B, ZIO[RC, EC, C]]
    ): Synchronized[RA, RC, EA, Option[EC], A, C] =
      foldZIO(
        identity,
        Some(_),
        ZIO.succeedNow,
        b => pf.andThen(_.asSomeError).applyOrElse[B, ZIO[RC, Option[EC], C]](b, _ => ZIO.fail(None))
      )

    /**
     * Transforms the `set` value of the `ZRef.Synchronized` with the specified
     * function.
     */
    override final def contramap[C](f: C => A): Synchronized[RA, RB, EA, EB, C, B] =
      contramapZIO(c => ZIO.succeedNow(f(c)))

    /**
     * Transforms the `set` value of the `ZRef` with the specified fallible
     * function.
     */
    override final def contramapEither[EC >: EA, C](f: C => Either[EC, A]): Synchronized[RA, RB, EC, EB, C, B] =
      dimapEither(f, Right(_))

    /**
     * Transforms the `set` value of the `ZRefM` with the specified effectual
     * function.
     */
    @deprecated("use contramapZIO", "2.0.0")
    final def contramapM[RC <: RA, EC >: EA, C](f: C => ZIO[RC, EC, A]): ZRefM[RC, RB, EC, EB, C, B] =
      contramapZIO(f)

    /**
     * Transforms the `set` value of the `ZRef.Synchronized` with the specified
     * effectual function.
     */
    final def contramapZIO[RC <: RA, EC >: EA, C](f: C => ZIO[RC, EC, A]): Synchronized[RC, RB, EC, EB, C, B] =
      dimapZIO(f, ZIO.succeedNow)

    /**
     * Transforms both the `set` and `get` values of the `ZRef.Synchronized`
     * with the specified functions.
     */
    override final def dimap[C, D](f: C => A, g: B => D): Synchronized[RA, RB, EA, EB, C, D] =
      dimapZIO(c => ZIO.succeedNow(f(c)), b => ZIO.succeedNow(g(b)))

    /**
     * Transforms both the `set` and `get` values of the `ZRef` with the
     * specified fallible functions.
     */
    override final def dimapEither[EC >: EA, ED >: EB, C, D](
      f: C => Either[EC, A],
      g: B => Either[ED, D]
    ): Synchronized[RA, RB, EC, ED, C, D] =
      fold(identity, identity, f, g)

    /**
     * Transforms both the `set` and `get` values of the `ZRefM` with the
     * specified effectual functions.
     */
    @deprecated("use dimapZIO", "2.0.0")
    final def dimapM[RC <: RA, RD <: RB, EC >: EA, ED >: EB, C, D](
      f: C => ZIO[RC, EC, A],
      g: B => ZIO[RD, ED, D]
    ): ZRefM[RC, RD, EC, ED, C, D] =
      dimapZIO(f, g)

    /**
     * Transforms both the `set` and `get` values of the `ZRef.Synchronized`
     * with the specified effectual functions.
     */
    final def dimapZIO[RC <: RA, RD <: RB, EC >: EA, ED >: EB, C, D](
      f: C => ZIO[RC, EC, A],
      g: B => ZIO[RD, ED, D]
    ): Synchronized[RC, RD, EC, ED, C, D] =
      foldZIO(identity, identity, f, g)

    /**
     * Transforms both the `set` and `get` errors of the `ZRef.Synchronized`
     * with the specified functions.
     */
    override final def dimapError[EC, ED](f: EA => EC, g: EB => ED): Synchronized[RA, RB, EC, ED, A, B] =
      fold(f, g, Right(_), Right(_))

    /**
     * Filters the `set` value of the `ZRef.Synchronized` with the specified
     * predicate, returning a `ZRef.Synchronized` with a `set` value that
     * succeeds if the predicate is satisfied or else fails with `None`.
     */
    override final def filterInput[A1 <: A](f: A1 => Boolean): Synchronized[RA, RB, Option[EA], EB, A1, B] =
      filterInputZIO(a => ZIO.succeedNow(f(a)))

    /**
     * Filters the `set` value of the `ZRefM` with the specified effectual
     * predicate, returning a `ZRefM` with a `set` value that succeeds if the
     * predicate is satisfied or else fails with `None`.
     */
    @deprecated("use filterInputZIO", "2.0.0")
    final def filterInputM[RC <: RA, EC >: EA, A1 <: A](
      f: A1 => ZIO[RC, EC, Boolean]
    ): ZRefM[RC, RB, Option[EC], EB, A1, B] =
      filterInputZIO(f)

    /**
     * Filters the `set` value of the `ZRef.Synchronized` with the specified
     * effectual predicate, returning a `ZRef.Synchronized` with a `set` value
     * that succeeds if the predicate is satisfied or else fails with `None`.
     */
    final def filterInputZIO[RC <: RA, EC >: EA, A1 <: A](
      f: A1 => ZIO[RC, EC, Boolean]
    ): Synchronized[RC, RB, Option[EC], EB, A1, B] =
      foldZIO(Some(_), identity, a => ZIO.ifZIO(f(a).asSomeError)(ZIO.succeedNow(a), ZIO.fail(None)), ZIO.succeedNow)

    /**
     * Filters the `get` value of the `ZRef.Synchronized` with the specified
     * predicate, returning a `ZRef.Synchronized` with a `get` value that
     * succeeds if the predicate is satisfied or else fails with `None`.
     */
    override final def filterOutput(f: B => Boolean): Synchronized[RA, RB, EA, Option[EB], A, B] =
      filterOutputZIO(a => ZIO.succeedNow(f(a)))

    /**
     * Filters the `get` value of the `ZRefM` with the specified effectual predicate,
     * returning a `ZRefM` with a `get` value that succeeds if the predicate is
     * satisfied or else fails with `None`.
     */
    @deprecated("use filterOutputZIO", "2.0.0")
    final def filterOutputM[RC <: RB, EC >: EB](f: B => ZIO[RC, EC, Boolean]): ZRefM[RA, RC, EA, Option[EC], A, B] =
      filterOutputZIO(f)

    /**
     * Filters the `get` value of the `ZRef.Synchronized` with the specified
     * effectual predicate, returning a `ZRef.Synchronized` with a `get` value
     * that succeeds if the predicate is satisfied or else fails with `None`.
     */
    final def filterOutputZIO[RC <: RB, EC >: EB](
      f: B => ZIO[RC, EC, Boolean]
    ): Synchronized[RA, RC, EA, Option[EC], A, B] =
      foldZIO(identity, Some(_), ZIO.succeedNow, b => ZIO.ifZIO(f(b).asSomeError)(ZIO.succeedNow(b), ZIO.fail(None)))

    /**
     * Folds over the error and value types of the `ZRef.Synchronized`.
     */
    final def fold[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ca: C => Either[EC, A],
      bd: B => Either[ED, D]
    ): Synchronized[RA, RB, EC, ED, C, D] =
      foldZIO(ea, eb, c => ZIO.fromEither(ca(c)), b => ZIO.fromEither(bd(b)))

    /**
     * Folds over the error and value types of the `ZRef.Synchronized`,
     * allowing access to the state in transforming the `set` value but
     * requiring unifying the error type.
     */
    final def foldAll[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ec: EB => EC,
      ca: C => B => Either[EC, A],
      bd: B => Either[ED, D]
    ): Synchronized[RA with RB, RB, EC, ED, C, D] =
      foldAllZIO(ea, eb, ec, c => b => ZIO.fromEither(ca(c)(b)), b => ZIO.fromEither(bd(b)))

    /**
     * Folds over the error and value types of the `ZRefM`, allowing access to
     * the state in transforming the `set` value. This is a more powerful version
     * of `foldM` but requires unifying the environment and error types.
     */
    @deprecated("use foldAllZIO", "2.0.0")
    final def foldAllM[RC <: RA with RB, RD <: RB, EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ec: EB => EC,
      ca: C => B => ZIO[RC, EC, A],
      bd: B => ZIO[RD, ED, D]
    ): ZRefM[RC, RD, EC, ED, C, D] =
      foldAllZIO(ea, eb, ec, ca, bd)

    /**
     * Folds over the error and value types of the `ZRef.Synchronized`,
     * allowing access to the state in transforming the `set` value. This is a
     * more powerful version of `foldZIO` but requires unifying the environment
     * and error types.
     */
    final def foldAllZIO[RC <: RA with RB, RD <: RB, EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ec: EB => EC,
      ca: C => B => ZIO[RC, EC, A],
      bd: B => ZIO[RD, ED, D]
    ): Synchronized[RC, RD, EC, ED, C, D] =
      new Synchronized[RC, RD, EC, ED, C, D] {
        def semaphores =
          self.semaphores
        def unsafeGet: ZIO[RD, ED, D] =
          self.get.foldZIO(e => ZIO.fail(eb(e)), bd)
        def unsafeSet(c: C): ZIO[RC, EC, Unit] =
          self.get.foldZIO(
            e => ZIO.fail(ec(e)),
            b => ca(c)(b).flatMap(a => self.unsafeSet(a).mapError(ea))
          )
        def unsafeSetAsync(c: C): ZIO[RC, EC, Unit] =
          self.get.foldZIO(
            e => ZIO.fail(ec(e)),
            b => ca(c)(b).flatMap(a => self.unsafeSetAsync(a).mapError(ea))
          )
      }

    /**
     * Folds over the error and value types of the `ZRefM`. This is a highly
     * polymorphic method that is capable of arbitrarily transforming the error
     * and value types of the `ZRefM`. For most use cases one of the more
     * specific combinators implemented in terms of `foldM` will be more
     * ergonomic but this method is extremely useful for implementing new
     * combinators.
     */
    @deprecated("use foldZIO", "2.0.0")
    final def foldM[RC <: RA, RD <: RB, EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ca: C => ZIO[RC, EC, A],
      bd: B => ZIO[RD, ED, D]
    ): ZRefM[RC, RD, EC, ED, C, D] =
      foldZIO(ea, eb, ca, bd)

    /**
     * Folds over the error and value types of the `ZRef.Synchronized`. This is
     * a highly polymorphic method that is capable of arbitrarily transforming
     * the error and value types of the `ZRef.Synchronized`. For most use cases
     * one of the more specific combinators implemented in terms of `foldZIO`
     * will be more ergonomic but this method is extremely useful for
     * implementing new combinators.
     */
    final def foldZIO[RC <: RA, RD <: RB, EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ca: C => ZIO[RC, EC, A],
      bd: B => ZIO[RD, ED, D]
    ): Synchronized[RC, RD, EC, ED, C, D] =
      new Synchronized[RC, RD, EC, ED, C, D] {
        def semaphores: Set[Semaphore] =
          self.semaphores
        def unsafeGet: ZIO[RD, ED, D] =
          self.unsafeGet.foldZIO(e => ZIO.fail(eb(e)), bd)
        def unsafeSetAsync(c: C): ZIO[RC, EC, Unit] =
          ca(c).flatMap(self.unsafeSetAsync(_).mapError(ea))
        def unsafeSet(c: C): ZIO[RC, EC, Unit] =
          ca(c).flatMap(self.unsafeSet(_).mapError(ea))
      }

    /**
     * Reads the value from the `ZRef`.
     */
    final def get: ZIO[RB, EB, B] =
      if (semaphores.size == 1) unsafeGet else withPermit(unsafeGet)

    /**
     * Transforms the `get` value of the `ZRef.Synchronized` with the specified
     * function.
     */
    override final def map[C](f: B => C): Synchronized[RA, RB, EA, EB, A, C] =
      mapZIO(b => ZIO.succeedNow(f(b)))

    /**
     * Transforms the `get` value of the `ZRef` with the specified fallible
     * function.
     */
    override final def mapEither[EC >: EB, C](f: B => Either[EC, C]): Synchronized[RA, RB, EA, EC, A, C] =
      dimapEither(Right(_), f)

    /**
     * Transforms the `get` value of the `ZRefM` with the specified effectual
     * function.
     */
    @deprecated("use mapZIO", "2.0.0")
    final def mapM[RC <: RB, EC >: EB, C](f: B => ZIO[RC, EC, C]): ZRefM[RA, RC, EA, EC, A, C] =
      mapZIO(f)

    /**
     * Transforms the `get` value of the `ZRef.Synchronized` with the specified
     * effectual function.
     */
    final def mapZIO[RC <: RB, EC >: EB, C](f: B => ZIO[RC, EC, C]): Synchronized[RA, RC, EA, EC, A, C] =
      dimapZIO(ZIO.succeedNow, f)

    /**
     * Returns a read only view of the `ZRef.Synchronized`.
     */
    override final def readOnly: Synchronized[RA, RB, EA, EB, Nothing, B] =
      self

    /**
     * Writes a new value to the `ZRef`, with a guarantee of immediate
     * consistency (at some cost to performance).
     */
    final def set(a: A): ZIO[RA, EA, Unit] =
      withPermit(unsafeSet(a))

    /**
     * Writes a new value to the `ZRef` without providing a guarantee of
     * immediate consistency.
     */
    final def setAsync(a: A): ZIO[RA, EA, Unit] =
      withPermit(unsafeSetAsync(a))

    /**
     * Performs the specified effect every time a value is written to this
     * `ZRef.Synchronized`.
     */
    final def tapInput[RC <: RA, EC >: EA, A1 <: A](f: A1 => ZIO[RC, EC, Any]): Synchronized[RC, RB, EC, EB, A1, B] =
      contramapZIO(a => f(a).as(a))

    /**
     * Performs the specified effect very time a value is read from this
     * `ZRef.Synchronized`.
     */
    final def tapOutput[RC <: RB, EC >: EB](f: B => ZIO[RC, EC, Any]): Synchronized[RA, RC, EA, EC, A, B] =
      mapZIO(b => f(b).as(b))

    /**
     * Returns a write only view of the `ZRef.Synchronized`.
     */
    override final def writeOnly: Synchronized[RA, RB, EA, Unit, A, Nothing] =
      fold(identity, _ => (), Right(_), _ => Left(()))

    /**
     * Combines this `ZRef.Synchronized` with the specified `ZRef.Synchronized`
     * to create a new `ZRef.Synchronized` with the `get` and `set` values of
     * both. The new `ZRef.Synchronized` value supports atomically modifying
     * both of the underlying `ZRef.Synchronized` values.
     */
    final def zip[RA1 <: RA, RB1 <: RB, EA1 >: EA, EB1 >: EB, A2, B2](
      that: Synchronized[RA1, RB1, EA1, EB1, A2, B2]
    ): Synchronized[RA1, RB1, EA1, EB1, (A, A2), (B, B2)] =
      new Synchronized[RA1, RB1, EA1, EB1, (A, A2), (B, B2)] {
        val semaphores: Set[Semaphore] =
          self.semaphores | that.semaphores
        def unsafeGet: ZIO[RB1, EB1, (B, B2)] =
          self.get <*> that.get
        def unsafeSetAsync(a: (A, A2)): ZIO[RA1, EA1, Unit] =
          self.unsafeSetAsync(a._1) *> that.unsafeSetAsync(a._2)
        def unsafeSet(a: (A, A2)): ZIO[RA1, EA1, Unit] =
          self.unsafeSet(a._1) *> that.unsafeSet(a._2)
      }

    private final def withPermit[R, E, A](zio: ZIO[R, E, A]): ZIO[R, E, A] =
      ZIO.uninterruptibleMask { restore =>
        restore(STM.foreach(semaphores)(_.acquire).commit) *>
          restore(zio).ensuring(STM.foreach(semaphores)(_.release).commit)
      }
  }

  object Synchronized {

    /**
     * Creates a new `RefM` and a `Dequeue` that will emit every change to the
     * `RefM`.
     */
    @deprecated("use SubscriptionRef", "2.0.0")
    def dequeueRef[A](a: A): UIO[(RefM[A], Dequeue[A])] =
      for {
        ref   <- make(a)
        queue <- Queue.unbounded[A]
      } yield (ref.tapInput(queue.offer), queue)

    /**
     * Creates a new `ZRef.Synchronized` with the specified value.
     */
    def make[A](a: A): UIO[Synchronized[Any, Any, Nothing, Nothing, A, A]] =
      for {
        ref       <- Ref.make(a)
        semaphore <- Semaphore.make(1)
      } yield new Ref.Synchronized[A] {
        val semaphores: Set[Semaphore] =
          Set(semaphore)
        def unsafeGet: ZIO[Any, Nothing, A] =
          ref.get
        def unsafeSet(a: A): ZIO[Any, Nothing, Unit] =
          ref.set(a)
        def unsafeSetAsync(a: A): ZIO[Any, Nothing, Unit] =
          ref.setAsync(a)
      }

    /**
     * Creates a new `ZRef.Synchronized` with the specified value in the
     * context of a `Managed.`
     */
    def makeManaged[A](a: A): UManaged[Synchronized[Any, Any, Nothing, Nothing, A, A]] =
      make(a).toManaged

    implicit class UnifiedSyntax[-R, +E, A](private val self: Synchronized[R, R, E, E, A, A]) extends AnyVal {

      /**
       * Atomically modifies the `RefM` with the specified function, returning the
       * value immediately before modification.
       */
      @deprecated("use getAndUpdateZIO", "2.0.0")
      def getAndUpdateM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, A] =
        getAndUpdateZIO(f)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified
       * function, returning the value immediately before modification.
       */
      def getAndUpdateZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, A] =
        modifyZIO(v => f(v).map(result => (v, result)))

      /**
       * Atomically modifies the `RefM` with the specified partial function,
       * returning the value immediately before modification.
       * If the function is undefined on the current value it doesn't change it.
       */
      @deprecated("use getAndUpdateSomeZIO", "2.0.0")
      def getAndUpdateSomeM[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, A] =
        getAndUpdateSomeZIO(pf)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified partial
       * function, returning the value immediately before modification. If the
       * function is undefined on the current value it doesn't change it.
       */
      def getAndUpdateSomeZIO[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, A] =
        modifyZIO(v => pf.applyOrElse[A, ZIO[R1, E1, A]](v, ZIO.succeedNow).map(result => (v, result)))

      /**
       * Atomically modifies the `RefM` with the specified function, which computes
       * a return value for the modification. This is a more powerful version of
       * `update`.
       */
      @deprecated("use modifyZIO", "2.0.0")
      def modifyM[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, (B, A)]): ZIO[R1, E1, B] =
        modifyZIO(f)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified
       * function, which computes a return value for the modification. This is
       * a more powerful version of `update`.
       */
      def modifyZIO[R1 <: R, E1 >: E, B](f: A => ZIO[R1, E1, (B, A)]): ZIO[R1, E1, B] =
        self.withPermit(self.unsafeGet.flatMap(f).flatMap { case (b, a) => self.unsafeSet(a).as(b) })

      /**
       * Atomically modifies the `RefM` with the specified function, which computes
       * a return value for the modification if the function is defined in the current value
       * otherwise it returns a default value.
       * This is a more powerful version of `updateSome`.
       */
      @deprecated("use modifySomeZIO", "2.0.0")
      def modifySomeM[R1 <: R, E1 >: E, B](default: B)(pf: PartialFunction[A, ZIO[R1, E1, (B, A)]]): ZIO[R1, E1, B] =
        modifySomeZIO[R1, E1, B](default)(pf)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified
       * function, which computes a return value for the modification if the
       * function is defined in the current value otherwise it returns a
       * default value. This is a more powerful version of `updateSome`.
       */
      def modifySomeZIO[R1 <: R, E1 >: E, B](default: B)(pf: PartialFunction[A, ZIO[R1, E1, (B, A)]]): ZIO[R1, E1, B] =
        modifyZIO(v => pf.applyOrElse[A, ZIO[R1, E1, (B, A)]](v, _ => ZIO.succeedNow((default, v))))

      /**
       * Atomically modifies the `RefM` with the specified function.
       */
      @deprecated("use updateZIO", "2.0.0")
      def updateM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, Unit] =
        updateZIO(f)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified
       * function.
       */
      def updateZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, Unit] =
        modifyZIO(v => f(v).map(result => ((), result)))

      /**
       * Atomically modifies the `RefM` with the specified function, returning the
       * value immediately after modification.
       */
      @deprecated("use updateAndGetZIO", "2.0.0")
      def updateAndGetM[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, A] =
        updateAndGetZIO(f)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified
       * function, returning the value immediately after modification.
       */
      def updateAndGetZIO[R1 <: R, E1 >: E](f: A => ZIO[R1, E1, A]): ZIO[R1, E1, A] =
        modifyZIO(v => f(v).map(result => (result, result)))

      /**
       * Atomically modifies the `RefM` with the specified partial function.
       * If the function is undefined on the current value it doesn't change it.
       */
      @deprecated("use updateSomeZIO", "2.0.0")
      def updateSomeM[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, Unit] =
        updateSomeZIO(pf)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified partial
       * function. If the function is undefined on the current value it doesn't
       * change it.
       */
      def updateSomeZIO[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, Unit] =
        modifyZIO(v => pf.applyOrElse[A, ZIO[R1, E1, A]](v, ZIO.succeedNow).map(result => ((), result)))

      /**
       * Atomically modifies the `RefM` with the specified partial function.
       * If the function is undefined on the current value it returns the old value
       * without changing it.
       */
      @deprecated("use updateSomeAndGetZIO", "2.0.0")
      def updateSomeAndGetM[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, A] =
        updateSomeAndGetZIO(pf)

      /**
       * Atomically modifies the `Ref.Synchronized` with the specified partial
       * function. If the function is undefined on the current value it returns
       * the old value without changing it.
       */
      def updateSomeAndGetZIO[R1 <: R, E1 >: E](pf: PartialFunction[A, ZIO[R1, E1, A]]): ZIO[R1, E1, A] =
        modifyZIO(v => pf.applyOrElse[A, ZIO[R1, E1, A]](v, ZIO.succeedNow).map(result => (result, result)))
    }
  }

  private[zio] final case class Atomic[A](value: AtomicReference[A]) extends Ref[A] { self =>

    def fold[EC, ED, C, D](
      ea: Nothing => EC,
      eb: Nothing => ED,
      ca: C => Either[EC, A],
      bd: A => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new Derived[EC, ED, C, D] {
        type S = A
        def getEither(s: S): Either[ED, D] =
          bd(s)
        def setEither(c: C): Either[EC, S] =
          ca(c)
        val value: Atomic[S] =
          self
      }

    def foldAll[EC, ED, C, D](
      ea: Nothing => EC,
      eb: Nothing => ED,
      ec: Nothing => EC,
      ca: C => A => Either[EC, A],
      bd: A => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new DerivedAll[EC, ED, C, D] {
        type S = A
        def getEither(s: S): Either[ED, D] =
          bd(s)
        def setEither(c: C)(s: S): Either[EC, S] =
          ca(c)(s)
        val value: Atomic[S] =
          self
      }

    def get: UIO[A] =
      UIO.succeed(value.get)

    def getAndSet(a: A): UIO[A] =
      UIO.succeed {
        var loop       = true
        var current: A = null.asInstanceOf[A]
        while (loop) {
          current = value.get
          loop = !value.compareAndSet(current, a)
        }
        current
      }

    def getAndUpdate(f: A => A): UIO[A] =
      UIO.succeed {
        {
          var loop       = true
          var current: A = null.asInstanceOf[A]
          while (loop) {
            current = value.get
            val next = f(current)
            loop = !value.compareAndSet(current, next)
          }
          current
        }
      }

    def getAndUpdateSome(pf: PartialFunction[A, A]): UIO[A] =
      UIO.succeed {
        var loop       = true
        var current: A = null.asInstanceOf[A]
        while (loop) {
          current = value.get
          val next = pf.applyOrElse(current, (_: A) => current)
          loop = !value.compareAndSet(current, next)
        }
        current
      }

    def modify[B](f: A => (B, A)): UIO[B] =
      UIO.succeed {
        var loop = true
        var b: B = null.asInstanceOf[B]
        while (loop) {
          val current = value.get
          val tuple   = f(current)
          b = tuple._1
          loop = !value.compareAndSet(current, tuple._2)
        }
        b
      }

    def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)]): UIO[B] =
      UIO.succeed {
        {
          var loop = true
          var b: B = null.asInstanceOf[B]
          while (loop) {
            val current = value.get
            val tuple   = pf.applyOrElse(current, (_: A) => (default, current))
            b = tuple._1
            loop = !value.compareAndSet(current, tuple._2)
          }
          b
        }
      }

    def set(a: A): UIO[Unit] =
      UIO.succeed(value.set(a))

    def setAsync(a: A): UIO[Unit] =
      UIO.succeed(value.lazySet(a))

    override def toString: String =
      s"Ref(${value.get})"

    def update(f: A => A): UIO[Unit] =
      UIO.succeed {
        var loop    = true
        var next: A = null.asInstanceOf[A]
        while (loop) {
          val current = value.get
          next = f(current)
          loop = !value.compareAndSet(current, next)
        }
        ()
      }

    def updateAndGet(f: A => A): UIO[A] =
      UIO.succeed {
        var loop    = true
        var next: A = null.asInstanceOf[A]
        while (loop) {
          val current = value.get
          next = f(current)
          loop = !value.compareAndSet(current, next)
        }
        next
      }

    def updateSome(pf: PartialFunction[A, A]): UIO[Unit] =
      UIO.succeed {
        var loop    = true
        var next: A = null.asInstanceOf[A]
        while (loop) {
          val current = value.get
          next = pf.applyOrElse(current, (_: A) => current)
          loop = !value.compareAndSet(current, next)
        }
        ()
      }

    def updateSomeAndGet(pf: PartialFunction[A, A]): UIO[A] =
      UIO.succeed {
        var loop    = true
        var next: A = null.asInstanceOf[A]
        while (loop) {
          val current = value.get
          next = pf.applyOrElse(current, (_: A) => current)
          loop = !value.compareAndSet(current, next)
        }
        next
      }
  }

  private abstract class Derived[+EA, +EB, -A, +B] extends ZRef[Any, Any, EA, EB, A, B] { self =>
    type S

    def getEither(s: S): Either[EB, B]

    def setEither(a: A): Either[EA, S]

    val value: Atomic[S]

    final def fold[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ca: C => Either[EC, A],
      bd: B => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new Derived[EC, ED, C, D] {
        type S = self.S
        def getEither(s: S): Either[ED, D] =
          self.getEither(s).fold(e => Left(eb(e)), bd)
        def setEither(c: C): Either[EC, S] =
          ca(c).flatMap(a => self.setEither(a).fold(e => Left(ea(e)), Right(_)))
        val value: Atomic[S] =
          self.value
      }

    final def foldAll[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ec: EB => EC,
      ca: C => B => Either[EC, A],
      bd: B => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new DerivedAll[EC, ED, C, D] {
        type S = self.S
        def getEither(s: S): Either[ED, D] =
          self.getEither(s).fold(e => Left(eb(e)), bd)
        def setEither(c: C)(s: S): Either[EC, S] =
          self
            .getEither(s)
            .fold(e => Left(ec(e)), ca(c))
            .flatMap(a => self.setEither(a).fold(e => Left(ea(e)), Right(_)))
        val value: Atomic[S] =
          self.value
      }

    final def get: IO[EB, B] =
      value.get.flatMap(getEither(_).fold(ZIO.fail(_), ZIO.succeedNow))

    final def set(a: A): IO[EA, Unit] =
      setEither(a).fold(ZIO.fail(_), value.set)

    final def setAsync(a: A): IO[EA, Unit] =
      setEither(a).fold(ZIO.fail(_), value.setAsync)
  }

  private abstract class DerivedAll[+EA, +EB, -A, +B] extends ZRef[Any, Any, EA, EB, A, B] { self =>
    type S

    def getEither(s: S): Either[EB, B]

    def setEither(a: A)(s: S): Either[EA, S]

    val value: Atomic[S]

    final def fold[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ca: C => Either[EC, A],
      bd: B => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new DerivedAll[EC, ED, C, D] {
        type S = self.S
        def getEither(s: S): Either[ED, D] =
          self.getEither(s).fold(e => Left(eb(e)), bd)
        def setEither(c: C)(s: S): Either[EC, S] =
          ca(c).flatMap(a => self.setEither(a)(s).fold(e => Left(ea(e)), Right(_)))
        val value: Atomic[S] =
          self.value
      }

    final def foldAll[EC, ED, C, D](
      ea: EA => EC,
      eb: EB => ED,
      ec: EB => EC,
      ca: C => B => Either[EC, A],
      bd: B => Either[ED, D]
    ): ZRef[Any, Any, EC, ED, C, D] =
      new DerivedAll[EC, ED, C, D] {
        type S = self.S
        def getEither(s: S): Either[ED, D] =
          self.getEither(s).fold(e => Left(eb(e)), bd)
        def setEither(c: C)(s: S): Either[EC, S] =
          self
            .getEither(s)
            .fold(e => Left(ec(e)), ca(c))
            .flatMap(a => self.setEither(a)(s).fold(e => Left(ea(e)), Right(_)))
        val value: Atomic[S] =
          self.value
      }

    final def get: IO[EB, B] =
      value.get.flatMap(getEither(_).fold(ZIO.fail(_), ZIO.succeedNow))

    final def set(a: A): IO[EA, Unit] =
      value.modify { s =>
        setEither(a)(s) match {
          case Left(e)  => (Left(e), s)
          case Right(s) => (Right(()), s)
        }
      }.absolve

    final def setAsync(a: A): IO[EA, Unit] =
      value.modify { s =>
        setEither(a)(s) match {
          case Left(e)  => (Left(e), s)
          case Right(s) => (Right(()), s)
        }
      }.absolve
  }
}
