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

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic._

/**
 * A bounded hub with capacity equal to one backed by an atomic reference.
 */
private final class BoundedHubSingle[A] extends Hub[A] {
  import BoundedHubSingle._

  private[this] val state = new AtomicReference(State(0, 0, 0, null.asInstanceOf[A]))

  val capacity: Int =
    1

  def isEmpty(): Boolean = {
    val currentState       = state.get
    val currentSubscribers = currentState.subscribers
    currentSubscribers == 0
  }

  def isFull(): Boolean = {
    val currentState       = state.get
    val currentSubscribers = currentState.subscribers
    currentSubscribers != 0
  }

  def publish(a: A): Boolean = {
    var loop      = true
    var published = true
    while (loop) {
      val currentState           = state.get
      val currentSubscriberCount = currentState.subscriberCount
      val currentSubscribers     = currentState.subscribers
      if (currentSubscribers != 0) {
        loop = false
        published = false
      } else if (currentSubscriberCount == 0) {
        loop = false
      } else {
        val currentPublisherIndex = currentState.publisherIndex
        val updatedState = currentState.copy(
          publisherIndex = currentPublisherIndex + 1,
          subscribers = currentSubscriberCount,
          value = a
        )
        loop = !state.compareAndSet(currentState, updatedState)
      }
    }
    published
  }

  def publishAll[A1 <: A](as: Iterable[A1]): Chunk[A1] =
    if (as.isEmpty) Chunk.empty
    else {
      val a = as.head
      if (publish(a)) Chunk.fromIterable(as.tail) else Chunk.fromIterable(as)
    }

  def size(): Int = {
    val currentState       = state.get
    val currentSubscribers = currentState.subscribers
    if (currentSubscribers == 0) 0 else 1
  }

  def slide(): Unit = {
    var loop = true
    while (loop) {
      val currentState       = state.get
      val currentSubscribers = currentState.subscribers
      if (currentSubscribers == 0) {
        loop = false
      } else {
        val updatedState = currentState.copy(subscribers = 0, value = null.asInstanceOf[A])
        loop = !state.compareAndSet(currentState, updatedState)
      }
    }
  }

  def subscribe(): Hub.Subscription[A] =
    new Hub.Subscription[A] {
      private[this] var currentPublisherIndex = 0
      private[this] var loop                  = true
      while (loop) {
        val currentState           = state.get
        val currentSubscriberCount = currentState.subscriberCount
        val updatedState           = currentState.copy(subscriberCount = currentSubscriberCount + 1)
        if (state.compareAndSet(currentState, updatedState)) {
          currentPublisherIndex = currentState.publisherIndex
          loop = false
        }
      }
      private[this] val subscriberIndex = new AtomicInteger(currentPublisherIndex)
      private[this] val unsubscribed    = new AtomicBoolean(false)

      def isEmpty(): Boolean =
        if (unsubscribed.get) true
        else {
          val currentState       = state.get
          val currentSubscribers = currentState.subscribers
          if (currentSubscribers == 0) true
          else {
            val currentPublisherIndex  = currentState.publisherIndex
            val currentSubscriberIndex = subscriberIndex.get
            currentPublisherIndex == currentSubscriberIndex
          }
        }

      def poll(default: A): A =
        if (unsubscribed.get) default
        else {
          var currentSubscriberIndex = subscriberIndex.get
          var loop                   = true
          var polled                 = default
          while (loop) {
            var currentState          = state.get
            var currentPublisherIndex = currentState.publisherIndex
            var currentSubscribers    = currentState.subscribers
            if (currentSubscribers == 0 || currentSubscriberIndex == currentPublisherIndex) {
              loop = false
            } else if (subscriberIndex.compareAndSet(currentSubscriberIndex, currentPublisherIndex)) {
              currentSubscriberIndex = currentPublisherIndex
              var continue = true
              while (continue) {
                val currentValue = currentState.value
                val updatedState =
                  if (currentSubscribers == 1)
                    currentState.copy(subscribers = 0, value = null.asInstanceOf[A])
                  else
                    currentState.copy(subscribers = currentSubscribers - 1)
                if (state.compareAndSet(currentState, updatedState)) {
                  continue = false
                  loop = false
                  polled = currentValue
                } else {
                  currentState = state.get
                  currentPublisherIndex = currentState.publisherIndex
                  currentSubscribers = currentState.subscribers
                  if (currentSubscribers == 0) {
                    continue = false
                    loop = false
                  } else if (currentSubscriberIndex != currentPublisherIndex) {
                    continue = false
                  }
                }
              }
            } else {
              currentSubscriberIndex += 1
            }
          }
          polled
        }

      def pollUpTo(n: Int): Chunk[A] =
        if (n < 1) Chunk.empty
        else {
          val default = null.asInstanceOf[A]
          val a       = poll(default)
          if (a == default) Chunk.empty else Chunk.single(a)
        }

      def size(): Int =
        if (unsubscribed.get) 0
        else {
          val currentState       = state.get
          val currentSubscribers = currentState.subscribers
          if (currentSubscribers == 0) 0
          else {
            val currentPublisherIndex  = currentState.publisherIndex
            val currentSubscriberIndex = subscriberIndex.get
            if (currentPublisherIndex == currentSubscriberIndex) 0 else 1
          }
        }

      def unsubscribe(): Unit =
        if (unsubscribed.compareAndSet(false, true)) {
          val currentSubscriberIndex = subscriberIndex.getAndAdd(Int.MaxValue)
          var loop                   = true
          while (loop) {
            val currentState           = state.get
            val currentPublisherIndex  = currentState.publisherIndex
            val currentSubscriberCount = currentState.subscriberCount
            val currentSubscribers     = currentState.subscribers
            val updatedState =
              if (currentSubscribers == 0 || currentSubscriberIndex == currentPublisherIndex) {
                currentState.copy(subscriberCount = currentSubscriberCount - 1)
              } else if (currentSubscribers == 1) {
                currentState.copy(
                  subscriberCount = currentSubscriberCount - 1,
                  subscribers = 0,
                  value = null.asInstanceOf[A]
                )
              } else {
                currentState.copy(subscriberCount = currentSubscriberCount - 1, subscribers = currentSubscribers - 1)
              }
            loop = !state.compareAndSet(currentState, updatedState)
          }
        }
    }
}

private object BoundedHubSingle {
  final case class State[A](publisherIndex: Int, subscriberCount: Int, subscribers: Int, value: A)
}
