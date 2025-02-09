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

import zio.{Chunk, Unsafe}
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * Wraps array of [[TRef]] and adds methods for convenience.
 */
final class TArray[A] private[stm] (private[stm] val array: Array[TRef[A]]) extends AnyVal {

  /**
   * Extracts value from ref in array.
   */
  def apply(index: Int): USTM[A] =
    if (0 <= index && index < array.length)
      array(index).get
    else
      STM.die(new ArrayIndexOutOfBoundsException(index))

  /**
   * Finds the result of applying a partial function to the first value in its
   * domain.
   */
  def collectFirst[B](pf: PartialFunction[A, B]): USTM[Option[B]] =
    find(pf.isDefinedAt).map(_.map(pf))

  /**
   * Finds the result of applying an transactional partial function to the first
   * value in its domain.
   */
  def collectFirstSTM[E, B](pf: PartialFunction[A, STM[E, B]]): STM[E, Option[B]] =
    find(pf.isDefinedAt).flatMap {
      case Some(a) => pf(a).map(Some(_))
      case _       => STM.none
    }

  /**
   * Determine if the array contains a specified value.
   */
  def contains(a: A): USTM[Boolean] = exists(_ == a)

  /**
   * Count the values in the array matching a predicate.
   */
  def count(p: A => Boolean): USTM[Int] =
    fold(0)((n, a) => if (p(a)) n + 1 else n)

  /**
   * Count the values in the array matching a transactional predicate.
   */
  def countSTM[E](p: A => STM[E, Boolean]): STM[E, Int] =
    foldSTM(0)((n, a) => p(a).map(result => if (result) n + 1 else n))

  /**
   * Determine if the array contains a value satisfying a predicate.
   */
  def exists(p: A => Boolean): USTM[Boolean] = find(p).map(_.isDefined)

  /**
   * Determine if the array contains a value satisfying a transactional
   * predicate.
   */
  def existsSTM[E](p: A => STM[E, Boolean]): STM[E, Boolean] =
    countSTM(p).map(_ > 0)

  /**
   * Find the first element in the array matching a predicate.
   */
  def find(p: A => Boolean): USTM[Option[A]] =
    ZSTM.Effect { (journal, _, _) =>
      var i   = 0
      var res = Option.empty[A]

      while (res.isEmpty && i < array.length) {
        val a = array(i).unsafeGet(journal)

        if (p(a))
          res = Some(a)

        i += 1
      }

      res
    }

  /**
   * Find the last element in the array matching a predicate.
   */
  def findLast(p: A => Boolean): USTM[Option[A]] =
    ZSTM.Effect { (journal, _, _) =>
      var i   = array.length - 1
      var res = Option.empty[A]

      while (res.isEmpty && i >= 0) {
        val a = array(i).unsafeGet(journal)

        if (p(a))
          res = Some(a)

        i -= 1
      }

      res
    }

  /**
   * Find the last element in the array matching a transactional predicate.
   */
  def findLastSTM[E](p: A => STM[E, Boolean]): STM[E, Option[A]] = {
    val init = (Option.empty[A], array.length - 1)
    val cont = (s: (Option[A], Int)) => s._1.isEmpty && s._2 >= 0

    ZSTM
      .iterate(init)(cont) { s =>
        val idx = s._2
        array(idx).get.flatMap(a => p(a).map(ok => (if (ok) Some(a) else None, idx - 1)))
      }
      .map(_._1)
  }

  /**
   * Find the first element in the array matching a transactional predicate.
   */
  def findSTM[E](p: A => STM[E, Boolean]): STM[E, Option[A]] = {
    val init = (Option.empty[A], 0)
    val cont = (s: (Option[A], Int)) => s._1.isEmpty && s._2 < array.length

    ZSTM
      .iterate(init)(cont) { s =>
        val idx = s._2
        array(idx).get.flatMap(a => p(a).map(ok => (if (ok) Some(a) else None, idx + 1)))
      }
      .map(_._1)
  }

  /**
   * The first entry of the array, if it exists.
   */
  def firstOption: USTM[Option[A]] =
    if (array.isEmpty) STM.succeedNow(None) else array.head.get.map(Some(_))

