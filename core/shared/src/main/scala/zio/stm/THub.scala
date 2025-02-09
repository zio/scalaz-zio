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

package zio.stm

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `THub` is a transactional message hub. Publishers can publish messages to
 * the hub and subscribers can subscribe to take messages from the hub.
 */
abstract class THub[A] extends TEnqueue[A] {

  /**
   * Publishes a message to the hub, returning whether the message was published
   * to the hub.
   */
  def publish(a: A): USTM[Boolean]

  /**
   * Publishes all of the specified messages to the hub, returning whether they
   * were published to the hub.
   */
  def publishAll(as: Iterable[A]): USTM[Boolean]

  /**
   * Subscribes to receive messages from the hub. The resulting subscription can
   * be evaluated multiple times to take a message from the hub each time. The
   * caller is responsible for unsubscribing from the hub by shutting down the
   * queue.
   */
  def subscribe: USTM[TDequeue[A]]

  override final def awaitShutdown: USTM[Unit] =
    isShutdown.flatMap(b => if (b) ZSTM.unit else ZSTM.retry)

  /**
   * Checks if the queue is empty.
   */
  override final def isEmpty: USTM[Boolean] =
    size.map(_ == 0)

  /**
   * Checks if the queue is at capacity.
   */
  override final def isFull: USTM[Boolean] =
    size.map(_ == capacity)

  final def offer(a: A): USTM[Boolean] =
    publish(a)

  final def offerAll(as: Iterable[A]): USTM[Boolean] =
    publishAll(as)

  /**
   * Subscribes to receive messages from the hub. The resulting subscription can
   * be evaluated multiple times within the scope to take a message from the hub
   * each time.
   */
  final def subscribeScoped(implicit trace: Trace): ZIO[Scope, Nothing, TDequeue[A]] =
    ZIO.acquireRelease(subscribe.commit)(_.shutdown.commit)
}

object THub {

  /**
   * Creates a bounded hub with the back pressure strategy. The hub will retain
   * messages until they have been taken by all subscribers, applying back
   * pressure to publishers if the hub is at capacity.
   */
  def bounded[A](requestedCapacity: => Int): USTM[THub[A]] =
    makeHub(requestedCapacity, Strategy.BackPressure)

  /**
   * Creates a bounded hub with the dropping strategy. The hub will drop new
   * messages if the hub is at capacity.
   */
  def dropping[A](requestedCapacity: => Int): USTM[THub[A]] =
    makeHub(requestedCapacity, Strategy.Dropping)

  /**
   * Creates a bounded hub with the sliding strategy. The hub will add new
   * messages and drop old messages if the hub is at capacity.
   *
   * For best performance use capacities that are powers of two.
   */
  def sliding[A](requestedCapacity: => Int): USTM[THub[A]] =
    makeHub(requestedCapacity, Strategy.Sliding)

  /**
   * Creates an unbounded hub.
   */
  def unbounded[A]: USTM[THub[A]] =
    makeHub(Int.MaxValue, Strategy.Dropping)

  /**
   * Creates a hub with the specified strategy.
   */
  private def makeHub[A](requestedCapacity: => Int, strategy: => Strategy): USTM[THub[A]] =
    for {
      empty           <- TRef.make[Node[A]](null)
      hubSize         <- TRef.make(0)
      publisherHead   <- TRef.make(empty)
      publisherTail   <- TRef.make(empty)
      subscriberCount <- TRef.make(0)
      subscribers     <- TRef.make[Set[TRef[TRef[Node[A]]]]](Set.empty)
    } yield unsafeMakeHub(
      hubSize,
      publisherHead,
      publisherTail,
      requestedCapacity,
      strategy,
      subscriberCount,
      subscribers
    )

