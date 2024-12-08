/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

import zio.internal.{FiberScope, IsFatal}
import zio.metrics.MetricLabel

/**
 * A `FiberRef` is ZIO's equivalent of Java's `ThreadLocal`. The value of a
 * `FiberRef` is automatically propagated to child fibers when they are forked
 * and merged back in to the value of the parent fiber after they are joined.
 *
 * {{{
 * for {
 *   fiberRef <- FiberRef.make("Hello world!")
 *   child    <- fiberRef.set("Hi!").fork
 *   _        <- child.join
 *   result   <- fiberRef.get
 * } yield result
 * }}}
 *
 * Here `result` will be equal to "Hi!" since changes made by a child fiber are
 * merged back in to the value of the parent fiber on join.
 *
 * By default the value of the child fiber will replace the value of the parent
 * fiber on join but you can specify your own logic for how values should be
 * merged.
 *
 * {{{
 * for {
 *   fiberRef <- FiberRef.make(0, identity[Int], math.max)
 *   child    <- fiberRef.update(_ + 1).fork
 *   _        <- fiberRef.update(_ + 2)
 *   _        <- child.join
 *   value    <- fiberRef.get
 * } yield value
 * }}}
 *
 * Here `value` will be 2 as the value in the joined fiber is lower and we
 * specified `max` as our combining function.
 */
trait FiberRef[A] extends Serializable { self =>

  /**
   * The type of the value of the `FiberRef`.
   */
  type Value = A

  /**
   * The type of the patch that describes updates to the value of the
   * `FiberRef`. In the simple case this will just be a function that sets the
   * value of the `FiberRef`. In more complex cases this will describe an update
   * to a piece of a whole value, allowing updates to the value by different
   * fibers to be combined in a compositional way when those fibers are joined.
   */
  type Patch

  /**
   * The initial value of the `FiberRef`.
   */
  def initial: Value

  /**
   * Constructs a patch describing the updates to a value from an old value and
   * a new value.
   */
  def diff(oldValue: Value, newValue: Value): Patch

  /**
   * Combines two patches to produce a new patch that describes the updates of
   * the first patch and then the updates of the second patch. The combine
   * operation should be associative. In addition, if the combine operation is
   * commutative then joining multiple fibers concurrently will result in
   * deterministic `FiberRef` values.
   */
  def combine(first: Patch, second: Patch): Patch

  /**
   * Applies a patch to an old value to produce a new value that is equal to the
   * old value with the updates described by the patch.
   */
  def patch(patch: Patch)(oldValue: Value): Value

  /**
   * The initial patch that is applied to the value of the `FiberRef` when a new
   * fiber is forked.
   */
  def fork: Patch

  def join(oldValue: Value, newValue: Value): Value

