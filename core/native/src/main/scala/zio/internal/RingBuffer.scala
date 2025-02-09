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
import zio.{Chunk, ChunkBuilder}

import java.util.concurrent.atomic.{AtomicLong, AtomicLongArray}

// NOTE: This is a port of the `/jvm` RingBuffer without padding. For info / comments on the method, see that one instead
private[zio] object RingBuffer {

  final def apply[A](requestedCapacity: Int): RingBuffer[A] = {
    assert(requestedCapacity >= 2)

    if (nextPow2(requestedCapacity) == requestedCapacity) RingBufferPow2(requestedCapacity)
    else RingBufferArb(requestedCapacity)
  }

  final def nextPow2(n: Int): Int = {
    val nextPow = (Math.log(n.toDouble) / Math.log(2.0)).ceil.toInt
    Math.pow(2.0, nextPow.toDouble).toInt.max(2)
  }

  private final val STATE_LOOP     = 0
  private final val STATE_EMPTY    = -1
  private final val STATE_FULL     = -2
  private final val STATE_RESERVED = 1
}

private[zio] abstract class RingBuffer[A](override final val capacity: Int)
    extends MutableConcurrentQueue[A]
    with Serializable {
  import RingBuffer.{STATE_EMPTY, STATE_FULL, STATE_LOOP, STATE_RESERVED}

  private val tailUpdater, headUpdater = new AtomicLong(0L)
  private val buf: Array[AnyRef]       = new Array[AnyRef](capacity)
  private val seq: AtomicLongArray     = new AtomicLongArray(capacity)
  0.until(capacity).foreach(i => seq.set(i, i.toLong))

  protected def posToIdx(pos: Long, capacity: Int): Int

  override final def size(): Int = (tailUpdater.get - headUpdater.get).toInt

  override final def enqueuedCount(): Long = tailUpdater.get

  override final def dequeuedCount(): Long = headUpdater.get

  override final def offer(a: A): Boolean = {
    val aCapacity = capacity

    val aSeq   = seq
    var curSeq = 0L

    val aHead   = headUpdater
    var curHead = 0L

    val aTail   = tailUpdater
    var curTail = aTail.get
    var curIdx  = 0

    var state = STATE_LOOP

    while (state == STATE_LOOP) {
      curIdx = posToIdx(curTail, aCapacity)
      curSeq = aSeq.get(curIdx)

      if (curSeq < curTail) {
        curHead = aHead.get
        if (curTail >= curHead + aCapacity) {
          state = STATE_FULL
        } else {
          state = STATE_LOOP
        }
      } else if (curSeq == curTail) {
        if (aTail.compareAndSet(curTail, curTail + 1)) {
          state = STATE_RESERVED
        } else {
          curTail += 1
          state = STATE_LOOP
        }
      } else {
        curTail = aTail.get
        state = STATE_LOOP
      }
    }

    if (state == STATE_RESERVED) {
      buf(curIdx) = a.asInstanceOf[AnyRef]
      aSeq.lazySet(curIdx, curTail + 1)
      true
    } else {
      false
    }
  }

  override final def offerAll[A1 <: A](as: Iterable[A1]): Chunk[A1] =
    offerAll(as.iterator, as.size)

  final def offerAll[A1 <: A](as: Iterator[A1], offers: Long): Chunk[A1] = {
    val aCapacity = capacity

    val aSeq   = seq
    var curSeq = 0L

    val aHead   = headUpdater
    var curHead = 0L

    val aTail   = tailUpdater
    var curTail = 0L
    var curIdx  = 0

    var enqHead = 0L
    var enqTail = 0L

    var state = STATE_LOOP

    while (state == STATE_LOOP) {
      curHead = aHead.get
      curTail = aTail.get
      val size      = curTail - curHead
      val available = aCapacity - size
      val forQueue  = math.min(offers, available)
      if (forQueue == 0) {
        state = STATE_FULL
      } else {
        enqHead = curTail
        enqTail = curTail + forQueue
        var continue = true
        while (continue & enqHead < enqTail) {
          curIdx = posToIdx(enqHead, aCapacity)
          curSeq = aSeq.get(curIdx)
          if (curSeq != enqHead) {
            continue = false
          }
          enqHead += 1
        }
        if (continue && aTail.compareAndSet(curTail, enqTail)) {
          enqHead = curTail
          state = STATE_RESERVED
        } else {
          state = STATE_LOOP
        }
      }
    }

    if (state == STATE_RESERVED) {
      while (enqHead < enqTail) {
        val a = as.next()
        curIdx = posToIdx(enqHead, aCapacity)
        buf(curIdx) = a.asInstanceOf[AnyRef]
        aSeq.lazySet(curIdx, enqHead + 1)
        enqHead += 1
      }
    }
    Chunk.fromIterator(as)
  }

  override final def poll(default: A): A = {
    val aCapacity = capacity

    val aBuf = buf

    val aSeq   = seq
    var curSeq = 0L

    val aHead   = headUpdater
    var curHead = aHead.get
    var curIdx  = 0

    val aTail   = tailUpdater
    var curTail = 0L

    var state = STATE_LOOP

    while (state == STATE_LOOP) {
      curIdx = posToIdx(curHead, aCapacity)
      curSeq = aSeq.get(curIdx)

      if (curSeq <= curHead) {

        curTail = aTail.get
        if (curHead >= curTail) {
          state = STATE_EMPTY
        } else {
          state = STATE_LOOP
        }
      } else if (curSeq == curHead + 1) {
        if (aHead.compareAndSet(curHead, curHead + 1)) {
          state = STATE_RESERVED
        } else {
          curHead += 1
          state = STATE_LOOP
        }
      } else {
        curHead = aHead.get
        state = STATE_LOOP
      }
    }

    if (state == STATE_RESERVED) {
      val deqElement = aBuf(curIdx)
      aBuf(curIdx) = null

      aSeq.lazySet(curIdx, curHead + aCapacity)

      deqElement.asInstanceOf[A]
    } else {
      default
    }
  }

  override final def pollUpTo(n: Int): Chunk[A] = {
    val aCapacity = capacity

    val aSeq   = seq
    var curSeq = 0L

    val aHead   = headUpdater
    var curHead = 0L
    var curIdx  = 0

    val aTail   = tailUpdater
    var curTail = 0L

    val takers  = n.toLong
    var deqHead = 0L
    var deqTail = 0L

    var state = STATE_LOOP

    while (state == STATE_LOOP) {
      curHead = aHead.get
      curTail = aTail.get
      val size   = curTail - curHead
      val toTake = math.min(takers, size)
      if (toTake <= 0) {
        state = STATE_EMPTY
      } else {
        deqHead = curHead
        deqTail = curHead + toTake
        var continue = true
        while (continue && deqHead < deqTail) {
          curIdx = posToIdx(deqHead, aCapacity)
          curSeq = aSeq.get(curIdx)
          if (curSeq != deqHead + 1) {
            continue = false
          }
          deqHead += 1
        }
        if (continue && aHead.compareAndSet(curHead, deqTail)) {
          deqHead = curHead
          state = STATE_RESERVED
        } else {
          state = STATE_LOOP
        }
      }
    }

    if (state == STATE_RESERVED) {
      val builder = ChunkBuilder.make[A]((deqTail - deqHead).toInt)
      while (deqHead < deqTail) {
        curIdx = posToIdx(deqHead, aCapacity)
        val a = buf(curIdx).asInstanceOf[A]
        buf(curIdx) = null
        aSeq.lazySet(curIdx, deqHead + aCapacity)
        builder.addOne(a)
        deqHead += 1
      }
      builder.result()
    } else {
      Chunk.empty
    }
  }

  override final def isEmpty(): Boolean = tailUpdater.get == headUpdater.get

  override final def isFull(): Boolean = tailUpdater.get == headUpdater.get + capacity
}
