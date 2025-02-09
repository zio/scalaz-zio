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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.collection.generic.CanBuildFrom

/**
 * `ChunkCanBuildFrom` provides implicit evidence that a collection of type
 * `Chunk[A]` can be built from elements of type `A`. Since a `Chunk[A]` can be
 * built from elements of type `A` for any type `A`, this implicit evidence
 * always exists. It is used primarily to provide proof that the target type of
 * a collection operation is a `Chunk` to support high performance
 * implementations of transformation operations for chunks.
 */
sealed abstract class ChunkCanBuildFrom[A] extends CanBuildFrom[Chunk[Any], A, Chunk[A]] {
  override def apply(from: Chunk[Any]): ChunkBuilder[A]
  override def apply(): ChunkBuilder[A]
}

object ChunkCanBuildFrom {

  /**
   * Construct a new instance of `ChunkCanBuildFrom` for the specified type.
   */
  implicit def apply[A]: ChunkCanBuildFrom[A] =
    new ChunkCanBuildFrom[A] {
      def apply(from: Chunk[Any]): ChunkBuilder[A] = ChunkBuilder.make()
      def apply(): ChunkBuilder[A]                 = ChunkBuilder.make()
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Boolean`.
   */
  implicit val chunkCanBuildFromBoolean: ChunkCanBuildFrom[Boolean] =
    new ChunkCanBuildFrom[Boolean] {
      def apply(from: Chunk[Any]): ChunkBuilder[Boolean] = new ChunkBuilder.Boolean
      def apply(): ChunkBuilder[Boolean]                 = new ChunkBuilder.Boolean
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Byte`.
   */
  implicit val chunkCanBuildFromByte: ChunkCanBuildFrom[Byte] =
    new ChunkCanBuildFrom[Byte] {
      def apply(from: Chunk[Any]): ChunkBuilder[Byte] = new ChunkBuilder.Byte
      def apply(): ChunkBuilder[Byte]                 = new ChunkBuilder.Byte
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Char`.
   */
  implicit val chunkCanBuildFromChar: ChunkCanBuildFrom[Char] =
    new ChunkCanBuildFrom[Char] {
      def apply(from: Chunk[Any]): ChunkBuilder[Char] = new ChunkBuilder.Char
      def apply(): ChunkBuilder[Char]                 = new ChunkBuilder.Char
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Double`.
   */
  implicit val chunkCanBuildFromDouble: ChunkCanBuildFrom[Double] =
    new ChunkCanBuildFrom[Double] {
      def apply(from: Chunk[Any]): ChunkBuilder[Double] = new ChunkBuilder.Double
      def apply(): ChunkBuilder[Double]                 = new ChunkBuilder.Double
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Float`.
   */
  implicit val chunkCanBuildFromFloat: ChunkCanBuildFrom[Float] =
    new ChunkCanBuildFrom[Float] {
      def apply(from: Chunk[Any]): ChunkBuilder[Float] = new ChunkBuilder.Float
      def apply(): ChunkBuilder[Float]                 = new ChunkBuilder.Float
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Int`.
   */
  implicit val chunkCanBuildFromInt: ChunkCanBuildFrom[Int] =
    new ChunkCanBuildFrom[Int] {
      def apply(from: Chunk[Any]): ChunkBuilder[Int] = new ChunkBuilder.Int
      def apply(): ChunkBuilder[Int]                 = new ChunkBuilder.Int
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Long`.
   */
  implicit val chunkCanBuildFromLong: ChunkCanBuildFrom[Long] =
    new ChunkCanBuildFrom[Long] {
      def apply(from: Chunk[Any]): ChunkBuilder[Long] = new ChunkBuilder.Long
      def apply(): ChunkBuilder[Long]                 = new ChunkBuilder.Long
    }

  /**
   * The instance of `ChunkCanBuildFrom` for `Short`.
   */
  implicit val chunkCanBuildFromShort: ChunkCanBuildFrom[Short] =
    new ChunkCanBuildFrom[Short] {
      def apply(from: Chunk[Any]): ChunkBuilder[Short] = new ChunkBuilder.Short
      def apply(): ChunkBuilder[Short]                 = new ChunkBuilder.Short
    }
}
