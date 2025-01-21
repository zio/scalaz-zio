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

import zio._

/**
 * A transactional queue that can only be dequeued.
 */
trait TDequeue[+A] extends Serializable {

  /**
   * The maximum capacity of the queue.
   */
  def capacity: Int

  /**
   * Checks whether the queue is shut down.
   */
  def isShutdown: USTM[Boolean]

  /**
   * Views the next element in the queue without removing it, retrying if the
   * queue is empty.
   */
  def peek: ZSTM[Any, Nothing, A]

  /**
   * Views the next element in the queue without removing it, returning `None`
   * if the queue is empty.
   */
  def peekOption: ZSTM[Any, Nothing, Option[A]]

  /**
   * Shuts down the queue.
   */
  def shutdown: USTM[Unit]

  /**
   * The current number of values in the queue.
   */
  def size: USTM[Int]

  /**
   * Takes a value from the queue.
   */
  def take: ZSTM[Any, Nothing, A]

  /**
   * Takes all the values from the queue.
   */
  def takeAll: ZSTM[Any, Nothing, Chunk[A]]

  /**
   * Takes up to the specified number of values from the queue.
   */
  def takeUpTo(max: Int): ZSTM[Any, Nothing, Chunk[A]]

  /**
   * Waits for the hub to be shut down.
   */
  def awaitShutdown: USTM[Unit] =
    isShutdown.flatMap(b => if (b) ZSTM.unit else ZSTM.retry)

  /**
   * Checks if the queue is empty.
   */
  def isEmpty: USTM[Boolean] =
    size.map(_ == 0)

  /**
   * Checks if the queue is at capacity.
   */
  def isFull: USTM[Boolean] =
    size.map(_ == capacity)

  /**
   * Takes a single element from the queue, returning `None` if the queue is
   * empty.
   */
  final def poll: ZSTM[Any, Nothing, Option[A]] =
    takeUpTo(1).map(_.headOption)

  /**
   * Drops elements from the queue while they do not satisfy the predicate,
   * taking and returning the first element that does satisfy the predicate.
   * Retries if no elements satisfy the predicate.
   */
  final def seek(f: A => Boolean): ZSTM[Any, Nothing, A] =
    take.flatMap(b => if (f(b)) ZSTM.succeedNow(b) else seek(f))

  /**
   * Takes a number of elements from the queue between the specified minimum and
   * maximum. If there are fewer than the minimum number of elements available,
   * retries until at least the minimum number of elements have been collected.
   */
  final def takeBetween(min: Int, max: Int): ZSTM[Any, Nothing, Chunk[A]] =
    ZSTM.suspend {

      def takeRemainder(min: Int, max: Int, acc: Chunk[A]): ZSTM[Any, Nothing, Chunk[A]] =
        if (max < min) ZSTM.succeedNow(acc)
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
              ZSTM.succeedNow(acc ++ bs)
          }

      takeRemainder(min, max, Chunk.empty)
    }

  /**
   * Takes the specified number of elements from the queue. If there are fewer
   * than the specified number of elements available, it retries until they
   * become available.
   */
  final def takeN(n: Int): ZSTM[Any, Nothing, Chunk[A]] =
    takeBetween(n, n)
}
