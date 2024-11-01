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

import zio.internal.{FiberRenderer, FiberScope}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.IOException
import scala.concurrent.Future
import zio.internal.WeakConcurrentBag

/**
 * A fiber is a lightweight thread of execution that never consumes more than a
 * whole thread (but may consume much less, depending on contention and
 * asynchronicity). Fibers are spawned by forking ZIO effects, which run
 * concurrently with the parent effect.
 *
 * Fibers can be joined, yielding their result to other fibers, or interrupted,
 * which terminates the fiber, safely releasing all resources.
 *
 * {{{
 * def parallel[A, B](io1: Task[A], io2: Task[B]): Task[(A, B)] =
 *   for {
 *     fiber1 <- io1.fork
 *     fiber2 <- io2.fork
 *     a      <- fiber1.join
 *     b      <- fiber2.join
 *   } yield (a, b)
 * }}}
 */
abstract class Fiber[+E, +A] { self =>

  /**
   * Same as `zip` but discards the output of the left hand side.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of the fiber
   * @return
   *   `Fiber[E1, B]` combined fiber
   */
  final def *>[E1 >: E, B](that: Fiber[E1, B]): Fiber.Synthetic[E1, B] =
    (self zipWith that)((_, b) => b)

  /**
   * Same as `zip` but discards the output of the right hand side.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of the fiber
   * @return
   *   `Fiber[E1, A]` combined fiber
   */
  final def <*[E1 >: E, B](that: Fiber[E1, B]): Fiber.Synthetic[E1, A] =
    (self zipWith that)((a, _) => a)

  /**
   * Zips this fiber and the specified fiber together, producing a tuple of
   * their output.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of that fiber
   * @return
   *   `Fiber[E1, (A, B)]` combined fiber
   */
  final def <*>[E1 >: E, B](that: => Fiber[E1, B])(implicit
    zippable: Zippable[A, B]
  ): Fiber.Synthetic[E1, zippable.Out] =
    (self zipWith that)((a, b) => zippable.zip(a, b))

  /**
   * A symbolic alias for `orElseEither`.
   */
  final def <+>[E1 >: E, B](that: => Fiber[E1, B])(implicit ev: CanFail[E]): Fiber.Synthetic[E1, Either[A, B]] =
    self.orElseEither(that)

  /**
   * A symbolic alias for `orElse`.
   */
  def <>[E1, A1 >: A](that: => Fiber[E1, A1])(implicit ev: CanFail[E]): Fiber.Synthetic[E1, A1] =
    self.orElse(that)

  /**
   * Maps the output of this fiber to the specified constant.
   *
   * @param b
   *   constant
   * @tparam B
   *   type of the fiber
   * @return
   *   `Fiber[E, B]` fiber mapped to constant
   */
  final def as[B](b: => B): Fiber.Synthetic[E, B] =
    self map (_ => b)

  /**
   * Awaits the fiber, which suspends the awaiting fiber until the result of the
   * fiber has been determined.
   *
   * @return
   *   `UIO[Exit[E, A]]`
   */
  def await(implicit trace: Trace): UIO[Exit[E, A]]

