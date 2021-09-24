/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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

/**
 * A bounded hub with capacity equal to a power of two backed by an array.
 */
private final class BoundedHubPow2[A](requestedCapacity: Int) extends Hub[A] {
  private[this] val array            = Array.ofDim[AnyRef](requestedCapacity)
  private[this] val mask             = requestedCapacity - 1
  private[this] var publisherIndex   = 0L
  private[this] val subscribers      = Array.ofDim[Int](requestedCapacity)
  private[this] var subscriberCount  = 0
  private[this] var subscribersIndex = 0L

  val capacity: Int =
    requestedCapacity

  def isEmpty(): Boolean =
    publisherIndex == subscribersIndex

  def isFull(): Boolean =
    publisherIndex == subscribersIndex + capacity

  def publish(a: A): Boolean =
    if (publisherIndex == subscribersIndex + capacity) false
    else {
      if (subscriberCount != 0) {
        val index = (publisherIndex & mask).toInt
        array(index) = a.asInstanceOf[AnyRef]
        subscribers(index) = subscriberCount
        publisherIndex += 1
      }
      true
    }

  def publishAll(as: Iterable[A]): Chunk[A] = {
    val n         = as.size
    val size      = (publisherIndex - subscribersIndex).toInt
    val available = capacity - size
    val forHub    = math.min(n, available)
    if (forHub == 0) Chunk.fromIterable(as)
    else {
      val iterator        = as.iterator
      val publishAllIndex = publisherIndex + forHub
      while (publisherIndex != publishAllIndex) {
        val a     = iterator.next()
        val index = (publisherIndex & mask).toInt
        array(index) = a.asInstanceOf[AnyRef]
        subscribers(index) = subscriberCount
        publisherIndex += 1
      }
      Chunk.fromIterator(iterator)
    }
  }

  def size(): Int =
    (publisherIndex - subscribersIndex).toInt

  def slide(): Unit =
    if (subscribersIndex != publisherIndex) {
      val index = (subscribersIndex & mask).toInt
      array(index) = null
      subscribers(index) = 0
      subscribersIndex += 1
    }

  def subscribe(): Hub.Subscription[A] =
    new Hub.Subscription[A] {
      private[this] var subscriberIndex = publisherIndex
      private[this] var unsubscribed    = false
      subscriberCount += 1

      def isEmpty(): Boolean =
        unsubscribed || publisherIndex == subscriberIndex || publisherIndex == subscribersIndex

      def poll(default: A): A =
        if (unsubscribed) default
        else {
          subscriberIndex = math.max(subscriberIndex, subscribersIndex)
          if (subscriberIndex != publisherIndex) {
            val index = (subscriberIndex & mask).toInt
            val a     = array(index)
            subscribers(index) -= 1
            if (subscribers(index) == 0) {
              array(index) = null
              subscribersIndex += 1
            }
            subscriberIndex += 1
            a.asInstanceOf[A]
          } else {
            default
          }
        }

      def pollUpTo(n: Int): Chunk[A] =
        if (unsubscribed) Chunk.empty
        else {
          subscriberIndex = math.max(subscriberIndex, subscribersIndex)
          val size   = (publisherIndex - subscriberIndex).toInt
          val toPoll = math.min(n, size)
          if (toPoll <= 0) Chunk.empty
          else {
            val builder       = ChunkBuilder.make[A]()
            val pollUpToIndex = subscriberIndex + toPoll
            while (subscriberIndex != pollUpToIndex) {
              val index = (subscriberIndex & mask).toInt
              val a     = array(index).asInstanceOf[A]
              subscribers(index) -= 1
              if (subscribers(index) == 0) {
                array(index) = null
                subscribersIndex += 1
              }
              builder += a
              subscriberIndex += 1
            }
            builder.result()
          }
        }

      def size(): Int =
        if (unsubscribed) 0
        else (publisherIndex - math.max(subscriberIndex, subscribersIndex)).toInt

      def unsubscribe(): Unit =
        if (!unsubscribed) {
          unsubscribed = true
          subscriberCount -= 1
          subscriberIndex = math.max(subscriberIndex, subscribersIndex)
          while (subscriberIndex != publisherIndex) {
            val index = (subscriberIndex & mask).toInt
            subscribers(index) -= 1
            if (subscribers(index) == 0) {
              array(index) = null
              subscribersIndex += 1
            }
            subscriberIndex += 1
          }
        }
    }
}
