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

import zio.Chunk.BitChunkByte
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.mutable.{ArrayBuilder, Builder}
import scala.{
  Boolean => SBoolean,
  Byte => SByte,
  Char => SChar,
  Double => SDouble,
  Float => SFloat,
  Int => SInt,
  Long => SLong,
  Short => SShort
}

/**
 * A `ChunkBuilder[A]` can build a `Chunk[A]` given elements of type `A`.
 * `ChunkBuilder` is a mutable data structure that is implemented to efficiently
 * build chunks of unboxed primitives and for compatibility with the Scala
 * collection library.
 */
sealed abstract class ChunkBuilder[A] extends Builder[A, Chunk[A]] { self =>
  def addAll(as: TraversableOnce[A]): this.type =
    self ++= as
  def addOne(a: A): this.type =
    self += a
}

object ChunkBuilder {

  /**
   * Constructs a generic `ChunkBuilder`.
   */
  def make[A](): ChunkBuilder[A] =
    new ChunkBuilder[A] {
      var arrayBuilder: ArrayBuilder[A] = null
      var size: SInt                    = -1
      def +=(a: A): this.type = {
        if (arrayBuilder eq null) {
          implicit val tag = Chunk.Tags.fromValue(a)
          arrayBuilder = ArrayBuilder.make()
          if (size != -1) {
            arrayBuilder.sizeHint(size)
          }
        }
        try {
          arrayBuilder += a
        } catch {
          case _: ClassCastException =>
            val as = arrayBuilder.result()
            arrayBuilder = ArrayBuilder.make[AnyRef].asInstanceOf[ArrayBuilder[A]]
            if (size != -1) {
              arrayBuilder.sizeHint(size)
            }
            arrayBuilder ++= as
            arrayBuilder += a
        }
        this
      }
      def clear(): Unit =
        if (arrayBuilder ne null) {
          arrayBuilder.clear()
        }
      def result(): Chunk[A] =
        if (arrayBuilder eq null) {
          Chunk.empty
        } else {
          Chunk.fromArray(arrayBuilder.result)
        }
      override def sizeHint(n: SInt): Unit =
        if (arrayBuilder eq null) {
          size = n
        } else {
          arrayBuilder.sizeHint(n)
        }
    }

