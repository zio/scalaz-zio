/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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

import zio.internal.FiberRenderer
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
sealed abstract class Fiber[+E, +A] { self =>

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
  def await(implicit trace: ZTraceElement): UIO[Exit[E, A]]

  /**
   * Retrieves the immediate children of the fiber.
   */
  def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]]

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
   * Gets the value of the fiber ref for this fiber, or the initial value of the
   * fiber ref, if the fiber is not storing the ref.
   */
  def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A]

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
  def inheritRefs(implicit trace: ZTraceElement): UIO[Unit]

  /**
   * Interrupts the fiber from whichever fiber is calling this method. If the
   * fiber has already exited, the returned effect will resume immediately.
   * Otherwise, the effect will resume when the fiber exits.
   *
   * @return
   *   `UIO[Exit, E, A]]`
   */
  final def interrupt(implicit trace: ZTraceElement): UIO[Exit[E, A]] =
    ZIO.fiberId.flatMap(fiberId => self.interruptAs(fiberId))

  /**
   * Interrupts the fiber as if interrupted from the specified fiber. If the
   * fiber has already exited, the returned effect will resume immediately.
   * Otherwise, the effect will resume when the fiber exits.
   *
   * @return
   *   `UIO[Exit, E, A]]`
   */
  def interruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]]

  /**
   * Interrupts the fiber from whichever fiber is calling this method. The
   * interruption will happen in a separate daemon fiber, and the returned
   * effect will always resume immediately without waiting.
   *
   * @return
   *   `UIO[Unit]`
   */
  final def interruptFork(implicit trace: ZTraceElement): UIO[Unit] = interrupt.forkDaemon.unit

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
  final def join(implicit trace: ZTraceElement): IO[E, A] = await.flatMap(IO.done(_)) <* inheritRefs

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
    mapZIO(f andThen UIO.succeedNow)

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
  final def mapFiber[E1 >: E, B](f: A => Fiber[E1, B])(implicit trace: ZTraceElement): UIO[Fiber[E1, B]] =
    self.await.map(_.fold(Fiber.failCause(_), f))

  /**
   * Effectually maps over the value the fiber computes.
   */
  @deprecated("use mapZIO", "2.0.0")
  final def mapM[E1 >: E, B](f: A => IO[E1, B]): Fiber.Synthetic[E1, B] =
    mapZIO(f)

  /**
   * Effectually maps over the value the fiber computes.
   */
  final def mapZIO[E1 >: E, B](f: A => IO[E1, B]): Fiber.Synthetic[E1, B] =
    new Fiber.Synthetic[E1, B] {
      final def await(implicit trace: ZTraceElement): UIO[Exit[E1, B]] =
        self.await.flatMap(_.foreach(f))

      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children
      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A]       = self.getRef(ref)

      def id: FiberId = self.id
      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] =
        self.inheritRefs
      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E1, B]] =
        self.interruptAs(id).flatMap(_.foreach(f))
      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E1, B]]] =
        self.poll.flatMap(_.fold[UIO[Option[Exit[E1, B]]]](UIO.succeedNow(None))(_.foreach(f).map(Some(_))))
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
      final def await(implicit trace: ZTraceElement): UIO[Exit[E1, A1]] =
        self.await.zipWith(that.await) {
          case (e1 @ Exit.Success(_), _) => e1
          case (_, e2)                   => e2

        }

      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children

      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A] =
        for {
          first  <- self.getRef(ref)
          second <- that.getRef(ref)
        } yield if (first == ref.initial) second else first

      final def id: FiberId = self.id <> that.id

      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E1, A1]] =
        self.interruptAs(id) *> that.interruptAs(id)

      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] =
        that.inheritRefs *> self.inheritRefs

      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E1, A1]]] =
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
  def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E, A]]]

  /**
   * Converts this fiber into a scoped [[zio.ZIO]]. The fiber is interrupted
   * when the scope is closed.
   */
  final def scoped(implicit trace: ZTraceElement): ZIO[Scope, Nothing, Fiber[E, A]] =
    ZIO.acquireRelease(ZIO.succeedNow(self))(_.interrupt)

  /**
   * Converts this fiber into a [[scala.concurrent.Future]].
   *
   * @param ev
   *   implicit witness that E is a subtype of Throwable
   * @return
   *   `UIO[Future[A]]`
   */
  final def toFuture(implicit ev: E IsSubtypeOfError Throwable, trace: ZTraceElement): UIO[CancelableFuture[A]] =
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
  final def toFutureWith(f: E => Throwable)(implicit trace: ZTraceElement): UIO[CancelableFuture[A]] =
    UIO.suspendSucceed {
      val p: scala.concurrent.Promise[A] = scala.concurrent.Promise[A]()

      def failure(cause: Cause[E]): UIO[p.type] = ZIO.succeed(p.failure(cause.squashTraceWith(f)))
      def success(value: A): UIO[p.type]        = ZIO.succeed(p.success(value))

      val completeFuture =
        self.await.flatMap(_.foldZIO[Any, Nothing, p.type](failure, success))

      for {
        runtime <- ZIO.runtime[Any]
        _ <- completeFuture.forkDaemon // Cannot afford to NOT complete the promise, no matter what, so we fork daemon
      } yield new CancelableFuture[A](p.future) {
        def cancel(): Future[Exit[Throwable, A]] =
          runtime.unsafeRunToFuture[Nothing, Exit[Throwable, A]](self.interrupt.map(_.mapError(f)))
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
      final def await(implicit trace: ZTraceElement): UIO[Exit[E1, C]] =
        self.await.flatMap(IO.done(_)).zipWithPar(that.await.flatMap(IO.done(_)))(f).exit

      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = self.children

      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A] =
        (self.getRef(ref) zipWith that.getRef(ref))(ref.join(_, _))

      final def id: FiberId = self.id <> that.id

      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E1, C]] =
        (self interruptAs id).zipWith(that interruptAs id)(_.zipWith(_)(f, _ && _))

      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] = that.inheritRefs *> self.inheritRefs

      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E1, C]]] =
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
  sealed abstract class Runtime[+E, +A] extends Fiber[E, A] { self =>

    /**
     * The location the fiber was forked from.
     */
    def location: ZTraceElement

    /**
     * Generates a fiber dump.
     */
    final def dump(implicit trace: ZTraceElement): UIO[Fiber.Dump] =
      for {
        status <- self.status
        trace  <- self.trace
      } yield Fiber.Dump(self.id, status, trace)

    /**
     * Evaluates the specified effect on the fiber. If this is not possible,
     * because the fiber has already ended life, then the specified alternate
     * effect will be executed instead.
     */
    def evalOn(effect: UIO[Any], orElse: UIO[Any])(implicit trace: ZTraceElement): UIO[Unit]

    /**
     * A fully-featured, but much slower version of `evalOn`, which is useful
     * when environment and error are required.
     */
    def evalOnZIO[R, E2, A2](effect: ZIO[R, E2, A2], orElse: ZIO[R, E2, A2])(implicit
      trace: ZTraceElement
    ): ZIO[R, E2, A2] =
      for {
        r <- ZIO.environment[R]
        p <- Promise.make[E2, A2]
        _ <- evalOn(effect.provideEnvironment(r).intoPromise(p), orElse.provideEnvironment(r).intoPromise(p))
        a <- p.await
      } yield a

    /**
     * The identity of the fiber.
     */
    override def id: FiberId.Runtime

    /**
     * The status of the fiber.
     */
    def status(implicit trace: ZTraceElement): UIO[Fiber.Status]

    /**
     * The trace of the fiber.
     */
    def trace(implicit trace: ZTraceElement): UIO[ZTrace]
  }

  private[zio] object Runtime {

    implicit def fiberOrdering[E, A]: Ordering[Fiber.Runtime[E, A]] =
      Ordering.by[Fiber.Runtime[E, A], (Long, Long)](fiber => (fiber.id.startTimeSeconds, fiber.id.id))

    abstract class Internal[+E, +A] extends Runtime[E, A]
  }

  /**
   * A synthetic fiber that is created from a pure value or that combines
   * existing fibers.
   */
  sealed abstract class Synthetic[+E, +A] extends Fiber[E, A] {}

  private[zio] object Synthetic {
    abstract class Internal[+E, +A] extends Synthetic[E, A]
  }

  sealed abstract class Descriptor {
    def id: FiberId
    def status: Status
    def interrupters: Set[FiberId]
    def interruptStatus: InterruptStatus
    def executor: Executor
    def isLocked: Boolean
  }

  object Descriptor {

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
    def apply(
      id0: FiberId,
      status0: Status,
      interrupters0: Set[FiberId],
      interruptStatus0: InterruptStatus,
      executor0: Executor,
      locked0: Boolean
    ): Descriptor =
      new Descriptor {
        def id: FiberId                      = id0
        def status: Status                   = status0
        def interrupters: Set[FiberId]       = interrupters0
        def interruptStatus: InterruptStatus = interruptStatus0
        def executor: Executor               = executor0
        def isLocked: Boolean                = locked0
      }
  }

  final case class Dump(fiberId: FiberId.Runtime, status: Status, trace: ZTrace) extends Product with Serializable {
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
    def prettyPrint(implicit trace: ZTraceElement): UIO[String] =
      FiberRenderer.prettyPrint(self)
  }

  /**
   * The identity of a Fiber, described by the time it began life, and a
   * monotonically increasing sequence number generated from an atomic counter.
   */
  @deprecated("use FiberId", "2.0.0")
  type Id = FiberId
  @deprecated("use FiberId", "2.0.0")
  val Id = FiberId

  sealed trait Status {
    def isInterrupting: Boolean

    def withInterrupting(newInterrupting: Boolean): Status
  }
  object Status {
    case object Done extends Status {
      def isInterrupting: Boolean = false

      def withInterrupting(newInterrupting: Boolean): Status = this
    }
    final case class Running(interrupting: Boolean) extends Status {
      def isInterrupting: Boolean = interrupting

      def withInterrupting(newInterrupting: Boolean): Status = copy(interrupting = newInterrupting)
    }
    final case class Suspended(
      interrupting: Boolean,
      interruptible: Boolean,
      asyncs: Long,
      blockingOn: FiberId,
      asyncTrace: ZTraceElement
    ) extends Status {
      def isInterrupting: Boolean = interrupting

      def withInterrupting(newInterrupting: Boolean): Status = copy(interrupting = newInterrupting)
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
  def awaitAll(fs: Iterable[Fiber[Any, Any]])(implicit trace: ZTraceElement): UIO[Unit] =
    collectAll(fs).await.unit

  /**
   * Collects all fibers into a single fiber producing an in-order list of the
   * results.
   */
  def collectAll[E, A, Collection[+Element] <: Iterable[Element]](
    fibers: Collection[Fiber[E, A]]
  )(implicit bf: BuildFrom[Collection[Fiber[E, A]], A, Collection[A]]): Fiber.Synthetic[E, Collection[A]] =
    new Fiber.Synthetic[E, Collection[A]] {
      def await(implicit trace: ZTraceElement): UIO[Exit[E, Collection[A]]] =
        IO.foreachPar(fibers)(_.await.flatMap(IO.done(_))).exit
      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] =
        ZIO.foreachPar(Chunk.fromIterable(fibers))(_.children).map(_.flatten)
      def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A] =
        UIO.foldLeft(fibers)(ref.initial)((a, fiber) => fiber.getRef(ref).map(ref.join(a, _)))

      final def id: FiberId = fibers.foldLeft(FiberId.None: FiberId)(_ <> _.id)

      def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] =
        UIO.foreachDiscard(fibers)(_.inheritRefs)
      def interruptAs(fiberId: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, Collection[A]]] =
        UIO
          .foreach[Any, Nothing, Fiber[E, A], Exit[E, A], Iterable](fibers)(_.interruptAs(fiberId))
          .map(_.foldRight[Exit[E, List[A]]](Exit.succeed(Nil))(_.zipWith(_)(_ :: _, _ && _)))
          .map(_.map(bf.fromSpecific(fibers)))
      def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E, Collection[A]]]] =
        UIO
          .foreach[Any, Nothing, Fiber[E, A], Option[Exit[E, A]], Iterable](fibers)(_.poll)
          .map(_.foldRight[Option[Exit[E, List[A]]]](Some(Exit.succeed(Nil))) {
            case (Some(ra), Some(rb)) => Some(ra.zipWith(rb)(_ :: _, _ && _))
            case _                    => None
          })
          .map(_.map(_.map(bf.fromSpecific(fibers))))
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
      final def await(implicit trace: ZTraceElement): UIO[Exit[E, A]]                    = IO.succeedNow(exit)
      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = IO.succeedNow(Chunk.empty)
      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A]       = ZIO.succeed(ref.initial)
      final def id: FiberId                                                              = FiberId.None
      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[E, A]] = IO.succeedNow(exit)
      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit]                    = IO.unit
      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[E, A]]]             = IO.succeedNow(Some(exit))
    }

  /**
   * Dumps all fibers to the console.
   */
  def dumpAll(implicit trace: ZTraceElement): ZIO[Console, IOException, Unit] =
    dumpAllWith { dump =>
      dump.prettyPrint.flatMap(Console.printError(_))
    }

  /**
   * Dumps all fibers to the specified callback.
   */
  def dumpAllWith[R, E](f: Dump => ZIO[R, E, Any])(implicit trace: ZTraceElement): ZIO[R, E, Unit] = {
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
   * Lifts an [[zio.IO]] into a `Fiber`.
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
  @deprecated("use fromZIO", "2.0.0")
  def fromEffect[E, A](io: IO[E, A])(implicit trace: ZTraceElement): UIO[Fiber.Synthetic[E, A]] =
    fromZIO(io)

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
  def fromFuture[A](thunk: => Future[A])(implicit trace: ZTraceElement): Fiber.Synthetic[Throwable, A] =
    new Fiber.Synthetic[Throwable, A] {
      lazy val ftr: Future[A] = thunk

      final def await(implicit trace: ZTraceElement): UIO[Exit[Throwable, A]] = Task.fromFuture(_ => ftr).exit

      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] = ZIO.succeedNow(Chunk.empty)

      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A] = ZIO.succeed(ref.initial)

      final def id: FiberId = FiberId.None

      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[Throwable, A]] =
        UIO.suspendSucceed {
          ftr match {
            case c: CancelableFuture[A] => ZIO.fromFuture(_ => c.cancel()).orDie
            case _                      => join.fold(Exit.fail, Exit.succeed)
          }
        }

      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit] = IO.unit

      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[Throwable, A]]] =
        IO.succeed(ftr.value.map(Exit.fromTry))
    }

  /**
   * Lifts an [[zio.IO]] into a `Fiber`.
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
  def fromZIO[E, A](io: IO[E, A])(implicit trace: ZTraceElement): UIO[Fiber.Synthetic[E, A]] =
    io.exit.map(done(_))

  /**
   * Creates a `Fiber` that is halted with the specified cause.
   */
  @deprecated("use failCause", "2.0.0")
  def halt[E](cause: Cause[E])(implicit trace: ZTraceElement): Fiber.Synthetic[E, Nothing] =
    failCause(cause)

  /**
   * Interrupts all fibers, awaiting their interruption.
   *
   * @param fs
   *   `Iterable` of fibers to be interrupted
   * @return
   *   `UIO[Unit]`
   */
  def interruptAll(fs: Iterable[Fiber[Any, Any]])(implicit trace: ZTraceElement): UIO[Unit] =
    ZIO.fiberId.flatMap(interruptAllAs(_)(fs))

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
  def interruptAllAs(fiberId: FiberId)(fs: Iterable[Fiber[Any, Any]])(implicit trace: ZTraceElement): UIO[Unit] =
    fs.foldLeft(IO.unit)((io, f) => io <* f.interruptAs(fiberId))

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
  def joinAll[E](fs: Iterable[Fiber[E, Any]])(implicit trace: ZTraceElement): IO[E, Unit] =
    collectAll(fs).join.unit

  /**
   * A fiber that never fails or succeeds.
   */
  val never: Fiber.Synthetic[Nothing, Nothing] =
    new Fiber.Synthetic[Nothing, Nothing] {
      final def await(implicit trace: ZTraceElement): UIO[Exit[Nothing, Nothing]]                    = ZIO.never
      final def children(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]]             = ZIO.succeedNow(Chunk.empty)
      final def getRef[A](ref: FiberRef[A])(implicit trace: ZTraceElement): UIO[A]                   = ZIO.succeed(ref.initial)
      final def id: FiberId                                                                          = FiberId.None
      final def interruptAs(id: FiberId)(implicit trace: ZTraceElement): UIO[Exit[Nothing, Nothing]] = ZIO.never
      final def inheritRefs(implicit trace: ZTraceElement): UIO[Unit]                                = ZIO.unit
      final def poll(implicit trace: ZTraceElement): UIO[Option[Exit[Nothing, Nothing]]]             = ZIO.succeedNow(None)
    }

  /**
   * Returns a chunk containing all root fibers. Due to concurrency, the
   * returned chunk is only weakly consistent.
   */
  def roots(implicit trace: ZTraceElement): UIO[Chunk[Fiber.Runtime[_, _]]] =
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
   * always be `None` unless called from within an executing effect.
   */
  def unsafeCurrentFiber(): Option[Fiber[Any, Any]] =
    Option(_currentFiber.get)

  private[zio] val _currentFiber: ThreadLocal[internal.FiberContext[_, _]] =
    new ThreadLocal[internal.FiberContext[_, _]]()

  private[zio] val _roots: WeakConcurrentBag[internal.FiberContext[_, _]] =
    WeakConcurrentBag(10000)
}
