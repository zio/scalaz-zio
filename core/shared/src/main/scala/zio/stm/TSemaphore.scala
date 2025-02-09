/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio.stm

import zio.{Scope, UIO, Unsafe, Trace, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `TSemaphore` is a semaphore that can be composed transactionally. Because
 * of the extremely high performance of ZIO's implementation of software
 * transactional memory `TSemaphore` can support both controlling access to some
 * resource on a standalone basis as well as composing with other STM data
 * structures to solve more advanced concurrency problems.
 *
 * For basic use cases, the most idiomatic way to work with a semaphore is to
 * use the `withPermit` operator, which acquires a permit before executing some
 * `ZIO` effect and release the permit immediately afterward. The permit is
 * guaranteed to be released immediately after the effect completes execution,
 * whether by success, failure, or interruption. Attempting to acquire a permit
 * when a sufficient number of permits are not available will semantically block
 * until permits become available without blocking any underlying operating
 * system threads. If you want to acquire more than one permit at a time you can
 * use `withPermits`, which allows specifying a number of permits to acquire.
 * You can also use `withPermitScoped` or `withPermitsScoped` to acquire and
 * release permits within the context of a scoped effect for composing with
 * other resources.
 *
 * For more advanced concurrency problems you can use the `acquire` and
 * `release` operators directly, or their variants `acquireN` and `releaseN`,
 * all of which return STM transactions. Thus, they can be composed to form
 * larger STM transactions, for example acquiring permits from two different
 * semaphores transactionally and later releasing them transactionally to safely
 * synchronize on access to two different mutable variables.
 */
final class TSemaphore private (val permits: TRef[Long]) extends Serializable {

  /**
   * Acquires a single permit in transactional context.
   */
  def acquire: USTM[Unit] =
    acquireBetween(1L, 1L).unit

  /**
   * Acquires the specified number of permits in a transactional context.
   */
  def acquireN(n: Long): USTM[Unit] =
    acquireBetween(n, n).unit

  /**
   * Acquire at least `min` permits and at most `max` permits in a transactional
   * context.
   */
  def acquireBetween(min: Long, max: Long): USTM[Long] =
    ZSTM.Effect { (journal, _, _) =>
      require(min <= max, s"Unexpected `$min` > `$max` passed to acquireRange.")
      assertNonNegative(min)
      assertNonNegative(max)

      val available: Long = permits.unsafeGet(journal)
      if (available < min) {
        throw ZSTM.RetryException
      }
      val requested: Long = available.min(max)
      permits.unsafeSet(journal, available - requested)
      requested
    }

  /**
   * Acquire at most `max` permits in a transactional context.
   */
  def acquireUpTo(max: Long): USTM[Long] =
    acquireBetween(0, max)

  /**
   * Returns the number of available permits in a transactional context.
   */
  def available: USTM[Long] =
    permits.get

  /**
   * Releases a single permit in a transactional context.
   */
  def release: USTM[Unit] =
    releaseN(1L)

  /**
   * Releases the specified number of permits in a transactional context
   */
  def releaseN(n: Long): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      assertNonNegative(n)

      val current = permits.unsafeGet(journal)
      permits.unsafeSet(journal, current + n)
    }

  /**
   * Executes the specified effect, acquiring a permit immediately before the
   * effect begins execution and releasing it immediately after the effect
   * completes execution, whether by success, failure, or interruption.
   */
  def withPermit[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    withPermits(1L)(zio)

  /**
   * Returns a scoped effect that describes acquiring a permit as the `acquire`
   * action and releasing it as the `release` action.
   */
  def withPermitScoped(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    withPermitsScoped(1L)

  /**
   * Executes the specified effect, acquiring the specified number of permits
   * immediately before the effect begins execution and releasing them
   * immediately after the effect completes execution, whether by success,
   * failure, or interruption.
   */
  def withPermits[R, E, A](n: Long)(zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZSTM.acquireReleaseWith(acquireN(n))(_ => releaseN(n).commit)(_ => zio)

  /**
   * Executes the specified effect, acquiring at least `min` and at most `max`
   * permits immediately before the effect begins execution and releasing them
   * immediately after the effect completes execution, whether by success,
   * failure, or interruption.
   */
  def withPermitsBetween[R, E, A](min: Long, max: Long)(zio: Long => ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    ZSTM.acquireReleaseWith(acquireBetween(min, max))((actualN: Long) => releaseN(actualN).commit)(zio)

  /**
   * Executes the specified effect, acquiring at most the specified number of
   * permits immediately before the effect begins execution and releasing them
   * immediately after the effect completes execution, whether by success,
   * failure, or interruption.
   */
  def withPermitsUpTo[R, E, A](max: Long)(zio: Long => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZSTM.acquireReleaseWith(acquireUpTo(max))((actualN: Long) => releaseN(actualN).commit)(zio)

  /**
   * Returns a scoped effect that describes acquiring the specified number of
   * permits and releasing them when the scope is closed.
   */
  def withPermitsScoped(n: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Unit] =
    ZSTM.acquireReleaseWith(acquireN(n))(_ => Scope.addFinalizer(releaseN(n).commit))(_ => ZIO.unit)

  /**
   * Returns a scoped effect that describes acquiring at least `min` and at most
   * `max` permits and releasing them when the scope is closed.
   */
  def withPermitsBetweenScoped(min: Long, max: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Long] =
    ZSTM.acquireReleaseWith(acquireBetween(min, max))((actualN: Long) => Scope.addFinalizer(releaseN(actualN).commit))(
      ZIO.succeed(_)
    )

  /**
   * Returns a scoped effect that describes acquiring at most `max` permits and
   * releasing them when the scope is closed.
   */
  def withPermitsUpToScoped(max: Long)(implicit trace: Trace): ZIO[Scope, Nothing, Long] =
    ZSTM.acquireReleaseWith(acquireUpTo(max))((actualN: Long) => Scope.addFinalizer(releaseN(actualN).commit))(
      ZIO.succeed(_)
    )

  private def assertNonNegative(n: Long): Unit =
    require(n >= 0, s"Unexpected negative `$n` permits requested.")
}

object TSemaphore {

  /**
   * Constructs a new `TSemaphore` with the specified number of permits.
   */
  def make(permits: => Long)(implicit trace: Trace): USTM[TSemaphore] =
    ZSTM.succeed(unsafe.make(permits)(Unsafe.unsafe))

  /**
   * Constructs a new `TSemaphore` with the specified number of permits,
   * immediately committing the transaction.
   */
  def makeCommit(permits: => Long)(implicit trace: Trace): UIO[TSemaphore] =
    make(permits).commit

  object unsafe {
    def make(permits: Long)(implicit unsafe: Unsafe): TSemaphore =
      new TSemaphore(TRef.unsafeMake(permits))
  }
}