  /**
   * Constructs a generic `ChunkBuilder` with size hint.
   */
  def make[A](sizeHint: SInt): ChunkBuilder[A] = {
    val builder = make[A]()
    builder.sizeHint(sizeHint)
    builder
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Boolean`
   * values.
   */
  final class Boolean extends ChunkBuilder[SBoolean] { self =>

    private val arrayBuilder: ArrayBuilder.ofByte =
      new ArrayBuilder.ofByte
    private var lastByte: SByte   = 0.toByte
    private var maxBitIndex: SInt = 0

    override def ++=(as: TraversableOnce[SBoolean]): this.type = {
      as.foreach(+= _)
      this
    }
    def +=(b: SBoolean): this.type = {
      if (b) {
        if (maxBitIndex == 8) {
          arrayBuilder += lastByte
          lastByte = (1 << 7).toByte
          maxBitIndex = 1
        } else {
          val bitIndex = 7 - maxBitIndex
          lastByte = (lastByte | (1 << bitIndex)).toByte
          maxBitIndex = maxBitIndex + 1
        }
      } else {
        if (maxBitIndex == 8) {
          arrayBuilder += lastByte
          lastByte = 0.toByte
          maxBitIndex = 1
        } else {
          maxBitIndex = maxBitIndex + 1
        }
      }
      this
    }
    override def addAll(as: TraversableOnce[SBoolean]): this.type =
      self ++= as
    override def addOne(a: SBoolean): this.type =
      self += a
    def clear(): Unit = {
      arrayBuilder.clear()
      maxBitIndex = 0
      lastByte = 0.toByte
    }
    override def equals(that: Any): SBoolean =
      that match {
        case that: Boolean =>
          self.arrayBuilder.equals(that.arrayBuilder) &&
          self.maxBitIndex == that.maxBitIndex &&
          self.lastByte == that.lastByte
        case _ => false
      }
    def result(): Chunk[SBoolean] = {
      val bytes: Chunk[SByte] = Chunk.fromArray(arrayBuilder.result() :+ lastByte)
      BitChunkByte(bytes, 0, 8 * (bytes.length - 1) + maxBitIndex)
    }
    override def sizeHint(n: SInt): Unit = {
      val hint = if (n == 0) 0 else n / 8 + 1
      arrayBuilder.sizeHint(hint)
    }
    override def toString: String =
      "ChunkBuilder.Boolean"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Byte` values.
   */
  final class Byte extends ChunkBuilder[SByte] { self =>
    private val arrayBuilder: ArrayBuilder.ofByte =
      new ArrayBuilder.ofByte
    override def ++=(as: TraversableOnce[SByte]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SByte): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SByte]): this.type =
      self ++= as
    override def addOne(a: SByte): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Byte => self.arrayBuilder == that.arrayBuilder
        case _          => false
      }
    def result(): Chunk[SByte] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Byte"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Char` values.
   */
  final class Char extends ChunkBuilder[SChar] { self =>
    private val arrayBuilder: ArrayBuilder.ofChar =
      new ArrayBuilder.ofChar
    override def ++=(as: TraversableOnce[SChar]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SChar): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SChar]): this.type =
      self ++= as
    override def addOne(a: SChar): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Char => self.arrayBuilder == that.arrayBuilder
        case _          => false
      }
    def result(): Chunk[SChar] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Char"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Double`
   * values.
   */
  final class Double extends ChunkBuilder[SDouble] { self =>
    private val arrayBuilder: ArrayBuilder.ofDouble =
      new ArrayBuilder.ofDouble
    override def ++=(as: TraversableOnce[SDouble]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SDouble): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SDouble]): this.type =
      self ++= as
    override def addOne(a: SDouble): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    def result(): Chunk[SDouble] =
      Chunk.fromArray(arrayBuilder.result())
    override def equals(that: Any): SBoolean =
      that match {
        case that: Double => self.arrayBuilder == that.arrayBuilder
        case _            => false
      }
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Double"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Float` values.
   */
  final class Float extends ChunkBuilder[SFloat] { self =>
    private val arrayBuilder: ArrayBuilder.ofFloat =
      new ArrayBuilder.ofFloat
    override def ++=(as: TraversableOnce[SFloat]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SFloat): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SFloat]): this.type =
      self ++= as
    override def addOne(a: SFloat): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Float => self.arrayBuilder == that.arrayBuilder
        case _           => false
      }
    def result(): Chunk[SFloat] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Float"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Int` values.
   */
  final class Int extends ChunkBuilder[SInt] { self =>
    private val arrayBuilder: ArrayBuilder.ofInt =
      new ArrayBuilder.ofInt
    override def ++=(as: TraversableOnce[SInt]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SInt): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SInt]): this.type =
      self ++= as
    override def addOne(a: SInt): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Int => self.arrayBuilder == that.arrayBuilder
        case _         => false
      }
    def result(): Chunk[SInt] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Int"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Long` values.
   */
  final class Long extends ChunkBuilder[SLong] { self =>
    private val arrayBuilder: ArrayBuilder.ofLong =
      new ArrayBuilder.ofLong
    override def ++=(as: TraversableOnce[SLong]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SLong): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SLong]): this.type =
      self ++= as
    override def addOne(a: SLong): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Long => self.arrayBuilder == that.arrayBuilder
        case _          => false
      }
    def result(): Chunk[SLong] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Long"
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Short` values.
   */
  final class Short extends ChunkBuilder[SShort] { self =>
    private val arrayBuilder: ArrayBuilder.ofShort =
      new ArrayBuilder.ofShort
    override def ++=(as: TraversableOnce[SShort]): this.type = {
      arrayBuilder ++= as
      this
    }
    def +=(a: SShort): this.type = {
      arrayBuilder += a
      this
    }
    override def addAll(as: TraversableOnce[SShort]): this.type =
      self ++= as
    override def addOne(a: SShort): this.type =
      self += a
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Short => self.arrayBuilder == that.arrayBuilder
        case _           => false
      }
    def result(): Chunk[SShort] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Short"
  }
}
