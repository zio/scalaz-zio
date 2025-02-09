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

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}

private final class PartitionedLinkedQueue[A <: AnyRef](preferredPartitions: Int) extends Serializable {

  private[this] val mask    = MutableConcurrentQueue.roundToPow2MinusOne(preferredPartitions)
  private[this] val nQueues = mask + 1
  private[this] val queues  = Array.fill(nQueues)(new ConcurrentLinkedQueue[A]())

  def nPartitions(): Int = nQueues

  def size(): Int = {
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

  def offer(a: A, random: ThreadLocalRandom): Unit = {
    val idx = random.nextInt(nQueues)
    queues(idx).offer(a)
  }

  def offer(a: A): Boolean = {
    offer(a, ThreadLocalRandom.current())
    true
  }

  def offerAll[A1 <: A](as: Iterable[A1], random: ThreadLocalRandom): Unit = {
    val from = random.nextInt(nQueues)
    var i    = 0
    val iter = as.iterator
    while (iter.hasNext) {
      val value = iter.next()
      val idx   = (from + i) & mask
      queues(idx).offer(value)
      i += 1
    }
  }

  def offerAll[A1 <: A](as: Iterable[A1]): Chunk[A1] = {
    offerAll(as, ThreadLocalRandom.current())
    Chunk.empty
  }

  def poll(random: ThreadLocalRandom): A = {
    val from   = random.nextInt(nQueues)
    var i      = 0
    var result = null.asInstanceOf[A]
    while ((result eq null) && i < nQueues) {
      val idx = (from + i) & mask
      result = queues(idx).poll()
      i += 1
    }
    result
  }

  def poll(): A =
    poll(ThreadLocalRandom.current())

  def isEmpty(): Boolean = {
    val random = ThreadLocalRandom.current()
    val from   = random.nextInt(nQueues)
    var i      = 0
    var result = true
    while (result && i < nQueues) {
      val idx = (from + i) & mask
      result = queues(idx).isEmpty
      i += 1
    }
    result
  }
}