  /**
   * Retrieves the immediate children of the fiber.
   */
  def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]]

  /**
   * Folds over the runtime or synthetic fiber.
   */
  final def fold[Z](
    runtime: Fiber.Runtime[E, A] => Z,
    synthetic: Fiber.Synthetic[E, A] => Z
  ): Z =
    self match {
      case fiber: Fiber.Runtime[_, _]   => runtime(fiber.asInstanceOf[Fiber.Runtime[E, A]])
      case fiber: Fiber.Synthetic[_, _] => synthetic(fiber.asInstanceOf[Fiber.Synthetic[E, A]])
    }

  /**
   * The identity of the fiber.
   */
  def id: FiberId

  /**
   * Inherits values from all [[FiberRef]] instances into current fiber. This
   * will resume immediately.
   *
   * @return
   *   `UIO[Unit]`
   */
  def inheritAll(implicit trace: Trace): UIO[Unit]

  /**
   * Interrupts the fiber from whichever fiber is calling this method. If the
   * fiber has already exited, the returned effect will resume immediately.
   * Otherwise, the effect will resume when the fiber exits.
   *
   * @return
   *   `UIO[Exit, E, A]]`
   */
  final def interrupt(implicit trace: Trace): UIO[Exit[E, A]] =
    ZIO.fiberIdWith(fiberId => self.interruptAs(fiberId))

  /**
   * Interrupts the fiber as if interrupted from the specified fiber. If the
   * fiber has already exited, the returned effect will resume immediately.
   * Otherwise, the effect will resume when the fiber exits.
   *
   * @return
   *   `UIO[Exit, E, A]]`
   */
  final def interruptAs(fiberId: FiberId)(implicit trace: Trace): UIO[Exit[E, A]] =
    self.interruptAsFork(fiberId) *> self.await

  /**
   * In the background, interrupts the fiber as if interrupted from the
   * specified fiber. If the fiber has already exited, the returned effect will
   * resume immediately. Otherwise, the effect will resume when the fiber exits.
   *
   * @return
   *   `UIO[Exit, E, A]]`
   */
  def interruptAsFork(fiberId: FiberId)(implicit trace: Trace): UIO[Unit]

  /**
   * Interrupts the fiber from whichever fiber is calling this method. The
   * interruption will happen in a separate daemon fiber, and the returned
   * effect will always resume immediately without waiting.
   *
   * @return
   *   `UIO[Unit]`
   */
  final def interruptFork(implicit trace: Trace): UIO[Unit] = ZIO.fiberIdWith(fiberId => self.interruptAsFork(fiberId))

  /**
   * Joins the fiber, which suspends the joining fiber until the result of the
   * fiber has been determined. Attempting to join a fiber that has erred will
   * result in a catchable error. Joining an interrupted fiber will result in an
   * "inner interruption" of this fiber, unlike interruption triggered by
   * another fiber, "inner interruption" can be caught and recovered.
   *
   * @return
   *   `IO[E, A]`
   */
  final def join(implicit trace: Trace): IO[E, A] =
    await.unexit <* inheritAll

  /**
   * Maps over the value the Fiber computes.
   *
   * @param f
   *   mapping function
   * @tparam B
   *   result type of f
   * @return
   *   `Fiber[E, B]` mapped fiber
   */
  final def map[B](f: A => B): Fiber.Synthetic[E, B] =
    mapZIO(f andThen Exit.succeed)

  /**
   * Passes the success of this fiber to the specified callback, and continues
   * with the fiber that it returns.
   *
   * @param f
   *   The callback.
   * @tparam B
   *   The success value.
   * @return
   *   `Fiber[E, B]` The continued fiber.
   */
  final def mapFiber[E1 >: E, B](f: A => Fiber[E1, B])(implicit trace: Trace): UIO[Fiber[E1, B]] =
    self.await.map(_.foldExit(Fiber.failCause(_), f))

  /**
   * Effectually maps over the value the fiber computes.
   */
  final def mapZIO[E1 >: E, B](f: A => IO[E1, B]): Fiber.Synthetic[E1, B] =
    new Fiber.Synthetic[E1, B] {
      final def await(implicit trace: Trace): UIO[Exit[E1, B]] =
        self.await.flatMap(_.foreach(f))

      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children

      def id: FiberId = self.id
      final def inheritAll(implicit trace: Trace): UIO[Unit] =
        self.inheritAll
      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit] =
        self.interruptAsFork(id)
      final def poll(implicit trace: Trace): UIO[Option[Exit[E1, B]]] =
        self.poll.flatMap(_.fold[UIO[Option[Exit[E1, B]]]](Exit.none)(_.foreach(f).map(Some(_))))
    }

  /**
   * Returns a fiber that prefers `this` fiber, but falls back to the `that` one
   * when `this` one fails. Interrupting the returned fiber will interrupt both
   * fibers, sequentially, from left to right.
   *
   * @param that
   *   fiber to fall back to
   * @tparam E1
   *   error type
   * @tparam A1
   *   type of the other fiber
   * @return
   *   `Fiber[E1, A1]`
   */
  def orElse[E1, A1 >: A](that: => Fiber[E1, A1])(implicit ev: CanFail[E]): Fiber.Synthetic[E1, A1] =
    new Fiber.Synthetic[E1, A1] {
      final def await(implicit trace: Trace): UIO[Exit[E1, A1]] =
        self.await.zipWith(that.await) {
          case (e1 @ Exit.Success(_), _) => e1
          case (_, e2)                   => e2

        }

      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children
      final def id: FiberId                                                      = self.id <> that.id

      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit] =
        self.interruptAsFork(id) *> that.interruptAsFork(id)

      final def inheritAll(implicit trace: Trace): UIO[Unit] =
        that.inheritAll *> self.inheritAll

      final def poll(implicit trace: Trace): UIO[Option[Exit[E1, A1]]] =
        self.poll.zipWith(that.poll) {
          case (Some(e1 @ Exit.Success(_)), _) => Some(e1)
          case (Some(_), o2)                   => o2
          case _                               => None
        }
    }

  /**
   * Returns a fiber that prefers `this` fiber, but falls back to the `that` one
   * when `this` one fails. Interrupting the returned fiber will interrupt both
   * fibers, sequentially, from left to right.
   *
   * @param that
   *   fiber to fall back to
   * @tparam E1
   *   error type
   * @tparam B
   *   type of the other fiber
   * @return
   *   `Fiber[E1, B]`
   */
  final def orElseEither[E1, B](that: => Fiber[E1, B]): Fiber.Synthetic[E1, Either[A, B]] =
    (self map (Left(_))) orElse (that map (Right(_)))

  /**
   * Tentatively observes the fiber, but returns immediately if it is not
   * already done.
   *
   * @return
   *   `UIO[Option[Exit, E, A]]]`
   */
  def poll(implicit trace: Trace): UIO[Option[Exit[E, A]]]

  /**
   * Converts this fiber into a scoped [[zio.ZIO]]. The fiber is interrupted
   * when the scope is closed.
   */
  final def scoped(implicit trace: Trace): ZIO[Scope, Nothing, Fiber[E, A]] =
    ZIO.acquireRelease(ZIO.succeed(self))(_.interrupt)

  /**
   * Converts this fiber into a [[scala.concurrent.Future]].
   *
   * @param ev
   *   implicit witness that E is a subtype of Throwable
   * @return
   *   `UIO[Future[A]]`
   */
  final def toFuture(implicit ev: E IsSubtypeOfError Throwable, trace: Trace): UIO[CancelableFuture[A]] =
    self toFutureWith ev

  /**
   * Converts this fiber into a [[scala.concurrent.Future]], translating any
   * errors to [[java.lang.Throwable]] with the specified conversion function,
   * using [[Cause.squashTraceWith]]
   *
   * @param f
   *   function to the error into a Throwable
   * @return
   *   `UIO[Future[A]]`
   */
  final def toFutureWith(f: E => Throwable)(implicit trace: Trace): UIO[CancelableFuture[A]] =
    ZIO.suspendSucceed {
      val p: scala.concurrent.Promise[A] = scala.concurrent.Promise[A]()

      def failure(cause: Cause[E]): UIO[p.type] = Exit.succeed(p.failure(cause.squashTraceWith(f)))
      def success(value: A): UIO[p.type]        = Exit.succeed(p.success(value))

      val completeFuture =
        self.await.flatMap(_.foldExitZIO[Any, Nothing, p.type](failure, success))

      for {
        runtime <- ZIO.runtime[Any]
        _ <- completeFuture.forkDaemon // Cannot afford to NOT complete the promise, no matter what, so we fork daemon
      } yield new CancelableFuture[A](p.future) {
        def cancel(): Future[Exit[Throwable, A]] =
          runtime.unsafe.runToFuture[Nothing, Exit[Throwable, A]](self.interrupt.map(_.mapErrorExit(f)))(
            trace,
            Unsafe.unsafe
          )
      }
    }.uninterruptible

  /**
   * Maps the output of this fiber to `()`.
   *
   * @return
   *   `Fiber[E, Unit]` fiber mapped to `()`
   */
  final def unit: Fiber.Synthetic[E, Unit] = as(())

  /**
   * Named alias for `<*>`.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of that fiber
   * @return
   *   `Fiber[E1, (A, B)]` combined fiber
   */
  final def zip[E1 >: E, B](that: => Fiber[E1, B])(implicit
    zippable: Zippable[A, B]
  ): Fiber.Synthetic[E1, zippable.Out] =
    self <*> that

  /**
   * Named alias for `<*`.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of the fiber
   * @return
   *   `Fiber[E1, A]` combined fiber
   */
  final def zipLeft[E1 >: E, B](that: Fiber[E1, B]): Fiber.Synthetic[E1, A] =
    self <* that

  /**
   * Named alias for `*>`.
   *
   * @param that
   *   fiber to be zipped
   * @tparam E1
   *   error type
   * @tparam B
   *   type of the fiber
   * @return
   *   `Fiber[E1, B]` combined fiber
   */
  final def zipRight[E1 >: E, B](that: Fiber[E1, B]): Fiber.Synthetic[E1, B] =
    self *> that

  /**
   * Zips this fiber with the specified fiber, combining their results using the
   * specified combiner function. Both joins and interruptions are performed in
   * sequential order from left to right.
   *
   * @param that
   *   fiber to be zipped
   * @param f
   *   function to combine the results of both fibers
   * @tparam E1
   *   error type
   * @tparam B
   *   type of that fiber
   * @tparam C
   *   type of the resulting fiber
   * @return
   *   `Fiber[E1, C]` combined fiber
   */
  final def zipWith[E1 >: E, B, C](that: => Fiber[E1, B])(f: (A, B) => C): Fiber.Synthetic[E1, C] =
    new Fiber.Synthetic[E1, C] {
      final def await(implicit trace: Trace): UIO[Exit[E1, C]] =
        self.await.zipWith(that.await)(_.zipWith(_)(f, _ && _))

      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children

      final def id: FiberId = self.id <> that.id

      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit] =
        self.interruptAsFork(id) *> that.interruptAsFork(id)

      final def inheritAll(implicit trace: Trace): UIO[Unit] = that.inheritAll *> self.inheritAll

      final def poll(implicit trace: Trace): UIO[Option[Exit[E1, C]]] =
        self.poll.zipWith(that.poll) {
          case (Some(ra), Some(rb)) => Some(ra.zipWith(rb)(f, _ && _))
          case _                    => None
        }
    }
}

