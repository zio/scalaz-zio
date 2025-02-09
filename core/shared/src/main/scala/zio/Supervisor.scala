/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio.Exit._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.{AtomicReference, LongAdder}
import scala.collection.immutable.SortedSet

/**
 * A `Supervisor[A]` is allowed to supervise the launching and termination of
 * fibers, producing some visible value of type `A` from the supervision.
 */
abstract class Supervisor[+A] { self =>

  /**
   * Returns an effect that succeeds with the value produced by this supervisor.
   * This value may change over time, reflecting what the supervisor produces as
   * it supervises fibers.
   */
  def value(implicit trace: Trace): UIO[A]

  /**
   * Maps this supervisor to another one, which has the same effect, but whose
   * value has been transformed by the specified function.
   */
  def map[B](f: A => B): Supervisor[B] =
    new Supervisor.ProxySupervisor(trace => value(trace).map(f)(trace), self)

  /**
   * Returns a new supervisor that performs the function of this supervisor, and
   * the function of the specified supervisor, producing a tuple of the outputs
   * produced by both supervisors.
   */
  final def ++[B](that: Supervisor[B]): Supervisor[(A, B)] =
    Supervisor.Zip(self, that)

  def onStart[R, E, A](
    environment: ZEnvironment[R],
    effect: ZIO[R, E, A],
    parent: Option[Fiber.Runtime[Any, Any]],
    fiber: Fiber.Runtime[E, A]
  )(implicit unsafe: Unsafe): Unit

  def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit

  def onEffect[E, A](fiber: Fiber.Runtime[E, A], effect: ZIO[_, _, _])(implicit unsafe: Unsafe): Unit = ()

  def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = ()

  def onResume[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = ()
}
object Supervisor {

  import zio.internal._

  /**
   * Creates a new supervisor that tracks children in a set.
   *
   * @param weak
   *   Whether or not to track the children in a weak set, if possible
   *   (platform-dependent).
   */
  def track(weak: Boolean)(implicit trace: Trace): UIO[Supervisor[Chunk[Fiber.Runtime[Any, Any]]]] =
    ZIO.succeed(unsafe.track(weak)(Unsafe.unsafe))

  def fromZIO[A](value: UIO[A]): Supervisor[A] = new ConstSupervisor(_ => value)

  /**
   * Creates a new supervisor that tracks children in a set.
   */
  def fibersIn(
    ref: AtomicReference[SortedSet[Fiber.Runtime[Any, Any]]]
  )(implicit trace: Trace): UIO[Supervisor[SortedSet[Fiber.Runtime[Any, Any]]]] =
    ZIO.succeed {

      new Supervisor[SortedSet[Fiber.Runtime[Any, Any]]] {
        def value(implicit trace: Trace): UIO[SortedSet[Fiber.Runtime[Any, Any]]] =
          ZIO.succeed(ref.get)

        def onStart[R, E, A](
          environment: ZEnvironment[R],
          effect: ZIO[R, E, A],
          parent: Option[Fiber.Runtime[Any, Any]],
          fiber: Fiber.Runtime[E, A]
        )(implicit unsafe: Unsafe): Unit = {
          var loop = true
          while (loop) {
            val set = ref.get
            loop = !ref.compareAndSet(set, set + fiber)
          }
        }

        def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
          var loop = true
          while (loop) {
            val set = ref.get
            loop = !ref.compareAndSet(set, set - fiber)
          }
        }
      }
    }

  /**
   * A supervisor that doesn't do anything in response to supervision events.
   */
  val none: Supervisor[Unit] = new ConstSupervisor(_ => ZIO.unit)

  private class ConstSupervisor[A](value0: Trace => UIO[A]) extends Supervisor[A] {
    def value(implicit trace: Trace): UIO[A] = value0(trace)

    def onStart[R, E, A](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A]
    )(implicit unsafe: Unsafe): Unit = ()

