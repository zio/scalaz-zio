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

package zio.internal

import zio.{Chunk, ChunkBuilder}
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] object MutableConcurrentQueue {

  /**
   * @note
   *   in case you need extreme performance, make sure to use capacity which is
   *   a power of 2 throughout your system. This will allow to use a more
   *   performant ring buffer implementation.
   */
  def bounded[A](capacity: Int): MutableConcurrentQueue[A] =
    if (capacity == 1) new OneElementConcurrentQueue()
    else RingBuffer[A](capacity)

  def unbounded[A]: MutableConcurrentQueue[A] =
    new LinkedQueue[A]

  /**
   * Rounds up to the nearest power of 2 and subtracts 1. e.g.,
   *
   * {{{
   * roundToPow2MinusOne(3) // 3
   * roundToPow2MinusOne(4) // 3
   * roundToPow2MinusOne(5) // 7
   * }}}
   */
  def roundToPow2MinusOne(n: Int): Int = {
    var value = n - 1
    value |= value >> 1
    value |= value >> 2
    value |= value >> 4
    value |= value >> 8
    value | value >> 16
  }
}

/**
 * A MutableConcurrentQueue interface to use under the hood in ZIO.
 *
 * The implementation at minimum:
 *   1. Should be non-blocking and ideally lock-free.
 *   1. Should provide basic metrics such as how many elements were
 *      enqueued/dequeued.
 *
 * @note
 *   this is declared as `abstract class` since `invokevirtual` is slightly
 *   cheaper than `invokeinterface`.
 */
private[zio] abstract class MutableConcurrentQueue[A] {

  /**
   * The '''maximum''' number of elements that a queue can hold.
   *
   * @note
   *   that unbounded queues can still implement this interface with `capacity =
   *   MAX_INT`.
   */
  val capacity: Int

  /**
   * A non-blocking enqueue.
   *
   * @return
   *   whether the enqueue was successful or not.
   */
  def offer(a: A): Boolean

  /**
   * A non-blocking enqueue of multiple elements.
   */
  def offerAll[A1 <: A](as: Iterable[A1]): Chunk[A1] = {
    val builder  = ChunkBuilder.make[A1]()
    val iterator = as.iterator
    var loop     = true
    while (loop && iterator.hasNext) {
      val a = iterator.next()
      if (!offer(a)) {
        builder += a
        loop = false
      }
    }
    builder ++= iterator
    builder.result()
  }

  /**
   * A non-blocking dequeue.
   *
   * @return
   *   either an element from the queue, or the `default` param.
   *
   * @note
   *   that if there's no meaningful default for your type, you can always use
   *   `poll(null)`. Not the best, but reasonable price to pay for lower heap
   *   churn from not using `Option` here.
   */
  def poll(default: A): A

  /**
   * A non-blocking dequeue of multiple elements.
   */
  def pollUpTo(n: Int): Chunk[A] = {
    val builder = ChunkBuilder.make[A]()
    val default = null.asInstanceOf[A]
    var i       = n
    while (i > 0) {
      val a = poll(default)
      if (a == default) i = 0
      else builder.addOne(a)
      i -= 1
    }
    builder.result()
  }

  /**
   * @return
   *   the '''current''' number of elements inside the queue.
   *
   * @note
   *   that this method can be non-atomic and return the approximate number in a
   *   concurrent setting.
   */
  def size(): Int

  /**
   * @return
   *   the number of elements that have ever been added to the queue.
   *
   * @note
   *   that `Long` is used here, since `Int` will be overflowed really quickly
   *   for busy queues.
   *
   * @note
   *   if you know how much time the queue is alive, you can calculate the rate
   *   at which elements are being enqueued.
   */
  def enqueuedCount(): Long

  /**
   * @return
   *   the number of elements that have ever been taken from the queue.
   *
   * @note
   *   if you know how much time the queue is alive, you can calculate the rate
   *   at which elements are being dequeued.
   */
  def dequeuedCount(): Long

  def isEmpty(): Boolean

  def isFull(): Boolean
}
