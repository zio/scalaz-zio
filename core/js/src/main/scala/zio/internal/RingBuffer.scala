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

import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] object RingBuffer {

  /**
   * @note
   *   minimum supported capacity is 2
   */
  def apply[A](requestedCapacity: Int): RingBuffer[A] = {
    assert(requestedCapacity >= 2)

    if (nextPow2(requestedCapacity) == requestedCapacity) RingBufferPow2(requestedCapacity)
    else RingBufferArb(requestedCapacity)
  }

  def nextPow2(n: Int): Int = {
    val nextPow = (Math.log(n.toDouble) / Math.log(2.0)).ceil.toInt
    Math.pow(2.0, nextPow.toDouble).toInt.max(2)
  }
}

/**
 * See [[zio.internal.RingBuffer]] for details on design, tradeoffs, etc.
 */
private[zio] abstract class RingBuffer[A](override final val capacity: Int) extends MutableConcurrentQueue[A] {
  private[this] val buf: Array[AnyRef] = new Array[AnyRef](capacity)

  private[this] var head: Long = 0L
  private[this] var tail: Long = 0L

  protected def posToIdx(pos: Long, capacity: Int): Int

  override final def size(): Int = (tail - head).toInt

  override final def enqueuedCount(): Long = tail

  override final def dequeuedCount(): Long = head

  override final def offer(a: A): Boolean =
    if (tail < head + capacity) {
      val curIdx = posToIdx(tail, capacity)
      buf(curIdx) = a.asInstanceOf[AnyRef]
      tail += 1
      true
    } else {
      false
    }

  override final def poll(default: A): A =
    if (head < tail) {
      val curIdx     = posToIdx(head, capacity)
      val deqElement = buf(curIdx)
      buf(curIdx) = null
      head += 1
      deqElement.asInstanceOf[A]
    } else {
      default
    }

  override final def isEmpty(): Boolean = tail == head

  override final def isFull(): Boolean = tail == head + capacity
}
