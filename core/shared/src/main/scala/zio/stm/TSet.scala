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

import zio.{Chunk, NonEmptyChunk, Unsafe}

/**
 * Transactional set implemented on top of [[TMap]].
 */
final class TSet[A] private (private val tmap: TMap[A, Unit]) extends AnyVal {

  /**
   * Tests whether or not set contains an element.
   */
  def contains(a: A): USTM[Boolean] =
    tmap.contains(a)

  /**
   * Tests if the set is empty or not
   */
  def isEmpty: USTM[Boolean] =
    tmap.isEmpty

  /**
   * Removes a single element from the set.
   */
  def delete(a: A): USTM[Unit] =
    tmap.delete(a)

  /**
   * Removes elements from the set.
   */
  def deleteAll(as: Iterable[A]): USTM[Unit] =
    tmap.deleteAll(as)

  /**
   * Atomically transforms the set into the difference of itself and the
   * provided set.
   */
  def diff(other: TSet[A]): USTM[Unit] =
    other.toSet.flatMap(vals => removeIfDiscard(vals.contains))

  /**
   * Atomically folds using a pure function.
   */
  def fold[B](zero: B)(op: (B, A) => B): USTM[B] =
    tmap.fold(zero)((acc, kv) => op(acc, kv._1))

  /**
   * Atomically folds using a transactional function.
   */
  def foldSTM[B, E](zero: B)(op: (B, A) => STM[E, B]): STM[E, B] =
    tmap.foldSTM(zero)((acc, kv) => op(acc, kv._1))

  /**
   * Atomically performs transactional-effect for each element in set.
   */
  def foreach[E](f: A => STM[E, Unit]): STM[E, Unit] =
    foldSTM(())((_, a) => f(a))

  /**
   * Atomically transforms the set into the intersection of itself and the
   * provided set.
   */
  def intersect(other: TSet[A]): USTM[Unit] =
    other.toSet.flatMap(vals => retainIfDiscard(vals.contains))

  /**
   * Stores new element in the set.
   */
  def put(a: A): USTM[Unit] =
    tmap.put(a, ())

  /**
   * Removes bindings matching predicate and returns the removed entries.
   */
  def removeIf(p: A => Boolean): USTM[Chunk[A]] =
    tmap.removeIf((k, _) => p(k)).map(_.map(_._1))

  /**
   * Removes elements matching predicate.
   */
  def removeIfDiscard(p: A => Boolean): USTM[Unit] =
    tmap.removeIfDiscard((k, _) => p(k))

  /**
   * Retains bindings matching predicate and returns removed bindings.
   */
  def retainIf(p: A => Boolean): USTM[Chunk[A]] =
    tmap.retainIf((k, _) => p(k)).map(_.map(_._1))

  /**
   * Retains elements matching predicate.
   */
  def retainIfDiscard(p: A => Boolean): USTM[Unit] =
    tmap.retainIfDiscard((k, _) => p(k))

  /**
   * Returns the set's cardinality.
   */
  def size: USTM[Int] = toList.map(_.size)

  /**
   * Takes the first matching value, or retries until there is one.
   */
  def takeFirst[B](pf: PartialFunction[A, B]): USTM[B] =
    tmap.takeFirst {
      case (k, _) if pf.isDefinedAt(k) => pf(k)
    }

  def takeFirstSTM[R, E, B](pf: A => ZSTM[R, Option[E], B]): ZSTM[R, E, B] =
    tmap.takeFirstSTM { case (k, _) =>
      pf(k)
    }

  /**
   * Takes all matching values, or retries until there is at least one.
   */
  def takeSome[B](pf: PartialFunction[A, B]): USTM[NonEmptyChunk[B]] =
    tmap.takeSome {
      case (k, _) if pf.isDefinedAt(k) => pf(k)
    }

  /**
   * Takes all matching values, or retries until there is at least one.
   */
  def takeSomeSTM[R, E, B](pf: A => ZSTM[R, Option[E], B]): ZSTM[R, E, NonEmptyChunk[B]] =
    tmap.takeSomeSTM { case (k, _) =>
      pf(k)
    }

  /**
   * Collects all elements into a list.
   */
  def toList: USTM[List[A]] = tmap.keys

  /**
   * Collects all elements into a set.
   */
  def toSet: USTM[Set[A]] = toList.map(_.toSet)

  /**
   * Atomically updates all elements using a pure function.
   */
  def transform(f: A => A): USTM[Unit] =
    tmap.transform((k, v) => f(k) -> v)

  /**
   * Atomically updates all elements using a transactional function.
   */
  def transformSTM[E](f: A => STM[E, A]): STM[E, Unit] =
    tmap.transformSTM((k, v) => f(k).map(_ -> v))

  /**
   * Atomically transforms the set into the union of itself and the provided
   * set.
   */
  def union(other: TSet[A]): USTM[Unit] =
    other.foreach(put)
}

object TSet {

  /**
   * Makes an empty `TSet`.
   */
  def empty[A]: USTM[TSet[A]] =
    fromIterable(Nil)

  /**
   * Makes a new `TSet` initialized with provided iterable.
   */
  def fromIterable[A](data: => Iterable[A]): USTM[TSet[A]] =
    make(data.toSeq: _*)

  /**
   * Makes a new `TSet` that is initialized with specified values.
   */
  def make[A](data: A*): USTM[TSet[A]] =
    ZSTM.succeed(unsafe.make(data: _*)(Unsafe.unsafe))

  object unsafe {
    def make[A](data: A*)(implicit unsafe: Unsafe): TSet[A] =
      new TSet(TMap.unsafe.make(data.map((_, ())): _*))
  }
}
