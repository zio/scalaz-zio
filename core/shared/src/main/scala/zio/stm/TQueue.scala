/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

import zio.Chunk
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A `TQueue` is a transactional queue. Offerors can offer values to the queue
 * and takers can take values from the queue.
 */
trait TQueue[A] extends TDequeue[A] with TEnqueue[A] {

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
}

object TQueue {

  /**
   * Creates a bounded queue with the back pressure strategy. The queue will
   * retain values until they have been taken, applying back pressure to
   * offerors if the queue is at capacity.
   *
   * For best performance use capacities that are powers of two.
   */
  def bounded[A](requestedCapacity: => Int): USTM[TQueue[A]] =
    makeQueue(requestedCapacity, Strategy.BackPressure)

  /**
   * Creates a bounded queue with the dropping strategy. The queue will drop new
   * values if the queue is at capacity.
   *
   * For best performance use capacities that are powers of two.
   */
  def dropping[A](requestedCapacity: => Int): USTM[TQueue[A]] =
    makeQueue(requestedCapacity, Strategy.Dropping)

  /**
   * Creates a bounded queue with the sliding strategy. The queue will add new
   * values and drop old values if the queue is at capacity.
   *
   * For best performance use capacities that are powers of two.
   */
  def sliding[A](requestedCapacity: => Int): USTM[TQueue[A]] =
    makeQueue(requestedCapacity, Strategy.Sliding)

  /**
   * Creates an unbounded queue.
   */
  def unbounded[A]: USTM[TQueue[A]] =
    makeQueue(Int.MaxValue, Strategy.Dropping)

  /**
   * Creates a queue with the specified strategy.
   */
  private def makeQueue[A](requestedCapacity: => Int, strategy: => Strategy): USTM[TQueue[A]] =
    TRef.make[ScalaQueue[A]](ScalaQueue.empty).map { ref =>
      unsafeMakeQueue(ref, requestedCapacity, strategy)
    }

  /**
   * Unsafely creates a queue with the specified strategy.
   */
  private def unsafeMakeQueue[A](
    ref: TRef[ScalaQueue[A]],
    requestedCapacity: Int,
    strategy: Strategy
  ): TQueue[A] =
    new TQueue[A] {
      val capacity: Int =
        requestedCapacity
      val isShutdown: USTM[Boolean] =
        ZSTM.Effect { (journal, _, _) =>
          val queue = ref.unsafeGet(journal)
          queue eq null
        }
      def offer(a: A): ZSTM[Any, Nothing, Boolean] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else if (queue.size < capacity) {
            ref.unsafeSet(journal, queue.enqueue(a))
            true
          } else
            strategy match {
              case Strategy.BackPressure => throw ZSTM.RetryException
              case Strategy.Dropping     => false
              case Strategy.Sliding =>
                queue.dequeueOption match {
                  case Some((_, queue)) =>
                    ref.unsafeSet(journal, queue.enqueue(a))
                    true
                  case None =>
                    true
                }
            }
        }
      def offerAll(as: Iterable[A]): ZSTM[Any, Nothing, Boolean] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else if (queue.size + as.size <= capacity) {
            ref.unsafeSet(journal, queue ++ as)
            true
          } else
            strategy match {
              case Strategy.BackPressure => throw ZSTM.RetryException
              case Strategy.Dropping =>
                val forQueue = as.take(capacity - queue.size)
                ref.unsafeSet(journal, queue ++ forQueue)
                false
              case Strategy.Sliding =>
                val forQueue = as.take(capacity)
                val toDrop   = queue.size + forQueue.size - capacity
                ref.unsafeSet(journal, queue.drop(toDrop) ++ forQueue)
                true
            }
        }
      val peek: USTM[A] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else
            queue.headOption match {
              case Some(a) => a
              case None    => throw ZSTM.RetryException
            }
        }
      val peekOption: USTM[Option[A]] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else queue.headOption
        }
      val shutdown: USTM[Unit] =
        ZSTM.Effect { (journal, _, _) =>
          ref.unsafeSet(journal, null)
        }
      val size: USTM[Int] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else queue.size
        }
      val take: ZSTM[Any, Nothing, A] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else
            queue.dequeueOption match {
              case Some((a, queue)) =>
                ref.unsafeSet(journal, queue)
                a
              case None => throw ZSTM.RetryException
            }
        }
      val takeAll: ZSTM[Any, Nothing, zio.Chunk[A]] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else {
            ref.unsafeSet(journal, ScalaQueue.empty)
            Chunk.fromIterable(queue)
          }
        }
      def takeUpTo(max: Int): ZSTM[Any, Nothing, Chunk[A]] =
        ZSTM.Effect { (journal, fiberId, _) =>
          val queue = ref.unsafeGet(journal)
          if (queue eq null) throw ZSTM.InterruptException(fiberId)
          else {
            val (toTake, remaining) = queue.splitAt(max)
            ref.unsafeSet(journal, remaining)
            Chunk.fromIterable(toTake)
          }
        }
    }

  /**
   * A `Strategy` describes how the queue will handle values if the queue is at
   * capacity.
   */
  private sealed trait Strategy

  private object Strategy {

    /**
     * A strategy that retries if the queue is at capacity.
     */
    case object BackPressure extends Strategy

    /**
     * A strategy that drops new values if the queue is at capacity.
     */
    case object Dropping extends Strategy

    /**
     * A strategy that drops old values if the queue is at capacity.
     */
    case object Sliding extends Strategy
  }
}
