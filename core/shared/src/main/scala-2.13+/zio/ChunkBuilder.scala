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
sealed abstract class ChunkBuilder[A] extends Builder[A, Chunk[A]]

object ChunkBuilder {

  /**
   * Constructs a generic `ChunkBuilder`.
   */
  def make[A](): ChunkBuilder[A] =
    new ChunkBuilder[A] {
      var arrayBuilder: ArrayBuilder[A] = null
      var size: SInt                    = -1
      def addOne(a: A): this.type = {
        if (arrayBuilder eq null) {
          implicit val tag = Chunk.Tags.fromValue(a)
          arrayBuilder = ArrayBuilder.make
          if (size != -1) {
            arrayBuilder.sizeHint(size)
          }
        }
        try {
          arrayBuilder.addOne(a)
        } catch {
          case _: ClassCastException =>
            val as = arrayBuilder.result()
            arrayBuilder = ArrayBuilder.make[AnyRef].asInstanceOf[ArrayBuilder[A]]
            if (size != -1) {
              arrayBuilder.sizeHint(size)
            }
            arrayBuilder.addAll(as)
            arrayBuilder.addOne(a)
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
          Chunk.fromArray(arrayBuilder.result())
        }
      override def sizeHint(n: SInt): Unit =
        if (arrayBuilder eq null) {
          size = n
        } else {
          arrayBuilder.sizeHint(n)
        }
      override def knownSize: SInt =
        if (arrayBuilder eq null) {
          -1
        } else {
          arrayBuilder.knownSize
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

    override def addAll(as: IterableOnce[SBoolean]): this.type = {
      as.iterator.foreach(addOne _)
      this
    }
    def addOne(b: SBoolean): this.type = {
      if (b) {
        if (maxBitIndex == 8) {
          arrayBuilder.addOne(lastByte)
          lastByte = (1 << 7).toByte
          maxBitIndex = 1
        } else {
          val bitIndex = 7 - maxBitIndex
          lastByte = (lastByte | (1 << bitIndex)).toByte
          maxBitIndex = maxBitIndex + 1
        }
      } else {
        if (maxBitIndex == 8) {
          arrayBuilder.addOne(lastByte)
          lastByte = 0.toByte
          maxBitIndex = 1
        } else {
          maxBitIndex = maxBitIndex + 1
        }
      }
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize * 8 + maxBitIndex
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Byte` values.
   */
  final class Byte extends ChunkBuilder[SByte] { self =>
    private val arrayBuilder: ArrayBuilder.ofByte =
      new ArrayBuilder.ofByte
    override def addAll(as: IterableOnce[SByte]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SByte): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Char` values.
   */
  final class Char extends ChunkBuilder[SChar] { self =>
    private val arrayBuilder: ArrayBuilder.ofChar =
      new ArrayBuilder.ofChar
    override def addAll(as: IterableOnce[SChar]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SChar): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Double`
   * values.
   */
  final class Double extends ChunkBuilder[SDouble] { self =>
    private val arrayBuilder: ArrayBuilder.ofDouble =
      new ArrayBuilder.ofDouble
    override def addAll(as: IterableOnce[SDouble]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SDouble): this.type = {
      arrayBuilder.addOne(a)
      this
    }
    def clear(): Unit =
      arrayBuilder.clear()
    override def equals(that: Any): SBoolean =
      that match {
        case that: Double => self.arrayBuilder == that.arrayBuilder
        case _            => false
      }
    def result(): Chunk[SDouble] =
      Chunk.fromArray(arrayBuilder.result())
    override def sizeHint(n: SInt): Unit =
      arrayBuilder.sizeHint(n)
    override def toString: String =
      "ChunkBuilder.Double"
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Float` values.
   */
  final class Float extends ChunkBuilder[SFloat] { self =>
    private val arrayBuilder: ArrayBuilder.ofFloat =
      new ArrayBuilder.ofFloat
    override def addAll(as: IterableOnce[SFloat]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SFloat): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Int` values.
   */
  final class Int extends ChunkBuilder[SInt] { self =>
    private val arrayBuilder: ArrayBuilder.ofInt =
      new ArrayBuilder.ofInt
    override def addAll(as: IterableOnce[SInt]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SInt): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Long` values.
   */
  final class Long extends ChunkBuilder[SLong] { self =>
    private val arrayBuilder: ArrayBuilder.ofLong =
      new ArrayBuilder.ofLong
    override def addAll(as: IterableOnce[SLong]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SLong): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }

  /**
   * A `ChunkBuilder` specialized for building chunks of unboxed `Short` values.
   */
  final class Short extends ChunkBuilder[SShort] { self =>
    private val arrayBuilder: ArrayBuilder.ofShort =
      new ArrayBuilder.ofShort
    override def addAll(as: IterableOnce[SShort]): this.type = {
      arrayBuilder.addAll(as)
      this
    }
    def addOne(a: SShort): this.type = {
      arrayBuilder.addOne(a)
      this
    }
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
    override def knownSize: SInt = arrayBuilder.knownSize
  }
}
