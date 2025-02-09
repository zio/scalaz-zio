/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

import java.util.concurrent.atomic._

/**
 * A bounded hub of arbitrary capacity backed by an array.
 */
private final class BoundedHubArb[A](requestedCapacity: Int) extends Hub[A] {
  private[this] val array            = Array.ofDim[AnyRef](requestedCapacity)
  private[this] val seq              = new AtomicLongArray(requestedCapacity)
  private[this] val sliding          = new AtomicLongArray(requestedCapacity)
  private[this] val state            = new AtomicReference(BoundedHubArb.State(0L, 0))
  private[this] val subscribers      = new AtomicIntegerArray(requestedCapacity)
  private[this] val subscribersIndex = new AtomicLong(0L)
  (0 until requestedCapacity).foreach(n => seq.set(n, n.toLong))
  (0 until requestedCapacity).foreach(n => sliding.set(n, n.toLong))

  val capacity: Int =
    requestedCapacity

  def isEmpty(): Boolean = {
    val currentState            = state.get
    val currentPublisherIndex   = currentState.publisherIndex
    val currentSubscribersIndex = subscribersIndex.get
    currentPublisherIndex == currentSubscribersIndex
  }

  def isFull(): Boolean = {
    val currentState            = state.get
    val currentPublisherIndex   = currentState.publisherIndex
    val currentSubscribersIndex = subscribersIndex.get
    currentPublisherIndex == currentSubscribersIndex + capacity
  }

  def publish(a: A): Boolean = {
    var currentState = state.get
    var loop         = true
    var published    = true
    while (loop) {
      val currentSubscriberCount = currentState.subscriberCount
      if (currentSubscriberCount == 0) {
        loop = false
      } else {
        val currentPublisherIndex = currentState.publisherIndex
        val currentIndex          = (currentPublisherIndex % capacity).toInt
        val currentSeq            = seq.get(currentIndex)
        if (currentPublisherIndex == currentSeq) {
          if (state.compareAndSet(currentState, currentState.copy(publisherIndex = currentPublisherIndex + 1))) {
            array(currentIndex) = a.asInstanceOf[AnyRef]
            subscribers.getAndAdd(currentIndex, currentSubscriberCount)
            sliding.set(currentIndex, currentPublisherIndex + 1)
            val currentSubscribers = subscribers.get(currentIndex)
            if (currentSubscribers == 0) {
              if (sliding.compareAndSet(currentIndex, currentPublisherIndex + 1, currentPublisherIndex + capacity)) {
                array(currentIndex) = null
                subscribersIndex.getAndIncrement()
                seq.lazySet(currentIndex, currentPublisherIndex + capacity)
              }
            } else {
              seq.compareAndSet(currentIndex, currentPublisherIndex, currentPublisherIndex + 1)
            }
            loop = false
          } else {
            currentState = state.get
          }
        } else {
          val currentSubscribersIndex = subscribersIndex.get
          if (currentPublisherIndex == currentSubscribersIndex + capacity) {
            loop = false
            published = false
          } else {
            currentState = state.get
          }
        }
      }
    }
    published
  }

  def publishAll[A1 <: A](as: Iterable[A1]): Chunk[A1] = {
    var currentState = state.get
    val iterator     = as.iterator
    var loop         = true
    var remaining    = as.size
    while (loop) {
      var currentPublisherIndex   = currentState.publisherIndex
      val currentSubscribersIndex = subscribersIndex.get
      val size                    = (currentPublisherIndex - currentSubscribersIndex).toInt
      val available               = capacity - size
      val forHub                  = math.min(remaining, available)
      if (forHub == 0) {
        loop = false
      } else {
        var continue        = true
        val publishAllIndex = currentPublisherIndex + forHub
        while (continue && currentPublisherIndex != publishAllIndex) {
          val currentIndex = (currentPublisherIndex % capacity).toInt
          val currentSeq   = seq.get(currentIndex)
          if (currentPublisherIndex != currentSeq) {
            continue = false
          }
          currentPublisherIndex += 1
        }
        if (continue) {
          if (state.compareAndSet(currentState, currentState.copy(publisherIndex = currentPublisherIndex))) {
            currentPublisherIndex -= forHub
            while (currentPublisherIndex != publishAllIndex) {
              val a            = iterator.next()
              val currentIndex = (currentPublisherIndex % capacity).toInt
              array(currentIndex) = a.asInstanceOf[AnyRef]
              val currentSubscriberCount = currentState.subscriberCount
              subscribers.getAndAdd(currentIndex, currentSubscriberCount)
              sliding.set(currentIndex, currentPublisherIndex + 1)
              val currentSubscribers = subscribers.get(currentIndex)
              if (currentSubscribers == 0) {
                if (sliding.compareAndSet(currentIndex, currentPublisherIndex + 1, currentPublisherIndex + capacity)) {
                  array(currentIndex) = null
                  subscribersIndex.getAndIncrement()
                  seq.lazySet(currentIndex, currentPublisherIndex + capacity)
                }
              } else {
                seq.compareAndSet(currentIndex, currentPublisherIndex, currentPublisherIndex + 1)
              }
              currentPublisherIndex += 1
            }
            remaining -= forHub
          } else {
            currentState = state.get
          }
        } else {
          currentState = state.get
        }
      }
    }
    Chunk.fromIterator(iterator)
  }