object Fiber extends FiberPlatformSpecific {

  /**
   * A runtime fiber that is executing an effect. Runtime fibers have an
   * identity and a trace.
   */
  sealed abstract class Runtime[+E, +A] extends Fiber.Internal[E, A] { self =>

    private[zio] def shouldYieldBeforeFork(): Boolean

    /**
     * The location the fiber was forked from.
     */
    def location: Trace

    /**
     * Generates a fiber dump.
     */
    final def dump(implicit trace: Trace): UIO[Fiber.Dump] =
      for {
        status <- self.status
        trace  <- self.trace
      } yield Fiber.Dump(self.id, status, trace)

    def fiberRefs(implicit trace: Trace): UIO[FiberRefs]

    /**
     * The identity of the fiber.
     */
    override def id: FiberId.Runtime

    def runtimeFlags(implicit trace: Trace): UIO[RuntimeFlags]

    /**
     * The status of the fiber.
     */
    def status(implicit trace: Trace): UIO[Fiber.Status]

    /**
     * The trace of the fiber.
     */
    def trace(implicit trace: Trace): UIO[StackTrace]

    def unsafe: UnsafeAPI
    trait UnsafeAPI {
      def addObserver(observer: Exit[E, A] => Unit)(implicit unsafe: Unsafe): Unit

