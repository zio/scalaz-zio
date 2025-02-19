/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
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
import zio.stm.TSemaphore
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

sealed trait Semaphore extends Serializable {
  def available(implicit trace: Trace): UIO[Long]
  def awaiting(implicit trace: Trace): UIO[Long]
  def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit]
  def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  def withPermitsScoped(n: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Unit]
  def release(implicit trace: Trace): UIO[Unit] = releaseN(1L)
  def releaseN(n: Long)(implicit trace: Trace): UIO[Unit]
}

object Semaphore {

  /**
   * Creates a new fair `Semaphore` with the specified number of permits.
   */
  def make(permits: => Long, fairness: Boolean = true)(implicit trace: Trace): UIO[Semaphore] =
    ZIO.succeed(unsafe.make(permits, fairness)(Unsafe.unsafe))

  /**
   * Creates a new unfair `Semaphore` with the specified number of permits.
   */
  def makeUnfair(permits: => Long)(implicit trace: Trace): UIO[Semaphore] =
    make(permits, fairness = false)

  object unsafe {
    def make(permits: Long, fairness: Boolean)(implicit unsafe: Unsafe): Semaphore = {
      if (fairness) new FairSemaphore(permits)
      else new UnfairSemaphore(permits)
    }
  }

  /**
   * Encapsulates the acquire and release actions for a permit reservation.
   */
  private case class Reservation(acquire: UIO[Unit], release: UIO[Unit])

  /**
   * A fair semaphore implementation that ensures FIFO order for waiting fibers.
   */
  private final class FairSemaphore(initialPermits: Long) extends Semaphore {
    private val state = new AtomicReference[Either[ScalaQueue[(Promise[Nothing, Unit], Long)], Long]](Right(initialPermits))

    def available(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(state.get() match {
        case Left(_)        => 0L
        case Right(permits) => permits
      })

    def awaiting(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(state.get() match {
        case Left(queue) => queue.size.toLong
        case Right(_)    => 0L
      })

    def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      withPermits(1L)(zio)

    def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      withPermitsScoped(1L)

    def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      ZIO.acquireReleaseWith(reserve(n))(_.release)(_.acquire *> zio)

    def withPermitsScoped(n: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      ZIO.acquireRelease(reserve(n))(_.release).flatMap(_.acquire)

    def releaseN(n: Long)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(releaseInternal(n))

    /**
     * Reserves `n` permits, returning a `Reservation` that encapsulates the acquire and release actions.
     */
    private def reserve(n: Long)(implicit trace: Trace): UIO[Reservation] = {
      require(n >= 0, s"Unexpected negative `$n` permits requested.")
      if (n == 0L) ZIO.succeedNow(Reservation(ZIO.unit, ZIO.unit))
      else Promise.make[Nothing, Unit].flatMap { promise =>
        ZIO.succeed {
          var loop = true
          var result: Reservation = null
          while (loop) {
            val current = state.get()
            current match {
              case Right(permits) if permits >= n =>
                if (state.compareAndSet(current, Right(permits - n))) {
                  result = Reservation(ZIO.unit, ZIO.succeed(releaseInternal(n)))
                  loop = false
                }
              case Right(permits) =>
                val newQueue = ScalaQueue(promise -> (n - permits))
                if (state.compareAndSet(current, Left(newQueue))) {
                  result = Reservation(promise.await, ZIO.succeed(releaseInternal(n)))
                  loop = false
                }
              case Left(queue) =>
                val newQueue = queue.enqueue(promise -> n)
                if (state.compareAndSet(current, Left(newQueue))) {
                  result = Reservation(promise.await, ZIO.succeed(releaseInternal(n)))
                  loop = false
                }
            }
          }
          result
        }
      }
    }

    /**
     * Releases `n` permits and wakes up waiting fibers in FIFO order.
     */
    @tailrec
    private def releaseInternal(n: Long): Unit = {
      val current = state.get()
      current match {
        case Right(permits) =>
          if (!state.compareAndSet(current, Right(permits + n))) releaseInternal(n)
        case Left(queue) =>
          queue.dequeueOption match {
            case None =>
              if (!state.compareAndSet(current, Right(n))) releaseInternal(n)
            case Some(((promise, required), remaining)) =>
              if (n >= required) {
                promise.unsafe.done(ZIO.succeedUnit)
                if (!state.compareAndSet(current, Left(remaining))) releaseInternal(n - required)
              } else {
                val newQueue = (promise -> (required - n)) +: remaining
                if (!state.compareAndSet(current, Left(newQueue))) releaseInternal(n)
              }
          }
      }
    }
  }

  /**
   * An unfair semaphore implementation that allows new fibers to "barge" ahead of waiting fibers.
   */
  private final class UnfairSemaphore(initialPermits: Long) extends Semaphore {
    private val permits = new AtomicLong(initialPermits)
    private val waiters = new ConcurrentLinkedQueue[Promise[Nothing, Unit]]

    def available(implicit trace: Trace): UIO[Long] = ZIO.succeed(permits.get())

    def awaiting(implicit trace: Trace): UIO[Long] = ZIO.succeed(waiters.size().toLong)

    def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      withPermits(1L)(zio)

    def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
      withPermitsScoped(1L)

    def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace, unsafe: Unsafe): ZIO[R, E, A] =
      ZIO.acquireReleaseWith(reserve(n))(_.release)(_.acquire *> zio)

    def withPermitsScoped(n: Long)(implicit trace: Trace, unsafe: Unsafe): ZIO[Scope, Nothing, Unit] =
      ZIO.acquireRelease(reserve(n))(_.release).flatMap(_.acquire)

    def releaseN(n: Long)(implicit trace: Trace): UIO[Unit] = ZIO.succeed {
      permits.addAndGet(n)
      var loop = true
      while (loop && permits.get() > 0 && !waiters.isEmpty) {
        val waiter = waiters.poll()
        if (waiter != null) {
          waiter.unsafe.done(ZIO.succeedUnit)
          loop = permits.decrementAndGet() >= 0
        }
      }
    }

    /**
     * Reserves `n` permits, returning a `Reservation` that encapsulates the acquire and release actions.
     */
    private def reserve(n: Long)(implicit trace: Trace, unsafe: Unsafe): UIO[Reservation] = {
      require(n >= 0, s"Unexpected negative `$n` permits requested.")
      if (n == 0L) ZIO.succeedNow(Reservation(ZIO.unit, ZIO.unit))
      else ZIO.succeed {
        var loop = true
        var result: Reservation = null
        while (loop) {
          val current = permits.get()
          if (current >= n && permits.compareAndSet(current, current - n)) {
            result = Reservation(ZIO.unit, releaseN(n))
            loop = false
          } else {
            val promise = Promise.unsafe.make[Nothing, Unit](FiberId.None)
            waiters.add(promise)
            result = Reservation(promise.await, releaseN(n))
            loop = false
          }
        }
        result
      }
    }
  }
}