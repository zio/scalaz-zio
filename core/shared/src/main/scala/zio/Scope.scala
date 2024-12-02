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
        .map(ZEnvironment.empty.unsafe.addScope(_)(Unsafe.unsafe))(Trace.empty)
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
    makeWith(ExecutionStrategy.Sequential)

  /**
   * Makes a scope. Finalizers added to this scope will be run according to the
   * specified `ExecutionStrategy`.
   */
  def makeWith(executionStrategy0: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable] =
    ReleaseMap.make.map { releaseMap =>
      new Scope.Closeable { self =>
        def addFinalizerExit(finalizer: Exit[Any, Any] => UIO[Any])(implicit trace: Trace): UIO[Unit] =
          releaseMap.add(finalizer).unit
        def close(exit: => Exit[Any, Any])(implicit trace: Trace): UIO[Unit] =
          ZIO.suspendSucceed(releaseMap.releaseAll(exit, executionStrategy).unit)
        override val executionStrategy: ExecutionStrategy =
          executionStrategy0
        def forkWith(executionStrategy: => ExecutionStrategy)(implicit trace: Trace): UIO[Scope.Closeable] =
          ZIO.uninterruptible {
            for {
              scope     <- Scope.makeWith(executionStrategy)
              finalizer <- releaseMap.add(scope.close(_))
              _         <- scope.addFinalizerExit(finalizer)
            } yield scope
          }
        def size: Int =
          releaseMap.size
      }
    }

  /**
   * Makes a scope. Finalizers added to this scope will be run in parallel when
   * this scope is closed.
   */
  def parallel(implicit trace: Trace): UIO[Scope.Closeable] =
    makeWith(ExecutionStrategy.Parallel)

  final class ExtendPartiallyApplied[R](private val scope: Scope) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      zio.provideSomeEnvironment[R](_.unsafe.addScope(scope)(Unsafe.unsafe))
  }

  final class UsePartiallyApplied[R](private val scope: Scope.Closeable) extends AnyVal {
    def apply[E, A](zio: => ZIO[Scope with R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      scope.extend[R](zio).onExit(scope.close(_))
  }

  private type Finalizer = Exit[Any, Any] => UIO[Any]

  private sealed abstract class State
  private final case class Exited(nextKey: Long, exit: Exit[Any, Any], update: Finalizer => Finalizer) extends State
  private final case class Running(nextKey: Long, finalizers: LongMap[Finalizer], update: Finalizer => Finalizer)
      extends State

  /**
   * A `ReleaseMap` represents the finalizers associated with a scope.
   *
   * The design of `ReleaseMap` is inspired by ResourceT, written by Michael
   * Snoyman @snoyberg.
   * (https://github.com/snoyberg/conduit/blob/master/resourcet/Control/Monad/Trans/Resource/Internal.hs)
   */
  private abstract class ReleaseMap extends Serializable {

    /**
     * An opaque identifier for a finalizer stored in the map.
     */
    type Key

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     *
     * The finalizer returned from this method will remove the original
     * finalizer from the map and run it.
     */
    def add(finalizer: Finalizer)(implicit trace: Trace): UIO[Finalizer]

    /**
     * Adds a finalizer to the finalizers associated with this scope. If the
     * scope is still open, a [[Key]] will be returned. This is an opaque
     * identifier that can be used to activate this finalizer and remove it from
     * the map. from the map. If the scope has been closed, the finalizer will
     * be executed immediately (with the [[Exit]] value with which the scope has
     * ended) and no Key will be returned.
     */
    def addIfOpen(finalizer: Finalizer)(implicit trace: Trace): UIO[Option[Key]]

    /**
     * Retrieves the finalizer associated with this key.
     */
    def get(key: Key)(implicit trace: Trace): UIO[Option[Finalizer]]

    /**
     * Runs the specified finalizer and removes it from the finalizers
     * associated with this scope.
     */
    def release(key: Key, exit: Exit[Any, Any])(implicit trace: Trace): UIO[Any]

    /**
     * Runs the finalizers associated with this scope using the specified
     * execution strategy. After this action finishes, any finalizers added to
     * this scope will be run immediately.
     */
    def releaseAll(exit: Exit[Any, Any], execStrategy: ExecutionStrategy)(implicit trace: Trace): UIO[Any]

    /**
     * Removes the finalizer associated with this key and returns it.
     */
    def remove(key: Key)(implicit trace: Trace): UIO[Option[Finalizer]]

    /**
     * Replaces the finalizer associated with this key and returns it. If the
     * finalizers associated with this scope have already been run this
     * finalizer will be run immediately.
     */
    def replace(key: Key, finalizer: Finalizer)(implicit trace: Trace): UIO[Option[Finalizer]]

    /**
     * Number of finalizers that have not yet been run
     */
    def size: Int

    /**
     * Updates the finalizers associated with this scope using the specified
     * function.
     */
    def updateAll(f: Finalizer => Finalizer)(implicit trace: Trace): UIO[Unit]

  }

  private object ReleaseMap {

    /**
     * Creates a new ReleaseMap.
     */
    def make(implicit trace: Trace): UIO[ReleaseMap] =
      ZIO.succeed(unsafe.make()(Unsafe.unsafe))

    private object unsafe {

      /**
       * Creates a new ReleaseMap.
       */
      def make()(implicit Unsafe: Unsafe) = {
        // The sorting order of the LongMap uses bit ordering (000, 001, ... 111 but with 64 bits). This
        // works out to be `0 ... Long.MaxValue, Long.MinValue, ... -1`. The order of the map is mainly
        // important for the finalization, in which we want to walk it in reverse order. So we insert
        // into the map using keys that will build it in reverse. That way, when we do the final iteration,
        // the finalizers are already in correct order.
        val initialKey: Long = -1L

        def next(l: Long) =
          if (l == 0L) throw new RuntimeException("ReleaseMap wrapped around")
          else if (l == Long.MinValue) Long.MaxValue
          else l - 1

        val ref =
          Ref.unsafe.make[State](Running(initialKey, LongMap.empty, identity))

        new ReleaseMap {
          type Key = Long

          def add(finalizer: Finalizer)(implicit trace: Trace): UIO[Finalizer] =
            addIfOpen(finalizer).map {
              case Some(key) => release(key, _)
              case None      => _ => ZIO.unit
            }

          def addIfOpen(finalizer: Finalizer)(implicit trace: Trace): UIO[Option[Key]] =
            ref.modify {
              case Exited(nextKey, exit, update) =>
                finalizer(exit).as(None) -> Exited(next(nextKey), exit, update)
              case Running(nextKey, fins, update) =>
                ZIO.succeed(Some(nextKey)) -> Running(next(nextKey), fins.updated(nextKey, finalizer), update)
            }.flatten

          def get(key: Key)(implicit trace: Trace): UIO[Option[Finalizer]] =
            ref.get.map {
              case Exited(_, _, _)     => None
              case Running(_, fins, _) => fins get key
            }

          def release(key: Key, exit: Exit[Any, Any])(implicit trace: Trace): UIO[Any] =
            ref.modify {
              case s @ Exited(_, _, _) => (ZIO.unit, s)
              case s @ Running(_, fins, update) =>
                (
                  fins.get(key).fold(ZIO.unit: UIO[Any])(fin => update(fin)(exit)),
                  s.copy(finalizers = fins - key)
                )
            }.flatten

          def releaseAll(exit: Exit[Any, Any], execStrategy: ExecutionStrategy)(implicit trace: Trace): UIO[Any] =
            ref.modify {
              case s @ Exited(_, _, _) => (ZIO.unit, s)
              case Running(nextKey, fins, update) =>
                execStrategy match {
                  case ExecutionStrategy.Sequential =>
                    (
                      ZIO
                        .foreach(fins.values) { fin =>
                          update(fin).apply(exit).exit
                        }
                        .flatMap(Exit.collectAllDiscard),
                      Exited(nextKey, exit, update)
                    )

                  case ExecutionStrategy.Parallel =>
                    (
                      ZIO
                        .foreachPar(fins.values) { fin =>
                          update(fin)(exit).exit
                        }
                        .flatMap(Exit.collectAllParDiscard),
                      Exited(nextKey, exit, update)
                    )

                  case ExecutionStrategy.ParallelN(n) =>
                    (
                      ZIO
                        .foreachPar(fins.values) { fin =>
                          update(fin)(exit).exit
                        }
                        .flatMap(Exit.collectAllParDiscard)
                        .withParallelism(n),
                      Exited(nextKey, exit, update)
                    )

                }
            }.flatten

          def remove(key: Key)(implicit trace: Trace): UIO[Option[Finalizer]] =
            ref.modify {
              case Exited(nk, exit, update)  => (None, Exited(nk, exit, update))
              case Running(nk, fins, update) => (fins get key, Running(nk, fins - key, update))
            }

          def replace(key: Key, finalizer: Finalizer)(implicit trace: Trace): UIO[Option[Finalizer]] =
            ref.modify {
              case Exited(nk, exit, update) => (finalizer(exit).as(None), Exited(nk, exit, update))
              case Running(nk, fins, update) =>
                (ZIO.succeed(fins get key), Running(nk, fins.updated(key, finalizer), update))
            }.flatten

          def size: Int =
            ref.unsafe.get match {
              case Exited(_, _, _)     => 0
              case Running(_, fins, _) => fins.size
            }

          def updateAll(f: Finalizer => Finalizer)(implicit trace: Trace): UIO[Unit] =
            ref.update {
              case Exited(key, exit, update)  => Exited(key, exit, update.andThen(f))
              case Running(key, exit, update) => Running(key, exit, update.andThen(f))
            }
        }
      }
    }
  }
}