  /**
   * Unsafely creates a hub with the specified strategy.
   */
  private def unsafeMakeHub[A](
    hubSize: TRef[Int],
    publisherHead: TRef[TRef[Node[A]]],
    publisherTail: TRef[TRef[Node[A]]],
    requestedCapacity: Int,
    strategy: Strategy,
    subscriberCount: TRef[Int],
    subscribers: TRef[Set[TRef[TRef[Node[A]]]]]
  ): THub[A] =
    new THub[A] {
      def capacity: Int =
        requestedCapacity
      def isShutdown: USTM[Boolean] =
        ZSTM.Effect { (journal, _, _) =>
          val currentPublisherTail = publisherTail.unsafeGet(journal)
          currentPublisherTail eq null
        }
      def publish(a: A): USTM[Boolean] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val currentPublisherTail = publisherTail.unsafeGet(journal)
          if (currentPublisherTail eq null) throw ZSTM.InterruptException(fiberId)
          else {
            val currentSubscriberCount = subscriberCount.unsafeGet(journal)
            if (currentSubscriberCount == 0) true
            else {
              val currentHubSize = hubSize.unsafeGet(journal)
              if (currentHubSize < capacity) {
                val updatedPublisherTail = TRef.unsafeMake[Node[A]](null)
                val updatedNode          = Node(a, currentSubscriberCount, updatedPublisherTail)
                currentPublisherTail.unsafeSet(journal, updatedNode)
                publisherTail.unsafeSet(journal, updatedPublisherTail)
                hubSize.unsafeSet(journal, currentHubSize + 1)
                true
              } else {
                strategy match {
                  case Strategy.BackPressure => throw ZSTM.RetryException
                  case Strategy.Dropping     => false
                  case Strategy.Sliding =>
                    if (capacity > 0) {
                      var currentPublisherHead = publisherHead.unsafeGet(journal)
                      var loop                 = true
                      while (loop) {
                        val node = currentPublisherHead.unsafeGet(journal)
                        if (node eq null) throw ZSTM.RetryException
                        else {
                          val head = node.head
                          val tail = node.tail
                          if (head != null) {
                            val updatedNode = node.copy(head = null.asInstanceOf[A])
                            currentPublisherHead.unsafeSet(journal, updatedNode)
                            publisherHead.unsafeSet(journal, tail)
                            loop = false
                          } else {
                            currentPublisherHead = tail
                          }
                        }
                      }
                      val updatedPublisherTail = TRef.unsafeMake[Node[A]](null)
                      val updatedNode          = Node(a, currentSubscriberCount, updatedPublisherTail)
                      currentPublisherTail.unsafeSet(journal, updatedNode)
                      publisherTail.unsafeSet(journal, updatedPublisherTail)
                    }
                    true
                }
              }
            }
          }
        }
      def publishAll(as: Iterable[A]): USTM[Boolean] =
        ZSTM.foreach(as)(publish).map(_.forall(identity))
      def shutdown: USTM[Unit] =
        ZSTM.Effect { (journal, _, _) =>
          val currentPublisherTail = publisherTail.unsafeGet(journal)
          if (currentPublisherTail ne null) {
            publisherTail.unsafeSet(journal, null)
            val currentSubscribers = subscribers.unsafeGet(journal)
            currentSubscribers.foreach(_.unsafeSet(journal, null))
            subscribers.unsafeSet(journal, Set.empty)
          }
        }
      def size: USTM[Int] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val currentPublisherTail = publisherTail.unsafeGet(journal)
          if (currentPublisherTail eq null) throw ZSTM.InterruptException(fiberId)
          else hubSize.unsafeGet(journal)
        }
      def subscribe: USTM[TDequeue[A]] =
        makeSubscription(hubSize, publisherHead, publisherTail, requestedCapacity, subscriberCount, subscribers)
    }

  private def makeSubscription[A](
    hubSize: TRef[Int],
    publisherHead: TRef[TRef[Node[A]]],
    publisherTail: TRef[TRef[Node[A]]],
    requestedCapacity: Int,
    subscriberCount: TRef[Int],
    subscribers: TRef[Set[TRef[TRef[Node[A]]]]]
  ): USTM[TDequeue[A]] =
    for {
      currentPublisherTail   <- publisherTail.get
      subscriberHead         <- TRef.make(currentPublisherTail)
      currentSubscriberCount <- subscriberCount.get
      currentSubscribers     <- subscribers.get
      _                      <- subscriberCount.set(currentSubscriberCount + 1)
      _                      <- subscribers.set(currentSubscribers + subscriberHead)
    } yield unsafeMakeSubscription(
      hubSize,
      publisherHead,
      requestedCapacity,
      subscriberHead,
      subscriberCount,
      subscribers
    )

  private def unsafeMakeSubscription[A](
    hubSize: TRef[Int],
    publisherHead: TRef[TRef[Node[A]]],
    requestedCapacity: Int,
    subscriberHead: TRef[TRef[Node[A]]],
    subscriberCount: TRef[Int],
    subscribers: TRef[Set[TRef[TRef[Node[A]]]]]
  ): TDequeue[A] =
    new TDequeue[A] {
      override def capacity: Int =
        requestedCapacity
      override def isShutdown: USTM[Boolean] =
        ZSTM.Effect { (journal, _, _) =>
          val currentSubscriberHead = subscriberHead.unsafeGet(journal)
          currentSubscriberHead eq null
        }
      override def peek: ZSTM[Any, Nothing, A] =
        ZSTM.Effect { (journal, fiberId, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead eq null) throw ZSTM.InterruptException(fiberId)
          else {
            var a    = null.asInstanceOf[A]
            var loop = true
            while (loop) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) throw ZSTM.RetryException
              else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  a = node.head
                  loop = false
                } else {
                  currentSubscriberHead = tail
                }
              }
            }
            a
          }
        }
      override def peekOption: ZSTM[Any, Nothing, Option[A]] =
        ZSTM.Effect { (journal, fiberId, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead eq null) throw ZSTM.InterruptException(fiberId)
          else {
            var a    = null.asInstanceOf[Option[A]]
            var loop = true
            while (loop) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) {
                a = None
                loop = false
              } else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  a = Some(node.head)
                  loop = false
                } else {
                  currentSubscriberHead = tail
                }
              }
            }
            a
          }
        }
      override def shutdown: USTM[Unit] =
        ZSTM.Effect { (journal, _, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead ne null) {
            subscriberHead.unsafeSet(journal, null)
            var loop = true
            while (loop) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) {
                loop = false
              } else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  val subscribers = node.subscribers
                  if (subscribers == 1) {
                    val size        = hubSize.unsafeGet(journal)
                    val updatedNode = node.copy(head = null.asInstanceOf[A], subscribers = 0)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                    publisherHead.unsafeSet(journal, tail)
                    hubSize.unsafeSet(journal, size - 1)
                  } else {
                    val updatedNode = node.copy(subscribers = subscribers - 1)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                  }
                }
                currentSubscriberHead = tail
              }
            }
            val currentSubscriberCount = subscriberCount.unsafeGet(journal)
            subscriberCount.unsafeSet(journal, currentSubscriberCount - 1)
            subscribers.unsafeSet(journal, subscribers.unsafeGet(journal) - subscriberHead)
          }
        }
      override def size: USTM[Int] =
        ZSTM.Effect { (journal, fiberId, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead eq null) throw ZSTM.InterruptException(fiberId)
          else {
            var loop = true
            var size = 0
            while (loop) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) loop = false
              else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  size += 1
                  if (size == Int.MaxValue) {
                    loop = false
                  }
                }
                currentSubscriberHead = tail
              }
            }
            size
          }
        }
      override def take: ZSTM[Any, Nothing, A] =
        ZSTM.Effect { (journal, fiberId, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead eq null) throw ZSTM.InterruptException(fiberId)
          else {
            var a    = null.asInstanceOf[A]
            var loop = true
            while (loop) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) throw ZSTM.RetryException
              else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  val subscribers = node.subscribers
                  if (subscribers == 1) {
                    val size        = hubSize.unsafeGet(journal)
                    val updatedNode = node.copy(head = null.asInstanceOf[A], subscribers = 0)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                    publisherHead.unsafeSet(journal, tail)
                    hubSize.unsafeSet(journal, size - 1)
                  } else {
                    val updatedNode = node.copy(subscribers = subscribers - 1)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                  }
                  subscriberHead.unsafeSet(journal, tail)
                  a = head
                  loop = false
                } else {
                  currentSubscriberHead = tail
                }
              }
            }
            a
          }
        }
      override def takeAll: ZSTM[Any, Nothing, Chunk[A]] =
        takeUpTo(Int.MaxValue)
      override def takeUpTo(max: Int): ZSTM[Any, Nothing, Chunk[A]] =
        ZSTM.Effect { (journal, fiberId, _) =>
          var currentSubscriberHead = subscriberHead.unsafeGet(journal)
          if (currentSubscriberHead eq null) throw ZSTM.InterruptException(fiberId)
          else {
            val builder = ChunkBuilder.make[A]()
            var n       = 0
            while (n != max) {
              val node = currentSubscriberHead.unsafeGet(journal)
              if (node eq null) {
                n = max
              } else {
                val head = node.head
                val tail = node.tail
                if (head != null) {
                  val subscribers = node.subscribers
                  if (subscribers == 1) {
                    val size        = hubSize.unsafeGet(journal)
                    val updatedNode = node.copy(head = null.asInstanceOf[A], subscribers = 0)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                    publisherHead.unsafeSet(journal, tail)
                    hubSize.unsafeSet(journal, size - 1)
                  } else {
                    val updatedNode = node.copy(subscribers = subscribers - 1)
                    currentSubscriberHead.unsafeSet(journal, updatedNode)
                  }
                  builder += head
                  n += 1
                }
                currentSubscriberHead = tail
              }
            }
            subscriberHead.unsafeSet(journal, currentSubscriberHead)
            builder.result()
          }
        }
    }

  private final case class Node[A](head: A, subscribers: Int, tail: TRef[Node[A]])

  /**
   * A `Strategy` describes how the hub will handle messages if the hub is at
   * capacity.
   */
  private sealed trait Strategy

  private object Strategy {

    /**
     * A strategy that retries if the hub is at capacity.
     */
    case object BackPressure extends Strategy

    /**
     * A strategy that drops new messages if the hub is at capacity.
     */
    case object Dropping extends Strategy

    /**
     * A strategy that drops old messages if the hub is at capacity.
     */
    case object Sliding extends Strategy
  }
}
