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

package zio

import zio.NonEmptyChunk._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.language.implicitConversions

/**
 * A `NonEmptyChunk` is a `Chunk` that is guaranteed to contain at least one
 * element. As a result, operations which would not be safe when performed on
 * `Chunk`, such as `head` or `reduce`, are safe when performed on
 * `NonEmptyChunk`. Operations on `NonEmptyChunk` which could potentially return
 * an empty chunk will return a `Chunk` instead.
 */
final class NonEmptyChunk[+A] private (private val chunk: Chunk[A]) extends Serializable { self =>

  /**
   * A symbolic alias for `prepended`.
   */
  def +:[A1 >: A](a: A1): NonEmptyChunk[A1] =
    prepended(a)

  /**
   * A symbolic alias for `appended`.
   */
  def :+[A1 >: A](a: A1): NonEmptyChunk[A1] =
    appended(a)

  /**
   * Appends the specified `Chunk` to the end of this `NonEmptyChunk`.
   */
  def ++[A1 >: A](that: Chunk[A1]): NonEmptyChunk[A1] =
    append(that)

  /**
   * A named alias for `++`.
   */
  def append[A1 >: A](that: Chunk[A1]): NonEmptyChunk[A1] =
    nonEmpty(chunk ++ that)

  /**
   * Appends a single element to the end of this `NonEmptyChunk`.
   */
  def appended[A1 >: A](a: A1): NonEmptyChunk[A1] =
    nonEmpty(chunk :+ a)

  /**
   * Converts this `NonEmptyChunk` of ints to a `NonEmptyChunk` of bits.
   */
  def asBitsInt(endianness: Chunk.BitChunk.Endianness)(implicit ev: A <:< Int): NonEmptyChunk[Boolean] =
    nonEmpty(chunk.asBitsInt(endianness))

  /**
   * Converts this `NonEmptyChunk` of longs to a `NonEmptyChunk` of bits.
   */
  def asBitsLong(endianness: Chunk.BitChunk.Endianness)(implicit ev: A <:< Long): NonEmptyChunk[Boolean] =
    nonEmpty(chunk.asBitsLong(endianness))

  /**
   * Converts this `NonEmptyChunk` of bytes to a `NonEmptyChunk` of bits.
   */
  def asBits(implicit ev: A <:< Byte): NonEmptyChunk[Boolean] =
    nonEmpty(chunk.asBitsByte)

  /**
   * Returns whether this `NonEmptyChunk` and the specified `NonEmptyChunk` are
   * equal to each other.
   */
  override def equals(that: Any): Boolean =
    that match {
      case that: NonEmptyChunk[_] => self.chunk == that.chunk
      case _                      => false
    }

  /**
   * Maps each element of this `NonEmptyChunk` to a new `NonEmptyChunk` and then
   * concatenates them together.
   */
  def flatMap[B](f: A => NonEmptyChunk[B]): NonEmptyChunk[B] =
    nonEmpty(chunk.flatMap(a => f(a).chunk))

  /**
   * Flattens a `NonEmptyChunk` of `NonEmptyChunk` values to a single
   * `NonEmptyChunk`.
   */
  def flatten[B](implicit ev: A <:< NonEmptyChunk[B]): NonEmptyChunk[B] =
    flatMap(ev)

  /**
   * Groups the values in this `NonEmptyChunk` using the specified function.
   */
  def groupBy[K](f: A => K): Map[K, NonEmptyChunk[A]] =
    groupMap(f)(identity)

  /**
   * Groups and transformers the values in this `NonEmptyChunk` using the
   * specified function.
   */
  def groupMap[K, V](key: A => K)(f: A => V): Map[K, NonEmptyChunk[V]] = {
    val m = collection.mutable.Map.empty[K, ChunkBuilder[V]]
    for (elem <- chunk) {
      val k       = key(elem)
      val builder = m.getOrElseUpdate(k, ChunkBuilder.make[V]())
      builder += f(elem)
    }
    var result = collection.immutable.Map.empty[K, NonEmptyChunk[V]]
    m.foreach { case (k, v) =>
      result = result + ((k, nonEmpty(v.result())))
    }
    result
  }

  /**
   * Returns the hashcode of this `NonEmptyChunk`.
   */
  override def hashCode: Int =
    chunk.hashCode

