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

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stm.TReentrantLock._
import zio.{FiberId, Scope, Trace, Unsafe, ZIO}

/**
 * A `TReentrantLock` is a reentrant read/write lock. Multiple readers may all
 * concurrently acquire read locks. Only one writer is allowed to acquire a
 * write lock at any given time. Read locks may be upgraded into write locks. A
 * fiber that has a write lock may acquire other write locks or read locks.
 *
 * The two primary methods of this structure are `readLock`, which acquires a
 * read lock in a scoped context, and `writeLock`, which acquires a write lock
 * in a scoped context.
 *
 * Although located in the STM package, there is no need for locks within STM
 * transactions. However, this lock can be quite useful in effectful code, to
 * provide consistent read/write access to mutable state; and being in STM
 * allows this structure to be composed into more complicated concurrent
 * structures that are consumed from effectful code.
 */
final class TReentrantLock private (data: TRef[LockState]) {

  /**
   * Acquires a read lock. The transaction will suspend until no other fiber is
   * holding a write lock. Succeeds with the number of read locks held by this
   * fiber.
   */
  lazy val acquireRead: USTM[Int] = adjustRead(1)

  /**
   * Acquires a write lock. The transaction will suspend until no other fibers
   * are holding read or write locks. Succeeds with the number of write locks
   * held by this fiber.
   */
  lazy val acquireWrite: USTM[Int] =
    ZSTM.Effect((journal, fiberId, _) =>
      data.unsafeGet(journal) match {

        case readLock: ReadLock if readLock.noOtherHolder(fiberId) =>
          data.unsafeSet(journal, WriteLock(1, readLock.readLocks(fiberId), fiberId))
          1

        case WriteLock(n, m, `fiberId`) =>
          data.unsafeSet(journal, WriteLock(n + 1, m, fiberId))
          n + 1

        case _ => throw ZSTM.RetryException
      }
    )

  /**
   * Just a convenience method for applications that only need reentrant locks,
   * without needing a distinction between readers / writers.
   *
   * See [[writeLock]].
   */
  def lock(implicit trace: Trace): ZIO[Scope, Nothing, Int] = writeLock

  /**
   * Determines if any fiber has a read or write lock.
   */
  def locked: USTM[Boolean] =
    (readLocked zipWith writeLocked)(_ || _)

  /**
   * Obtains a read lock in a scoped context.
   */
  def readLock(implicit trace: Trace): ZIO[Scope, Nothing, Int] =
    ZIO.acquireRelease(acquireRead.commit)(_ => releaseRead.commit)

  /**
   * Retrieves the total number of acquired read locks.
   */
  def readLocks: USTM[Int] = data.get.map(_.readLocks)

  /**
   * Retrieves the number of acquired read locks for this fiber.
   */
  def fiberReadLocks: USTM[Int] =
    ZSTM.Effect((journal, fiberId, _) => data.unsafeGet(journal).readLocks(fiberId))

  /**
   * Retrieves the number of acquired write locks for this fiber.
   */
  def fiberWriteLocks: USTM[Int] =
    ZSTM.Effect((journal, fiberId, _) => data.unsafeGet(journal).writeLocks(fiberId))

  /**
   * Determines if any fiber has a read lock.
   */
  def readLocked: USTM[Boolean] = data.get.map(_.readLocks > 0)

  /**
   * Releases a read lock held by this fiber. Succeeds with the outstanding
   * number of read locks held by this fiber.
   */
  lazy val releaseRead: USTM[Int] = adjustRead(-1)

  /**
   * Releases a write lock held by this fiber. Succeeds with the outstanding
   * number of write locks held by this fiber.
   */
  lazy val releaseWrite: USTM[Int] =
    ZSTM.Effect { (journal, fiberId, _) =>
      val res = data.unsafeGet(journal) match {
        case WriteLock(1, m, `fiberId`) => ReadLock(fiberId, m)
        case WriteLock(n, m, `fiberId`) if n > 1 =>
          WriteLock(n - 1, m, fiberId)
        case s => die(s"Defect: Fiber $fiberId releasing write lock it does not hold: $s")
      }
      data.unsafeSet(journal, res)
      res.writeLocks(fiberId)
    }

