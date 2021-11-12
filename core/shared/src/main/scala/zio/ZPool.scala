package zio

/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `ZPool[E, A]` is a pool of items of type `A`, each of which may be
 * associated with the acquisition and release of resources. An attempt to get
 * an item `A` from a pool may fail with an error of type `E`.
 */
trait ZPool[+Error, Item] {

  /**
   * Retrieves an item from the pool in a `Managed` effect. Note that if
   * acquisition fails, then the returned effect will fail for that same reason.
   * Retrying a failed acquisition attempt will repeat the acquisition attempt.
   */
  def get(implicit trace: ZTraceElement): ZManaged[Any, Error, Item]

  /**
   * Invalidates the specified item. This will cause the pool to eventually
   * reallocate the item, although this reallocation may occur lazily rather
   * than eagerly.
   */
  def invalidate(item: Item)(implicit trace: ZTraceElement): UIO[Unit]
}
object ZPool {

  /**
   * Creates a pool from a fixed number of pre-allocated items. This method
   * should only be used when there is no cleanup or release operation
   * associated with items in the pool. If cleanup or release is required, then
   * the `make` constructor should be used instead.
   */
  def fromIterable[A](iterable0: => Iterable[A])(implicit trace: ZTraceElement): UManaged[ZPool[Nothing, A]] =
    for {
      iterable <- ZManaged.succeed(iterable0)
      source   <- Ref.make(iterable.toList).toManaged
      get = if (iterable.isEmpty) ZIO.never
            else
              source.modify {
                case head :: tail => (head, tail)
                case Nil          => throw new IllegalArgumentException("No items in list!")
              }
      pool <- ZPool.make(ZManaged.fromZIO(get), iterable.size)
    } yield pool

  /**
   * Makes a new pool of the specified fixed size. The pool is returned in a
   * `Managed`, which governs the lifetime of the pool. When the pool is
   * shutdown because the `Managed` is used, the individual items allocated by
   * the pool will be released in some unspecified order.
   */
  def make[R, E, A](get: ZManaged[R, E, A], size: Int)(implicit trace: ZTraceElement): URManaged[R, ZPool[E, A]] =
    makeWith(get, size to size)(Strategy.None)

  /**
   * Makes a new pool with the specified minimum and maximum sizes and time to
   * live before a pool whose excess items are not being used will be shrunk
   * down to the minimum size. The pool is returned in a `Managed`, which
   * governs the lifetime of the pool. When the pool is shutdown because the
   * `Managed` is used, the individual items allocated by the pool will be
   * released in some unspecified order.
   * {{{
   * ZPool.make(acquireDbConnection, 10 to 20, 60.seconds).use { pool =>
   *   pool.get.use {
   *     connection => useConnection(connection)
   *   }
   * }
   * }}}
   */
  def make[R, E, A](get: ZManaged[R, E, A], range: Range, timeToLive: Duration)(implicit
    trace: ZTraceElement
  ): ZManaged[R with Has[Clock], Nothing, ZPool[E, A]] =
    makeWith(get, range)(Strategy.TimeToLive(timeToLive))

  /**
   * A more powerful variant of `make` that allows specifying a `Strategy` that
   * describes how a pool whose excess items are not being used will be shrunk
   * down to the minimum size.
   */
  private def makeWith[R, R1, E, A](get: ZManaged[R, E, A], range: Range)(strategy: Strategy[R1, E, A])(implicit
    trace: ZTraceElement
  ): ZManaged[R with R1, Nothing, ZPool[E, A]] =
    for {
      env     <- ZManaged.environment[R]
      down    <- Ref.make(false).toManaged
      state   <- Ref.make(State(0, 0)).toManaged
      items   <- Queue.bounded[Attempted[E, A]](range.end).toManaged
      inv     <- Ref.make(Set.empty[A]).toManaged
      initial <- strategy.initial.toManaged
      pool     = DefaultPool(get.provide(env), range, down, state, items, inv, strategy.track(initial))
      fiber   <- pool.initialize.forkDaemon.toManaged
      shrink  <- strategy.run(initial, pool.excess, pool.shrink).forkDaemon.toManaged
      _       <- ZManaged.finalizer(fiber.interrupt *> shrink.interrupt *> pool.shutdown)
    } yield pool

