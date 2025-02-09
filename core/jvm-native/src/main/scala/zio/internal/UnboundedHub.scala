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
 * An unbounded hub backed by a linked list.
 */
private final class UnboundedHub[A] extends Hub[A] {
  import UnboundedHub._

  private[this] val publisherHead: AtomicReference[Node[A]] =
    new AtomicReference(new Node[A](null.asInstanceOf[A], new AtomicReference(Pointer(null, 0))))

  private[this] val publisherTail: AtomicReference[Node[A]] =
    new AtomicReference(publisherHead.get)

  val capacity: Int =
    Int.MaxValue

  def isEmpty(): Boolean = {
    var empty = true
    var loop  = true
    while (loop) {
      val currentPublisherHead = publisherHead.get
      val currentPublisherTail = publisherTail.get
      val currentNode          = currentPublisherHead.pointer.get.node
      if (currentPublisherHead eq publisherHead.get) {
        if (currentPublisherHead eq currentPublisherTail) {
          if (currentNode eq null) {
            loop = false
          } else {
            publisherTail.compareAndSet(currentPublisherTail, currentNode)
          }
        } else {
          if (currentNode.value != null) {
            empty = false
            loop = false
          } else {
            publisherHead.compareAndSet(currentPublisherHead, currentNode)
          }
        }
      }
    }
    empty
  }

  def isFull(): Boolean =
    false

