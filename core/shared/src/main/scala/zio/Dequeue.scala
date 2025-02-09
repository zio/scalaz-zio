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

/**
 * A queue that can only be dequeued.
 */
trait Dequeue[+A] extends Serializable {

  /**
   * Waits until the queue is shutdown. The `IO` returned by this method will
   * not resume until the queue has been shutdown. If the queue is already
   * shutdown, the `IO` will resume right away.
   */
  def awaitShutdown(implicit trace: Trace): UIO[Unit]

  /**
   * How many elements can hold in the queue
   */
  def capacity: Int

  /**
   * `true` if `shutdown` has been called.
   */
  def isShutdown(implicit trace: Trace): UIO[Boolean]

  /**
   * Interrupts any fibers that are suspended on `offer` or `take`. Future calls
   * to `offer*` and `take*` will be interrupted immediately.
   */
  def shutdown(implicit trace: Trace): UIO[Unit]

  /**
   * Retrieves the size of the queue. This may be negative if fibers are
   * suspended waiting for elements to be added to the queue or greater than the
   * capacity if fibers are suspended waiting to add elements to the queue.
   */
  def size(implicit trace: Trace): UIO[Int]

  /**
   * Removes the oldest value in the queue. If the queue is empty, this will
   * return a computation that resumes when an item has been added to the queue.
   */
  def take(implicit trace: Trace): UIO[A]

  /**
   * Removes all the values in the queue and returns the values. If the queue is
   * empty returns an empty collection.
   */
  def takeAll(implicit trace: Trace): UIO[Chunk[A]]

  /**
   * Takes up to max number of values in the queue.
   */
  def takeUpTo(max: Int)(implicit trace: Trace): UIO[Chunk[A]]

  /**
   * Checks whether the queue is currently empty.
   */
  def isEmpty(implicit trace: Trace): UIO[Boolean] =
    size.map(_ == 0)

  /**
   * Checks whether the queue is currently full.
   */
  def isFull(implicit trace: Trace): UIO[Boolean] =
    size.map(_ == capacity)

  /**
   * Takes a number of elements from the queue between the specified minimum and
   * maximum. If there are fewer than the minimum number of elements available,
   * suspends until at least the minimum number of elements have been collected.
   */
  final def takeBetween(min: Int, max: Int)(implicit trace: Trace): UIO[Chunk[A]] =
    ZIO.suspendSucceed {

      def takeRemainder(min: Int, max: Int, acc: Chunk[A]): UIO[Chunk[A]] =
        if (max < min) ZIO.succeed(acc)
        else
          takeUpTo(max).flatMap { bs =>
            val remaining = min - bs.length
            if (remaining == 1)
              take.map(b => acc ++ bs :+ b)
            else if (remaining > 1) {
              take.flatMap { b =>
                takeRemainder(remaining - 1, max - bs.length - 1, acc ++ bs :+ b)

              }
            } else
              ZIO.succeed(acc ++ bs)
          }

      takeRemainder(min, max, Chunk.empty)
    }

  /**
   * Takes the specified number of elements from the queue. If there are fewer
   * than the specified number of elements available, it suspends until they
   * become available.
   */
  final def takeN(n: Int)(implicit trace: Trace): UIO[Chunk[A]] =
    takeBetween(n, n)

  /**
   * Take the head option of values in the queue.
   */
  def poll(implicit trace: Trace): UIO[Option[A]] =
    takeUpTo(1).map(_.headOption)
}