  private case class Attempted[+E, +A](result: Exit[E, A], finalizer: UIO[Any]) {
    def isFailure: Boolean = result.isFailure

    def forEach[R, E2](f: A => ZIO[R, E2, Any]): ZIO[R, E2, Any] =
      result match {
        case Exit.Failure(_) => ZIO.unit
        case Exit.Success(a) => f(a)
      }

    def toManaged(implicit trace: ZTraceElement): ZManaged[Any, E, A] =
      ZIO.done(result).toManaged
  }

  private case class DefaultPool[R, E, A, S](
    creator: ZManaged[Any, E, A],
    range: Range,
    isShuttingDown: Ref[Boolean],
    state: Ref[State],
    items: Queue[Attempted[E, A]],
    invalidated: Ref[Set[A]],
    track: Exit[E, A] => UIO[Any]
  ) extends ZPool[E, A] {

    /**
     * Returns the number of items in the pool in excess of the minimum size.
     */
    def excess(implicit trace: ZTraceElement): UIO[Int] =
      state.get.map { case State(free, size) => size - range.start min free }

    def get(implicit trace: ZTraceElement): ZManaged[Any, E, A] = {

      def acquire: UIO[Attempted[E, A]] =
        isShuttingDown.get.flatMap { down =>
          if (down) ZIO.interrupt
          else
            state.modify { case State(size, free) =>
              if (free > 0 || size >= range.end)
                (
                  items.take.flatMap { acquired =>
                    acquired.result match {
                      case Exit.Success(item) =>
                        invalidated.get.flatMap { set =>
                          if (set.contains(item))
                            state.update(state => state.copy(free = state.free + 1)) *>
                              allocate *>
                              acquire
                          else
                            ZIO.succeedNow(acquired)
                        }
                      case _ =>
                        ZIO.succeedNow(acquired)
                    }
                  },
                  State(size, free - 1)
                )
              else if (size >= 0)
                allocate *> acquire -> State(size + 1, free + 1)
              else
                ZIO.interrupt -> State(size, free)
            }.flatten
        }

      def release(attempted: Attempted[E, A]): UIO[Any] =
        if (attempted.isFailure)
          state.modify { case State(size, free) =>
            if (size <= range.start)
              allocate -> State(size, free + 1)
            else
              ZIO.unit -> State(size - 1, free)
          }.flatten
        else
          state.update(state => state.copy(free = state.free + 1)) *>
            items.offer(attempted) *>
            track(attempted.result) *>
            getAndShutdown.whenZIO(isShuttingDown.get)

      ZManaged.acquireReleaseWith(acquire)(release(_)).flatMap(_.toManaged)
    }

    /**
     * Begins pre-allocating pool entries based on minimum pool size.
     */
    final def initialize(implicit trace: ZTraceElement): UIO[Unit] =
      ZIO.replicateZIODiscard(range.start) {
        ZIO.uninterruptibleMask { restore =>
          state.modify { case State(size, free) =>
            if (size < range.start && size >= 0)
              (
                for {
                  reservation <- creator.reserve
                  exit        <- restore(reservation.acquire).exit
                  attempted   <- ZIO.succeed(Attempted(exit, reservation.release(Exit.succeed(()))))
                  _           <- items.offer(attempted)
                  _           <- track(attempted.result)
                  _           <- getAndShutdown.whenZIO(isShuttingDown.get)
                } yield attempted,
                State(size + 1, free + 1)
              )
            else
              ZIO.unit -> State(size, free)
          }.flatten
        }
      }

    def invalidate(item: A)(implicit trace: zio.ZTraceElement): UIO[Unit] =
      invalidated.update(_ + item)

    /**
     * Shrinks the pool down, but never to less than the minimum size.
     */
    def shrink(implicit trace: ZTraceElement): UIO[Any] =
      ZIO.uninterruptible {
        state.modify { case State(size, free) =>
          if (size > range.start && free > 0)
            (
              items.take.flatMap { attempted =>
                attempted.forEach(a => invalidated.update(_ - a)) *>
                  attempted.finalizer *>
                  state.update(state => state.copy(size = state.size - 1))
              },
              State(size, free - 1)
            )
          else
            ZIO.unit -> State(size, free)
        }.flatten
      }

    private def allocate(implicit trace: ZTraceElement): UIO[Any] =
      ZIO.uninterruptibleMask { restore =>
        for {
          reservation <- creator.reserve
          exit        <- restore(reservation.acquire).exit
          attempted   <- ZIO.succeed(Attempted(exit, reservation.release(Exit.succeed(()))))
          _           <- items.offer(attempted)
          _           <- track(attempted.result)
          _           <- getAndShutdown.whenZIO(isShuttingDown.get)
        } yield attempted
      }

    /**
     * Gets items from the pool and shuts them down as long as there are items
     * free, signalling shutdown of the pool if the pool is empty.
     */
    private def getAndShutdown(implicit trace: ZTraceElement): UIO[Unit] =
      state.modify { case State(size, free) =>
        if (free > 0)
          (
            items.take.foldCauseZIO(
              _ => ZIO.unit,
              attempted =>
                attempted.forEach(a => invalidated.update(_ - a)) *>
                  attempted.finalizer *>
                  state.update(state => state.copy(size = state.size - 1)) *>
                  getAndShutdown
            ),
            State(size, free - 1)
          )
        else if (size > 0)
          ZIO.unit -> State(size, free)
        else
          items.shutdown -> State(size - 1, free)
      }.flatten

    final def shutdown(implicit trace: ZTraceElement): UIO[Unit] =
      isShuttingDown.modify { down =>
        if (down)
          items.awaitShutdown -> true
        else
          getAndShutdown *> items.awaitShutdown -> true
      }.flatten
  }

