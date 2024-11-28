/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import java.util.UUID
import scala.annotation.tailrec

trait Random extends Serializable { self =>
  def nextBoolean(implicit trace: Trace): UIO[Boolean]
  def nextBytes(length: => Int)(implicit trace: Trace): UIO[Chunk[Byte]]
  def nextDouble(implicit trace: Trace): UIO[Double]
  def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit trace: Trace): UIO[Double]
  def nextFloat(implicit trace: Trace): UIO[Float]
  def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: Trace): UIO[Float]
  def nextGaussian(implicit trace: Trace): UIO[Double]
  def nextInt(implicit trace: Trace): UIO[Int]
  def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: Trace): UIO[Int]
  def nextIntBounded(n: => Int)(implicit trace: Trace): UIO[Int]
  def nextLong(implicit trace: Trace): UIO[Long]
  def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: Trace): UIO[Long]
  def nextLongBounded(n: => Long)(implicit trace: Trace): UIO[Long]
  def nextPrintableChar(implicit trace: Trace): UIO[Char]
  def nextString(length: => Int)(implicit trace: Trace): UIO[String]
  def nextUUID(implicit trace: Trace): UIO[UUID]
  def setSeed(seed: => Long)(implicit trace: Trace): UIO[Unit]
  def shuffle[A, Collection[+Element] <: Iterable[Element]](collection: => Collection[A])(implicit
    bf: BuildFrom[Collection[A], A, Collection[A]],
    trace: Trace
  ): UIO[Collection[A]]

  trait UnsafeAPI extends Serializable {
    def nextBoolean()(implicit unsafe: Unsafe): Boolean
    def nextBytes(length: Int)(implicit unsafe: Unsafe): Chunk[Byte]
    def nextDouble()(implicit unsafe: Unsafe): Double
    def nextDoubleBetween(minInclusive: Double, maxExclusive: Double)(implicit unsafe: Unsafe): Double
    def nextFloat()(implicit unsafe: Unsafe): Float
    def nextFloatBetween(minInclusive: Float, maxExclusive: Float)(implicit unsafe: Unsafe): Float
    def nextGaussian()(implicit unsafe: Unsafe): Double
    def nextInt()(implicit unsafe: Unsafe): Int
    def nextIntBetween(minInclusive: Int, maxExclusive: Int)(implicit unsafe: Unsafe): Int
    def nextIntBounded(n: Int)(implicit unsafe: Unsafe): Int
    def nextLong()(implicit unsafe: Unsafe): Long
    def nextLongBetween(minInclusive: Long, maxExclusive: Long)(implicit unsafe: Unsafe): Long
    def nextLongBounded(n: Long)(implicit unsafe: Unsafe): Long
    def nextPrintableChar()(implicit unsafe: Unsafe): Char
    def nextString(length: Int)(implicit unsafe: Unsafe): String
    def nextUUID()(implicit unsafe: Unsafe): UUID
    def setSeed(seed: Long)(implicit unsafe: Unsafe): Unit
    def shuffle[A, Collection[+Element] <: Iterable[Element]](
      collection: Collection[A]
    )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], unsafe: Unsafe): Collection[A]
  }

  def unsafe: UnsafeAPI =
    new UnsafeAPI {
      def nextBoolean()(implicit unsafe: Unsafe): Boolean =
        Runtime.default.unsafe.run(self.nextBoolean(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextBytes(length: Int)(implicit unsafe: Unsafe): Chunk[Byte] =
        Runtime.default.unsafe.run(self.nextBytes(length)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextDouble()(implicit unsafe: Unsafe): Double =
        Runtime.default.unsafe.run(self.nextDouble(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextDoubleBetween(minInclusive: Double, maxExclusive: Double)(implicit
        unsafe: Unsafe
      ): Double =
        Runtime.default.unsafe
          .run(self.nextDoubleBetween(minInclusive, maxExclusive)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def nextFloat()(implicit unsafe: Unsafe): Float =
        Runtime.default.unsafe.run(self.nextFloat(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextFloatBetween(minInclusive: Float, maxExclusive: Float)(implicit
        unsafe: Unsafe
      ): Float =
        Runtime.default.unsafe
          .run(self.nextFloatBetween(minInclusive, maxExclusive)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def nextGaussian()(implicit unsafe: Unsafe): Double =
        Runtime.default.unsafe.run(self.nextGaussian(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextInt()(implicit unsafe: Unsafe): Int =
        Runtime.default.unsafe.run(self.nextInt(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextIntBetween(minInclusive: Int, maxExclusive: Int)(implicit unsafe: Unsafe): Int =
        Runtime.default.unsafe
          .run(self.nextIntBetween(minInclusive, maxExclusive)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def nextIntBounded(n: Int)(implicit unsafe: Unsafe): Int =
        Runtime.default.unsafe.run(self.nextIntBounded(n)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextLong()(implicit unsafe: Unsafe): Long =
        Runtime.default.unsafe.run(self.nextLong(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextLongBetween(minInclusive: Long, maxExclusive: Long)(implicit unsafe: Unsafe): Long =
        Runtime.default.unsafe
          .run(self.nextLongBetween(minInclusive, maxExclusive)(Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()

      def nextLongBounded(n: Long)(implicit unsafe: Unsafe): Long =
        Runtime.default.unsafe.run(self.nextLongBounded(n)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextPrintableChar()(implicit unsafe: Unsafe): Char =
        Runtime.default.unsafe.run(self.nextPrintableChar(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextString(length: Int)(implicit unsafe: Unsafe): String =
        Runtime.default.unsafe.run(self.nextString(length)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def nextUUID()(implicit unsafe: Unsafe): UUID =
        Runtime.default.unsafe.run(self.nextUUID(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def setSeed(seed: Long)(implicit unsafe: Unsafe): Unit =
        Runtime.default.unsafe.run(self.setSeed(seed)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def shuffle[A, Collection[+Element] <: Iterable[Element]](collection: Collection[A])(implicit
        bf: BuildFrom[Collection[A], A, Collection[A]],
        unsafe: Unsafe
      ): Collection[A] =
        Runtime.default.unsafe
          .run(self.shuffle(collection)(bf, Trace.empty))(Trace.empty, unsafe)
          .getOrThrowFiberFailure()
    }
}

object Random extends Serializable {

  val tag: Tag[Random] = Tag[Random]

  object RandomLive extends Random {

    def nextBoolean(implicit trace: Trace): UIO[Boolean] =
      ZIO.succeed(unsafe.nextBoolean()(Unsafe.unsafe))

    def nextBytes(length: => Int)(implicit trace: Trace): UIO[Chunk[Byte]] =
      ZIO.succeed(unsafe.nextBytes(length)(Unsafe.unsafe))

    def nextDouble(implicit trace: Trace): UIO[Double] =
      ZIO.succeed(unsafe.nextDouble()(Unsafe.unsafe))

    def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit
      trace: Trace
    ): UIO[Double] =
      ZIO.succeed(unsafe.nextDoubleBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextFloat(implicit trace: Trace): UIO[Float] =
      ZIO.succeed(unsafe.nextFloat()(Unsafe.unsafe))

    def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: Trace): UIO[Float] =
      ZIO.succeed(unsafe.nextFloatBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextGaussian(implicit trace: Trace): UIO[Double] =
      ZIO.succeed(unsafe.nextGaussian()(Unsafe.unsafe))

    def nextInt(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextInt()(Unsafe.unsafe))

    def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextIntBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextIntBounded(n: => Int)(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextIntBounded(n)(Unsafe.unsafe))

    def nextLong(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLong()(Unsafe.unsafe))

    def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLongBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextLongBounded(n: => Long)(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLongBounded(n)(Unsafe.unsafe))

    def nextPrintableChar(implicit trace: Trace): UIO[Char] =
      ZIO.succeed(unsafe.nextPrintableChar()(Unsafe.unsafe))

    def nextString(length: => Int)(implicit trace: Trace): UIO[String] =
      ZIO.succeed(unsafe.nextString(length)(Unsafe.unsafe))

    def nextUUID(implicit trace: Trace): UIO[UUID] =
      ZIO.succeed(unsafe.nextUUID()(Unsafe.unsafe))

    def setSeed(seed: => Long)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.setSeed(seed)(Unsafe.unsafe))

    def shuffle[A, Collection[+Element] <: Iterable[Element]](
      collection: => Collection[A]
    )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): UIO[Collection[A]] =
      ZIO.succeed(unsafe.shuffle(collection)(bf, Unsafe.unsafe))

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def nextBoolean()(implicit unsafe: Unsafe): Boolean =
          scala.util.Random.nextBoolean()

        override def nextBytes(length: Int)(implicit unsafe: Unsafe): Chunk[Byte] = {
          val array = Array.ofDim[Byte](length)
          scala.util.Random.nextBytes(array)
          Chunk.fromArray(array)
        }

        override def nextDouble()(implicit unsafe: Unsafe): Double =
          scala.util.Random.nextDouble()

        override def nextDoubleBetween(minInclusive: Double, maxExclusive: Double)(implicit unsafe: Unsafe): Double =
          nextDoubleBetweenWith(minInclusive, maxExclusive)(() => nextDouble())

        override def nextFloat()(implicit unsafe: Unsafe): Float =
          scala.util.Random.nextFloat()

        override def nextFloatBetween(minInclusive: Float, maxExclusive: Float)(implicit unsafe: Unsafe): Float =
          nextFloatBetweenWith(minInclusive, maxExclusive)(() => nextFloat())

        override def nextGaussian()(implicit unsafe: Unsafe): Double =
          scala.util.Random.nextGaussian()

        override def nextInt()(implicit unsafe: Unsafe): Int =
          scala.util.Random.nextInt()

        override def nextIntBetween(minInclusive: Int, maxExclusive: Int)(implicit
          unsafe: Unsafe
        ): Int =
          nextIntBetweenWith(minInclusive, maxExclusive)(() => nextInt(), nextIntBounded(_))

        override def nextIntBounded(n: Int)(implicit unsafe: Unsafe): Int =
          scala.util.Random.nextInt(n)

        override def nextLong()(implicit unsafe: Unsafe): Long =
          scala.util.Random.nextLong()

        override def nextLongBetween(minInclusive: Long, maxExclusive: Long)(implicit unsafe: Unsafe): Long =
          nextLongBetweenWith(minInclusive, maxExclusive)(() => nextLong(), nextLongBounded(_))

        override def nextLongBounded(n: Long)(implicit unsafe: Unsafe): Long =
          Random.nextLongBoundedWith(n)(() => nextLong())

        override def nextPrintableChar()(implicit unsafe: Unsafe): Char =
          scala.util.Random.nextPrintableChar()

        override def nextString(length: Int)(implicit unsafe: Unsafe): String =
          scala.util.Random.nextString(length)

        override def nextUUID()(implicit unsafe: Unsafe): UUID =
          Random.nextUUIDWith(() => nextLong())

        override def setSeed(seed: Long)(implicit unsafe: Unsafe): Unit =
          scala.util.Random.setSeed(seed)

        override def shuffle[A, Collection[+Element] <: Iterable[Element]](
          collection: Collection[A]
        )(implicit bf: zio.BuildFrom[Collection[A], A, Collection[A]], unsafe: Unsafe): Collection[A] =
          Random.shuffleWith(nextIntBounded(_), collection)
      }
  }

  /**
   * An implementation of the `Random` service backed by a `scala.util.Random`.
   */
  final case class RandomScala(random: scala.util.Random) extends Random {

    def nextBoolean(implicit trace: Trace): UIO[Boolean] =
      ZIO.succeed(unsafe.nextBoolean()(Unsafe.unsafe))

    def nextBytes(length: => Int)(implicit trace: Trace): UIO[Chunk[Byte]] =
      ZIO.succeed(unsafe.nextBytes(length)(Unsafe.unsafe))

    def nextDouble(implicit trace: Trace): UIO[Double] =
      ZIO.succeed(unsafe.nextDouble()(Unsafe.unsafe))

    def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit
      trace: Trace
    ): UIO[Double] =
      ZIO.succeed(unsafe.nextDoubleBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextFloat(implicit trace: Trace): UIO[Float] =
      ZIO.succeed(unsafe.nextFloat()(Unsafe.unsafe))

    def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit trace: Trace): UIO[Float] =
      ZIO.succeed(unsafe.nextFloatBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextGaussian(implicit trace: Trace): UIO[Double] =
      ZIO.succeed(unsafe.nextGaussian()(Unsafe.unsafe))

    def nextInt(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextInt()(Unsafe.unsafe))

    def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextIntBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextIntBounded(n: => Int)(implicit trace: Trace): UIO[Int] =
      ZIO.succeed(unsafe.nextIntBounded(n)(Unsafe.unsafe))

    def nextLong(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLong()(Unsafe.unsafe))

    def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLongBetween(minInclusive, maxExclusive)(Unsafe.unsafe))

    def nextLongBounded(n: => Long)(implicit trace: Trace): UIO[Long] =
      ZIO.succeed(unsafe.nextLongBounded(n)(Unsafe.unsafe))

    def nextPrintableChar(implicit trace: Trace): UIO[Char] =
      ZIO.succeed(unsafe.nextPrintableChar()(Unsafe.unsafe))

    def nextString(length: => Int)(implicit trace: Trace): UIO[String] =
      ZIO.succeed(unsafe.nextString(length)(Unsafe.unsafe))

    def nextUUID(implicit trace: Trace): UIO[UUID] =
      ZIO.succeed(unsafe.nextUUID()(Unsafe.unsafe))

    def setSeed(seed: => Long)(implicit trace: Trace): UIO[Unit] =
      ZIO.succeed(unsafe.setSeed(seed)(Unsafe.unsafe))

    def shuffle[A, Collection[+Element] <: Iterable[Element]](
      collection: => Collection[A]
    )(implicit bf: BuildFrom[Collection[A], A, Collection[A]], trace: Trace): UIO[Collection[A]] =
      ZIO.succeed(unsafe.shuffle(collection)(bf, Unsafe.unsafe))

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def nextBoolean()(implicit unsafe: Unsafe): Boolean =
          random.nextBoolean()

        override def nextBytes(length: Int)(implicit unsafe: Unsafe): Chunk[Byte] = {
          val array = Array.ofDim[Byte](length)
          random.nextBytes(array)
          Chunk.fromArray(array)
        }

        override def nextDouble()(implicit unsafe: Unsafe): Double =
          random.nextDouble()

        override def nextDoubleBetween(minInclusive: Double, maxExclusive: Double)(implicit unsafe: Unsafe): Double =
          nextDoubleBetweenWith(minInclusive, maxExclusive)(() => nextDouble())

        override def nextFloat()(implicit unsafe: Unsafe): Float =
          random.nextFloat()

        override def nextFloatBetween(minInclusive: Float, maxExclusive: Float)(implicit unsafe: Unsafe): Float =
          nextFloatBetweenWith(minInclusive, maxExclusive)(() => nextFloat())

        override def nextGaussian()(implicit unsafe: Unsafe): Double =
          random.nextGaussian()

        override def nextInt()(implicit unsafe: Unsafe): Int =
          random.nextInt()

        override def nextIntBetween(minInclusive: Int, maxExclusive: Int)(implicit
          unsafe: Unsafe
        ): Int =
          nextIntBetweenWith(minInclusive, maxExclusive)(() => nextInt(), nextIntBounded(_))

        override def nextIntBounded(n: Int)(implicit unsafe: Unsafe): Int =
          random.nextInt(n)

        override def nextLong()(implicit unsafe: Unsafe): Long =
          random.nextLong()

        override def nextLongBetween(minInclusive: Long, maxExclusive: Long)(implicit unsafe: Unsafe): Long =
          nextLongBetweenWith(minInclusive, maxExclusive)(() => nextLong(), nextLongBounded(_))

        override def nextLongBounded(n: Long)(implicit unsafe: Unsafe): Long =
          Random.nextLongBoundedWith(n)(() => nextLong())

        override def nextPrintableChar()(implicit unsafe: Unsafe): Char =
          random.nextPrintableChar()

        override def nextString(length: Int)(implicit unsafe: Unsafe): String =
          random.nextString(length)

        override def nextUUID()(implicit unsafe: Unsafe): UUID =
          Random.nextUUIDWith(() => nextLong())

        override def setSeed(seed: Long)(implicit unsafe: Unsafe): Unit =
          random.setSeed(seed)

        override def shuffle[A, Collection[+Element] <: Iterable[Element]](
          collection: Collection[A]
        )(implicit bf: zio.BuildFrom[Collection[A], A, Collection[A]], unsafe: Unsafe): Collection[A] =
          Random.shuffleWith(nextIntBounded(_), collection)
      }
  }

  private[zio] def nextDoubleBetweenWith(minInclusive: Double, maxExclusive: Double)(nextDouble: () => Double): Double =
    if (minInclusive >= maxExclusive)
      throw new IllegalArgumentException("invalid bounds")
    else {
      val n      = nextDouble()
      val result = n * (maxExclusive - minInclusive) + minInclusive
      if (result < maxExclusive) result
      else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
    }

  private[zio] def nextFloatBetweenWith(minInclusive: Float, maxExclusive: Float)(
    nextFloat: () => Float
  ): Float =
    if (minInclusive >= maxExclusive)
      throw new IllegalArgumentException("invalid bounds")
    else {
      val n      = nextFloat()
      val result = n * (maxExclusive - minInclusive) + minInclusive
      if (result < maxExclusive) result
      else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
    }

  private[zio] def nextIntBetweenWith(
    minInclusive: Int,
    maxExclusive: Int
  )(nextInt: () => Int, nextIntBounded: Int => Int): Int =
    if (minInclusive >= maxExclusive) {
      throw new IllegalArgumentException("invalid bounds")
    } else {
      val difference = maxExclusive - minInclusive
      if (difference > 0) nextIntBounded(difference) + minInclusive
      else {
        @tailrec
        def loop: Int = {
          val n = nextInt()
          if (minInclusive <= n && n < maxExclusive) n
          else loop
        }
        loop
      }
    }

  private[zio] def nextLongBetweenWith(
    minInclusive: Long,
    maxExclusive: Long
  )(nextLong: () => Long, nextLongBounded: Long => Long): Long =
    if (minInclusive >= maxExclusive)
      throw new IllegalArgumentException("invalid bounds")
    else {
      val difference = maxExclusive - minInclusive
      if (difference > 0) nextLongBounded(difference) + minInclusive
      else {
        @tailrec
        def loop: Long = {
          val n = nextLong()
          if (minInclusive <= n && n < maxExclusive) n
          else loop
        }
        loop
      }
    }

  private[zio] def nextLongBoundedWith(n: Long)(nextLong: => () => Long): Long =
    if (n <= 0)
      throw new IllegalArgumentException("n must be positive")
    else {
      val r = nextLong()
      val m = n - 1
      if ((n & m) == 0L)
        r & m
      else {
        @tailrec
        def loop(u: Long): Long =
          if (u + m - u % m < 0L) {
            val r = nextLong()
            loop(r >>> 1)
          } else u % n
        loop(r >>> 1)
      }
    }

  private[zio] def nextUUIDWith(nextLong: () => Long): UUID = {
    val mostSigBits  = nextLong()
    val leastSigBits = nextLong()
    new UUID(
      (mostSigBits & ~0x0000f000) | 0x00004000,
      (leastSigBits & ~(0xc0000000L << 32)) | (0x80000000L << 32)
    )
  }

  private[zio] def shuffleWith[A, Collection[+Element] <: Iterable[Element]](
    nextIntBounded: Int => Int,
    collection: Collection[A]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): Collection[A] = {
    val buffer = new scala.collection.mutable.ArrayBuffer[A]
    buffer ++= collection
    def swap(i1: Int, i2: Int): Unit = {
      val tmp = buffer(i1)
      buffer(i1) = buffer(i2)
      buffer(i2) = tmp
    }
    (collection.size to 2 by -1).foreach { n =>
      val k = nextIntBounded(n)
      swap(n - 1, k)
    }
    bf.fromSpecific(collection)(buffer)
  }

  /**
   * generates a pseudo-random boolean.
   */
  def nextBoolean(implicit trace: Trace): UIO[Boolean] =
    ZIO.randomWith(_.nextBoolean)

  /**
   * Generates a pseudo-random chunk of bytes of the specified length.
   */
  def nextBytes(length: => Int)(implicit trace: Trace): UIO[Chunk[Byte]] =
    ZIO.randomWith(_.nextBytes(length))

  /**
   * Generates a pseudo-random, uniformly distributed double between 0.0 and
   * 1.0.
   */
  def nextDouble(implicit trace: Trace): UIO[Double] =
    ZIO.randomWith(_.nextDouble)

  /**
   * Generates a pseudo-random double in the specified range.
   */
  def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double)(implicit
    trace: Trace
  ): UIO[Double] =
    ZIO.randomWith(_.nextDoubleBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random, uniformly distributed float between 0.0 and
   * 1.0.
   */
  def nextFloat(implicit trace: Trace): UIO[Float] =
    ZIO.randomWith(_.nextFloat)

  /**
   * Generates a pseudo-random float in the specified range.
   */
  def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float)(implicit
    trace: Trace
  ): UIO[Float] =
    ZIO.randomWith(_.nextFloatBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random double from a normal distribution with mean 0.0
   * and standard deviation 1.0.
   */
  def nextGaussian(implicit trace: Trace): UIO[Double] =
    ZIO.randomWith(_.nextGaussian)

  /**
   * Generates a pseudo-random integer.
   */
  def nextInt(implicit trace: Trace): UIO[Int] =
    ZIO.randomWith(_.nextInt)

  /**
   * Generates a pseudo-random integer in the specified range.
   */
  def nextIntBetween(minInclusive: => Int, maxExclusive: => Int)(implicit
    trace: Trace
  ): UIO[Int] =
    ZIO.randomWith(_.nextIntBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random integer between 0 (inclusive) and the specified
   * value (exclusive).
   */
  def nextIntBounded(n: => Int)(implicit trace: Trace): UIO[Int] =
    ZIO.randomWith(_.nextIntBounded(n))

  /**
   * Generates a pseudo-random long.
   */
  def nextLong(implicit trace: Trace): UIO[Long] =
    ZIO.randomWith(_.nextLong)

  /**
   * Generates a pseudo-random long in the specified range.
   */
  def nextLongBetween(minInclusive: => Long, maxExclusive: => Long)(implicit
    trace: Trace
  ): UIO[Long] =
    ZIO.randomWith(_.nextLongBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random long between 0 (inclusive) and the specified
   * value (exclusive).
   */
  def nextLongBounded(n: => Long)(implicit trace: Trace): UIO[Long] =
    ZIO.randomWith(_.nextLongBounded(n))

  /**
   * Generates psuedo-random universally unique identifiers.
   */
  def nextUUID(implicit trace: Trace): UIO[UUID] =
    ZIO.randomWith(_.nextUUID)

  /**
   * Generates a pseudo-random character from the ASCII range 33-126.
   */
  def nextPrintableChar(implicit trace: Trace): UIO[Char] =
    ZIO.randomWith(_.nextPrintableChar)

  /**
   * Generates a pseudo-random string of the specified length.
   */
  def nextString(length: => Int)(implicit trace: Trace): UIO[String] =
    ZIO.randomWith(_.nextString(length))

  /**
   * Sets the seed of this random number generator.
   */
  def setSeed(seed: => Long)(implicit trace: Trace): UIO[Unit] =
    ZIO.randomWith(_.setSeed(seed))

  /**
   * Randomly shuffles the specified list.
   */
  def shuffle[A](list: => List[A])(implicit trace: Trace): UIO[List[A]] =
    ZIO.randomWith(_.shuffle(list))
}
