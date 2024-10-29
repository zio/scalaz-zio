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

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * An asynchronous semaphore, which is a generalization of a mutex. Semaphores
 * have a certain number of permits, which can be held and released concurrently
 * by different parties. Attempts to acquire more permits than available result
 * in the acquiring fiber being suspended until the specified number of permits
 * become available.
 *
 * If you need functionality that `Semaphore` doesnt' provide, use a
 * [[TSemaphore]] and define it in a [[zio.stm.ZSTM]] transaction.
 */
sealed trait Semaphore extends Serializable {

  /**
   * Returns the number of available permits.
   */
  def available(implicit trace: Trace): UIO[Long]

  /**
   * Returns the number of tasks currently waiting for permits. The default
   * implementation returns 0.
   */
  def awaiting(implicit trace: Trace): UIO[Long] = ZIO.succeed(0L)

  /**
   * Executes the specified workflow, acquiring a permit immediately before the
   * workflow begins execution and releasing it immediately after the workflow
   * completes execution, whether by success, failure, or interruption.
   */
  def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Returns a scoped workflow that describes acquiring a permit as the
   * `acquire` action and releasing it as the `release` action.
   */
  def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit]

  /**
   * Executes the specified workflow, acquiring the specified number of permits
   * immediately before the workflow begins execution and releasing them
   * immediately after the workflow completes execution, whether by success,
   * failure, or interruption.
   */
  def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]

  /**
   * Returns a scoped workflow that describes acquiring the specified number of
   * permits and releasing them when the scope is closed.
   */
  def withPermitsScoped(n: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Unit]
}

object Semaphore {

  /**
   * Creates a new `Semaphore` with the specified number of permits.
   */
  def make(permits: => Long)(implicit trace: Trace): UIO[Semaphore] =
    ZIO.succeed(unsafe.make(permits)(Unsafe.unsafe))

  object unsafe {
    def make(permits: Long)(implicit unsafe: Unsafe): Semaphore =
      new Semaphore {
        val ref = Ref.unsafe.make[Either[ScalaQueue[(Promise[Nothing, Unit], Long)], Long]](Right(permits))

        def available(implicit trace: Trace): UIO[Long] =
          ref.get.map {
            case Left(_)        => 0L
            case Right(permits) => permits
          }

        override def awaiting(implicit trace: Trace): UIO[Long] =
          ref.get.map {
            case Left(queue) => queue.size.toLong
            case Right(_)    => 0L
          }

        def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          withPermits(1L)(zio)

        def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
          withPermitsScoped(1L)

        def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
          ZIO.acquireReleaseWith(reserve(n))(_.release)(_.acquire *> zio)

        def withPermitsScoped(n: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
          ZIO.acquireRelease(reserve(n))(_.release).flatMap(_.acquire)

        case class Reservation(acquire: UIO[Unit], release: UIO[Any])

        def reserve(n: Long)(implicit trace: Trace): UIO[Reservation] =
          if (n < 0)
            ZIO.die(new IllegalArgumentException(s"Unexpected negative `$n` permits requested."))
          else if (n == 0L)
            ZIO.succeedNow(Reservation(ZIO.unit, ZIO.unit))
          else
            Promise.make[Nothing, Unit].flatMap { promise =>
              ref.modify {
                case Right(permits) if permits >= n =>
                  Reservation(ZIO.unit, releaseN(n)) -> Right(permits - n)
                case Right(permits) =>
                  Reservation(promise.await, restore(promise, n)) -> Left(ScalaQueue(promise -> (n - permits)))
                case Left(queue) =>
                  Reservation(promise.await, restore(promise, n)) -> Left(queue.enqueue(promise -> n))
              }
            }

        def restore(promise: Promise[Nothing, Unit], n: Long)(implicit trace: Trace): UIO[Any] =
          ref.modify {
            case Left(queue) =>
              queue
                .find(_._1 == promise)
                .fold(releaseN(n) -> Left(queue)) { case (_, permits) =>
                  releaseN(n - permits) -> Left(queue.filter(_._1 != promise))
                }
            case Right(permits) => ZIO.unit -> Right(permits + n)
          }.flatten

        def releaseN(n: Long)(implicit trace: Trace): UIO[Any] = {

          @tailrec
          def loop(
            n: Long,
            state: Either[ScalaQueue[(Promise[Nothing, Unit], Long)], Long],
            acc: UIO[Any]
          ): (UIO[Any], Either[ScalaQueue[(Promise[Nothing, Unit], Long)], Long]) =
            state match {
              case Right(permits) => acc -> Right(permits + n)
              case Left(queue) =>
                queue.dequeueOption match {
                  case None => acc -> Right(n)
                  case Some(((promise, permits), queue)) =>
                    if (n > permits)
                      loop(n - permits, Left(queue), acc *> promise.succeed(()))
                    else if (n == permits)
                      (acc *> promise.succeed(())) -> Left(queue)
                    else
                      acc -> Left((promise -> (permits - n)) +: queue)
                }
            }

          ref.modify(loop(n, _, ZIO.unit)).flatten
        }
      }
  }
}