  private case class State(size: Int, free: Int)

  /**
   * A `Strategy` describes the protocol for how a pool whose excess items are
   * not being used should shrink down to the minimum pool size.
   */
  private trait Strategy[-Environment, -Error, -Item] {

    /**
     * Describes the type of the state maintained by the strategy.
     */
    type State

    /**
     * Describes how the initial state of the strategy should be allocated.
     */
    def initial(implicit trace: ZTraceElement): URIO[Environment, State]

    /**
     * Describes how the state of the strategy should be updated when an item is
     * added to the pool or returned to the pool.
     */
    def track(state: State)(item: Exit[Error, Item])(implicit trace: ZTraceElement): UIO[Unit]

    /**
     * Describes how excess items that are not being used should shrink down.
     */
    def run(state: State, getExcess: UIO[Int], shrink: UIO[Any])(implicit trace: ZTraceElement): UIO[Unit]
  }

  private object Strategy {

    /**
     * A strategy that does nothing to shrink excess items. This is useful when
     * the minimum size of the pool is equal to its maximum size and so there is
     * nothing to do.
     */
    case object None extends Strategy[Any, Any, Any] {
      type State = Unit
      def initial(implicit trace: ZTraceElement): URIO[Any, Unit] =
        ZIO.unit
      def track(state: Unit)(attempted: Exit[Any, Any])(implicit trace: ZTraceElement): UIO[Unit] =
        ZIO.unit
      def run(state: Unit, getExcess: UIO[Int], shrink: UIO[Any])(implicit trace: ZTraceElement): UIO[Unit] =
        ZIO.unit
    }

    /**
     * A strategy that shrinks the pool down to its minimum size if items in the
     * pool have not been used for the specified duration.
     */
    final case class TimeToLive(timeToLive: Duration) extends Strategy[Has[Clock], Any, Any] {
      type State = (Clock, Ref[java.time.Instant])
      def initial(implicit trace: ZTraceElement): URIO[Has[Clock], State] =
        for {
          clock <- ZIO.service[Clock]
          now   <- Clock.instant
          ref   <- Ref.make(now)
        } yield (clock, ref)
      def track(state: (Clock, Ref[java.time.Instant]))(attempted: Exit[Any, Any])(implicit
        trace: ZTraceElement
      ): UIO[Unit] = {
        val (clock, ref) = state
        for {
          now <- clock.instant
          _   <- ref.set(now)
        } yield ()
      }
      def run(state: (Clock, Ref[java.time.Instant]), getExcess: UIO[Int], shrink: UIO[Any])(implicit
        trace: ZTraceElement
      ): UIO[Unit] = {
        val (clock, ref) = state
        getExcess.flatMap { excess =>
          if (excess <= 0)
            clock.sleep(timeToLive) *> run(state, getExcess, shrink)
          else
            ref.get.zip(clock.instant).flatMap { case (start, end) =>
              val duration = java.time.Duration.between(start, end)
              if (duration >= timeToLive) shrink *> run(state, getExcess, shrink)
              else clock.sleep(timeToLive) *> run(state, getExcess, shrink)
            }
        }
      }
    }
  }

}
