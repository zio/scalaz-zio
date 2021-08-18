/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

import java.util.UUID

trait Random extends Serializable {
  def nextBoolean: UIO[Boolean]
  def nextBytes(length: => Int): UIO[Chunk[Byte]]
  def nextDouble: UIO[Double]
  def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double): UIO[Double]
  def nextFloat: UIO[Float]
  def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float): UIO[Float]
  def nextGaussian: UIO[Double]
  def nextInt: UIO[Int]
  def nextIntBetween(minInclusive: => Int, maxExclusive: => Int): UIO[Int]
  def nextIntBounded(n: => Int): UIO[Int]
  def nextLong: UIO[Long]
  def nextLongBetween(minInclusive: => Long, maxExclusive: => Long): UIO[Long]
  def nextLongBounded(n: => Long): UIO[Long]
  def nextPrintableChar: UIO[Char]
  def nextString(length: => Int): UIO[String]
  def nextUUID: UIO[UUID] = Random.nextUUIDWith(nextLong)
  def setSeed(seed: => Long): UIO[Unit]
  def shuffle[A, Collection[+Element] <: Iterable[Element]](collection: => Collection[A])(implicit
    bf: BuildFrom[Collection[A], A, Collection[A]]
  ): UIO[Collection[A]]
}

object Random extends Serializable {
  object RandomLive extends Random {
    import scala.util.{Random => SRandom}