    def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = ()
  }

  private[zio] object unsafe {
    def track(weak: Boolean)(implicit unsafe: Unsafe): Supervisor[Chunk[Fiber.Runtime[Any, Any]]] = {
      val set: java.util.Set[Fiber.Runtime[Any, Any]] =
        if (weak) Platform.newWeakSet[Fiber.Runtime[Any, Any]]()
        else new java.util.HashSet[Fiber.Runtime[Any, Any]]()

      new Supervisor[Chunk[Fiber.Runtime[Any, Any]]] {
        def value(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[Any, Any]]] =
          ZIO.succeed(
            Sync(set)(Chunk.fromArray(set.toArray[Fiber.Runtime[Any, Any]](Array[Fiber.Runtime[Any, Any]]())))
          )

        def onStart[R, E, A](
          environment: ZEnvironment[R],
          effect: ZIO[R, E, A],
          parent: Option[Fiber.Runtime[Any, Any]],
          fiber: Fiber.Runtime[E, A]
        )(implicit unsafe: Unsafe): Unit = {
          Sync(set)(set.add(fiber))
          ()
        }

        def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
          Sync(set)(set.remove(fiber))
          ()
        }
      }
    }
  }

  private case class ProxySupervisor[A](value0: Trace => UIO[A], underlying: Supervisor[Any]) extends Supervisor[A] {
    def value(implicit trace: Trace): UIO[A] = value0(trace)

    def onStart[R, E, A](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A]
    )(implicit unsafe: Unsafe): Unit = underlying.onStart(environment, effect, parent, fiber)

    def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit =
      underlying.onEnd(value, fiber)

    override def onEffect[E, A](fiber: Fiber.Runtime[E, A], effect: ZIO[_, _, _])(implicit unsafe: Unsafe): Unit =
      underlying.onEffect(fiber, effect)

    override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit =
      underlying.onSuspend(fiber)

    override def onResume[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit =
      underlying.onResume(fiber)
  }

  sealed trait Patch { self =>
    import Patch._

    /**
     * Applies an update to the supervisor to produce a new supervisor.
     */
    def apply(supervisor: Supervisor[Any]): Supervisor[Any] = {

      def loop(supervisor: Supervisor[Any], patches: List[Patch]): Supervisor[Any] =
        patches match {
          case AddSupervisor(added) :: patches      => loop(supervisor ++ added, patches)
          case AndThen(first, second) :: patches    => loop(supervisor, first :: second :: patches)
          case Empty :: patches                     => loop(supervisor, patches)
          case RemoveSupervisor(removed) :: patches => loop(removeSupervisor(supervisor, removed), patches)
          case Nil                                  => supervisor
        }

      loop(supervisor, List(self))
    }

    /**
     * Combines two patches to produce a new patch that describes applying the
     * updates from this patch and then the updates from the specified patch.
     */
    def combine(that: Patch): Patch =
      AndThen(self, that)
  }

  object Patch {

    /**
     * Constructs a patch that describes the updates necessary to transform the
     * specified old environment into the specified new environment.
     */
    def diff(oldValue: Supervisor[Any], newValue: Supervisor[Any]): Patch =
      if (oldValue == newValue) Empty
      else {
        val oldSupervisors = toSet(oldValue)
        val newSupervisors = toSet(newValue)
        val added = newSupervisors
          .diff(oldSupervisors)
          .foldLeft(empty)((patch, supervisor) => patch.combine(AddSupervisor(supervisor)))
        val removed = oldSupervisors
          .diff(newSupervisors)
          .foldLeft(empty)((patch, supervisor) => patch.combine(RemoveSupervisor(supervisor)))
        added.combine(removed)
      }

    val empty: Patch =
      Empty

    private final case class AddSupervisor(supervisor: Supervisor[Any])    extends Patch
    private final case class AndThen(first: Patch, second: Patch)          extends Patch
    private case object Empty                                              extends Patch
    private final case class RemoveSupervisor(supervisor: Supervisor[Any]) extends Patch
  }

  private final case class Zip[A, B](left: Supervisor[A], right: Supervisor[B]) extends Supervisor[(A, B)] {

    def value(implicit trace: Trace) = left.value zip right.value

    def onStart[R, E, A](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A]
    )(implicit unsafe: Unsafe): Unit =
      try left.onStart(environment, effect, parent, fiber)
      finally right.onStart(environment, effect, parent, fiber)

    def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      left.onEnd(value, fiber)
      right.onEnd(value, fiber)
    }

    override def onEffect[E, A](fiber: Fiber.Runtime[E, A], effect: ZIO[_, _, _])(implicit
      unsafe: Unsafe
    ): Unit = {
      left.onEffect(fiber, effect)
      right.onEffect(fiber, effect)
    }

    override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      left.onSuspend(fiber)
      right.onSuspend(fiber)
    }

    override def onResume[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      left.onResume(fiber)
      right.onResume(fiber)
    }
  }

  private def removeSupervisor(self: Supervisor[Any], that: Supervisor[Any]): Supervisor[Any] =
    if (self eq that) Supervisor.none
    else
      self match {
        case Zip(left, right) => removeSupervisor(left, that) ++ removeSupervisor(right, that)
        case supervisor       => supervisor
      }

  private[zio] def toSet(supervisor: Supervisor[Any]): Set[Supervisor[Any]] =
    if (supervisor eq Supervisor.none) Set.empty
    else
      supervisor match {
        case Zip(left, right) => toSet(left) ++ toSet(right)
        case supervisor       => Set(supervisor)
      }
}
