package zio.concurrent

import zio._
import scala.util.Random

final class ReentrantLock private (fairness: Boolean, state: Ref[ReentrantLock.State]) {
  import ReentrantLock.State

  /** Queries whether the given fiber is waiting to acquire this lock. */
  def hasQueuedFiber(fiberId: FiberId): UIO[Boolean] =
    state.get.map(_.waiters.contains(fiberId))

  /** Queries whether any fibers are waiting to acquire this lock. */
  lazy val hasQueuedFibers: UIO[Boolean] =
    state.get.map(_.waiters.nonEmpty)

  /** Queries the number of holds on this lock by the current fiber. */
  lazy val holdCount: UIO[Int] =
    ZIO.fiberIdWith { fiberId =>
      state.get.map {
        case State(_, Some(`fiberId`), cnt, _) => cnt
        case _                                 => 0
      }
    }

  /** Returns true if this lock has fairness set to true. */
  def isFair: Boolean = fairness

  /** Queries if this lock is held by the current fiber. */
  lazy val isHeldByCurrentFiber: UIO[Boolean] =
    ZIO.fiberIdWith { fiberId =>
      state.get.map {
        case State(_, Some(`fiberId`), _, _) => true
        case _                               => false
      }
    }

  /**
   * Acquires the lock.
   *
   * Acquires the lock if it is not held by another fiber and returns
   * immediately, setting the lock hold count to one.
   *
   * If the current fiber already holds the lock then the hold count is
   * incremented by one and the method returns immediately.
   *
   * If the lock is held by another fiber then the current fiber is put to sleep
   * until the lock has been acquired, at which time the lock hold count is set
   * to one.
   */
  lazy val lock: UIO[Unit] =
    (ZIO.fiberId <*> Promise.make[Nothing, Unit]).flatMap { case (fiberId, p) =>
      state.modify {
        case State(ep, None, _, _) =>
          ZIO.unit -> State(ep + 1, Some(fiberId), 1, Map.empty)
        case State(ep, Some(`fiberId`), cnt, waiters) =>
          ZIO.unit -> State(ep + 1, Some(fiberId), cnt + 1, waiters)
        case State(ep, holder, cnt, waiters) =>
          p.await.onInterrupt(_ => cleanupWaiter(fiberId)).unit -> State(
            ep + 1,
            holder,
            cnt,
            waiters.updated(fiberId, (ep, p))
          )
      }.flatten
    }

  /** Queries if this lock is held by any fiber. */
  lazy val locked: UIO[Boolean] =
    state.get.map(_.holder.nonEmpty)

  /**
   * Returns the fiber ID of the fiber that currently owns this lock, if owned,
   * or None otherwise.
   */
  lazy val owner: UIO[Option[FiberId]] =
    state.get.map(_.holder)

  /**
   * Returns the fiber IDs of the fibers that are waiting to acquire this lock.
   */
  lazy val queuedFibers: UIO[List[FiberId]] =
    state.get.map(_.waiters.keys.toList)

  /** Returns the number of fibers waiting to acquire this lock. */
  lazy val queueLength: UIO[Int] =
    state.get.map(_.waiters.size)

  /**
   * Acquires the lock only if it is not held by another fiber at the time of
   * invocation.
   */
  lazy val tryLock: UIO[Boolean] =
    ZIO.fiberIdWith { fiberId =>
      state.modify {
        case State(ep, Some(`fiberId`), cnt, holders) =>
          true -> State(ep + 1, Some(fiberId), cnt + 1, holders)
        case State(ep, None, _, holders) =>
          true -> State(ep + 1, Some(fiberId), 1, holders)
        case otherwise =>
          false -> otherwise
      }
    }

  /**
   * Attempts to release this lock.
   *
   * If the current fiber is the holder of this lock then the hold count is
   * decremented. If the hold count is now zero then the lock is released. If
   * the current thread is not the holder of this lock then nothing happens.
   */
  lazy val unlock: UIO[Unit] =
    ZIO.fiberIdWith { fiberId =>
      state.modify {
        case State(ep, Some(`fiberId`), 1, holders) =>
          relock(ep, holders)
        case State(ep, Some(`fiberId`), cnt, holders) =>
          ZIO.unit -> State(ep, Some(fiberId), cnt - 1, holders)
        case otherwise =>
          ZIO.unit -> otherwise
      }.flatten
    }

  /** Acquires and releases the lock as a managed effect. */
  lazy val withLock: URIO[Scope, Int] =
    ZIO.acquireReleaseInterruptible(lock *> holdCount)(unlock)

  private def relock(epoch: Long, holders: Map[FiberId, (Long, Promise[Nothing, Unit])]): (UIO[Unit], State) =
    if (holders.isEmpty)
      ZIO.unit -> State(epoch + 1, None, 0, Map.empty)
    else {
      val (fiberId, (_, promise)) = if (fairness) holders.minBy(_._2._1) else pickRandom(holders)
      promise.succeed(()).unit -> State(epoch + 1, Some(fiberId), 1, holders - fiberId)
    }

  private def pickRandom(
    holders: Map[FiberId, (Long, Promise[Nothing, Unit])]
  ): (FiberId, (Long, Promise[Nothing, Unit])) = {
    val n  = Random.nextInt(holders.size)
    val it = holders.iterator
    var i  = 0

    while (it.hasNext && i < n) {
      it.next()
      i += 1
    }

    it.next()
  }

  private def cleanupWaiter(fiberId: FiberId): UIO[Any] =
    state.update { case State(ep, holder, cnt, waiters) =>
      State(ep, holder, cnt, waiters - fiberId)
    }

}

object ReentrantLock {
  def make(fairness: Boolean = false): UIO[ReentrantLock] =
    Ref.make(State.empty).map(new ReentrantLock(fairness, _))

  private case class State(
    epoch: Long,
    holder: Option[FiberId],
    holdCount: Int,
    waiters: Map[FiberId, (Long, Promise[Nothing, Unit])]
  )

  private object State {
    val empty: State = State(0L, None, 0, Map.empty)
  }
}