      def deleteFiberRef(ref: FiberRef[_])(implicit unsafe: Unsafe): Unit

      def getFiberRefs()(implicit unsafe: Unsafe): FiberRefs

      def removeObserver(observer: Exit[E, A] => Unit)(implicit unsafe: Unsafe): Unit

      def poll(implicit unsafe: Unsafe): Option[Exit[E, A]]

      def interrupt(cause: Cause[Nothing])(implicit unsafe: Unsafe): Unit = ()
    }

    /**
     * Adds a weakly-held reference to the specified fiber inside the children
     * set.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def addChild(child: Fiber.Runtime[_, _]): Unit
    private[zio] def addChildren(children: Iterable[Fiber.Runtime[_, _]]): Unit

    /**
     * Deletes the specified fiber ref.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def deleteFiberRef(ref: FiberRef[_]): Unit

    /**
     * Retrieves the current executor that effects are executed on.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def getCurrentExecutor(): Executor

    /**
     * Retrieves the state of the fiber ref, or else its initial value.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def getFiberRef[A](fiberRef: FiberRef[A]): A

    /**
     * Retrieves the state of the fiber ref, or `null` if it hasn't been
     * modified.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def getFiberRefOrNull[A](fiberRef: FiberRef[A]): A

    /**
     * Retrieves all fiber refs of the fiber.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def getFiberRefs(): FiberRefs = getFiberRefs(true)

    /**
     * Retrieves all fiber refs of the fiber. If `updateRuntimeFlagsWithin` is
     * set to `false`, the FiberRefs will ''not'' be updated with the Fiber's
     * current runtime flags. This can help performance for cases that we don't
     * need to extract the runtime flags from the FiberRefs.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def getFiberRefs(updateRuntimeFlagsWithin: Boolean): FiberRefs

    /**
     * Retrieves the executor that this effect is currently executing on.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def getRunningExecutor(): Option[Executor]

    private[zio] def isAlive(): Boolean

    /**
     * Determines if the specified throwable is fatal, based on the fatal errors
     * tracked by the fiber's state.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] final def isFatal(t: Throwable): Boolean =
      getFiberRef(FiberRef.currentFatal).apply(t)

    /**
     * Logs using the current set of loggers.
     *
     * '''NOTE''': This method is safe to invoke on any fiber, but if not
     * invoked on this fiber, then values derived from the fiber's state
     * (including the log annotations and log level) may not be up-to-date.
     */
    private[zio] def log(
      message: () => String,
      cause: Cause[Any],
      overrideLogLevel: Option[LogLevel],
      trace: Trace
    ): Unit