  /**
   * Transforms the elements of this `NonEmptyChunk` with the specified
   * function.
   */
  def map[B](f: A => B): NonEmptyChunk[B] =
    nonEmpty(chunk.map(f))

  /**
   * Maps over the elements of this `NonEmptyChunk`, maintaining some state
   * along the way.
   */
  def mapAccum[S, B](s: S)(f: (S, A) => (S, B)): (S, NonEmptyChunk[B]) =
    chunk.mapAccum(s)(f) match { case (s, chunk) => (s, nonEmpty(chunk)) }

  /**
   * Effectfully maps over the elements of this `NonEmptyChunk`, maintaining
   * some state along the way.
   */
  def mapAccumZIO[R, E, S, B](s: S)(f: (S, A) => ZIO[R, E, (S, B)])(implicit
    trace: Trace
  ): ZIO[R, E, (S, NonEmptyChunk[B])] =
    chunk.mapAccumZIO(s)(f).map { case (s, chunk) => (s, nonEmpty(chunk)) }

  /**
   * Effectfully maps the elements of this `NonEmptyChunk`.
   */
  def mapZIO[R, E, B](f: A => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, NonEmptyChunk[B]] =
    chunk.mapZIO(f).map(nonEmpty)

  /**
   * Effectfully maps the elements of this `NonEmptyChunk` in parallel.
   */
  def mapZIOPar[R, E, B](f: A => ZIO[R, E, B])(implicit trace: Trace): ZIO[R, E, NonEmptyChunk[B]] =
    chunk.mapZIOPar(f).map(nonEmpty)

  /**
   * Materialize the elements of this `NonEmptyChunk` into a `NonEmptyChunk`
   * backed by an array.
   */
  def materialize[A1 >: A]: NonEmptyChunk[A1] =
    nonEmpty(chunk.materialize)

  /**
   * Prepends the specified `Chunk` to the beginning of this `NonEmptyChunk`.
   */
  def prepend[A1 >: A](that: Chunk[A1]): NonEmptyChunk[A1] =
    nonEmpty(that ++ chunk)

  /**
   * Prepends a single element to the beginning of this `NonEmptyChunk`.
   */
  def prepended[A1 >: A](a: A1): NonEmptyChunk[A1] =
    nonEmpty(a +: chunk)

  /**
   * Reduces the elements of this `NonEmptyChunk` from left to right using the
   * function `map` to transform the first value to the type `B` and then the
   * function `reduce` to combine the `B` value with each other `A` value.
   */
  def reduceMapLeft[B](map: A => B)(reduce: (B, A) => B): B = {
    val iterator = chunk.iterator
    var b: B     = null.asInstanceOf[B]
    while (iterator.hasNext) {
      val a = iterator.next()
      if (b == null) b = map(a) else b = reduce(b, a)
    }
    b
  }

  /**
   * Reduces the elements of this `NonEmptyChunk` from right to left using the
   * function `map` to transform the first value to the type `B` and then the
   * function `reduce` to combine the `B` value with each other `A` value.
   */
  def reduceMapRight[B](map: A => B)(reduce: (A, B) => B): B = {
    val iterator = chunk.reverseIterator
    var b: B     = null.asInstanceOf[B]
    while (iterator.hasNext) {
      val a = iterator.next()
      if (b == null) b = map(a) else b = reduce(a, b)
    }
    b
  }

  /**
   * Converts this `NonEmptyChunk` to a `Chunk`, discarding information about it
   * not being empty.
   */
  def toChunk: Chunk[A] =
    chunk

  /**
   * Converts this `NonEmptyChunk` to the `::` case of a `List`.
   */
  def toCons[A1 >: A]: ::[A1] =
    ::(chunk(0), chunk.drop(1).toList)

  /**
   * Renders this `NonEmptyChunk` as a `String`.
   */
  override def toString: String =
    chunk.mkString("NonEmptyChunk(", ", ", ")")

  /**
   * Zips this `NonEmptyChunk` with the specified `NonEmptyChunk`, only keeping
   * as many elements as are in the smaller chunk.
   */
  def zip[B](that: NonEmptyChunk[B])(implicit zippable: Zippable[A, B]): NonEmptyChunk[zippable.Out] =
    zipWith(that)(zippable.zip(_, _))

  /**
   * Zips this `NonEmptyChunk` with the specified `Chunk`, using `None` to "fill
   * in" missing values if one chunk has fewer elements than the other.
   */
  def zipAll[B](that: Chunk[B]): NonEmptyChunk[(Option[A], Option[B])] =
    zipAllWith(that)(a => (Some(a), None), b => (None, Some(b)))((a, b) => (Some(a), Some(b)))

