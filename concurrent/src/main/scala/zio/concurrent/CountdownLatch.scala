package zio.concurrent

import zio._

/**
 * A synchronization aid that allows one or more fibers to wait until a set of
 * operations being performed in other fibers completes.
 *
 * A `CountDownLatch` is initialized with a given count. The `await` method
 * block until the current count reaches zero due to invocations of the
 * `countDown` method, after which all waiting fibers are released and any
 * subsequent invocations of `await` return immediately. This is a one-shot
 * phenomenon -- the count cannot be reset. If you need a version that resets
 * the count, consider using a [[CyclicBarrier]].
 *
 * A `CountDownLatch` is a versatile synchronization tool and can be used for a
 * number of purposes. A `CountDownLatch` initialized with a count of one serves
 * as a simple on/off latch, or gate: all fibers invoking `await` wait at the
 * gate until it is opened by a fiber invoking `countDown`. A `CountDownLatch`
 * initialized to N can be used to make one fiber wait until N fibers have
 * completed some action, or some action has been completed N times.
 *
 * A useful property of a `CountDownLatch` is that it doesn't require that
 * fibers calling `countDown` wait for the count to reach zero before
 * proceeding, it simply prevents any fiber from proceeding past an `await`
 * until all fibers could pass.
 */
final class CountdownLatch private (_count: Ref[Int], _waiters: Promise[Nothing, Unit]) {

  /**
   * Causes the current fiber to wait until the latch has counted down to zero
   */
  val await: UIO[Unit] = _waiters.await

  /**
   * Decrements the count of the latch, releasing all waiting fibers if the
   * count reaches zero
   */
  val countDown: UIO[Unit] = _count.modify {
    case 0 => ZIO.unit             -> 0
    case 1 => _waiters.succeed(()) -> 0
    case n => ZIO.unit             -> (n - 1)
  }.flatten.unit

  /** Returns the current count */
  val count: UIO[Int] = _count.get
}

object CountdownLatch {
  def make(n: Int): UIO[CountdownLatch] =
    if (n <= 0)
      ZIO.die(new IllegalArgumentException("n must be positive"))
    else
      Ref.make(n).zipWith(Promise.make[Nothing, Unit])(new CountdownLatch(_, _))
}
