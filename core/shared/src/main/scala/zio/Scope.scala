/*
 * Copyright 2022-2024 John A. De Goes and the ZIO Contributors
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
import scala.collection.immutable.LongMap

/**
 * A `Scope` is the foundation of safe, composable resource management in ZIO. A
 * scope has two fundamental operators, `addFinalizer`, which adds a finalizer
 * to the scope, and `close`, which closes a scope and runs all finalizers that
 * have been added to the scope.
 */
trait Scope extends Serializable { self =>

  /**
   * Adds a finalizer to this scope. The finalizer is guaranteed to be run when
   * the scope is closed.
   */
  def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any])(implicit trace: Trace): UIO[Unit]

  /**
   * Forks a new scope that is a child of this scope. Finalizers added to the
   * child scope will be run according to the specified `ExecutionStrategy`. The
   * child scope will automatically be closed when this scope is closed.
   */
  def forkWith(executionStrategy: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable]

  /**
   * A simplified version of `addFinalizerWith` when the `finalizer` does not
   * depend on the `Exit` value that the scope is closed with.
   */
  final def addFinalizer(finalizer: => UIO[Any])(implicit trace: Trace): UIO[Unit] =
    addFinalizerExit(_ => finalizer)

  /**
   * The execution strategy finalizers associated with this scope will be run
   * with.
   */
  def executionStrategy: ExecutionStrategy =
    ExecutionStrategy.Sequential

  /**
   * Extends the scope of a `ZIO` workflow that needs a scope into this scope by
   * providing it to the workflow but not closing the scope when the workflow
   * completes execution. This allows extending a scoped value into a larger
   * scope.
   */
  final def extend[R]: Scope.ExtendPartiallyApplied[R] =
    new Scope.ExtendPartiallyApplied[R](self)

  /**
   * Forks a new scope that is a child of this scope. Finalizers added to this
   * scope will be run sequentially in the reverse of the order in which they
   * were added when this scope is closed. The child scope will automatically be
   * closed when this scope is closed.
   */
  final def fork(implicit trace: Trace): UIO[Scope.Closeable] =
    forkWith(executionStrategy)
}

object Scope {

  sealed trait Closeable extends Scope { self =>

    /**
     * Closes a scope with the specified exit value, running all finalizers that
     * have been added to the scope.
     */
    def close(exit: => Exit[Any, Any])(implicit trace: Trace): UIO[Unit]

    /**
     * Returns the number of finalizers that have been added to this scope and
     * not yet finalized.
     */
    def size: Int

    /**
     * Uses the scope by providing it to a `ZIO` workflow that needs a scope,
     * guaranteeing that the scope is closed with the result of that workflow as
     * soon as the workflow completes execution, whether by success, failure, or
     * interruption.
     */
    final def use[R]: Scope.UsePartiallyApplied[R] =
      new Scope.UsePartiallyApplied[R](self)
  }

  /**
   * Accesses a scope in the environment and adds a finalizer to it.
   */
  def addFinalizer(finalizer: => UIO[Any])(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addFinalizer(finalizer))