  /**
   * Runs the specified workflow with a lock.
   */
  def withLock[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    withWriteLock(zio)

  /**
   * Runs the specified workflow with a read lock.
   */
  def withReadLock[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.uninterruptibleMask(restore => restore(acquireRead.commit) *> restore(zio).ensuring(releaseRead.commit))

  /**
   * Runs the specified workflow with a write lock.
   */
  def withWriteLock[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    ZIO.uninterruptibleMask(restore => restore(acquireWrite.commit) *> restore(zio).ensuring(releaseWrite.commit))

  /**
   * Obtains a write lock in a scoped context.
   */
  def writeLock(implicit trace: Trace): ZIO[Scope, Nothing, Int] =
    ZIO.acquireRelease(acquireWrite.commit)(_ => releaseWrite.commit)

  /**
   * Determines if a write lock is held by some fiber.
   */
  def writeLocked: USTM[Boolean] = data.get.map(_.writeLocks > 0)

  /**
   * Computes the number of write locks held by fibers.
   */
  def writeLocks: USTM[Int] = data.get.map(_.writeLocks)

  private def adjustRead(delta: Int): USTM[Int] =
    ZSTM.Effect((journal, fiberId, _) =>
      data.unsafeGet(journal) match {
        case readLock: ReadLock =>
          val res = readLock.adjust(fiberId, delta)
          data.unsafeSet(journal, res)
          res.readLocks(fiberId)

        case WriteLock(w, r, `fiberId`) =>
          val newTotal = r + delta
          if (newTotal < 0)
            die(s"Defect: Fiber $fiberId releasing read locks it does not hold, newTotal: $newTotal")
          else
            data.unsafeSet(journal, WriteLock(w, newTotal, fiberId))

          newTotal

        case _ => throw ZSTM.RetryException // another fiber is holding a write lock
      }
    )
}
object TReentrantLock {

  private[stm] sealed abstract class LockState {
    def readLocks: Int
    def readLocks(fiberId: FiberId): Int
    val writeLocks: Int
    def writeLocks(fiberId: FiberId): Int
  }

  /**
   * This data structure describes the state of the lock when a single fiber has
   * a write lock. The fiber has an identity, and may also have acquired a
   * certain number of read locks.
   */
  private[stm] final case class WriteLock(writeLocks: Int, readLocks: Int, fiberId: FiberId) extends LockState {
    override def readLocks(fiberId0: FiberId): Int = if (fiberId0 == fiberId) readLocks else 0

    override def writeLocks(fiberId0: FiberId): Int = if (fiberId0 == fiberId) writeLocks else 0
  }

  /**
   * This data structure describes the state of the lock when multiple fibers
   * have acquired read locks. The state is tracked as a map from fiber identity
   * to number of read locks acquired by the fiber. This level of detail permits
   * upgrading a read lock to a write lock.
   */
  private[stm] final class ReadLock(readers: Map[FiberId, Int]) extends LockState {

    /**
     * Computes the total number of read locks acquired.
     */
    lazy val readLocks: Int = readers.values.sum

    /**
     * Determines if there is no other holder of read locks aside from the
     * specified fiber id. If there are no other holders of read locks aside
     * from the specified fiber id, then it is safe to upgrade the read lock
     * into a write lock.
     */
    def noOtherHolder(fiberId: FiberId): Boolean =
      readers.isEmpty || (readers.size == 1 && readers.contains(fiberId))

    /**
     * Computes the number of read locks held by the specified fiber id.
     */
    def readLocks(fiberId: FiberId): Int = readers.getOrElse(fiberId, 0)

    /**
     * Adjusts the number of read locks held by the specified fiber id.
     */
    def adjust(fiberId: FiberId, adjust: Int): ReadLock = {
      val total = readLocks(fiberId)

      val newTotal = total + adjust

      new ReadLock(
        if (newTotal < 0) die(s"Defect: Fiber $fiberId releasing read lock it does not hold: $readers")
        else if (newTotal == 0) readers - fiberId
        else readers.updated(fiberId, newTotal)
      )
    }

    override val writeLocks: Int = 0

    override def writeLocks(fiberId: FiberId): Int = 0
  }
  private[stm] object ReadLock {

    /**
     * An empty read lock state, in which no fiber holds any read locks.
     */
    val empty: ReadLock = new ReadLock(Map())

    /**
     * Creates a new read lock where the specified fiber holds the specified
     * number of read locks.
     */
    def apply(fiberId: FiberId, count: Int): ReadLock =
      if (count <= 0) empty else new ReadLock(Map(fiberId -> count))
  }

  /**
   * Makes a new reentrant read/write lock.
   */
  def make: USTM[TReentrantLock] =
    ZSTM.succeed(unsafe.make()(Unsafe.unsafe))

  object unsafe {
    def make()(implicit unsafe: Unsafe): TReentrantLock =
      new TReentrantLock(TRef.unsafeMake[LockState](ReadLock.empty))
  }

  private def die(message: String): Nothing =
    throw new RuntimeException(message)
}
