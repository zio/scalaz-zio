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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.AtomicReference

/**
 * A `Ref` is a purely functional description of a mutable reference. The
 * fundamental operations of a `Ref` are `set` and `get`. `set` sets the
 * reference to a new value. `get` gets the current value of the reference.
 *
 * By default, `Ref` is implemented in terms of compare and swap operations for
 * maximum performance and does not support performing effects within update
 * operations. If you need to perform effects within update operations you can
 * create a `Ref.Synchronized`, a specialized type of `Ref` that supports
 * performing effects within update operations at some cost to performance. In
 * this case writes will semantically block other writers, while multiple
 * readers can read simultaneously.
 *
 * NOTE: While `Ref` provides the functional equivalent of a mutable reference,
 * the value inside the `Ref` should normally be immutable since compare and
 * swap operations are not safe for mutable values that do not support
 * concurrent access. If you do need to use a mutable value `Ref.Synchronized`
 * will guarantee that access to the value is properly synchronized.
 */
abstract class Ref[A] extends Serializable {

  /**
   * Reads the value from the `Ref`.
   */
  def get(implicit trace: Trace): UIO[A]

  /**
   * Atomically modifies the `Ref` with the specified function, which computes a
   * return value for the modification. This is a more powerful version of
   * `update`.
   */
  def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B]

  /**
   * Writes a new value to the `Ref`, with a guarantee of immediate consistency
   * (at some cost to performance).
   */
  def set(a: A)(implicit trace: Trace): UIO[Unit]

  /**
   * Writes a new value to the `Ref` without providing a guarantee of immediate
   * consistency.
   */
  def setAsync(a: A)(implicit trace: Trace): UIO[Unit]

  /**
   * Atomically writes the specified value to the `Ref`, returning the value
   * immediately before modification.
   */
  def getAndSet(a: A)(implicit trace: Trace): UIO[A] =
    modify(v => (v, a))

  /**
   * Atomically modifies the `Ref` with the specified function, returning the
   * value immediately before modification.
   */
  def getAndUpdate(f: A => A)(implicit trace: Trace): UIO[A] =
    modify(v => (v, f(v)))

  /**
   * Atomically modifies the `Ref` with the specified partial function,
   * returning the value immediately before modification. If the function is
   * undefined on the current value it doesn't change it.
   */
  def getAndUpdateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      (v, result)
    }

  /**
   * Atomically modifies the `Ref` with the specified partial function, which
   * computes a return value for the modification if the function is defined on
   * the current value otherwise it returns a default value. This is a more
   * powerful version of `updateSome`.
   */
  def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)])(implicit trace: Trace): UIO[B] =
    modify(v => pf.applyOrElse[A, (B, A)](v, _ => (default, v)))

  /**
   * Atomically modifies the `Ref` with the specified function.
   */
  def update(f: A => A)(implicit trace: Trace): UIO[Unit] =
    modify(v => ((), f(v)))

  /**
   * Atomically modifies the `Ref` with the specified function and returns the
   * updated value.
   */
  def updateAndGet(f: A => A)(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = f(v)
      (result, result)
    }

  /**
   * Atomically modifies the `Ref` with the specified partial function. If the
   * function is undefined on the current value it doesn't change it.
   */
  def updateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[Unit] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      ((), result)
    }

  /**
   * Atomically modifies the `Ref` with the specified partial function. If the
   * function is undefined on the current value it returns the old value without
   * changing it.
   */
  def updateSomeAndGet(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      (result, result)
    }

  /**
   * Atomically increments the current value of the `Ref` by 1, returning the
   * value immediately before modification.
   */
  final def getAndIncrement(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    getAndUpdate(v => num.plus(v, num.one))

  /**
   * Atomically decrements the current value of the `Ref` by 1, returning the
   * value immediately before modification.
   */
  final def getAndDecrement(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    getAndUpdate(v => num.minus(v, num.one))

  /**
   * Atomically adds `delta` to the current value of the `Ref`, returning the
   * value immediately before modification.
   */
  final def getAndAdd(delta: A)(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    getAndUpdate(v => num.plus(v, delta))

  /**
   * Atomically increments the current value of the `Ref` by 1 and returns the
   * updated value.
   */
  final def incrementAndGet(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    updateAndGet(v => num.plus(v, num.one))

  /**
   * Atomically decrements the current value of the `Ref` by 1 and returns the
   * updated value.
   */
  final def decrementAndGet(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    updateAndGet(v => num.minus(v, num.one))

  /**
   * Atomically adds `delta` to the current value of the `Ref` and returns the
   * updated value.
   */
  final def addAndGet(delta: A)(implicit num: math.Numeric[A], trace: Trace): UIO[A] =
    updateAndGet(v => num.plus(v, delta))

}

object Ref extends Serializable {

  /**
   * Creates a new `Ref` with the specified value.
   */
  def make[A](a: => A)(implicit trace: Trace): UIO[Ref[A]] =
    ZIO.succeed(unsafe.make(a)(Unsafe))

  object unsafe {
    def make[A](a: A)(implicit unsafe: Unsafe): Ref.Atomic[A] =
      Atomic(new AtomicReference(a))
  }

  /**
   * A `Ref.Synchronized` is a purely functional description of a mutable
   * reference. The fundamental operations of a `Ref.Synchronized` are `set` and
   * `get`. `set` sets the reference to a new value. `get` gets the current
   * value of the reference.
   *
   * Unlike an ordinary `Ref`, a `Ref.Synchronized` allows performing effects
   * within update operations, at some cost to performance. Writes will
   * semantically block other writers, while multiple readers can read
   * simultaneously.
   */
  abstract class Synchronized[A] extends Ref[A] {

    /**
     * Reads the value from the `Ref`.
     */
    def get(implicit trace: Trace): UIO[A]

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified function,
     * which computes a return value for the modification. This is a more
     * powerful version of `update`.
     */
    def modifyZIO[R, E, B](f: A => ZIO[R, E, (B, A)])(implicit trace: Trace): ZIO[R, E, B]

    /**
     * Writes a new value to the `Ref`, with a guarantee of immediate
     * consistency (at some cost to performance).
     */
    def set(a: A)(implicit trace: Trace): UIO[Unit]

    /**
     * Writes a new value to the `Ref` without providing a guarantee of
     * immediate consistency.
     */
    def setAsync(a: A)(implicit trace: Trace): UIO[Unit]

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified function,
     * returning the value immediately before modification.
     */
    def getAndUpdateZIO[R, E](f: A => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      modifyZIO(v => f(v).map(result => (v, result)))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified partial
     * function, returning the value immediately before modification. If the
     * function is undefined on the current value it doesn't change it.
     */
    def getAndUpdateSomeZIO[R, E](pf: PartialFunction[A, ZIO[R, E, A]])(implicit
      trace: Trace
    ): ZIO[R, E, A] =
      modifyZIO(v => pf.applyOrElse[A, ZIO[R, E, A]](v, ZIO.succeed(_)).map(result => (v, result)))

    final def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B] =
      modifyZIO(a => ZIO.succeed(f(a)))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified function,
     * which computes a return value for the modification if the function is
     * defined in the current value otherwise it returns a default value. This
     * is a more powerful version of `updateSome`.
     */
    def modifySomeZIO[R, E, B](default: B)(pf: PartialFunction[A, ZIO[R, E, (B, A)]])(implicit
      trace: Trace
    ): ZIO[R, E, B] =
      modifyZIO(v => pf.applyOrElse[A, ZIO[R, E, (B, A)]](v, _ => ZIO.succeed((default, v))))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified function.
     */
    def updateZIO[R, E](f: A => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, Unit] =
      modifyZIO(v => f(v).map(result => ((), result)))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified function,
     * returning the value immediately after modification.
     */
    def updateAndGetZIO[R, E](f: A => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      modifyZIO(v => f(v).map(result => (result, result)))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified partial
     * function. If the function is undefined on the current value it doesn't
     * change it.
     */
    def updateSomeZIO[R, E](pf: PartialFunction[A, ZIO[R, E, A]])(implicit
      trace: Trace
    ): ZIO[R, E, Unit] =
      modifyZIO(v => pf.applyOrElse[A, ZIO[R, E, A]](v, ZIO.succeed(_)).map(result => ((), result)))

    /**
     * Atomically modifies the `Ref.Synchronized` with the specified partial
     * function. If the function is undefined on the current value it returns
     * the old value without changing it.
     */
    def updateSomeAndGetZIO[R, E](pf: PartialFunction[A, ZIO[R, E, A]])(implicit
      trace: Trace
    ): ZIO[R, E, A] =
      modifyZIO(v => pf.applyOrElse[A, ZIO[R, E, A]](v, ZIO.succeed(_)).map(result => (result, result)))
  }

  object Synchronized {

    /**
     * Creates a new `Ref.Synchronized` with the specified value.
     */
    def make[A](a: => A)(implicit trace: Trace): UIO[Synchronized[A]] =
      ZIO.succeed(unsafe.make(a)(Unsafe))

    object unsafe {
      def make[A](a: A)(implicit unsafe: Unsafe): Synchronized[A] = {
        val ref       = Ref.unsafe.make(a)
        val semaphore = Semaphore.unsafe.make(1)
        new Ref.Synchronized[A] {
          def get(implicit trace: Trace): UIO[A] =
            ref.get
          def modifyZIO[R, E, B](f: A => ZIO[R, E, (B, A)])(implicit trace: Trace): ZIO[R, E, B] =
            semaphore.withPermit(get.flatMap(f).flatMap { case (b, a) => ref.set(a).as(b) })
          def set(a: A)(implicit trace: Trace): UIO[Unit] =
            semaphore.withPermit(ref.set(a))
          def setAsync(a: A)(implicit trace: Trace): UIO[Unit] =
            semaphore.withPermit(ref.setAsync(a))
        }
      }
    }
  }

  private[zio] final case class Atomic[A](value: AtomicReference[A]) extends Ref[A] {
    self =>

    def get(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.get(Unsafe))

    override def getAndSet(a: A)(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.getAndSet(a)(Unsafe))

    override def getAndUpdate(f: A => A)(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.getAndUpdate(f)(Unsafe))

    override def getAndUpdateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.getAndUpdateSome(pf)(Unsafe))

    def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B] =
      ZIO.succeed(unsafe.modify(f)(Unsafe))

    override def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)])(implicit trace: Trace): UIO[B] =
      ZIO.succeed(unsafe.modifySome(default)(pf)(Unsafe))

    def set(a: A)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.set(a)(Unsafe))

    def setAsync(a: A)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.setAsync(a)(Unsafe))

    override def update(f: A => A)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.update(f)(Unsafe))

    override def updateAndGet(f: A => A)(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.updateAndGet(f)(Unsafe))

    override def updateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.updateSome(pf)(Unsafe))

    override def updateSomeAndGet(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
      ZIO.succeed(unsafe.updateSomeAndGet(pf)(Unsafe))

    override def toString: String =
      s"Ref(${value.get})"

    trait UnsafeAPI extends Serializable {
      def get(implicit unsafe: Unsafe): A
      def getAndSet(a: A)(implicit unsafe: Unsafe): A
      def getAndUpdate(f: A => A)(implicit unsafe: Unsafe): A
      def getAndUpdateSome(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): A
      def modify[B](f: A => (B, A))(implicit unsafe: Unsafe): B
      def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)])(implicit unsafe: Unsafe): B
      def set(a: A)(implicit unsafe: Unsafe): Unit
      def setAsync(a: A)(implicit unsafe: Unsafe): Unit
      def update(f: A => A)(implicit unsafe: Unsafe): Unit
      def updateAndGet(f: A => A)(implicit unsafe: Unsafe): A
      def updateSome(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): Unit
      def updateSomeAndGet(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): A
    }

    val unsafe: UnsafeAPI =
      new UnsafeAPI {
        def get(implicit unsafe: Unsafe): A =
          value.get

        def getAndSet(a: A)(implicit unsafe: Unsafe): A = {
          var loop       = true
          var current: A = null.asInstanceOf[A]
          while (loop) {
            current = value.get
            loop = !value.compareAndSet(current, a)
          }
          current
        }

        def getAndUpdate(f: A => A)(implicit unsafe: Unsafe): A = {
          var loop       = true
          var current: A = null.asInstanceOf[A]
          while (loop) {
            current = value.get
            val next = f(current)
            loop = !value.compareAndSet(current, next)
          }
          current
        }

        def getAndUpdateSome(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): A = {
          var loop       = true
          var current: A = null.asInstanceOf[A]
          while (loop) {
            current = value.get
            val next = pf.applyOrElse(current, (_: A) => current)
            loop = !value.compareAndSet(current, next)
          }
          current
        }

        def modify[B](f: A => (B, A))(implicit unsafe: Unsafe): B = {
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

        def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)])(implicit unsafe: Unsafe): B = {
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

        def set(a: A)(implicit unsafe: Unsafe): Unit =
          value.set(a)

        def setAsync(a: A)(implicit unsafe: Unsafe): Unit =
          value.lazySet(a)

        def update(f: A => A)(implicit unsafe: Unsafe): Unit = {
          var loop    = true
          var next: A = null.asInstanceOf[A]
          while (loop) {
            val current = value.get
            next = f(current)
            loop = !value.compareAndSet(current, next)
          }
          ()
        }

        def updateAndGet(f: A => A)(implicit unsafe: Unsafe): A = {
          var loop    = true
          var next: A = null.asInstanceOf[A]
          while (loop) {
            val current = value.get
            next = f(current)
            loop = !value.compareAndSet(current, next)
          }
          next
        }

        def updateSome(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): Unit = {
          var loop    = true
          var next: A = null.asInstanceOf[A]
          while (loop) {
            val current = value.get
            next = pf.applyOrElse(current, (_: A) => current)
            loop = !value.compareAndSet(current, next)
          }
          ()
        }

        def updateSomeAndGet(pf: PartialFunction[A, A])(implicit unsafe: Unsafe): A = {
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
  }
}
