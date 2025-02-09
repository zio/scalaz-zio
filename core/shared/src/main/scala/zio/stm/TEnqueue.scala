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
 * A transactional queue that can only be enqueued.
 */
trait TEnqueue[-A] extends Serializable {

  /**
   * The maximum capacity of the queue.
   */
  def capacity: Int

  /**
   * Checks whether the queue is shut down.
   */
  def isShutdown: USTM[Boolean]

  /**
   * Offers a value to the queue, returning whether the value was offered to the
   * queue.
   */
  def offer(a: A): ZSTM[Any, Nothing, Boolean]

  /**
   * Offers all of the specified values to the queue, returning whether they
   * were offered to the queue.
   */
  def offerAll(as: Iterable[A]): ZSTM[Any, Nothing, Boolean]

  /**
   * Shuts down the queue.
   */
  def shutdown: USTM[Unit]

  /**
   * The current number of values in the queue.
   */
  def size: USTM[Int]

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
}