  def delete(implicit trace: Trace): UIO[Unit] =
    ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
      fiberState.deleteFiberRef(self)
      ZIO.unit
    }

  /**
   * Reads the value associated with the current fiber. Returns initial value if
   * no value was `set` or inherited from parent.
   */
  def get(implicit trace: Trace): UIO[A] =
    modify { v =>
      (v, v)
    }

  /**
   * Atomically sets the value associated with the current fiber and returns the
   * old value.
   */
  final def getAndSet(newValue: A)(implicit trace: Trace): UIO[A] =
    modify { v =>
      (v, newValue)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified function and returns
   * the old value.
   */
  final def getAndUpdate(f: A => A)(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = f(v)
      (v, result)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified partial function and
   * returns the old value. If the function is undefined on the current value it
   * doesn't change it.
   */
  final def getAndUpdateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      (v, result)
    }

  /**
   * Gets the value associated with the current fiber and uses it to run the
   * specified effect.
   */
  def getWith[R, E, B](f: A => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
    get.flatMap(f)

  /**
   * Returns a `ZIO` that runs with `value` bound to the current fiber.
   *
   * Guarantees that fiber data is properly restored via `acquireRelease`.
   */
  def locally[R, E, B](newValue: A)(zio: ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
    ZIO.acquireReleaseWith(get <* set(newValue))(set)(_ => zio)

  /**
   * Returns a `ZIO` that runs with `f` applied to the current fiber.
   *
   * Guarantees that fiber data is properly restored via `acquireRelease`.
   */
  final def locallyWith[R, E, B](f: A => A)(zio: ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, B] =
    getWith(a => locally(f(a))(zio))

  /**
   * Returns a scoped workflow that sets the value associated with the curent
   * fiber to the specified value and restores it to its original value when the
   * scope is closed.
   */
  final def locallyScoped(value: A)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ZIO.acquireRelease(get.flatMap(old => set(value).as(old)))(set).unit

  /**
   * Returns a scoped workflow that updates the value associated with the
   * current fiber using the specified function and restores it to its original
   * value when the scope is closed.
   */
  final def locallyScopedWith(f: A => A)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    getWith(a => locallyScoped(f(a))(trace))

  /**
   * Atomically modifies the `FiberRef` with the specified function, which
   * computes a return value for the modification. This is a more powerful
   * version of `update`.
   */
  def modify[B](f: A => (B, A))(implicit trace: Trace): UIO[B] =
    ZIO.withFiberRuntime[Any, Nothing, B] { (fiberState, _) =>
      val (b, a) = f(fiberState.getFiberRef(self))

      fiberState.setFiberRef(self, a)

      ZIO.succeed(b)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified partial function,
   * which computes a return value for the modification if the function is
   * defined in the current value otherwise it returns a default value. This is
   * a more powerful version of `updateSome`.
   */
  final def modifySome[B](default: B)(pf: PartialFunction[A, (B, A)])(implicit trace: Trace): UIO[B] =
    modify { v =>
      pf.applyOrElse[A, (B, A)](v, _ => (default, v))
    }

  final def reset(implicit trace: Trace): UIO[Unit] =
    set(initial)

  /**
   * Sets the value associated with the current fiber.
   */
  def set(value: A)(implicit trace: Trace): UIO[Unit] =
    modify { _ =>
      ((), value)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified function.
   */
  def update(f: A => A)(implicit trace: Trace): UIO[Unit] =
    modify { v =>
      ((), f(v))
    }

  /**
   * Atomically modifies the `FiberRef` with the specified function and returns
   * the result.
   */
  final def updateAndGet(f: A => A)(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = f(v)
      (result, result)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified partial function. If
   * the function is undefined on the current value it doesn't change it.
   */
  final def updateSome(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[Unit] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      ((), result)
    }

  /**
   * Atomically modifies the `FiberRef` with the specified partial function. If
   * the function is undefined on the current value it returns the old value
   * without changing it.
   */
  final def updateSomeAndGet(pf: PartialFunction[A, A])(implicit trace: Trace): UIO[A] =
    modify { v =>
      val result = pf.applyOrElse[A, A](v, identity)
      (result, result)
    }

  /**
   * Returns a `ThreadLocal` that can be used to interact with this `FiberRef`
   * from side effecting code.
   *
   * This feature is meant to be used for integration with side effecting code,
   * that needs to access fiber specific data, like MDC contexts and the like.
   * The returned `ThreadLocal` will be backed by this `FiberRef` on all threads
   * that are currently managed by ZIO when this feature is enabled using
   * [[Runtime.enableCurrentFiber]], and behave like an ordinary `ThreadLocal`
   * on all other threads.
   */
  def asThreadLocal(implicit trace: Trace, unsafe: Unsafe): UIO[ThreadLocal[A]] =
    ZIO.succeed {
      new ThreadLocal[A] {
        override def get(): A = {
          val fiber = Fiber._currentFiber.get()

          if (fiber eq null) super.get()
          else fiber.getFiberRef(self)
        }

        override def set(a: A): Unit = {
          val fiber    = Fiber._currentFiber.get()
          val fiberRef = self.asInstanceOf[FiberRef[Any]]

          if (fiber eq null) super.set(a)
          else fiber.setFiberRef(fiberRef, a)
        }

        override def remove(): Unit = {
          val fiber    = Fiber._currentFiber.get()
          val fiberRef = self

          if (fiber eq null) super.remove()
          else
            fiber.deleteFiberRef(fiberRef)
        }

        override def initialValue(): A = initial
      }
    }

  /**
   * Flag used to determine whether Patch created by the fork method creates an
   * empty Patch that doesn't modify the value.
   *
   * '''NOTE FOR ZIO LIBRARIES''' This method is package-private only so that it
   * can be accessed from `FiberRefs`. Do not use it
   */
  private[zio] def hasIdentityFork: Boolean = false

  /**
   * Flag used to determine whether join method returns
   *
   * '''NOTE FOR ZIO LIBRARIES''' This method is package-private only so that it
   * can be accessed from `FiberRefs`. Do not use it
   */
  private[zio] def hasSecondFnJoin: Boolean = false
}

object FiberRef {
  import Differ._

  /**
   * Wraps another `FiberRef` and delegates all operations to it. Extend this if
   * you need a `FiberRef` with some specific behavior overridden.
   */
  abstract class Proxy[A](val delegate: FiberRef[A]) extends FiberRef[A] {
    override def initial: A = delegate.initial

    override type Patch = delegate.Patch

    override def diff(oldValue: Value, newValue: Value): Patch = delegate.diff(oldValue, newValue)

    override def combine(first: Patch, second: Patch): Patch = delegate.combine(first, second)

    override def patch(patch: Patch)(oldValue: Value): Value = delegate.patch(patch)(oldValue)

    override def fork: Patch = delegate.fork

    override def join(oldValue: Value, newValue: Value): Value = delegate.join(oldValue, newValue)

    override def hasIdentityFork: Boolean = delegate.hasIdentityFork

    override def hasSecondFnJoin: Boolean = delegate.hasSecondFnJoin
  }

  type WithPatch[Value0, Patch0] = FiberRef[Value0] { type Patch = Patch0 }

  val currentLogLevel: FiberRef[LogLevel] =
    FiberRef.unsafe.make(LogLevel.Info)(Unsafe.unsafe)

  val currentLogSpan: FiberRef[List[LogSpan]] =
    FiberRef.unsafe.make[List[LogSpan]](Nil)(Unsafe.unsafe)

  val currentLogAnnotations: FiberRef[Map[String, String]] =
    FiberRef.unsafe.make[Map[String, String]](Map.empty)(Unsafe.unsafe)

  val currentTags: FiberRef.WithPatch[Set[MetricLabel], SetPatch[MetricLabel]] =
    FiberRef.unsafe.makeSet(Set.empty)(Unsafe.unsafe)

  /**
   * Creates a new `FiberRef` with given initial value.
   */
  def make[A](
    initial: => A,
    fork: A => A = ZIO.identityFn[A],
    join: (A, A) => A = ZIO.secondFn[A]
  )(implicit trace: Trace): ZIO[Scope, Nothing, FiberRef[A]] =
    makeWith(unsafe.make(initial, fork, join)(Unsafe.unsafe))

  /**
   * Creates a new `FiberRef` with specified initial value of the
   * `ZEnvironment`, using `ZEnvironment.Patch` to combine updates to the
   * `ZEnvironment` in a compositional way.
   */
  def makeEnvironment[A](initial: => ZEnvironment[A])(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, FiberRef.WithPatch[ZEnvironment[A], ZEnvironment.Patch[A, A]]] =
    makeWith(unsafe.makeEnvironment(initial)(Unsafe.unsafe))

  /**
   * Creates a new `FiberRef` with the specified initial value, using the
   * specified patch type to combine updates to the value in a compositional
   * way.
   */
  def makePatch[Value, Patch](
    initial: Value,
    differ: Differ[Value, Patch]
  )(implicit trace: Trace): ZIO[Scope, Nothing, FiberRef.WithPatch[Value, Patch]] =
    makePatch(initial, differ, differ.empty)

  /**
   * Creates a new `FiberRef` with the specified initial value, using the
   * specified patch type to combine updates to the value in a compositional
   * way.
   */
  def makePatch[Value, Patch](
    initial: Value,
    differ: Differ[Value, Patch],
    fork: Patch
  )(implicit trace: Trace): ZIO[Scope, Nothing, FiberRef.WithPatch[Value, Patch]] =
    makeWith(unsafe.makePatch(initial, differ, fork)(Unsafe.unsafe))

  def makeRuntimeFlags(initial: RuntimeFlags)(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, FiberRef.WithPatch[RuntimeFlags, RuntimeFlags.Patch]] =
    makeWith(unsafe.makeRuntimeFlags(initial)(Unsafe.unsafe))

  def makeSet[A](initial: => Set[A])(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, FiberRef.WithPatch[Set[A], SetPatch[A]]] =
    makeWith(unsafe.makeSet(initial)(Unsafe.unsafe))

  object unsafe {
    def make[A](
      initial: A,
      fork: A => A = ZIO.identityFn[A],
      join: (A, A) => A = ZIO.secondFn[A]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[A, A => A] =
      makePatch[A, A => A](
        initial,
        Differ.update[A],
        fork,
        join
      )

    def makeEnvironment[A](
      initial: ZEnvironment[A]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[ZEnvironment[A], ZEnvironment.Patch[A, A]] =
      makePatch[ZEnvironment[A], ZEnvironment.Patch[A, A]](
        initial,
        Differ.environment,
        ZEnvironment.Patch.empty
      )

    def makeIsFatal[A](
      initial: IsFatal
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[IsFatal, IsFatal.Patch] =
      makePatch[IsFatal, IsFatal.Patch](
        initial,
        Differ.isFatal,
        IsFatal.Patch.empty
      )

    def makeMap[A, B, Patch](
      initial: Map[A, B],
      differ: Differ[B, Patch]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[Map[A, B], MapPatch[A, B, Patch]] =
      makePatch[Map[A, B], MapPatch[A, B, Patch]](
        initial,
        Differ.map(differ),
        MapPatch.empty
      )

    def makePatch[Value0, Patch0](
      initialValue0: Value0,
      differ: Differ[Value0, Patch0],
      fork0: Patch0,
      join0: (Value0, Value0) => Value0 = ZIO.secondFn[Value0]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[Value0, Patch0] =
      new FiberRef[Value0] {
        self =>
        type Patch = Patch0

        def combine(first: Patch, second: Patch): Patch =
          differ.combine(first, second)

        def diff(oldValue: Value, newValue: Value): Patch =
          differ.diff(oldValue, newValue)

        def fork: Patch =
          fork0

        def initial: Value =
          initialValue0

        def patch(patch: Patch)(oldValue: Value): Value =
          differ.patch(patch)(oldValue)

        def join(oldValue: Value, newValue: Value): Value =
          join0(oldValue, newValue)

        override def get(implicit trace: Trace): UIO[Value] =
          ZIO.withFiberRuntime[Any, Nothing, Value] { (fiberState, _) =>
            ZIO.succeed(fiberState.getFiberRef(self))
          }

        override def getWith[R, E, A](f: Value => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          ZIO.withFiberRuntime[R, E, A] { (fiberState, _) =>
            f(fiberState.getFiberRef(self))
          }

        override def locally[R, E, A](newValue: Value)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          ZIO.withFiberRuntime[R, E, A] { (fiberState, _) =>
            val oldRefs = fiberState.getFiberRefs(false)
            val newRefs = oldRefs.updatedAs(fiberState.id)(self, newValue)

            if (newRefs eq oldRefs) zio
            else {
              fiberState.setFiberRefs(newRefs)
              ZIO.uninterruptibleMask { restore =>
                restore(zio).exitWith { exit =>
                  val oldValue = oldRefs.getOrNull(self)
                  if (oldValue == null) fiberState.resetFiberRef(self)
                  else fiberState.setFiberRef(self, oldValue)
                  exit
                }
              }
            }
          }

        override def set(value: Value)(implicit trace: Trace): UIO[Unit] =
          ZIO.withFiberRuntime[Any, Nothing, Unit] { (fiberState, _) =>
            fiberState.setFiberRef(self, value)

            ZIO.unit
          }

        // Store the hash code in a val to avoid recomputing it on every access of the FiberRefs map
        // Ideally we'd do that in `FiberRef` itself, but that's not binary compatible
        final override val hashCode: Int = super.hashCode()

        final override private[zio] val hasIdentityFork: Boolean = fork0 == differ.empty
        final override private[zio] val hasSecondFnJoin: Boolean = join0 == ZIO.secondFn[Value0]
      }

    def makeRuntimeFlags(
      initial: RuntimeFlags
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[RuntimeFlags, RuntimeFlags.Patch] =
      makePatch[RuntimeFlags, RuntimeFlags.Patch](
        initial,
        Differ.runtimeFlags,
        RuntimeFlags.Patch.empty
      )

    def makeSet[A](
      initial: Set[A]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[Set[A], SetPatch[A]] =
      makePatch[Set[A], SetPatch[A]](
        initial,
        Differ.set,
        SetPatch.empty
      )

    def makeSupervisor[A](
      initial: Supervisor[Any]
    )(implicit unsafe: Unsafe): FiberRef.WithPatch[Supervisor[Any], Supervisor.Patch] =
      makePatch[Supervisor[Any], Supervisor.Patch](
        initial,
        Differ.supervisor,
        Supervisor.Patch.empty
      )
  }

  private[zio] val forkScopeOverride: FiberRef[Option[FiberScope]] =
    FiberRef.unsafe.make[Option[FiberScope]](None, _ => None, (parent, _) => parent)(Unsafe.unsafe)

  private[zio] val overrideExecutor: FiberRef[Option[Executor]] =
    FiberRef.unsafe.make[Option[Executor]](None)(Unsafe.unsafe)

  private[zio] val currentEnvironment: FiberRef.WithPatch[ZEnvironment[Any], ZEnvironment.Patch[Any, Any]] =
    FiberRef.unsafe.makeEnvironment(ZEnvironment.empty)(Unsafe.unsafe)

  private[zio] val interruptedCause: FiberRef[Cause[Nothing]] =
    FiberRef.unsafe.make[Cause[Nothing]](Cause.empty, _ => Cause.empty, (parent, _) => parent)(Unsafe.unsafe)

  private[zio] val currentBlockingExecutor: FiberRef[Executor] =
    FiberRef.unsafe.make(Runtime.defaultBlockingExecutor)(Unsafe.unsafe)

  private[zio] val currentFatal: FiberRef.WithPatch[IsFatal, IsFatal.Patch] =
    FiberRef.unsafe.makeIsFatal(Runtime.defaultFatal)(Unsafe.unsafe)

  private[zio] val currentFiberIdGenerator: FiberRef[FiberId.Gen] =
    FiberRef.unsafe.make[FiberId.Gen](FiberId.Gen.Live)(Unsafe.unsafe)

  private[zio] val currentLoggers: FiberRef.WithPatch[Set[ZLogger[String, Any]], SetPatch[ZLogger[String, Any]]] =
    FiberRef.unsafe.makeSet(Runtime.defaultLoggers)(Unsafe.unsafe)

  private[zio] val currentReportFatal: FiberRef[Throwable => Nothing] =
    FiberRef.unsafe.make(Runtime.defaultReportFatal)(Unsafe.unsafe)

  private[zio] val currentRuntimeFlags: FiberRef.WithPatch[RuntimeFlags, RuntimeFlags.Patch] =
    FiberRef.unsafe.makeRuntimeFlags(RuntimeFlags.none)(Unsafe.unsafe)

  private[zio] val currentSupervisor: FiberRef.WithPatch[Supervisor[Any], Supervisor.Patch] =
    FiberRef.unsafe.makeSupervisor(Runtime.defaultSupervisor)(Unsafe.unsafe)

  private[zio] val unhandledErrorLogLevel: FiberRef[Option[LogLevel]] =
    FiberRef.unsafe.make[Option[LogLevel]](Some(LogLevel.Debug))(Unsafe.unsafe)

  private def makeWith[Value, Patch](
    ref: => FiberRef.WithPatch[Value, Patch]
  )(implicit trace: Trace): ZIO[Scope, Nothing, FiberRef.WithPatch[Value, Patch]] =
    ZIO.acquireRelease(ZIO.succeed(ref).tap(_.update(identity)))(_.delete)
}
