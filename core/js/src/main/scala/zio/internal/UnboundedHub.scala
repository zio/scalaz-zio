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

import zio.{Chunk, ChunkBuilder}
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * An unbounded hub backed by a linked list.
 */
private final class UnboundedHub[A] extends Hub[A] {
  import UnboundedHub._

  private[this] var publisherHead    = new Node(null.asInstanceOf[A], 0, null)
  private[this] var publisherIndex   = 0L
  private[this] var publisherTail    = publisherHead
  private[this] var subscribersIndex = 0L

  val capacity: Int =
    Int.MaxValue

  def isEmpty(): Boolean =
    publisherHead == publisherTail

  def isFull(): Boolean =
    false

  def publish(a: A): Boolean = {
    assert(a != null)
    val subscribers = publisherTail.subscribers
    if (subscribers != 0) {
      publisherTail.next = new Node(a, subscribers, null)
      publisherTail = publisherTail.next
      publisherIndex += 1
    }
    true
  }

  def publishAll[A1 <: A](as: Iterable[A1]): Chunk[A1] = {
    val iterator = as.iterator
    while (iterator.hasNext) {
      val a = iterator.next()
      publish(a)
    }
    Chunk.empty
  }

  def size(): Int =
    (publisherIndex - subscribersIndex).toInt

  def slide(): Unit =
    if (publisherHead ne publisherTail) {
      publisherHead = publisherHead.next
      publisherHead.value = null.asInstanceOf[A]
      subscribersIndex += 1
    }

  def subscribe(): Hub.Subscription[A] =
    new Hub.Subscription[A] {
      private[this] var subscriberHead  = publisherTail
      private[this] var subscriberIndex = publisherIndex
      private[this] var unsubscribed    = false
      publisherTail.subscribers += 1

      def isEmpty(): Boolean =
        if (unsubscribed) true
        else {
          var empty = true
          var loop  = true
          while (loop) {
            if (subscriberHead eq publisherTail) {
              loop = false
            } else {
              if (subscriberHead.next.value != null) {
                empty = false
                loop = false
              } else {
                subscriberHead = subscriberHead.next
                subscriberIndex += 1
              }
            }
          }
          empty
        }

      def poll(default: A): A =
        if (unsubscribed) default
        else {
          var loop   = true
          var polled = default
          while (loop) {
            if (subscriberHead eq publisherTail) {
              loop = false
            } else {
              val a = subscriberHead.next.value
              if (a != null) {
                polled = a
                subscriberHead.subscribers -= 1
                if (subscriberHead.subscribers == 0) {
                  publisherHead = publisherHead.next
                  publisherHead.value = null.asInstanceOf[A]
                  subscribersIndex += 1
                }
                loop = false
              }
              subscriberHead = subscriberHead.next
              subscriberIndex += 1
            }
          }
          polled
        }

      def pollUpTo(n: Int): Chunk[A] = {
        val builder = ChunkBuilder.make[A]()
        val default = null.asInstanceOf[A]
        var i       = 0
        while (i != n) {
          val a = poll(default)
          if (a == default) {
            i = n
          } else {
            builder += a
            i += 1
          }
        }
        builder.result()
      }

      def size(): Int =
        if (unsubscribed) 0
        else (publisherIndex - math.max(subscriberIndex, subscribersIndex)).toInt

      def unsubscribe(): Unit =
        if (!unsubscribed) {
          unsubscribed = true
          publisherTail.subscribers -= 1
          while (subscriberHead ne publisherTail) {
            if (subscriberHead.next.value != null) {
              subscriberHead.subscribers -= 1
              if (subscriberHead.subscribers == 0) {
                publisherHead = publisherHead.next
                publisherHead.value = null.asInstanceOf[A]
                subscribersIndex += 1
              }
            }
            subscriberHead = subscriberHead.next
          }
        }
    }
}

private object UnboundedHub {
  final class Node[A](var value: A, var subscribers: Int, var next: Node[A])
}