  /**
   * Zips this `NonEmptyChunk` with the specified `Chunk`, using the specified
   * functions to "fill in" missing values if one chunk has fewer elements than
   * the other.
   */
  def zipAllWith[B, C](
    that: Chunk[B]
  )(left: A => C, right: B => C)(both: (A, B) => C): NonEmptyChunk[C] =
    nonEmpty(chunk.zipAllWith(that)(left, right)(both))

  /**
   * Zips this `NonEmptyChunk` with the specified `NonEmptyChunk`, only keeping
   * as many elements as are in the smaller chunk.
   */
  def zipWith[B, C](that: NonEmptyChunk[B])(f: (A, B) => C): NonEmptyChunk[C] =
    nonEmpty(chunk.zipWith(that.chunk)(f))

  /**
   * Annotates each element of this `NonEmptyChunk` with its index.
   */
  def zipWithIndex: NonEmptyChunk[(A, Int)] =
    nonEmpty(chunk.zipWithIndex)

  /**
   * Annotates each element of this `NonEmptyChunk` with its index, with the
   * specified offset.
   */
  final def zipWithIndexFrom(indexOffset: Int): NonEmptyChunk[(A, Int)] =
    nonEmpty(chunk.zipWithIndexFrom(indexOffset))
}

object NonEmptyChunk {

  /**
   * Constructs a `NonEmptyChunk` from one or more values.
   */
  def apply[A](a: A, as: A*): NonEmptyChunk[A] =
    fromIterable(a, as)

  /**
   * Checks if a `chunk` is not empty and constructs a `NonEmptyChunk` from it.
   */
  def fromChunk[A](chunk: Chunk[A]): Option[NonEmptyChunk[A]] =
    chunk.nonEmptyOrElse[Option[NonEmptyChunk[A]]](None)(Some(_))

  /**
   * Constructs a `NonEmptyChunk` from the `::` case of a `List`.
   */
  def fromCons[A](as: ::[A]): NonEmptyChunk[A] =
    as match { case h :: t => fromIterable(h, t) }

  /**
   * Constructs a `NonEmptyChunk` from an `Iterable`.
   */
  def fromIterable[A](a: A, as: Iterable[A]): NonEmptyChunk[A] =
    if (as.isEmpty) single(a)
    else
      nonEmpty {
        val builder = ChunkBuilder.make[A]()
        builder.sizeHint(as, 1)
        builder += a
        builder ++= as
        builder.result()
      }

  /**
   * Constructs a `NonEmptyChunk` from an `Iterable` or `None` otherwise.
   */
  def fromIterableOption[A](as: Iterable[A]): Option[NonEmptyChunk[A]] =
    if (as.isEmpty) None else Some(nonEmpty(Chunk.fromIterable(as)))

  /**
   * Constructs a `NonEmptyChunk` from a single value.
   */
  def single[A](a: A): NonEmptyChunk[A] =
    nonEmpty(Chunk.single(a))

  /**
   * Extracts the elements from a `Chunk`.
   */
  def unapplySeq[A](seq: Seq[A]): Option[Seq[A]] =
    seq match {
      case chunk: Chunk[A] if chunk.nonEmpty => Some(chunk)
      case _                                 => None
    }

  /**
   * Extracts the elements from a `NonEmptyChunk`.
   */
  def unapplySeq[A](nonEmptyChunk: NonEmptyChunk[A]): Some[Seq[A]] =
    Some(nonEmptyChunk.chunk)

  /**
   * The unit non-empty chunk.
   */
  val unit: NonEmptyChunk[Unit] = single(())

  /**
   * Provides an implicit conversion from `NonEmptyChunk` to `Chunk` for methods
   * that may not return a `NonEmptyChunk`.
   */
  implicit def toChunk[A](nonEmptyChunk: NonEmptyChunk[A]): Chunk[A] =
    nonEmptyChunk.chunk

  /**
   * Constructs a `NonEmptyChunk` from a `Chunk`. This should only be used when
   * it is statically known that the `Chunk` must have at least one element.
   */
  private[zio] def nonEmpty[A](chunk: Chunk[A]): NonEmptyChunk[A] =
    new NonEmptyChunk(chunk)
}