  def size(): Int = {
    val currentState            = state.get
    val currentPublisherIndex   = currentState.publisherIndex
    val currentSubscribersIndex = subscribersIndex.get
    (currentPublisherIndex - currentSubscribersIndex).toInt
  }

  def slide(): Unit = {
    var currentSubscribersIndex = subscribersIndex.get
    var loop                    = true
    while (loop) {
      val currentIndex = (currentSubscribersIndex % capacity).toInt
      if (sliding.compareAndSet(currentIndex, currentSubscribersIndex + 1, currentSubscribersIndex + capacity)) {
        array(currentIndex) = null
        subscribersIndex.getAndIncrement()
        seq.lazySet(currentIndex, currentSubscribersIndex + capacity)
        loop = false
      } else {
        val currentSliding = sliding.get(currentIndex)
        if (currentSliding == currentSubscribersIndex + capacity) {
          currentSubscribersIndex += 1
        } else {
          val currentState          = state.get
          val currentPublisherIndex = currentState.publisherIndex
          if (currentSubscribersIndex == currentPublisherIndex) {
            loop = false
          } else {
            currentSubscribersIndex = subscribersIndex.get
          }
        }
      }
    }
  }

  def subscribe(): Hub.Subscription[A] =
    new Hub.Subscription[A] {
      private[this] val currentState =
        state.getAndUpdate(currentState => currentState.copy(subscriberCount = currentState.subscriberCount + 1))
      private[this] val currentPublisherIndex = currentState.publisherIndex
      private[this] val subscriberIndex       = new AtomicLong(currentPublisherIndex)
      private[this] val unsubscribed          = new AtomicBoolean(false)

      def isEmpty(): Boolean =
        if (unsubscribed.get) true
        else {
          val currentState           = state.get
          val currentPublisherIndex  = currentState.publisherIndex
          val currentSubscriberIndex = subscriberIndex.get
          if (currentPublisherIndex == currentSubscriberIndex) true
          else {
            val currentSubscribersIndex = subscribersIndex.get
            currentPublisherIndex == currentSubscribersIndex
          }
        }

      def poll(default: A): A = {
        var currentSubscriberIndex = subscriberIndex.get
        var loop                   = true
        var polled                 = default
        while (loop && !unsubscribed.get) {
          val currentIndex = (currentSubscriberIndex % capacity).toInt
          val currentSeq   = seq.get(currentIndex)
          if (currentSubscriberIndex + 1 == currentSeq) {
            if (subscriberIndex.compareAndSet(currentSubscriberIndex, currentSubscriberIndex + 1)) {
              polled = array(currentIndex).asInstanceOf[A]
              val currentSliding     = sliding.get(currentIndex)
              val currentSubscribers = subscribers.decrementAndGet(currentIndex)
              if (currentSubscribers == 0) {
                if (
                  sliding.compareAndSet(currentIndex, currentSubscriberIndex + 1, currentSubscriberIndex + capacity)
                ) {
                  array(currentIndex) = null
                  subscribersIndex.getAndIncrement()
                  seq.lazySet(currentIndex, currentSubscriberIndex + capacity)
                }
              }
              if (currentSubscriberIndex + 1 == currentSliding) {
                loop = false
              } else {
                polled = default
                currentSubscriberIndex = subscriberIndex.get
              }
            } else {
              currentSubscriberIndex += 1
            }
          } else {
            val currentState          = state.get
            val currentPublisherIndex = currentState.publisherIndex
            if (currentSubscriberIndex == currentPublisherIndex) {
              loop = false
            } else {
              val currentSubscribersIndex = subscribersIndex.get
              if (currentSubscriberIndex - currentSubscribersIndex < 0) {
                if (subscriberIndex.compareAndSet(currentSubscriberIndex, currentSubscriberIndex + 1)) {
                  subscribers.getAndDecrement(currentIndex)
                }
              }
              currentSubscriberIndex = subscriberIndex.get
            }
          }
        }
        polled
      }

      def pollUpTo(n: Int): Chunk[A] = {
        val builder                = ChunkBuilder.make[A]()
        var currentSubscriberIndex = subscriberIndex.get
        var loop                   = true
        var remaining              = n
        while (loop && !unsubscribed.get) {
          val currentState          = state.get
          val currentPublisherIndex = currentState.publisherIndex
          val size                  = (currentPublisherIndex - currentSubscriberIndex).toInt
          val toPoll                = math.min(remaining, size)
          if (toPoll <= 0) {
            loop = false
          } else {
            var continue      = true
            val pollUpToIndex = currentSubscriberIndex + toPoll
            while (continue && currentSubscriberIndex != pollUpToIndex) {
              val currentIndex = (currentSubscriberIndex % capacity).toInt
              val currentSeq   = seq.get(currentIndex)
              if (currentSubscriberIndex + 1 != currentSeq) {
                continue = false
              }
              currentSubscriberIndex += 1
            }
            if (continue) {
              currentSubscriberIndex -= toPoll
              if (subscriberIndex.compareAndSet(currentSubscriberIndex, currentSubscriberIndex + toPoll)) {
                while (currentSubscriberIndex != pollUpToIndex) {
                  val currentIndex       = (currentSubscriberIndex % capacity).toInt
                  val a                  = array(currentIndex).asInstanceOf[A]
                  val currentSliding     = sliding.get(currentIndex)
                  val currentSubscribers = subscribers.decrementAndGet(currentIndex)
                  if (currentSubscribers == 0) {
                    if (
                      sliding.compareAndSet(
                        currentIndex,
                        currentSubscriberIndex + 1,
                        currentSubscriberIndex + capacity
                      )
                    ) {
                      array(currentIndex) = null
                      subscribersIndex.getAndIncrement()
                      seq.lazySet(currentIndex, currentSubscriberIndex + capacity)
                    }
                  }
                  if (currentSubscriberIndex + 1 == currentSliding) {
                    builder += a
                    remaining -= 1
                  }
                  currentSubscriberIndex += 1
                }
              } else {
                currentSubscriberIndex = subscriberIndex.get
              }
            } else {
              val currentState           = state.get
              val currentPublisherIndex  = currentState.publisherIndex
              val currentSubscriberIndex = subscriberIndex.get
              val currentIndex           = (currentSubscriberIndex % capacity).toInt
              if (currentSubscriberIndex == currentPublisherIndex) {
                loop = false
              } else {
                val currentSubscribersIndex = subscribersIndex.get
                if (currentSubscriberIndex - currentSubscribersIndex < 0) {
                  if (subscriberIndex.compareAndSet(currentSubscriberIndex, currentSubscriberIndex + 1)) {
                    subscribers.getAndDecrement(currentIndex)
                  }
                }
              }
            }
          }
        }
        builder.result()
      }

      def size(): Int =
        if (unsubscribed.get) 0
        else {
          val currentState            = state.get
          val currentPublisherIndex   = currentState.publisherIndex
          val currentSubscriberIndex  = subscriberIndex.get
          val currentSubscribersIndex = subscribersIndex.get
          if (currentSubscriberIndex - currentSubscribersIndex < 0) {
            (currentPublisherIndex - currentSubscribersIndex).toInt
          } else {
            (currentPublisherIndex - currentSubscriberIndex).toInt
          }
        }

      def unsubscribe(): Unit =
        if (unsubscribed.compareAndSet(false, true)) {
          var currentSubscriberIndex = subscriberIndex.getAndAdd(Int.MaxValue)
          val currentState =
            state.getAndUpdate(currentState => currentState.copy(subscriberCount = currentState.subscriberCount - 1))
          val currentPublisherIndex = (currentState.publisherIndex).toInt
          while (currentSubscriberIndex != currentPublisherIndex) {
            val currentIndex       = (currentSubscriberIndex % capacity).toInt
            val currentSubscribers = subscribers.decrementAndGet(currentIndex)
            if (currentSubscribers == 0) {
              val currentSliding = sliding.get(currentIndex)
              if (currentSliding == currentSubscriberIndex + 1) {
                val updatedSubscribers = subscribers.get(currentIndex)
                if (updatedSubscribers == 0) {
                  if (
                    sliding.compareAndSet(currentIndex, currentSubscriberIndex + 1, currentSubscriberIndex + capacity)
                  ) {
                    array(currentIndex) = null
                    subscribersIndex.getAndIncrement()
                    seq.lazySet(currentIndex, currentSubscriberIndex + capacity)
                  }
                }
              }
            }
            currentSubscriberIndex += 1
          }
        }
    }
}

private object BoundedHubArb {
  final case class State(publisherIndex: Long, subscriberCount: Int)
}