    private[zio] def scope: FiberScope

    /**
     * Sets the fiber ref to the specified value.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def setFiberRef[A](fiberRef: FiberRef[A], value: A): Unit

    /**
     * Resets the fiber ref to its initial value.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def resetFiberRef(fiberRef: FiberRef[?]): Unit

    /**
     * Wholesale replaces all fiber refs of this fiber.
     *
     * '''NOTE''': This method must be invoked by the fiber itself.
     */
    private[zio] def setFiberRefs(fiberRefs: FiberRefs): Unit

    /**
     * Adds a message to add a child to this fiber.
     */
    private[zio] def tellAddChild(child: Fiber.Runtime[_, _]): Unit
    private[zio] def tellAddChildren(children: Iterable[Fiber.Runtime[_, _]]): Unit

    /**
     * Adds a message to interrupt this fiber.
     */
    private[zio] def tellInterrupt(cause: Cause[Nothing]): Unit

    /**
     * Transfers all children of this fiber that are currently running to the
     * specified fiber scope
     *
     * '''NOTE''': This method must be invoked by the fiber itself after it has
     * evaluated the effects but prior to exiting
     */
    private[zio] def transferChildren(scope: FiberScope): Unit

  }

  private[zio] object Runtime {

    implicit val fiberOrdering: Ordering[Fiber.Runtime[?, ?]] = { (x, y) =>
      val byTime = x.id.startTimeMillis.compare(y.id.startTimeMillis)
      if (byTime == 0) x.id.id.compare(y.id.id)
      else byTime
    }

    abstract class Internal[+E, +A] extends Runtime[E, A]
  }

  /**
   * A synthetic fiber that is created from a pure value or that combines
   * existing fibers.
   */
  sealed abstract class Synthetic[+E, +A] extends Fiber.Internal[E, A] {}

  private[zio] object Synthetic {
    abstract class Internal[+E, +A] extends Synthetic[E, A]
  }

  private[zio] abstract class Internal[+E, +A] extends Fiber[E, A]

  /**
   * A record containing information about a [[Fiber]].
   *
   * @param id
   *   The fiber's unique identifier
   * @param interrupters
   *   The set of fibers attempting to interrupt the fiber or its ancestors.
   * @param executor
   *   The [[Executor]] executing this fiber
   * @param children
   *   The fiber's forked children.
   */
  final case class Descriptor(
    id: FiberId.Runtime,
    status: Status.Running,
    interrupters: Set[FiberId],
    executor: Executor,
    isLocked: Boolean
  ) {
    def interruptStatus: InterruptStatus =
      InterruptStatus.fromBoolean(RuntimeFlags.interruption(status.runtimeFlags))
  }

  final case class Dump(fiberId: FiberId.Runtime, status: Status, trace: StackTrace) extends Product with Serializable {
    self =>

    /**
     * {{{
     * "Fiber Name" #432 (16m2s) waiting on fiber #283
     *     Status: Suspended (interruptible, 12 asyncs, ...)
     *     at ...
     *     at ...
     *     at ...
     *     at ...
     * }}}
     */
    def prettyPrint(implicit trace: Trace): UIO[String] =
      FiberRenderer.prettyPrint(self)
  }

  sealed trait Status { self =>
    def isDone: Boolean = self match { case Status.Done => true; case _ => false }

