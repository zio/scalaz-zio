package zio.concurrent

import zio._

/**
 * A synchronization aid that allows a set of fibers to all wait for each other
 * to reach a common barrier point.
 *
 * CyclicBarriers are useful in programs involving a fixed sized party of fibers
 * that must occasionally wait for each other. The barrier is called cyclic
 * because it can be re-used after the waiting fibers are released.
 *
 * A CyclicBarrier supports an optional action command that is run once per
 * barrier point, after the last fiber in the party arrives, but before any
 * fibers are released. This barrier action is useful for updating shared-state
 * before any of the parties continue.
 */
final class CyclicBarrier private (
  private val _parties: Int,
  private val _waiting: Ref[Int],
  private val _lock: Ref[Promise[Unit, Unit]],
  private val _action: UIO[Any],
  private val _broken: Ref[Boolean]
) {
  private val break: UIO[Unit] =
    _broken.set(true) *> fail

  private val fail: UIO[Unit] =
    _lock.get.flatMap(_.fail(()).unit)

  private val succeed: UIO[Unit] =
    _lock.get.flatMap(_.succeed(()).unit)

  /** The number of parties required to trip this barrier. */
  def parties: Int = _parties

  /** The number of parties currently waiting at the barrier. */
  val waiting: UIO[Int] = _waiting.get

  /**
   * Waits until all parties have invoked await on this barrier. Fails if the
   * barrier is broken.
   */
  val await: IO[Unit, Int] =
    ZIO.uninterruptibleMask { restore =>
      _broken.get.flatMap(if (_) ZIO.fail(()) else ZIO.unit) *>
        _waiting.modify {
          case n if n + 1 == parties => (restore(_action) *> succeed.as(_parties - n - 1) <* reset)                      -> 0
          case n                     => _lock.get.flatMap(l => restore(l.await).onInterrupt(break)).as(_parties - n - 1) -> (n + 1)
        }.flatten
    }

  /** Resets the barrier to its initial state. Breaks any waiting party. */
  val reset: UIO[Unit] =
    (fail.whenZIO(waiting.map(_ > 0)) *>
      Promise.make[Unit, Unit].flatMap(_lock.set) *>
      _waiting.set(0) *>
      _broken.set(false)).uninterruptible

  /** Queries if this barrier is in a broken state. */
  val isBroken: UIO[Boolean] = _broken.get
}

object CyclicBarrier {
  def make(parties: Int): UIO[CyclicBarrier] =
    make(parties, ZIO.unit)

  def make(parties: Int, action: UIO[Any]): UIO[CyclicBarrier] =
    for {
      waiting <- Ref.make(0)
      broken  <- Ref.make(false)
      lock    <- Promise.make[Unit, Unit].flatMap(Ref.make(_))
    } yield new CyclicBarrier(parties, waiting, lock, action, broken)
}
