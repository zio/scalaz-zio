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

import java.util.concurrent.ThreadLocalRandom

private[zio] final class PartitionedRingBuffer[A <: AnyRef](
  preferredPartitions: Int,
  preferredCapacity: Int,
  roundToPow2: Boolean
) extends MutableConcurrentQueue[A]
    with Serializable {

  private[this] val mask: Int    = MutableConcurrentQueue.roundToPow2MinusOne(preferredPartitions)
  private[this] val nQueues: Int = mask + 1

  private[this] val partitionSize: Int = {
    val cap = Math.ceil(preferredCapacity.toDouble / nQueues).toInt.max(2)
    if (roundToPow2) RingBuffer.nextPow2(cap) else cap
  }

  private[this] val queues = Array.fill(nQueues)(RingBuffer[A](partitionSize))

  override final val capacity = nQueues * partitionSize

  def nPartitions(): Int = nQueues

  override def size(): Int = {
    val from = ThreadLocalRandom.current().nextInt(nQueues)
    var i    = 0
    var size = 0
    while (i < nQueues) {
      val idx = (from + i) & mask
      size += queues(idx).size()
      i += 1
    }
    size
  }

  override def enqueuedCount(): Long = {
    val from = ThreadLocalRandom.current().nextInt(nQueues)
    var i    = 0
    var size = 0L
    while (i < nQueues) {
      val idx = (from + i) & mask
      size += queues(idx).enqueuedCount()
      i += 1
    }
    size
  }

  override def dequeuedCount(): Long = {
    val from = ThreadLocalRandom.current().nextInt(nQueues)
    var i    = 0
    var size = 0L
    while (i < nQueues) {
      val idx = (from + i) & mask
      size += queues(idx).dequeuedCount()
      i += 1
    }
    size
  }

  def offer(a: A, random: ThreadLocalRandom): Boolean = {
    val from   = random.nextInt(nQueues)
    var i      = 0
    var result = false
    while (i < nQueues && !result) {
      val idx = (from + i) & mask
      result = queues(idx).offer(a)
      i += 1
    }
    result
  }

  def randomPartition(random: ThreadLocalRandom): RingBuffer[A] =
    queues(random.nextInt(nQueues))

  override def offer(a: A): Boolean =
    offer(a, ThreadLocalRandom.current())

  def poll(default: A, random: ThreadLocalRandom): A = {
    val from   = random.nextInt(nQueues)
    var i      = 0
    var result = null.asInstanceOf[A]
    while ((result eq null) && i < nQueues) {
      val idx   = (from + i) & mask
      val queue = queues(idx)
      result = queue.poll(result)
      i += 1
    }
    if (result eq null) default else result
  }

  override def poll(default: A): A =
    poll(default, ThreadLocalRandom.current())

  override def isEmpty(): Boolean = {
    val from   = ThreadLocalRandom.current().nextInt(nQueues)
    var i      = 0
    var result = true
    while (result && i < nQueues) {
      val idx = (from + i) & mask
      result = queues(idx).isEmpty()
      i += 1
    }
    result
  }

  override def isFull(): Boolean = {
    val from   = ThreadLocalRandom.current().nextInt(nQueues)
    var i      = 0
    var result = true
    while (result && i < nQueues) {
      val idx = (from + i) & mask
      result = queues(idx).isFull()
      i += 1
    }
    result
  }

  def partitionIterator: Iterator[RingBuffer[A]] = new Iterator[RingBuffer[A]] {
    private[this] val from    = ThreadLocalRandom.current().nextInt(nQueues)
    private[this] var i       = 0
    private[this] val _size   = nQueues
    private[this] val _mask   = mask
    private[this] val _queues = queues

    def hasNext: Boolean = i < _size

    def next(): RingBuffer[A] =
      if (!hasNext) throw new NoSuchElementException("next on empty iterator")
      else {
        val idx = (from + i) & _mask
        val q   = _queues(idx)
        i += 1
        q
      }

  }

}