    def isRunning: Boolean = self match { case _: Status.Running => true; case _ => false }

    def isSuspended: Boolean = self match { case _: Status.Suspended => true; case _ => false }
  }
  object Status {
    sealed trait Unfinished extends Status {
      def runtimeFlags: RuntimeFlags

      def trace: Trace
    }

    case object Done extends Status {
      def trace: Trace = Trace.empty

      override def toString(): String = "Done"
    }
    final case class Running(runtimeFlags: RuntimeFlags, trace: Trace) extends Unfinished {
      override def toString(): String = {
        val currentLocation =
          if (trace == Trace.empty) "<trace unavailable>"
          else trace

        s"Running(${RuntimeFlags.render(runtimeFlags)}, ${currentLocation})"
      }
    }
    final case class Suspended(
      runtimeFlags: RuntimeFlags,
      trace: Trace,
      blockingOn: FiberId
    ) extends Unfinished {
      override def toString(): String = {
        val currentLocation =
          if (trace == Trace.empty) "<trace unavailable>"
          else trace

        s"Suspended(${RuntimeFlags.render(runtimeFlags)}, ${currentLocation}, ${blockingOn})"
      }
    }
  }

  /**
   * Awaits on all fibers to be completed, successfully or not.
   *
   * @param fs
   *   `Iterable` of fibers to be awaited
   * @return
   *   `UIO[Unit]`
   */
  def awaitAll(fs: Iterable[Fiber[Any, Any]])(implicit trace: Trace): UIO[Unit] =
    collectAll(fs).await.unit

  /**
   * Collects all fibers into a single fiber producing an in-order list of the
   * results.
   */
  def collectAll[E, A, Collection[+Element] <: Iterable[Element]](
    fibers: Collection[Fiber[E, A]]
  )(implicit bf: BuildFrom[Collection[Fiber[E, A]], A, Collection[A]]): Fiber.Synthetic[E, Collection[A]] =
    new Fiber.Synthetic[E, Collection[A]] {
      def await(implicit trace: Trace): UIO[Exit[E, Collection[A]]] =
        ZIO
          .foreach[Any, Nothing, Fiber[E, A], Exit[E, A], Iterable](fibers)(_.await)
          .map(Exit.collectAllPar(_).getOrElse(Exit.succeed(Iterable.empty)).mapExit(bf.fromSpecific(fibers)))
      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] =
        ZIO.foreachPar(Chunk.fromIterable(fibers))(_.children).map(_.flatten)

      final def id: FiberId = fibers.foldLeft(FiberId.None: FiberId)(_ <> _.id)

