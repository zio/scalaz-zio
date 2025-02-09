/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio.{Chunk, ChunkBuilder}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.immutable.SortedMap

/**
 * A `TPriorityQueue` contains values of type `A` that an `Ordering` is defined
 * on. Unlike a `TQueue`, `take` returns the highest priority value (the value
 * that is first in the specified ordering) as opposed to the first value
 * offered to the queue. The ordering that elements with the same priority will
 * be taken from the queue is not guaranteed.
 */
final class TPriorityQueue[A] private (private val ref: TRef[SortedMap[A, ::[A]]]) extends AnyVal {

  /**
   * Checks whether the queue is empty.
   */
  def isEmpty: USTM[Boolean] =
    ref.get.map(_.isEmpty)

  /**
   * Checks whether the queue is not empty..
   */
  def nonEmpty: USTM[Boolean] =
    ref.get.map(_.nonEmpty)

  /**
   * Offers the specified value to the queue.
   */
  def offer(a: A): USTM[Unit] =
    ref.update(map => map.updated(a, map.get(a).fold(::(a, Nil))(::(a, _))))

  /**
   * Offers all of the elements in the specified collection to the queue.
   */
  def offerAll(values: Iterable[A]): USTM[Unit] =
    ref.update(map => values.foldLeft(map)((map, a) => map.updated(a, map.get(a).fold(::(a, Nil))(::(a, _)))))

  /**
   * Peeks at the first value in the queue without removing it, retrying until a
   * value is in the queue.
   */
  def peek: USTM[A] =
    ZSTM.Effect((journal, _, _) =>
      ref.unsafeGet(journal).headOption match {
        case None          => throw ZSTM.RetryException
        case Some((_, as)) => as.head
      }
    )

  /**
   * Peeks at the first value in the queue without removing it, returning `None`
   * if there is not a value in the queue.
   */
  def peekOption: USTM[Option[A]] =
    ref.modify(map => (map.headOption.map(_._2.head), map))

  /**
   * Removes all elements from the queue matching the specified predicate.
   */
  def removeIf(f: A => Boolean): USTM[Unit] =
    retainIf(!f(_))

  /**
   * Retains only elements from the queue matching the specified predicate.
   */
  def retainIf(f: A => Boolean): USTM[Unit] =
    ref.update { map =>
      map.keys.foldLeft(map) { case (map, a) =>
        map(a).filter(f) match {
          case h :: t => map.updated(a, ::(h, t))
          case Nil    => map - a
        }
      }
    }

  /**
   * Returns the size of the queue.
   */
  def size: USTM[Int] =
    ref.modify(map => (map.foldLeft(0) { case (n, (_, as)) => n + as.length }, map))

  /**
   * Takes a value from the queue, retrying until a value is in the queue.
   */
  def take: USTM[A] =
    ZSTM.Effect { (journal, _, _) =>
      val map = ref.unsafeGet(journal)
      map.headOption match {
        case None => throw ZSTM.RetryException
        case Some((a, as)) =>
          ref.unsafeSet(
            journal,
            as.tail match {
              case h :: t => map.updated(a, ::(h, t))
              case Nil    => map - a
            }
          )
          as.head
      }
    }

  /**
   * Takes all values from the queue.
   */
  def takeAll: USTM[Chunk[A]] =
    ref.modify { map =>
      val builder = ChunkBuilder.make[A]()
      map.foreach(builder ++= _._2)
      (builder.result(), SortedMap.empty(map.ordering))
    }

  /**
   * Takes up to the specified maximum number of elements from the queue.
   */
  def takeUpTo(n: Int): USTM[Chunk[A]] =
    ref.modify { map =>
      val builder  = ChunkBuilder.make[A]()
      val iterator = map.iterator
      var updated  = map
      var i        = 0
      while (iterator.hasNext && i < n) {
        val (a, as) = iterator.next()
        val (l, r)  = as.splitAt(n - i)
        builder ++= l
        r match {
          case h :: t => updated += (a -> ::(h, t))
          case Nil    => updated -= a
        }
        i += l.length
      }
      (builder.result(), updated)
    }

  /**
   * Takes a value from the queue, returning `None` if there is not a value in
   * the queue.
   */
  def takeOption: USTM[Option[A]] =
    ZSTM.Effect { (journal, _, _) =>
      val map = ref.unsafeGet(journal)
      map.headOption match {
        case None => None
        case Some((a, as)) =>
          ref.unsafeSet(
            journal,
            as.tail match {
              case h :: t => map.updated(a, ::(h, t))
              case Nil    => map - a
            }
          )
          Some(as.head)
      }
    }

  /**
   * Collects all values into a chunk.
   */
  def toChunk: USTM[Chunk[A]] =
    ref.modify { map =>
      val builder = ChunkBuilder.make[A]()
      map.foreach(builder ++= _._2)
      (builder.result(), map)
    }

  /**
   * Collects all values into a list.
   */
  def toList: USTM[List[A]] =
    toChunk.map(_.toList)

  /**
   * Collects all values into a vector.
   */
  def toVector: USTM[Vector[A]] =
    toChunk.map(_.toVector)
}

object TPriorityQueue {

  /**
   * Constructs a new empty `TPriorityQueue` with the specified `Ordering`.
   */
  def empty[A](implicit ord: Ordering[A]): USTM[TPriorityQueue[A]] =
    TRef.make(SortedMap.empty[A, ::[A]]).map(ref => new TPriorityQueue(ref))

  /**
   * Makes a new `TPriorityQueue` initialized with provided iterable.
   */
  def fromIterable[A](data: => Iterable[A])(implicit ord: Ordering[A]): USTM[TPriorityQueue[A]] =
    TRef
      .make(data.foldLeft(SortedMap.empty[A, ::[A]])((map, a) => map.updated(a, map.get(a).fold(::(a, Nil))(::(a, _)))))
      .map(ref => new TPriorityQueue(ref))

  /**
   * Makes a new `TPriorityQueue` that is initialized with specified values.
   */
  def make[A](data: A*)(implicit ord: Ordering[A]): USTM[TPriorityQueue[A]] =
    fromIterable(data)
}
