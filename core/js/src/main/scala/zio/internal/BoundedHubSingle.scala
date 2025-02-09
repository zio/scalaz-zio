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

/**
 * A bounded hub with capacity equal to one backed by an atomic reference.
 */
private final class BoundedHubSingle[A] extends Hub[A] {
  private[this] var publisherIndex  = 0L
  private[this] var subscriberCount = 0
  private[this] var subscribers     = 0
  private[this] var value           = null.asInstanceOf[A]

  val capacity: Int =
    1

  def isEmpty(): Boolean =
    subscribers == 0

  def isFull(): Boolean =
    !isEmpty()

  def publish(a: A): Boolean =
    if (isFull()) false
    else {
      if (subscriberCount != 0) {
        value = a
        subscribers = subscriberCount
        publisherIndex += 1
      }
      true
    }

  def publishAll[A1 <: A](as: Iterable[A1]): Chunk[A1] =
    if (as.isEmpty) Chunk.empty
    else {
      val a = as.head
      if (publish(a)) Chunk.fromIterable(as.tail) else Chunk.fromIterable(as)
    }

  def size(): Int =
    if (isEmpty()) 0 else 1

  def slide(): Unit =
    if (isFull()) {
      subscribers = 0
      value = null.asInstanceOf[A]
    }

  def subscribe(): Hub.Subscription[A] =
    new Hub.Subscription[A] {
      private[this] var subscriberIndex = publisherIndex
      private[this] var unsubscribed    = false
      subscriberCount += 1

      def isEmpty(): Boolean =
        unsubscribed || subscribers == 0 || subscriberIndex == publisherIndex

      def poll(default: A): A =
        if (isEmpty()) default
        else {
          val a = value
          subscribers -= 1
          if (subscribers == 0) {
            value = null.asInstanceOf[A]
          }
          subscriberIndex += 1
          a
        }

      def pollUpTo(n: Int): Chunk[A] =
        if (isEmpty() || n < 1) Chunk.empty
        else {
          val a = value
          subscribers -= 1
          if (subscribers == 0) {
            value = null.asInstanceOf[A]
          }
          subscriberIndex += 1
          Chunk.single(a)
        }

      def size(): Int =
        if (isEmpty()) 0 else 1

      def unsubscribe(): Unit =
        if (!unsubscribed) {
          unsubscribed = true
          subscriberCount -= 1
          if (subscriberIndex != publisherIndex) {
            subscribers -= 1
            if (subscribers == 0) {
              value = null.asInstanceOf[A]
            }
          }
        }
    }
}
