/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.generic.{CanBuildFrom, GenericCompanion, GenericTraversableTemplate}
import scala.collection.immutable.IndexedSeq
import scala.collection.{GenTraversableOnce, IndexedSeqLike}
import scala.reflect.ClassTag

/**
 * `ChunkLike` represents the capability for a `Chunk` to extend Scala's
 * collection library. Because of changes to Scala's collection library in 2.13,
 * separate versions of this trait are implemented for 2.12 and 2.13 / Dotty.
 * This allows code in `Chunk` to be written without concern for the
 * implementation details of Scala's collection library to the maximum extent
 * possible.
 *
 * Note that `IndexedSeq` is not a referentially transparent interface in that
 * it exposes methods that are partial (e.g. `apply`), allocate mutable state
 * (e.g. `iterator`), or are purely side effecting (e.g. `foreach`). `Chunk`
 * extends `IndexedSeq` to provide interoperability with Scala's collection
 * library but users should avoid these methods whenever possible.
 */
private[zio] trait ChunkLike[+A]
    extends IndexedSeq[A]
    with IndexedSeqLike[A, Chunk[A]]
    with GenericTraversableTemplate[A, Chunk] { self: Chunk[A] =>

  override final def :+[A1 >: A, That](a1: A1)(implicit bf: CanBuildFrom[Chunk[A], A1, That]): That =
    if (isChunkCanBuildFrom[A, A1, That](bf)) append(a1).asInstanceOf[That]
    else super.+:(a1)

  override final def +:[A1 >: A, That](a1: A1)(implicit bf: CanBuildFrom[Chunk[A], A1, That]): That =
    if (isChunkCanBuildFrom[A, A1, That](bf)) prepend(a1).asInstanceOf[That]
    else super.:+(a1)

  override def copyToArray[A1 >: A](dest: Array[A1], destPos: Int, length: Int): Unit = {
    val n = math.max(math.min(math.min(length, self.length), dest.length - destPos), 0)
    if (n > 0) {
      toArray(0, dest, destPos, n)
    }
  }

  /**
   * Returns a filtered, mapped subset of the elements of this chunk.
   */
  override final def collect[B, That](pf: PartialFunction[A, B])(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    if (isChunkCanBuildFrom[A, B, That](bf)) collectChunk(pf).asInstanceOf[That]
    else super.collect(pf)

  override def companion: GenericCompanion[Chunk] =
    Chunk

  /**
   * Returns the concatenation of mapping every element into a new chunk using
   * the specified function.
   */
  override final def flatMap[B, That](
    f: A => GenTraversableOnce[B]
  )(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    if (isChunkCanBuildFrom[A, B, That](bf)) flatMapChunk(f).asInstanceOf[That]
    else super.flatMap(f)

  /**
   * Flattens a chunk of chunks into a single chunk by concatenating all chunks.
   */
  override def flatten[B](implicit ev: A => GenTraversableOnce[B]): Chunk[B] =
    flatMap(ev(_))

  /**
   * Returns the first index for which the given predicate is satisfied.
   */
  override final def indexWhere(f: A => Boolean): Int =
    indexWhere(f, 0)

  /**
   * Returns a chunk with the elements mapped by the specified function.
   */
  override final def map[B, That](f: A => B)(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    if (isChunkCanBuildFrom[A, B, That](bf)) mapChunk(f).asInstanceOf[That]
    else super.map(f)

  /**
   * Generates a readable string representation of this chunk using the
   * specified start, separator, and end strings.
   */
  override final def mkString(start: String, sep: String, end: String): String = {
    val iterator = self.chunkIterator
    var index    = 0
    val builder  = new scala.collection.mutable.StringBuilder()
    builder.sizeHint(length)
    builder.append(start)
    var started = false
    while (iterator.hasNextAt(index)) {
      val a = iterator.nextAt(index)
      index += 1
      if (started) {
        builder.append(sep)
      } else {
        started = true
      }
      builder.append(a.toString)
    }
    builder.append(end)

    builder.toString
  }

  /**
   * Generates a readable string representation of this chunk using the
   * specified separator string.
   */
  override final def mkString(sep: String): String =
    mkString("", sep, "")

  /**
   * Generates a readable string representation of this chunk.
   */
  override final def mkString: String =
    mkString("")

  /**
   * Determines if the chunk is not empty.
   */
  override final def nonEmpty: Boolean =
    length > 0

  /**
   * Partitions the elements of this chunk into two chunks using the specified
   * function.
   */
  def partitionMap[B, C](f: A => Either[B, C]): (Chunk[B], Chunk[C])

  /**
   * The number of elements in the chunk.
   */
  override final def size: Int =
    length

  override final def updated[B >: A, That](index: Int, elem: B)(implicit
    bf: scala.collection.generic.CanBuildFrom[zio.Chunk[A], B, That]
  ): That =
    if (isChunkCanBuildFrom[A, B, That](bf)) update(index, elem).asInstanceOf[That]
    else super.updated(index, elem)

  /**
   * The implementation of `flatMap` for `Chunk`.
   */
  protected final def flatMapChunk[B, That](f: A => GenTraversableOnce[B]): Chunk[B] = {
    val iterator               = self.chunkIterator
    var index                  = 0
    var chunks: List[Chunk[B]] = Nil
    var total                  = 0
    var B0: ClassTag[B]        = null.asInstanceOf[ClassTag[B]]
    while (iterator.hasNextAt(index)) {
      val a = iterator.nextAt(index)
      index += 1
      val bs    = f(a)
      val chunk = ChunkLike.fromGenTraversableOnce(bs)
      if (chunk.length > 0) {
        if (B0 eq null) {
          B0 = Chunk.classTagOf(chunk)
        }
        chunks ::= chunk
        total += chunk.length
      }
    }
    if (B0 eq null) Chunk.empty
    else {
      implicit val B: ClassTag[B] = B0
      val dest: Array[B]          = Array.ofDim(total)
      val it                      = chunks.iterator
      var n                       = total
      while (it.hasNext) {
        val chunk = it.next
        n -= chunk.length
        chunk.toArray(n, dest)
      }
      Chunk.fromArray(dest)
    }
  }

  override def sorted[A1 >: A](implicit ord: Ordering[A1]): Chunk[A] = {
    implicit val classTag = Chunk.classTagOf(self)
    (ord, classTag) match {
      case (Ordering.Byte, ClassTag.Byte) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Byte]])
        Chunk.fromArray(array).asInstanceOf[Chunk[A]]
      case (Ordering.Char, ClassTag.Char) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Char]])
        Chunk.fromArray(array)
      case (Ordering.Double, ClassTag.Double) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Double]])
        Chunk.fromArray(array)
      case (Ordering.Float, ClassTag.Float) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Float]])
        Chunk.fromArray(array)
      case (Ordering.Int, ClassTag.Int) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Int]])
        Chunk.fromArray(array)
      case (Ordering.Long, ClassTag.Long) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Long]])
        Chunk.fromArray(array)
      case (Ordering.Short, ClassTag.Short) =>
        val array = self.toArray
        java.util.Arrays.sort(array.asInstanceOf[Array[Short]])
        Chunk.fromArray(array)
      case _ =>
        val array = self.toArray[Any]
        java.util.Arrays.sort(array.asInstanceOf[Array[AnyRef]], ord.asInstanceOf[Ordering[AnyRef]])
        Chunk.fromArray(array).asInstanceOf[Chunk[A]]
    }
  }

  /**
   * Zips this chunk with the index of every element.
   */
  final def zipWithIndex: Chunk[(A, Int)] =
    zipWithIndexFrom(0)

  /**
   * Constructs a new `ChunkBuilder`. This operation allocates mutable state and
   * is not referentially transparent. It is provided for compatibility with
   * Scala's collection library and should not be used for other purposes.
   */
  override protected[this] def newBuilder: ChunkBuilder[A] =
    ChunkBuilder.make()

  /**
   * Returns whether the specified `CanBuildFrom` is a `ChunkCanBuildFrom` or a
   * `CanBuildFrom` of one of the supertypes of `Chunk`.
   */
  @inline
  private[this] def isChunkCanBuildFrom[A, B, That](bf: CanBuildFrom[Chunk[A], B, That]): Boolean =
    bf.isInstanceOf[
      ChunkCanBuildFrom[_]
    ] || (bf eq IndexedSeq.ReusableCBF) || (bf eq collection.immutable.Seq.ReusableCBF) || (bf eq collection.Seq.ReusableCBF)
}

object ChunkLike {

  /**
   * Provides implicit evidence that that a collection of type `Chunk[A]` can be
   * build from elements of type `A`.
   */
  implicit def chunkCanBuildFrom[A](implicit bf: ChunkCanBuildFrom[A]): ChunkCanBuildFrom[A] =
    bf

  /**
   * Constructs a `Chunk` from a collection that may potentially only be
   * traversed once.
   */
  private def fromGenTraversableOnce[A](as: GenTraversableOnce[A]): Chunk[A] =
    as match {
      case iterable: Iterable[A] => Chunk.fromIterable(iterable)
      case iterableOnce =>
        val chunkBuilder = ChunkBuilder.make[A]()
        iterableOnce.foreach(chunkBuilder += _)
        chunkBuilder.result()
    }
}
