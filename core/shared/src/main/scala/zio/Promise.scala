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

import zio.Promise.internal._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.AtomicReference

/**
 * A promise represents an asynchronous variable, of [[zio.ZIO]] type, that can
 * be set exactly once, with the ability for an arbitrary number of fibers to
 * suspend (by calling `await`) and automatically resume when the variable is
 * set.
 *
 * Promises can be used for building primitive actions whose completions require
 * the coordinated action of multiple fibers, and for building higher-level
 * concurrent or asynchronous structures.
 * {{{
 * for {
 *   promise <- Promise.make[Nothing, Int]
 *   _       <- promise.succeed(42).delay(1.second).fork
 *   value   <- promise.await // Resumes when forked fiber completes promise
 * } yield value
 * }}}
 */
final class Promise[E, A] private (
  private val state: AtomicReference[Promise.internal.State[E, A]],
  blockingOn: FiberId
) extends Serializable {

  /**
   * Retrieves the value of the promise, suspending the fiber running the action
   * until the result is available.
   */
  def await(implicit trace: Trace): IO[E, A] =
    ZIO.suspendSucceed {
      state.get match {
        case Done(value) =>
          value
        case _ =>
          ZIO.asyncInterrupt[Any, E, A](
            k => {
              var result = null.asInstanceOf[Either[UIO[Any], IO[E, A]]]
              var retry  = true

              while (retry) {
                val oldState = state.get

                val newState = oldState match {
                  case Pending(joiners) =>
                    result = Left(interruptJoiner(k))

                    Pending(k :: joiners)
                  case s @ Done(value) =>
                    result = Right(value)

                    s
                }

                retry = !state.compareAndSet(oldState, newState)
              }

              result
            },
            blockingOn
          )
      }
    }

  /**
   * Kills the promise with the specified error, which will be propagated to all
   * fibers waiting on the value of the promise.
   */
  def die(e: Throwable)(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.die(e)(trace, Unsafe.unsafe))

  /**
   * Exits the promise with the specified exit, which will be propagated to all
   * fibers waiting on the value of the promise.
   */
  def done(e: Exit[E, A])(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.completeWith(e)(Unsafe.unsafe))

  /**
   * Completes the promise with the result of the specified effect. If the
   * promise has already been completed, the method will produce false.
   *
   * Note that [[Promise.completeWith]] will be much faster, so consider using
   * that if you do not need to memoize the result of the specified effect.
   */
  def complete(io: IO[E, A])(implicit trace: Trace): UIO[Boolean] = io.intoPromise(this)

  /**
   * Completes the promise with the specified effect. If the promise has already
   * been completed, the method will produce false.
   *
   * Note that since the promise is completed with an effect, the effect will be
   * evaluated each time the value of the promise is retrieved through
   * combinators such as `await`, potentially producing different results if the
   * effect produces different results on subsequent evaluations. In this case
   * te meaning of the "exactly once" guarantee of `Promise` is that the promise
   * can be completed with exactly one effect. For a version that completes the
   * promise with the result of an effect see [[Promise.complete]].
   */
  def completeWith(io: IO[E, A])(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.completeWith(io)(Unsafe.unsafe))

  /**
   * Fails the promise with the specified error, which will be propagated to all
   * fibers waiting on the value of the promise.
   */
  def fail(e: E)(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.fail(e)(trace, Unsafe.unsafe))

  /**
   * Fails the promise with the specified cause, which will be propagated to all
   * fibers waiting on the value of the promise.
   */
  def failCause(e: Cause[E])(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.failCause(e)(trace, Unsafe.unsafe))

  /**
   * Completes the promise with interruption. This will interrupt all fibers
   * waiting on the value of the promise as by the fiber calling this method.
   */
  def interrupt(implicit trace: Trace): UIO[Boolean] =
    ZIO.fiberIdWith(id => interruptAs(id))

  /**
   * Completes the promise with interruption. This will interrupt all fibers
   * waiting on the value of the promise as by the specified fiber.
   */
  def interruptAs(fiberId: FiberId)(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.interruptAs(fiberId)(trace, Unsafe.unsafe))

  /**
   * Checks for completion of this Promise. Produces true if this promise has
   * already been completed with a value or an error and false otherwise.
   */
  def isDone(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.isDone(Unsafe.unsafe))

  /**
   * Checks for completion of this Promise. Returns the result effect if this
   * promise has already been completed or a `None` otherwise.
   */
  def poll(implicit trace: Trace): UIO[Option[IO[E, A]]] =
    ZIO.succeed(unsafe.poll(Unsafe.unsafe))

  /**
   * Fails the promise with the specified cause, which will be propagated to all
   * fibers waiting on the value of the promise. No new stack trace is attached
   * to the cause.
   */
  def refailCause(e: Cause[E])(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.refailCause(e)(trace, Unsafe.unsafe))

  /**
   * Completes the promise with the specified value.
   */
  def succeed(a: A)(implicit trace: Trace): UIO[Boolean] =
    ZIO.succeed(unsafe.succeed(a)(trace, Unsafe.unsafe))

  private def interruptJoiner(joiner: IO[E, A] => Any)(implicit trace: Trace): UIO[Any] = ZIO.succeed {
    var retry = true

    while (retry) {
      val oldState = state.get

      val newState = oldState match {
        case Pending(joiners) =>
          Pending(joiners.filter(j => !j.eq(joiner)))

        case _ =>
          oldState
      }

      retry = !state.compareAndSet(oldState, newState)
    }
  }

  private[zio] trait UnsafeAPI extends Serializable {
    def completeWith(io: IO[E, A])(implicit unsafe: Unsafe): Boolean
    def die(e: Throwable)(implicit trace: Trace, unsafe: Unsafe): Boolean
    def done(io: IO[E, A])(implicit unsafe: Unsafe): Unit
    def fail(e: E)(implicit trace: Trace, unsafe: Unsafe): Boolean
    def failCause(e: Cause[E])(implicit trace: Trace, unsafe: Unsafe): Boolean
    def interruptAs(fiberId: FiberId)(implicit trace: Trace, unsafe: Unsafe): Boolean
    def isDone(implicit unsafe: Unsafe): Boolean
    def poll(implicit unsafe: Unsafe): Option[IO[E, A]]
    def refailCause(e: Cause[E])(implicit trace: Trace, unsafe: Unsafe): Boolean
    def succeed(a: A)(implicit trace: Trace, unsafe: Unsafe): Boolean
  }

  private[zio] val unsafe: UnsafeAPI =
    new UnsafeAPI {
      def completeWith(io: IO[E, A])(implicit unsafe: Unsafe): Boolean = {
        var action: () => Boolean = null.asInstanceOf[() => Boolean]
        var retry                 = true

        while (retry) {
          val oldState = state.get

          val newState = oldState match {
            case Pending(joiners) =>
              action = () => { joiners.foreach(_(io)); true }

              Done(io)

            case _ =>
              action = Promise.ConstFalse

              oldState
          }

          retry = !state.compareAndSet(oldState, newState)
        }

        action()
      }

      def die(e: Throwable)(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(ZIO.die(e))

      def done(io: IO[E, A])(implicit unsafe: Unsafe): Unit = {
        var retry: Boolean                 = true
        var joiners: List[IO[E, A] => Any] = null

        while (retry) {
          val oldState = state.get

          val newState = oldState match {
            case Pending(js) =>
              joiners = js
              Done(io)
            case _ => oldState
          }

          retry = !state.compareAndSet(oldState, newState)
        }

        if (joiners ne null) joiners.foreach(_(io))
      }

      def fail(e: E)(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(ZIO.fail(e))

      def failCause(e: Cause[E])(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(ZIO.failCause(e))

      def interruptAs(fiberId: FiberId)(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(ZIO.interruptAs(fiberId))

      def isDone(implicit unsafe: Unsafe): Boolean =
        state.get().isInstanceOf[Done[?, ?]]

      def poll(implicit unsafe: Unsafe): Option[IO[E, A]] =
        state.get() match {
          case _: Pending[?, ?] => None
          case Done(io)         => Some(io)
        }

      def refailCause(e: Cause[E])(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(Exit.failCause(e))

      def succeed(a: A)(implicit trace: Trace, unsafe: Unsafe): Boolean =
        completeWith(ZIO.succeed(a))
    }

}
object Promise {
  private val ConstFalse: () => Boolean = () => false

  private[zio] object internal {
    sealed abstract class State[E, A]                              extends Serializable with Product
    final case class Pending[E, A](joiners: List[IO[E, A] => Any]) extends State[E, A]
    final case class Done[E, A](value: IO[E, A])                   extends State[E, A]
  }

  /**
   * Makes a new promise to be completed by the fiber creating the promise.
   */
  def make[E, A](implicit trace: Trace): UIO[Promise[E, A]] = ZIO.fiberIdWith(makeAs(_))

  /**
   * Makes a new promise to be completed by the fiber with the specified id.
   */
  def makeAs[E, A](fiberId: => FiberId)(implicit trace: Trace): UIO[Promise[E, A]] =
    ZIO.succeed(unsafe.make(fiberId)(Unsafe.unsafe))

  object unsafe {
    def make[E, A](fiberId: FiberId)(implicit unsafe: Unsafe): Promise[E, A] =
      new Promise[E, A](new AtomicReference[State[E, A]](new internal.Pending[E, A](Nil)), fiberId)
  }
}
