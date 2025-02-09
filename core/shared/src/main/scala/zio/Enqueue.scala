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
 * A queue that can only be enqueued.
 */
trait Enqueue[-A] extends Serializable {

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
   * Places one value in the queue.
   */
  def offer(a: A)(implicit trace: Trace): UIO[Boolean]

  /**
   * For Bounded Queue: uses the `BackPressure` Strategy, places the values in
   * the queue and always returns no leftovers. If the queue has reached
   * capacity, then the fiber performing the `offerAll` will be suspended until
   * there is room in the queue.
   *
   * For Unbounded Queue: Places all values in the queue and returns no
   * leftovers.
   *
   * For Sliding Queue: uses `Sliding` Strategy If there is room in the queue,
   * it places the values otherwise it removes the old elements and enqueues the
   * new ones. Always returns no leftovers.
   *
   * For Dropping Queue: uses `Dropping` Strategy, It places the values in the
   * queue but if there is no room it will not enqueue them and return the
   * leftovers.
   */
  def offerAll[A1 <: A](as: Iterable[A1])(implicit trace: Trace): UIO[Chunk[A1]]

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
   * Checks whether the queue is currently empty.
   */
  def isEmpty(implicit trace: Trace): UIO[Boolean] =
    size.map(_ <= 0)

  /**
   * Checks whether the queue is currently full.
   */
  def isFull(implicit trace: Trace): UIO[Boolean] =
    size.map(_ >= capacity)
}