  /**
   * Atomically folds using a pure function.
   */
  def fold[Z](zero: Z)(op: (Z, A) => Z): USTM[Z] =
    ZSTM.Effect { (journal, _, _) =>
      var res = zero
      var i   = 0

      while (i < array.length) {
        val value = array(i).unsafeGet(journal)
        res = op(res, value)
        i += 1
      }

      res
    }

  /**
   * Atomically folds using a transactional function.
   */
  def foldSTM[E, Z](zero: Z)(op: (Z, A) => STM[E, Z]): STM[E, Z] =
    toChunk.flatMap(STM.foldLeft(_)(zero)(op))

  /**
   * Atomically evaluate the conjunction of a predicate across the members of
   * the array.
   */
  def forall(p: A => Boolean): USTM[Boolean] = exists(a => !p(a)).map(!_)

  /**
   * Atomically evaluate the conjunction of a transactional predicate across the
   * members of the array.
   */
  def forallSTM[E](p: A => STM[E, Boolean]): STM[E, Boolean] =
    countSTM(p).map(_ == array.length)

  /**
   * Atomically performs transactional effect for each item in array.
   */
  def foreach[E](f: A => STM[E, Unit]): STM[E, Unit] =
    foldSTM(())((_, a) => f(a))

  /**
   * Get the first index of a specific value in the array or -1 if it does not
   * occur.
   */
  def indexOf(a: A): USTM[Int] = indexOf(a, 0)

  /**
   * Get the first index of a specific value in the array, starting at a
   * specific index, or -1 if it does not occur.
   */
  def indexOf(a: A, from: Int): USTM[Int] = indexWhere(_ == a, from)

  /**
   * Get the index of the first entry in the array matching a predicate.
   */
  def indexWhere(p: A => Boolean): USTM[Int] = indexWhere(p, 0)

  /**
   * Get the index of the first entry in the array, starting at a specific
   * index, matching a predicate.
   */
  def indexWhere(p: A => Boolean, from: Int): USTM[Int] =
    if (from < 0)
      STM.succeedNow(-1)
    else
      ZSTM.Effect { (journal, _, _) =>
        var i     = from
        var found = false

        while (!found && i < array.length) {
          val a = array(i).unsafeGet(journal)
          found = p(a)
          i += 1
        }

        if (found) i - 1 else -1
      }

  /**
   * Get the index of the first entry in the array matching a transactional
   * predicate.
   */
  def indexWhereSTM[E](p: A => STM[E, Boolean]): STM[E, Int] =
    indexWhereSTM(p, 0)

  /**
   * Starting at specified index, get the index of the next entry that matches a
   * transactional predicate.
   */
  def indexWhereSTM[E](p: A => STM[E, Boolean], from: Int): STM[E, Int] = {
    def forIndex(i: Int): STM[E, Int] =
      if (i < array.length)
        array(i).get.flatMap(p).flatMap(ok => if (ok) STM.succeedNow(i) else forIndex(i + 1))
      else
        STM.succeedNow(-1)

    if (from >= 0) forIndex(from) else STM.succeedNow(-1)
  }

  /**
   * Get the last index of a specific value in the array or -1 if it does not
   * occur.
   */
  def lastIndexOf(a: A): USTM[Int] =
    if (array.isEmpty) STM.succeedNow(-1) else lastIndexOf(a, array.length - 1)

  /**
   * Get the first index of a specific value in the array, bounded above by a
   * specific index, or -1 if it does not occur.
   */
  def lastIndexOf(a: A, end: Int): USTM[Int] =
    if (end >= array.length)
      STM.succeedNow(-1)
    else
      ZSTM.Effect { (journal, _, _) =>
        var i     = end
        var found = false

        while (!found && i >= 0) {
          found = array(i).unsafeGet(journal) == a
          i -= 1
        }

        if (found) i + 1 else -1
      }

  /**
   * The last entry in the array, if it exists.
   */
  def lastOption: USTM[Option[A]] =
    if (array.isEmpty) STM.succeedNow(None) else array.last.get.map(Some(_))