  def publish(a: A): Boolean = {
    assert(a != null)
    var loop = true
    while (loop) {
      val currentPublisherTail = publisherTail.get
      val currentPointer       = currentPublisherTail.pointer.get
      val currentNode          = currentPointer.node
      val currentSubscribers   = currentPointer.subscribers
      if (currentPublisherTail eq publisherTail.get) {
        if (currentNode eq null) {
          if (currentSubscribers == 0) {
            loop = false
          } else {
            val updatedNode    = new Node[A](a, new AtomicReference(Pointer(null, currentSubscribers)))
            val updatedPointer = Pointer[A](updatedNode, currentSubscribers)
            if (currentPublisherTail.pointer.compareAndSet(currentPointer, updatedPointer)) {
              publisherTail.compareAndSet(currentPublisherTail, updatedNode)
              loop = false
            }
          }
        } else {
          publisherTail.compareAndSet(currentPublisherTail, currentNode)
        }
      }
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

  def size(): Int = {
    var currentNode = publisherHead.get.pointer.get.node
    var loop        = true
    var size        = 0
    while (currentNode ne null) {
      if (currentNode.value != null) {
        size += 1
        if (size == Int.MaxValue) {
          loop = false
        }
      }
      currentNode = currentNode.pointer.get.node
    }
    size
  }

  def slide(): Unit = {
    var loop = true
    while (loop) {
      val currentPublisherHead = publisherHead.get
      val currentPublisherTail = publisherTail.get
      val currentNode          = currentPublisherHead.pointer.get.node
      if (currentPublisherHead eq publisherHead.get) {
        if (currentPublisherHead eq currentPublisherTail) {
          if (currentNode eq null) {
            loop = false
          } else {
            publisherHead.compareAndSet(currentPublisherHead, currentNode)
          }
        } else {
          if (currentNode.value != null) {
            if (publisherHead.compareAndSet(currentPublisherHead, currentNode)) {
              currentNode.value = null.asInstanceOf[A]
              loop = false
            }
          } else {
            publisherHead.compareAndSet(currentPublisherHead, currentNode)
          }
        }
      }
    }
  }

  def subscribe(): Hub.Subscription[A] = {
    var currentPublisherTail = null.asInstanceOf[Node[A]]
    var loop                 = true
    while (loop) {
      currentPublisherTail = publisherTail.get
      val currentPointer = currentPublisherTail.pointer.get
      val currentNode    = currentPointer.node
      if (currentPublisherTail eq publisherTail.get) {
        if (currentNode eq null) {
          val updatedPointer = currentPointer.copy(subscribers = currentPointer.subscribers + 1)
          if (currentPublisherTail.pointer.compareAndSet(currentPointer, updatedPointer)) {
            loop = false
          }
        } else {
          publisherTail.compareAndSet(currentPublisherTail, currentNode)
        }
      }
    }

    new Hub.Subscription[A] {

      private[this] val subscriberHead: AtomicReference[Node[A]] =
        new AtomicReference(currentPublisherTail)

      private[this] val unsubscribed: AtomicBoolean =
        new AtomicBoolean(false)

      def isEmpty(): Boolean = {
        var empty = true
        var loop  = true
        while (loop) {
          val currentSubscriberHead = subscriberHead.get
          val currentPublisherTail  = publisherTail.get
          val currentNode           = currentSubscriberHead.pointer.get.node
          if (currentSubscriberHead eq subscriberHead.get) {
            if (currentSubscriberHead eq currentPublisherTail) {
              if (currentNode eq null) {
                loop = false
              } else {
                publisherTail.compareAndSet(currentPublisherTail, currentNode)
              }
            } else {
              if (currentNode.value != null) {
                empty = false
                loop = false
              } else {
                subscriberHead.compareAndSet(currentSubscriberHead, currentNode)
              }
            }
          }
        }
        empty
      }

      def poll(default: A): A = {
        var loop   = true
        var polled = default
        while (loop && !unsubscribed.get) {
          val currentSubscriberHead = subscriberHead.get
          val currentPublisherTail  = publisherTail.get
          val currentNode           = currentSubscriberHead.pointer.get.node
          if (currentSubscriberHead eq subscriberHead.get) {
            if (currentSubscriberHead eq currentPublisherTail) {
              if (currentNode eq null) {
                loop = false
              } else {
                publisherTail.compareAndSet(currentPublisherTail, currentNode)
              }
            } else {
              val a = currentNode.value
              if (a != null) {
                if (subscriberHead.compareAndSet(currentSubscriberHead, currentNode)) {
                  val currentPointer = currentSubscriberHead.pointer.updateAndGet { pointer =>
                    pointer.copy(subscribers = pointer.subscribers - 1)
                  }
                  val currentSubscribers = currentPointer.subscribers
                  if (currentSubscribers == 0) {
                    currentNode.value = null.asInstanceOf[A]
                    publisherHead.lazySet(currentNode)
                  }
                  loop = false
                  polled = a
                }
              } else {
                subscriberHead.compareAndSet(currentSubscriberHead, currentNode)
              }
            }
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

      def size(): Int = {
        var currentNode = subscriberHead.get.pointer.get.node
        var loop        = true
        var size        = 0
        while (currentNode ne null) {
          if (currentNode.value != null) {
            size += 1
            if (size == Int.MaxValue) {
              loop = false
            }
          }
          currentNode = currentNode.pointer.get.node
        }
        size
      }

      def unsubscribe(): Unit =
        if (unsubscribed.compareAndSet(false, true)) {
          var currentSubscriberHead = subscriberHead.get
          var currentPublisherTail  = null.asInstanceOf[Node[A]]
          var loop                  = true
          while (loop) {
            currentPublisherTail = publisherTail.get
            val currentPointer = currentPublisherTail.pointer.get
            val currentNode    = currentPointer.node
            if (currentPublisherTail eq publisherTail.get) {
              if (currentNode eq null) {
                val updatedPointer = currentPointer.copy(subscribers = currentPointer.subscribers - 1)
                if (currentPublisherTail.pointer.compareAndSet(currentPointer, updatedPointer)) {
                  loop = false
                }
              } else {
                subscriberHead.compareAndSet(currentSubscriberHead, currentNode)
              }
            }
          }
          while (currentSubscriberHead ne currentPublisherTail) {
            val currentPointer = currentSubscriberHead.pointer.updateAndGet { currentPointer =>
              currentPointer.copy(subscribers = currentPointer.subscribers - 1)
            }
            val currentNode        = currentPointer.node
            val currentSubscribers = currentPointer.subscribers
            if (currentSubscribers == 0) {
              currentNode.value = null.asInstanceOf[A]
              publisherHead.lazySet(currentNode)
            }
            currentSubscriberHead = currentNode
          }
        }
    }
  }
}

private object UnboundedHub {
  final class Node[A](var value: A, val pointer: AtomicReference[Pointer[A]])
  final case class Pointer[A](node: Node[A], subscribers: Int)
}