    val nextBoolean: UIO[Boolean] =
      ZIO.succeed(SRandom.nextBoolean())
    def nextBytes(length: => Int): UIO[Chunk[Byte]] =
      ZIO.succeed {
        val array = Array.ofDim[Byte](length)
        SRandom.nextBytes(array)
        Chunk.fromArray(array)
      }
    val nextDouble: UIO[Double] =
      ZIO.succeed(SRandom.nextDouble())
    def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double): UIO[Double] =
      nextDoubleBetweenWith(minInclusive, maxExclusive)(nextDouble)
    val nextFloat: UIO[Float] =
      ZIO.succeed(SRandom.nextFloat())
    def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float): UIO[Float] =
      nextFloatBetweenWith(minInclusive, maxExclusive)(nextFloat)
    val nextGaussian: UIO[Double] =
      ZIO.succeed(SRandom.nextGaussian())
    val nextInt: UIO[Int] =
      ZIO.succeed(SRandom.nextInt())
    def nextIntBetween(minInclusive: => Int, maxExclusive: => Int): UIO[Int] =
      nextIntBetweenWith(minInclusive, maxExclusive)(nextInt, nextIntBounded(_))
    def nextIntBounded(n: => Int): UIO[Int] =
      ZIO.succeed(SRandom.nextInt(n))
    val nextLong: UIO[Long] =
      ZIO.succeed(SRandom.nextLong())
    def nextLongBetween(minInclusive: => Long, maxExclusive: => Long): UIO[Long] =
      nextLongBetweenWith(minInclusive, maxExclusive)(nextLong, nextLongBounded(_))
    def nextLongBounded(n: => Long): UIO[Long] =
      Random.nextLongBoundedWith(n)(nextLong)
    val nextPrintableChar: UIO[Char] =
      ZIO.succeed(SRandom.nextPrintableChar())
    def nextString(length: => Int): UIO[String] =
      ZIO.succeed(SRandom.nextString(length))
    def setSeed(seed: => Long): UIO[Unit] =
      ZIO.succeed(SRandom.setSeed(seed))
    def shuffle[A, Collection[+Element] <: Iterable[Element]](
      collection: => Collection[A]
    )(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
      Random.shuffleWith(nextIntBounded(_), collection)
  }

  val any: ZLayer[Has[Random], Nothing, Has[Random]] =
    ZLayer.service[Random]

  val live: Layer[Nothing, Has[Random]] =
    ZLayer.succeed(RandomLive)

  private[zio] def nextDoubleBetweenWith(minInclusive0: => Double, maxExclusive0: => Double)(
    nextDouble: UIO[Double]
  ): UIO[Double] =
    ZIO.suspendSucceed {
      val minInclusive = minInclusive0
      val maxExclusive = maxExclusive0

      if (minInclusive >= maxExclusive)
        UIO.die(new IllegalArgumentException("invalid bounds"))
      else
        nextDouble.map { n =>
          val result = n * (maxExclusive - minInclusive) + minInclusive
          if (result < maxExclusive) result
          else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
        }
    }

  private[zio] def nextFloatBetweenWith(minInclusive0: => Float, maxExclusive0: => Float)(
    nextFloat: UIO[Float]
  ): UIO[Float] =
    ZIO.suspendSucceed {
      val minInclusive = minInclusive0
      val maxExclusive = maxExclusive0

      if (minInclusive >= maxExclusive)
        UIO.die(new IllegalArgumentException("invalid bounds"))
      else
        nextFloat.map { n =>
          val result = n * (maxExclusive - minInclusive) + minInclusive
          if (result < maxExclusive) result
          else Math.nextAfter(maxExclusive, Float.NegativeInfinity)
        }
    }

  private[zio] def nextIntBetweenWith(
    minInclusive0: => Int,
    maxExclusive0: => Int
  )(nextInt: UIO[Int], nextIntBounded: Int => UIO[Int]): UIO[Int] =
    ZIO.suspendSucceed {
      val minInclusive = minInclusive0
      val maxExclusive = maxExclusive0

      if (minInclusive >= maxExclusive) {
        UIO.die(new IllegalArgumentException("invalid bounds"))
      } else {
        val difference = maxExclusive - minInclusive
        if (difference > 0) nextIntBounded(difference).map(_ + minInclusive)
        else nextInt.repeatUntil(n => minInclusive <= n && n < maxExclusive)
      }
    }

  private[zio] def nextLongBetweenWith(
    minInclusive0: => Long,
    maxExclusive0: => Long
  )(nextLong: UIO[Long], nextLongBounded: Long => UIO[Long]): UIO[Long] =
    ZIO.suspendSucceed {
      val minInclusive = minInclusive0
      val maxExclusive = maxExclusive0

      if (minInclusive >= maxExclusive)
        UIO.die(new IllegalArgumentException("invalid bounds"))
      else {
        val difference = maxExclusive - minInclusive
        if (difference > 0) nextLongBounded(difference).map(_ + minInclusive)
        else nextLong.repeatUntil(n => minInclusive <= n && n < maxExclusive)
      }
    }

  private[zio] def nextLongBoundedWith(n0: => Long)(nextLong: => UIO[Long]): UIO[Long] =
    ZIO.suspendSucceed {
      val n = n0

      if (n <= 0)
        UIO.die(new IllegalArgumentException("n must be positive"))
      else {
        nextLong.flatMap { r =>
          val m = n - 1
          if ((n & m) == 0L)
            UIO.succeedNow(r & m)
          else {
            def loop(u: Long): UIO[Long] =
              if (u + m - u % m < 0L) nextLong.flatMap(r => loop(r >>> 1))
              else UIO.succeedNow(u % n)
            loop(r >>> 1)
          }
        }
      }
    }

  private[zio] def nextUUIDWith(nextLong: UIO[Long]): UIO[UUID] =
    for {
      mostSigBits  <- nextLong
      leastSigBits <- nextLong
    } yield new UUID(
      (mostSigBits & ~0x0000f000) | 0x00004000,
      (leastSigBits & ~(0xc0000000L << 32)) | (0x80000000L << 32)
    )

  private[zio] def shuffleWith[A, Collection[+Element] <: Iterable[Element]](
    nextIntBounded: Int => UIO[Int],
    collection0: => Collection[A]
  )(implicit bf: BuildFrom[Collection[A], A, Collection[A]]): UIO[Collection[A]] =
    ZIO.suspendSucceed {
      val collection = collection0

      for {
        buffer <- ZIO.succeed {
                    val buffer = new scala.collection.mutable.ArrayBuffer[A]
                    buffer ++= collection
                  }
        swap = (i1: Int, i2: Int) =>
                 ZIO.succeed {
                   val tmp = buffer(i1)
                   buffer(i1) = buffer(i2)
                   buffer(i2) = tmp
                   buffer
                 }
        _ <-
          ZIO.foreachDiscard((collection.size to 2 by -1).toList)((n: Int) =>
            nextIntBounded(n).flatMap(k => swap(n - 1, k))
          )
      } yield bf.fromSpecific(collection)(buffer)
    }

  // Accessor Methods

  /**
   * generates a pseudo-random boolean.
   */
  val nextBoolean: URIO[Has[Random], Boolean] =
    ZIO.serviceWith(_.nextBoolean)

  /**
   * Generates a pseudo-random chunk of bytes of the specified length.
   */
  def nextBytes(length: => Int): ZIO[Has[Random], Nothing, Chunk[Byte]] =
    ZIO.serviceWith(_.nextBytes(length))

  /**
   * Generates a pseudo-random, uniformly distributed double between 0.0 and
   * 1.0.
   */
  val nextDouble: URIO[Has[Random], Double] = ZIO.serviceWith(_.nextDouble)

  /**
   * Generates a pseudo-random double in the specified range.
   */
  def nextDoubleBetween(minInclusive: => Double, maxExclusive: => Double): URIO[Has[Random], Double] =
    ZIO.serviceWith(_.nextDoubleBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random, uniformly distributed float between 0.0 and
   * 1.0.
   */
  val nextFloat: URIO[Has[Random], Float] =
    ZIO.serviceWith(_.nextFloat)

  /**
   * Generates a pseudo-random float in the specified range.
   */
  def nextFloatBetween(minInclusive: => Float, maxExclusive: => Float): URIO[Has[Random], Float] =
    ZIO.serviceWith(_.nextFloatBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random double from a normal distribution with mean 0.0
   * and standard deviation 1.0.
   */
  val nextGaussian: URIO[Has[Random], Double] =
    ZIO.serviceWith(_.nextGaussian)

  /**
   * Generates a pseudo-random integer.
   */
  val nextInt: URIO[Has[Random], Int] =
    ZIO.serviceWith(_.nextInt)

  /**
   * Generates a pseudo-random integer in the specified range.
   */
  def nextIntBetween(minInclusive: => Int, maxExclusive: => Int): URIO[Has[Random], Int] =
    ZIO.serviceWith(_.nextIntBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random integer between 0 (inclusive) and the specified
   * value (exclusive).
   */
  def nextIntBounded(n: => Int): URIO[Has[Random], Int] =
    ZIO.serviceWith(_.nextIntBounded(n))

  /**
   * Generates a pseudo-random long.
   */
  val nextLong: URIO[Has[Random], Long] =
    ZIO.serviceWith(_.nextLong)

  /**
   * Generates a pseudo-random long in the specified range.
   */
  def nextLongBetween(minInclusive: => Long, maxExclusive: => Long): URIO[Has[Random], Long] =
    ZIO.serviceWith(_.nextLongBetween(minInclusive, maxExclusive))

  /**
   * Generates a pseudo-random long between 0 (inclusive) and the specified
   * value (exclusive).
   */
  def nextLongBounded(n: => Long): URIO[Has[Random], Long] =
    ZIO.serviceWith(_.nextLongBounded(n))

  /**
   * Generates psuedo-random universally unique identifiers.
   */
  val nextUUID: URIO[Has[Random], UUID] =
    ZIO.serviceWith(_.nextUUID)

  /**
   * Generates a pseudo-random character from the ASCII range 33-126.
   */
  val nextPrintableChar: URIO[Has[Random], Char] =
    ZIO.serviceWith(_.nextPrintableChar)

  /**
   * Generates a pseudo-random string of the specified length.
   */
  def nextString(length: => Int): URIO[Has[Random], String] =
    ZIO.serviceWith(_.nextString(length))

  /**
   * Sets the seed of this random number generator.
   */
  def setSeed(seed: => Long): URIO[Has[Random], Unit] =
    ZIO.serviceWith(_.setSeed(seed))

  /**
   * Randomly shuffles the specified list.
   */
  def shuffle[A](list: => List[A]): ZIO[Has[Random], Nothing, List[A]] =
    ZIO.serviceWith(_.shuffle(list))
}