      def inheritAll(implicit trace: Trace): UIO[Unit] =
        ZIO.foreachDiscard(fibers)(_.inheritAll)
      def interruptAsFork(fiberId: FiberId)(implicit trace: Trace): UIO[Unit] =
        ZIO
          .foreachDiscard(fibers)(_.interruptAsFork(fiberId))
      def poll(implicit trace: Trace): UIO[Option[Exit[E, Collection[A]]]] =
        ZIO
          .foreach[Any, Nothing, Fiber[E, A], Option[Exit[E, A]], Iterable](fibers)(_.poll)
          .map(_.foldRight[Option[Exit[E, List[A]]]](Some(Exit.succeed(Nil))) {
            case (Some(ra), Some(rb)) => Some(ra.zipWith(rb)(_ :: _, _ && _))
            case _                    => None
          })
          .map(_.map(_.mapExit(bf.fromSpecific(fibers))))
    }

  /**
   * Collects all fibers into a single fiber discarding their results.
   */
  def collectAllDiscard[E, A](fibers: Iterable[Fiber[E, A]]): Fiber.Synthetic[E, Unit] =
    new Fiber.Synthetic[E, Unit] {
      def await(implicit trace: Trace): UIO[Exit[E, Unit]] =
        ZIO
          .foreach[Any, Nothing, Fiber[E, A], Exit[E, A], Iterable](fibers)(_.await)
          .map(_.foldLeft[Exit[E, Unit]](Exit.unit)(_ <& _))
      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] =
        ZIO.foreachPar(Chunk.fromIterable(fibers))(_.children).map(_.flatten)

      final def id: FiberId = fibers.foldLeft(FiberId.None: FiberId)(_ <> _.id)

      def inheritAll(implicit trace: Trace): UIO[Unit] =
        ZIO.foreachDiscard(fibers)(_.inheritAll)
      def interruptAsFork(fiberId: FiberId)(implicit trace: Trace): UIO[Unit] =
        ZIO
          .foreachDiscard(fibers)(_.interruptAsFork(fiberId))
      def poll(implicit trace: Trace): UIO[Option[Exit[E, Unit]]] =
        ZIO
          .foreach[Any, Nothing, Fiber[E, A], Option[Exit[E, A]], Iterable](fibers)(_.poll)
          .map(_.foldRight[Option[Exit[E, Unit]]](Some(Exit.succeed(Nil))) {
            case (Some(ra), Some(rb)) => Some(ra.zipWith(rb)((_, b) => b, _ && _))
            case _                    => None
          })
    }

  /**
   * A fiber that is done with the specified [[zio.Exit]] value.
   *
   * @param exit
   *   [[zio.Exit]] value
   * @tparam E
   *   error type
   * @tparam A
   *   type of the fiber
   * @return
   *   `Fiber[E, A]`
   */
  def done[E, A](exit: => Exit[E, A]): Fiber.Synthetic[E, A] =
    new Fiber.Synthetic[E, A] {
      final def await(implicit trace: Trace): UIO[Exit[E, A]]                    = ZIO.succeed(exit)
      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = ZIO.succeed(Chunk.empty)
      final def id: FiberId                                                      = FiberId.None
      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit]   = ZIO.unit
      final def inheritAll(implicit trace: Trace): UIO[Unit]                     = ZIO.unit
      final def poll(implicit trace: Trace): UIO[Option[Exit[E, A]]]             = ZIO.succeed(Some(exit))
    }

  /**
   * Dumps all fibers to the console.
   */
  def dumpAll(implicit trace: Trace): ZIO[Any, IOException, Unit] =
    dumpAllWith { dump =>
      dump.prettyPrint.flatMap(Console.printError(_))
    }

  /**
   * Dumps all fibers to the specified callback.
   */
  def dumpAllWith[R, E](f: Dump => ZIO[R, E, Any])(implicit trace: Trace): ZIO[R, E, Unit] = {
    def process(fiber: Fiber.Runtime[_, _]): ZIO[R, E, Unit] =
      fiber.dump.flatMap(f) *> fiber.children.flatMap(chunk => ZIO.foreachDiscard(chunk)(process(_)))

    Fiber.roots.flatMap(_.mapZIODiscard(process(_)))
  }

  /**
   * A fiber that has already failed with the specified value.
   *
   * @param e
   *   failure value
   * @tparam E
   *   error type
   * @return
   *   `Fiber[E, Nothing]` failed fiber
   */
  def fail[E](e: E): Fiber.Synthetic[E, Nothing] = done(Exit.fail(e))

  /**
   * Creates a `Fiber` that has already failed with the specified cause.
   */
  def failCause[E](cause: Cause[E]): Fiber.Synthetic[E, Nothing] =
    done(Exit.failCause(cause))

  /**
   * Returns a `Fiber` that is backed by the specified `Future`.
   *
   * @param thunk
   *   `Future[A]` backing the `Fiber`
   * @tparam A
   *   type of the `Fiber`
   * @return
   *   `Fiber[Throwable, A]`
   */
  def fromFuture[A](thunk: => Future[A])(implicit trace: Trace): Fiber.Synthetic[Throwable, A] =
    new Fiber.Synthetic[Throwable, A] {
      lazy val ftr: Future[A] = thunk

      final def await(implicit trace: Trace): UIO[Exit[Throwable, A]] =
        ZIO.suspend(ZIO.fromFutureNow(ftr)(trace, Unsafe.unsafe)).exit

      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = ZIO.succeed(Chunk.empty)

      final def id: FiberId = FiberId.None

      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit] =
        ZIO.suspendSucceed {
          ftr match {
            case c: CancelableFuture[A] => ZIO.attempt(c.cancel()).ignore
            case _                      => join.ignore
          }
        }

      final def inheritAll(implicit trace: Trace): UIO[Unit] = ZIO.unit

      final def poll(implicit trace: Trace): UIO[Option[Exit[Throwable, A]]] =
        ZIO.succeed(ftr.value.map(Exit.fromTry))
    }

  /**
   * Lifts an [[zio.ZIO]] into a `Fiber`.
   *
   * @param io
   *   `IO[E, A]` to turn into a `Fiber`
   * @tparam E
   *   error type
   * @tparam A
   *   type of the fiber
   * @return
   *   `UIO[Fiber[E, A]]`
   */
  def fromZIO[E, A](io: IO[E, A])(implicit trace: Trace): UIO[Fiber.Synthetic[E, A]] =
    io.exit.map(done(_))

  /**
   * Interrupts all fibers, awaiting their interruption.
   *
   * @param fs
   *   `Iterable` of fibers to be interrupted
   * @return
   *   `UIO[Unit]`
   */
  def interruptAll(fs: Iterable[Fiber[Any, Any]])(implicit trace: Trace): UIO[Unit] =
    ZIO.fiberIdWith(interruptAllAs(_)(fs))

  /**
   * Interrupts all fibers as by the specified fiber, awaiting their
   * interruption.
   *
   * @param fiberId
   *   The identity of the fiber to interrupt as.
   * @param fs
   *   `Iterable` of fibers to be interrupted
   * @return
   *   `UIO[Unit]`
   */
  def interruptAllAs(fiberId: FiberId)(fs: Iterable[Fiber[Any, Any]])(implicit trace: Trace): UIO[Unit] =
    ZIO.foreachDiscard(fs)(_.interruptAsFork(fiberId)) *> ZIO.foreachDiscard(fs)(_.await)

  /**
   * A fiber that is already interrupted.
   *
   * @return
   *   `Fiber[Nothing, Nothing]` interrupted fiber
   */
  def interruptAs(id: FiberId): Fiber.Synthetic[Nothing, Nothing] =
    done(Exit.interrupt(id))

  /**
   * Joins all fibers, awaiting their _successful_ completion. Attempting to
   * join a fiber that has erred will result in a catchable error, _if_ that
   * error does not result from interruption.
   *
   * @param fs
   *   `Iterable` of fibers to be joined
   * @return
   *   `UIO[Unit]`
   */
  def joinAll[E](fs: Iterable[Fiber[E, Any]])(implicit trace: Trace): IO[E, Unit] =
    collectAll(fs).join.unit

  /**
   * A fiber that never fails or succeeds.
   */
  val never: Fiber.Synthetic[Nothing, Nothing] =
    new Fiber.Synthetic[Nothing, Nothing] {
      final def await(implicit trace: Trace): UIO[Exit[Nothing, Nothing]]        = ZIO.never
      final def children(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] = ZIO.succeed(Chunk.empty)
      final def id: FiberId                                                      = FiberId.None
      final def interruptAsFork(id: FiberId)(implicit trace: Trace): UIO[Unit]   = ZIO.unit
      final def inheritAll(implicit trace: Trace): UIO[Unit]                     = ZIO.unit
      final def poll(implicit trace: Trace): UIO[Option[Exit[Nothing, Nothing]]] = ZIO.succeed(None)
    }

  /**
   * Returns a chunk containing all root fibers. Due to concurrency, the
   * returned chunk is only weakly consistent.
   */
  def roots(implicit trace: Trace): UIO[Chunk[Fiber.Runtime[_, _]]] =
    ZIO.succeed(Chunk.fromIterator(_roots.iterator))

  /**
   * Returns a fiber that has already succeeded with the specified value.
   *
   * @param a
   *   success value
   * @tparam E
   *   error type
   * @tparam A
   *   type of the fiber
   * @return
   *   `Fiber[E, A]` succeeded fiber
   */
  def succeed[A](a: A): Fiber.Synthetic[Nothing, A] =
    done(Exit.succeed(a))

  /**
   * A fiber that has already succeeded with unit.
   */
  val unit: Fiber.Synthetic[Nothing, Unit] =
    Fiber.succeed(())

  /**
   * Retrieves the fiber currently executing on this thread, if any. This will
   * always be `None` unless called from within an executing effect and this
   * feature is enabled using [[Runtime.enableCurrentFiber]].
   */
  def currentFiber()(implicit unsafe: Unsafe): Option[Fiber[Any, Any]] =
    Option(_currentFiber.get)

  private[zio] val _currentFiber: ThreadLocal[Fiber.Runtime[_, _]] =
    new ThreadLocal[Fiber.Runtime[_, _]]()

  private[zio] val _roots: WeakConcurrentBag[Fiber.Runtime[_, _]] =
    WeakConcurrentBag[Fiber.Runtime[_, _]](10000, _.isAlive())
      .withAutoGc(5.seconds)
}
