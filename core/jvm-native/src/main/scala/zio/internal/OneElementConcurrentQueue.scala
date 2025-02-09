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

import java.util.concurrent.atomic.{AtomicReference, LongAdder}

// format: off
/**
 * This is a specialized implementation of MutableConcurrentQueue of capacity 1.
 * Since capacity 1 queues are by default used under the hood in Streams as
 * intermediate resource they should be very cheap to create and throw away.
 * Hence this queue is optimized (unlike RingBuffer*) for a very small
 * footprint, while still being plenty fast.
 *
 * Allocating an object takes only 24 bytes + 8+ bytes in long adder (so 32+
 * bytes total), which is 15x less than the smallest RingBuffer.
 *
 * zio.internal.OneElementConcurrentQueue object internals:
 *  OFFSET  SIZE                                          TYPE DESCRIPTION
 *       0     4                                               (object header)
 *       4     4                                               (object header)
 *       8     4                                               (object header)
 *      12     4                                           int OneElementConcurrentQueue.capacity
 *      16     4   java.util.concurrent.atomic.AtomicReference OneElementConcurrentQueue.ref
 *      20     4         java.util.concurrent.atomic.LongAdder OneElementConcurrentQueue.deqAdder
 * Instance size: 24 bytes
 * Space losses: 0 bytes internal + 0 bytes external = 0 bytes total
 */
// format: on
private[zio] final class OneElementConcurrentQueue[A] extends MutableConcurrentQueue[A] with Serializable {
  private[this] val ref      = new AtomicReference[AnyRef]()
  private[this] val deqAdder = new LongAdder()

  override final val capacity = 1

  override def dequeuedCount(): Long = deqAdder.sum()
  override def enqueuedCount(): Long =
    if (isEmpty()) dequeuedCount() else dequeuedCount() + 1

  override def isEmpty(): Boolean = ref.get() eq null
  override def isFull(): Boolean  = !isEmpty()

  override def offer(a: A): Boolean = {
    assert(a != null)

    val aRef    = ref
    var ret     = false
    var looping = true

    while (looping) {
      if (aRef.get() ne null) looping = false
      else {
        if (aRef.compareAndSet(null, a.asInstanceOf[AnyRef])) {
          ret = true
          looping = false
        }
      }
    }

    ret
  }

  override def poll(default: A): A = {
    var ret     = default
    var looping = true
    val aRef    = ref
    var el      = null.asInstanceOf[AnyRef]

    while (looping) {
      el = aRef.get()
      if (el eq null) looping = false
      else {
        if (aRef.compareAndSet(el, null)) {
          ret = el.asInstanceOf[A]
          deqAdder.increment()
          looping = false
        }
      }
    }

    ret
  }

  override def size(): Int = if (isEmpty()) 0 else 1
}