  /**
   * Atomically compute the greatest element in the array, if it exists.
   */
  def maxOption(implicit ord: Ordering[A]): USTM[Option[A]] =
    reduceOption((acc, a) => if (ord.gt(a, acc)) a else acc)

  /**
   * Atomically compute the least element in the array, if it exists.
   */
  def minOption(implicit ord: Ordering[A]): USTM[Option[A]] =
    reduceOption((acc, a) => if (ord.lt(a, acc)) a else acc)

  /**
   * Atomically reduce the array, if non-empty, by a binary operator.
   */
  def reduceOption(op: (A, A) => A): USTM[Option[A]] =
    ZSTM.Effect { (journal, _, _) =>
      var i   = 0
      var res = null.asInstanceOf[A]

      while (i < array.length) {
        val a = array(i).unsafeGet(journal)

        res = res match {
          case null => a
          case v    => op(v, a)
        }

        i += 1
      }

      Option(res)
    }

  /**
   * Atomically reduce the non-empty array using a transactional binary
   * operator.
   */
  def reduceOptionSTM[E](op: (A, A) => STM[E, A]): STM[E, Option[A]] =
    foldSTM(Option.empty[A]) { (acc, a) =>
      acc match {
        case Some(acc) => op(acc, a).map(Some(_))
        case _         => STM.some(a)
      }
    }

  /**
   * Returns the size of the array.
   */
  def size: Int =
    array.size

  /**
   * Collects all elements into a list.
   */
  def toList: USTM[List[A]] =
    STM.foreach(array.toList)(_.get)

  /**
   * Collects all elements into a chunk.
   */
  def toChunk: USTM[Chunk[A]] =
    STM.foreach(Chunk.fromArray(array))(_.get)

  /**
   * Atomically updates all elements using a pure function.
   */
  def transform(f: A => A): USTM[Unit] =
    ZSTM.Effect { (journal, _, _) =>
      var i = 0

      while (i < array.length) {
        val current = array(i).unsafeGet(journal)
        array(i).unsafeSet(journal, f(current))
        i += 1
      }

      ()
    }

  /**
   * Atomically updates all elements using a transactional effect.
   */
  def transformSTM[E](f: A => STM[E, A]): STM[E, Unit] =
    STM.foreach(Chunk.fromArray(array))(_.get.flatMap(f)).flatMap { newData =>
      ZSTM.Effect { (journal, _, _) =>
        var i  = 0
        val it = newData.iterator

        while (it.hasNext) {
          array(i).unsafeSet(journal, it.next())
          i += 1
        }

        ()
      }
    }

  /**
   * Updates element in the array with given function.
   */
  def update(index: Int, fn: A => A): USTM[Unit] =
    if (0 <= index && index < array.length)
      array(index).update(fn)
    else
      STM.die(new ArrayIndexOutOfBoundsException(index))

  /**
   * Atomically updates element in the array with given transactional effect.
   */
  def updateSTM[E](index: Int, fn: A => STM[E, A]): STM[E, Unit] =
    if (0 <= index && index < array.length)
      for {
        currentVal <- array(index).get
        newVal     <- fn(currentVal)
        _          <- array(index).set(newVal)
      } yield ()
    else STM.die(new ArrayIndexOutOfBoundsException(index))
}

object TArray {

  /**
   * Makes a new `TArray` that is initialized with specified values.
   */
  def make[A](data: A*): USTM[TArray[A]] =
    STM.succeed(unsafe.make(data: _*)(Unsafe.unsafe))

  object unsafe {
    def make[A](data: A*)(implicit unsafe: Unsafe): TArray[A] =
      new TArray(data.map(TRef.unsafeMake(_)).toArray)
  }

  /**
   * Makes an empty `TArray`.
   */
  def empty[A]: USTM[TArray[A]] =
    fromIterable(Nil)

  /**
   * Makes a new `TArray` initialized with provided iterable.
   */
  def fromIterable[A](data: => Iterable[A]): USTM[TArray[A]] =
    make(data.toSeq: _*)
}