  /**
   * Accesses a scope in the environment and adds a finalizer to it.
   */
  def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any])(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, Unit] =
    ZIO.serviceWithZIO(_.addFinalizerExit(finalizer))

  /**
   * A layer that constructs a scope and closes it when the workflow the layer
   * is provided to completes execution, whether by success, failure, or
   * interruption. This can be used to close a scope when providing a layer to a
   * workflow.
   */
  val default: ZLayer[Any, Nothing, Scope] =
    ZLayer.scopedEnvironment(
      ZIO
        .acquireReleaseExit(Scope.make(Trace.empty))((scope, exit) => scope.close(exit)(Trace.empty))(
          Trace.empty
        )
        .map(ZEnvironment.empty.unsafe.addScope(_)(Unsafe))(Trace.empty)
    )(Trace.empty)

  /**
   * The global scope which is never closed. Finalizers added to this scope will
   * be immediately discarded and closing this scope has no effect.
   */
  val global: Scope.Closeable =
    new Scope.Closeable {
      def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any])(implicit trace: Trace): UIO[Unit] =
        ZIO.unit
      def close(exit: => Exit[Any, Any])(implicit trace: Trace): UIO[Unit] =
        ZIO.unit
      def forkWith(executionStrategy: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable] =
        makeWith(executionStrategy)
      def size: Int = 0
    }

  /**
   * Makes a scope. Finalizers added to this scope will be run sequentially in
   * the reverse of the order in which they were added when this scope is
   * closed.
   */
  def make(implicit trace: Trace): UIO[Scope.Closeable] =
    ZIO.succeed(unsafe.make(Unsafe))

  /**
   * Makes a scope. Finalizers added to this scope will be run according to the
   * specified `ExecutionStrategy`.
   */
  def makeWith(executionStrategy0: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable] =
    ZIO.succeed(unsafe.makeWith(executionStrategy0)(Unsafe))

  /**
   * Makes a scope. Finalizers added to this scope will be run in parallel when
   * this scope is closed.
   */
  def parallel(implicit trace: Trace): UIO[Scope.Closeable] =
    makeWith(ExecutionStrategy.Parallel)

  object unsafe {
    def make(implicit unsafe: Unsafe): Scope.Closeable =
      new CloseableScope(ExecutionStrategy.Sequential)

    def makeWith(executionStrategy: ExecutionStrategy)(implicit unsafe: Unsafe): Scope.Closeable =
      new CloseableScope(executionStrategy)

    private final class CloseableScope(override val executionStrategy: ExecutionStrategy) extends Scope.Closeable {
      private[this] val releaseMap = new ReleaseMap

      def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any])(implicit trace: Trace): UIO[Unit] =
        releaseMap.addDiscard(finalizer)

      def close(exit: => Exit[Any, Any])(implicit trace: Trace): UIO[Unit] =
        releaseMap.releaseAll(exit, executionStrategy)

      def forkWith(executionStrategy: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable] =
        ZIO.uninterruptible {
          val scope = Scope.unsafe.makeWith(executionStrategy)(Unsafe)
          releaseMap
            .add(scope.close(_))
            .flatMap(scope.addFinalizerExit)
            .zipRight(Exit.succeed(scope))
        }

      def size: Int =
        releaseMap.size
    }
  }

  final class ExtendPartiallyApplied[R](private val scope: Scope) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      zio.provideSomeEnvironment[R](_.unsafe.addScope(scope)(Unsafe))
  }

  final class UsePartiallyApplied[R](private val scope: Scope.Closeable) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      scope.extend[R](zio).onExit(scope.close(_))
  }

  private type Finalizer = Exit[Any, Any] => UIO[Any]

  private sealed abstract class State
  private object State {
    // The sorting order of the LongMap uses bit ordering (000, 001, ... 111 but with 64 bits). This
    // works out to be `0 ... Long.MaxValue, Long.MinValue, ... -1`. The order of the map is mainly
    // important for the finalization, in which we want to walk it in reverse order. So we insert
    // into the map using keys that will build it in reverse. That way, when we do the final iteration,
    // the finalizers are already in correct order.
    final val initial: State = Running(-1L, LongMap.empty)

    final case class Exited(exit: Exit[Any, Any])                           extends State
    final case class Running(nextKey: Long, finalizers: LongMap[Finalizer]) extends State
  }

  /**
   * A `ReleaseMap` represents the finalizers associated with a scope.
   *
   * The design of `ReleaseMap` is inspired by ResourceT, written by Michael
   * Snoyman @snoyberg.
   * (https://github.com/snoyberg/conduit/blob/master/resourcet/Control/Monad/Trans/Resource/Internal.hs)
   */
  private final class ReleaseMap extends AtomicReference[State](State.initial) with Serializable {
    ref: AtomicReference[State] =>
    import State.{Exited, Running}

    private[this] def next(l: Long) =
      if (l == 0L) throw new RuntimeException("ReleaseMap wrapped around")
      else if (l == Long.MinValue) Long.MaxValue
      else l - 1

    private[this] def modify[B](f: State => (UIO[B], State))(implicit trace: Trace): UIO[B] =
      ZIO.suspendSucceed {
        var b = null.asInstanceOf[UIO[B]]
        while (b eq null) {
          val current = ref.get()
          val kv      = f(current)
          if (ref.compareAndSet(current, kv._2)) b = kv._1
        }
        b
      }

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     *
     * The finalizer returned from this method will remove the original
     * finalizer from the map and run it.
     */
    def add(finalizer: Finalizer)(implicit trace: Trace): UIO[Finalizer] =
      modify {
        case Running(nextKey, fins) =>
          (
            Exit.succeed(release(nextKey, _)),
            Running(next(nextKey), fins.updated(nextKey, finalizer))
          )
        case Exited(exit) =>
          (
            ZIO.suspendSucceed(finalizer(exit)) *> ReleaseMap.noopFinalizer,
            Exited(exit)
          )
      }

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     */
    def addDiscard(finalizer: Finalizer)(implicit trace: Trace): UIO[Unit] =
      modify {
        case Running(nextKey, fins) =>
          (
            Exit.unit,
            Running(next(nextKey), fins.updated(nextKey, finalizer))
          )
        case Exited(exit) =>
          (
            ZIO.suspendSucceed(finalizer(exit).unit),
            Exited(exit)
          )
      }

    /**
     * Runs the specified finalizer and removes it from the finalizers
     * associated with this scope.
     */
    def release(key: Long, exit: Exit[Any, Any])(implicit trace: Trace): UIO[Any] =
      modify {
        case s @ Running(_, fins) =>
          (
            ZIO.suspendSucceed {
              val fin = fins.getOrElse(key, null)
              if (fin eq null) Exit.unit
              else fin(exit)
            },
            s.copy(finalizers = fins - key)
          )
        case s: Exited => (Exit.unit, s)
      }

    /**
     * Runs the finalizers associated with this scope using the specified
     * execution strategy. After this action finishes, any finalizers added to
     * this scope will be run immediately.
     */
    def releaseAll(exit: Exit[Any, Any], execStrategy: ExecutionStrategy)(implicit trace: Trace): UIO[Unit] =
      modify {
        case s: Exited => (Exit.unit, s)
        case Running(_, fins) =>
          val finalizer =
            if (fins.isEmpty)
              Exit.unit
            else if (fins.firstKey == fins.lastKey) // Most efficient way of checking for size == 1 on LongMap
              ZIO.suspendSucceed(fins(fins.firstKey)(exit)).unit
            else {
              execStrategy match {
                case ExecutionStrategy.Sequential =>
                  ZIO.suspendSucceed {
                    var error = null.asInstanceOf[Cause[Nothing]]
                    val it    = fins.values.iterator
                    ZIO
                      .whileLoop(it.hasNext)(it.next()(exit).exit) {
                        case _: Exit.Success[?]               => ()
                        case Exit.Failure(c) if error eq null => error = c
                        case Exit.Failure(c)                  => error = error ++ c
                      }
                      .flatMap(_ => if (error eq null) Exit.unit else Exit.failCause(error))
                  }
                case ExecutionStrategy.Parallel =>
                  ZIO
                    .foreachPar(fins.values)(fin => fin(exit).exit)
                    .flatMap(Exit.collectAllParDiscard)

                case ExecutionStrategy.ParallelN(n) =>
                  ZIO
                    .foreachPar(fins.values)(fin => fin(exit).exit)
                    .flatMap(Exit.collectAllParDiscard)
                    .withParallelism(n)
              }
            }
          (finalizer, Exited(exit))
      }

    /**
     * Number of finalizers that have not yet been run
     */
    def size: Int =
      ref.get match {
        case _: Exited        => 0
        case Running(_, fins) => fins.size
      }
  }

  private object ReleaseMap {
    private val noopFinalizer = Exit.succeed((_: Exit[Any, Any]) => Exit.unit)

    /**
     * Creates a new ReleaseMap.
     */
    def make(implicit trace: Trace): UIO[ReleaseMap] =
      ZIO.succeed(new ReleaseMap)
  }
}
