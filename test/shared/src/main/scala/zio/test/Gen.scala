/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio.Random._
import zio.stream.{Stream, ZStream}
import zio.{Chunk, ExecutionStrategy, Has, NonEmptyChunk, Random, UIO, URIO, ZIO, Zippable}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.immutable.SortedMap
import scala.math.Numeric.DoubleIsFractional

/**
 * A `Gen[R, A]` represents a generator of values of type `A`, which requires
 * an environment `R`. Generators may be random or deterministic.
 */
final case class Gen[-R, +A](sample: ZStream[R, Nothing, Sample[R, A]]) { self =>

  /**
   * A symbolic alias for `zip`.
   */
  def <&>[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B]): Gen[R1, zippable.Out] =
    self.zip(that)

  /**
   * A symbolic alias for `cross`.
   */
  def <*>[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B]): Gen[R1, zippable.Out] =
    self.cross(that)

  /**
   * Maps the values produced by this generator with the specified partial
   * function, discarding any values the partial function is not defined at.
   */
  def collect[B](pf: PartialFunction[A, B]): Gen[R, B] =
    self.flatMap { a =>
      pf.andThen(Gen.const(_)).applyOrElse[A, Gen[Any, B]](a, _ => Gen.empty)
    }

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements.
   */
  def cross[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B]): Gen[R1, zippable.Out] =
    self.crossWith(that)(zippable.zip(_, _))

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements with the specified function.
   */
  def crossWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C): Gen[R1, C] =
    self.flatMap(a => that.map(b => f(a, b)))

  /**
   * Filters the values produced by this generator, discarding any values that
   * do not meet the specified predicate. Using `filter` can reduce test
   * performance, especially if many values must be discarded. It is
   * recommended to use combinators such as `map` and `flatMap` to create
   * generators of the desired values instead.
   *
   * {{{
   * val evens: Gen[Has[Random], Int] = Gen.anyInt.map(_ * 2)
   * }}}
   */
  def filter(f: A => Boolean): Gen[R, A] = Gen {
    sample.flatMap(sample => if (f(sample.value)) sample.filter(f) else ZStream.empty)
  }

  /**
   * Filters the values produced by this generator, discarding any values that
   * meet the specified predicate.
   */
  def filterNot(f: A => Boolean): Gen[R, A] =
    filter(a => !f(a))

  def withFilter(f: A => Boolean): Gen[R, A] = filter(f)

  def flatMap[R1 <: R, B](f: A => Gen[R1, B]): Gen[R1, B] = Gen {
    self.sample.flatMap { sample =>
      val values  = f(sample.value).sample
      val shrinks = Gen(sample.shrink).flatMap(f).sample
      values.map(_.flatMap(Sample(_, shrinks)))
    }
  }

  def flatten[R1 <: R, B](implicit ev: A <:< Gen[R1, B]): Gen[R1, B] =
    flatMap(ev)

  def map[B](f: A => B): Gen[R, B] =
    Gen(sample.map(_.map(f)))

  /**
   * Maps an effectual function over a generator.
   */
  @deprecated("use mapZIO", "2.0.0")
  def mapM[R1 <: R, B](f: A => ZIO[R1, Nothing, B]): Gen[R1, B] =
    mapZIO(f)

  /**
   * Maps an effectual function over a generator.
   */
  def mapZIO[R1 <: R, B](f: A => ZIO[R1, Nothing, B]): Gen[R1, B] =
    Gen(sample.mapZIO(_.foreach(f)))

  /**
   * Discards the shrinker for this generator.
   */
  def noShrink: Gen[R, A] =
    reshrink(Sample.noShrink)

  /**
   * Discards the shrinker for this generator and applies a new shrinker by
   * mapping each value to a sample using the specified function. This is
   * useful when the process to shrink a value is simpler than the process used
   * to generate it.
   */
  def reshrink[R1 <: R, B](f: A => Sample[R1, B]): Gen[R1, B] =
    Gen(sample.map(sample => f(sample.value)))

  /**
   * Runs the generator and collects all of its values in a list.
   */
  def runCollect: ZIO[R, Nothing, List[A]] =
    sample.map(_.value).runCollect.map(_.toList)

  /**
   * Repeatedly runs the generator and collects the specified number of values
   * in a list.
   */
  def runCollectN(n: Int): ZIO[R, Nothing, List[A]] =
    sample.map(_.value).forever.take(n.toLong).runCollect.map(_.toList)

  /**
   * Runs the generator returning the first value of the generator.
   */
  def runHead: ZIO[R, Nothing, Option[A]] =
    sample.map(_.value).runHead

  /**
   * Zips two generators together pairwise. The new generator will generate
   * elements as long as either generator is generating elements, running the
   * other generator multiple times if necessary.
   */
  def zip[R1 <: R, B](that: Gen[R1, B])(implicit zippable: Zippable[A, B]): Gen[R1, zippable.Out] =
    self.zipWith(that)(zippable.zip(_, _))

  /**
   * Zips two generators together pairwise with the specified function. The new
   * generator will generate elements as long as either generator is generating
   * elements, running the other generator multiple times if necessary.
   */
  def zipWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C): Gen[R1, C] = Gen {
    val left  = self.sample.map(Right(_)) ++ self.sample.map(Left(_)).forever
    val right = that.sample.map(Right(_)) ++ that.sample.map(Left(_)).forever
    left
      .zipAllWithExec(right)(ExecutionStrategy.Sequential)(
        l => (Some(l), None),
        r => (None, Some(r))
      )((l, r) => (Some(l), Some(r)))
      .collectWhile {
        case (Some(Right(l)), Some(Right(r))) => l.zipWith(r)(f)
        case (Some(Right(l)), Some(Left(r)))  => l.zipWith(r)(f)
        case (Some(Left(l)), Some(Right(r)))  => l.zipWith(r)(f)
      }
  }
}

object Gen extends GenZIO with FunctionVariants with TimeVariants {

  /**
   * A generator of alpha characters.
   */
  val alphaChar: Gen[Has[Random], Char] =
    weighted(char(65, 90) -> 26, char(97, 122) -> 26)

  /**
   * A generator of alphanumeric characters. Shrinks toward '0'.
   */
  val alphaNumericChar: Gen[Has[Random], Char] =
    weighted(char(48, 57) -> 10, char(65, 90) -> 26, char(97, 122) -> 26)

  /**
   * A generator of alphanumeric strings. Shrinks towards the empty string.
   */
  val alphaNumericString: Gen[Has[Random] with Has[Sized], String] =
    Gen.string(alphaNumericChar)

  /**
   * A generator of alphanumeric strings whose size falls within the specified
   * bounds.
   */
  def alphaNumericStringBounded(min: Int, max: Int): Gen[Has[Random] with Has[Sized], String] =
    Gen.stringBounded(min, max)(alphaNumericChar)

  /**
   * A generator US-ASCII strings. Shrinks towards the empty string.
   */
  def anyASCIIString: Gen[Has[Random] with Has[Sized], String] =
    Gen.string(Gen.anyASCIIChar)

  /**
   * A generator of US-ASCII characters. Shrinks toward '0'.
   */
  def anyASCIIChar: Gen[Has[Random], Char] =
    Gen.oneOf(Gen.char('\u0000', '\u007F'))

  /**
   * A generator of bytes. Shrinks toward '0'.
   */
  val anyByte: Gen[Has[Random], Byte] =
    fromZIOSample {
      nextIntBounded(Byte.MaxValue - Byte.MinValue + 1)
        .map(r => (Byte.MinValue + r).toByte)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of characters. Shrinks toward '0'.
   */
  val anyChar: Gen[Has[Random], Char] =
    fromZIOSample {
      nextIntBounded(Char.MaxValue - Char.MinValue + 1)
        .map(r => (Char.MinValue + r).toChar)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of doubles. Shrinks toward '0'.
   */
  val anyDouble: Gen[Has[Random], Double] =
    fromZIOSample(nextDouble.map(Sample.shrinkFractional(0f)))

  /**
   * A generator of floats. Shrinks toward '0'.
   */
  val anyFloat: Gen[Has[Random], Float] =
    fromZIOSample(nextFloat.map(Sample.shrinkFractional(0f)))

  /**
   * A generator of hex chars(0-9,a-f,A-F).
   */
  val anyHexChar: Gen[Has[Random], Char] = weighted(
    char('\u0030', '\u0039') -> 10,
    char('\u0041', '\u0046') -> 6,
    char('\u0061', '\u0066') -> 6
  )

  /**
   * A generator of integers. Shrinks toward '0'.
   */
  val anyInt: Gen[Has[Random], Int] =
    fromZIOSample(nextInt.map(Sample.shrinkIntegral(0)))

  /**
   * A generator of longs. Shrinks toward '0'.
   */
  val anyLong: Gen[Has[Random], Long] =
    fromZIOSample(nextLong.map(Sample.shrinkIntegral(0L)))

  /**
   * A generator of lower hex chars(0-9, a-f).
   */
  val anyLowerHexChar: Gen[Has[Random], Char] =
    weighted(char('\u0030', '\u0039') -> 10, char('\u0061', '\u0066') -> 6)

  /**
   * A generator of shorts. Shrinks toward '0'.
   */
  val anyShort: Gen[Has[Random], Short] =
    fromZIOSample {
      nextIntBounded(Short.MaxValue - Short.MinValue + 1)
        .map(r => (Short.MinValue + r).toShort)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of strings. Shrinks towards the empty string.
   */
  def anyString: Gen[Has[Random] with Has[Sized], String] =
    Gen.string(Gen.anyUnicodeChar)

  /**
   * A generator of Unicode characters. Shrinks toward '0'.
   */
  val anyUnicodeChar: Gen[Has[Random], Char] =
    Gen.oneOf(Gen.char('\u0000', '\uD7FF'), Gen.char('\uE000', '\uFFFD'))

  /**
   * A generator of upper hex chars(0-9, A-F).
   */
  val anyUpperHexChar: Gen[Has[Random], Char] =
    weighted(
      char('\u0030', '\u0039') -> 10,
      char('\u0041', '\u0046') -> 6
    )

  /**
   * A generator of universally unique identifiers. The returned generator will
   * not have any shrinking.
   */
  val anyUUID: Gen[Has[Random], UUID] =
    Gen.fromZIO(nextUUID)

  /**
   * A generator of big decimals inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   *
   * The values generated will have a precision equal to the precision of the
   * difference between `max` and `min`.
   */
  def bigDecimal(min: BigDecimal, max: BigDecimal): Gen[Has[Random], BigDecimal] =
    if (min > max)
      Gen.fromZIO(UIO.die(new IllegalArgumentException("invalid bounds")))
    else {
      val difference = max - min
      val decimals   = difference.scale max 0
      val bigInt     = (difference * BigDecimal(10).pow(decimals)).toBigInt
      Gen.bigInt(0, bigInt).map(bigInt => min + BigDecimal(bigInt) / BigDecimal(10).pow(decimals))
    }

  /**
   * A generator of big integers inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def bigInt(min: BigInt, max: BigInt): Gen[Has[Random], BigInt] =
    Gen.fromZIOSample {
      if (min > max) UIO.die(new IllegalArgumentException("invalid bounds"))
      else {
        val bitLength  = (max - min).bitLength
        val byteLength = ((bitLength.toLong + 7) / 8).toInt
        val excessBits = byteLength * 8 - bitLength
        val mask       = (1 << (8 - excessBits)) - 1
        val effect = nextBytes(byteLength).map { bytes =>
          val arr = bytes.toArray
          arr(0) = (arr(0) & mask).toByte
          min + BigInt(arr)
        }.repeatUntil(n => min <= n && n <= max)
        effect.map(Sample.shrinkIntegral(min))
      }
    }

  /**
   * A generator of booleans. Shrinks toward 'false'.
   */
  val boolean: Gen[Has[Random], Boolean] =
    elements(false, true)

  /**
   * A generator whose size falls within the specified bounds.
   */
  def bounded[R <: Has[Random], A](min: Int, max: Int)(f: Int => Gen[R, A]): Gen[R, A] =
    int(min, max).flatMap(f)

  /**
   * A generator of byte values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def byte(min: Byte, max: Byte): Gen[Has[Random], Byte] =
    int(min.toInt, max.toInt).map(_.toByte)

  /**
   * A generator of character values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def char(min: Char, max: Char): Gen[Has[Random], Char] =
    int(min.toInt, max.toInt).map(_.toChar)

  /**
   * A sized generator of chunks.
   */
  def chunkOf[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, Chunk[A]] =
    listOf(g).map(Chunk.fromIterable)

  /**
   * A sized generator of non-empty chunks.
   */
  def chunkOf1[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, NonEmptyChunk[A]] =
    listOf1(g).map { case h :: t => NonEmptyChunk.fromIterable(h, t) }

  /**
   * A generator of chunks whose size falls within the specified bounds.
   */
  def chunkOfBounded[R <: Has[Random], A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, Chunk[A]] =
    bounded(min, max)(chunkOfN(_)(g))

  /**
   * A generator of chunks of the specified size.
   */
  def chunkOfN[R <: Has[Random], A](n: Int)(g: Gen[R, A]): Gen[R, Chunk[A]] =
    listOfN(n)(g).map(Chunk.fromIterable)

  /**
   * Combines the specified deterministic generators to return a new
   * deterministic generator that generates all of the values generated by
   * the specified generators.
   */
  def concatAll[R, A](gens: => Iterable[Gen[R, A]]): Gen[R, A] =
    Gen(ZStream.concatAll(Chunk.fromIterable(gens).map(_.sample)))

  /**
   * A constant generator of the specified value.
   */
  def const[A](a: => A): Gen[Any, A] =
    Gen(ZStream.succeed(Sample.noShrink(a)))

  /**
   * A constant generator of the specified sample.
   */
  def constSample[R, A](sample: => Sample[R, A]): Gen[R, A] =
    fromZIOSample(ZIO.succeedNow(sample))

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossAll[R, A](gens: Iterable[Gen[R, A]]): Gen[R, List[A]] =
    gens.foldRight[Gen[R, List[A]]](Gen.const(List.empty))(_.crossWith(_)(_ :: _))

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C](gen1: Gen[R, A], gen2: Gen[R, B])(f: (A, B) => C): Gen[R, C] =
    gen1.crossWith(gen2)(f)

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C, D](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C])(f: (A, B, C) => D): Gen[R, D] =
    for {
      a <- gen1
      b <- gen2
      c <- gen3
    } yield f(a, b, c)

  /**
   * Composes the specified generators to create a cartesian product of
   * elements with the specified function.
   */
  def crossN[R, A, B, C, D, F](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C], gen4: Gen[R, D])(
    f: (A, B, C, D) => F
  ): Gen[R, F] =
    for {
      a <- gen1
      b <- gen2
      c <- gen3
      d <- gen4
    } yield f(a, b, c, d)

  /**
   * A generator of double values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def double(min: Double, max: Double): Gen[Has[Random], Double] =
    if (min > max)
      Gen.fromZIO(UIO.die(new IllegalArgumentException("invalid bounds")))
    else
      uniform.map { r =>
        val n = min + r * (max - min)
        if (n < max) n else Math.nextAfter(max, Double.NegativeInfinity)
      }

  def either[R <: Has[Random], A, B](left: Gen[R, A], right: Gen[R, B]): Gen[R, Either[A, B]] =
    oneOf(left.map(Left(_)), right.map(Right(_)))

  def elements[A](as: A*): Gen[Has[Random], A] =
    if (as.isEmpty) empty else int(0, as.length - 1).map(as)

  val empty: Gen[Any, Nothing] =
    Gen(Stream.empty)

  /**
   * A generator of exponentially distributed doubles with mean `1`.
   * The shrinker will shrink toward `0`.
   */
  val exponential: Gen[Has[Random], Double] =
    uniform.map(n => -math.log(1 - n))

  /**
   * Constructs a generator from an effect that constructs a value.
   */
  @deprecated("use fromZIO", "2.0.0")
  def fromEffect[R, A](effect: URIO[R, A]): Gen[R, A] =
    fromZIO(effect)

  /**
   * Constructs a generator from an effect that constructs a sample.
   */
  @deprecated("use fromZIOSample", "2.0.0")
  def fromEffectSample[R, A](effect: ZIO[R, Nothing, Sample[R, A]]): Gen[R, A] =
    fromZIOSample(effect)

  /**
   * Constructs a deterministic generator that only generates the specified fixed values.
   */
  def fromIterable[R, A](
    as: Iterable[A],
    shrinker: A => ZStream[R, Nothing, A] = defaultShrinker
  ): Gen[R, A] =
    Gen(ZStream.fromIterable(as).map(a => Sample.unfold(a)(a => (a, shrinker(a)))))

  /**
   * Constructs a generator from a function that uses randomness. The returned
   * generator will not have any shrinking.
   */
  final def fromRandom[A](f: Random => UIO[A]): Gen[Has[Random], A] =
    Gen(ZStream.fromZIO(ZIO.accessZIO[Has[Random]](r => f(r.get)).map(Sample.noShrink)))

  /**
   * Constructs a generator from a function that uses randomness to produce a
   * sample.
   */
  final def fromRandomSample[R <: Has[Random], A](f: Random => UIO[Sample[R, A]]): Gen[R, A] =
    Gen(ZStream.fromZIO(ZIO.accessZIO[Has[Random]](r => f(r.get))))

  /**
   * Constructs a generator from an effect that constructs a value.
   */
  def fromZIO[R, A](effect: URIO[R, A]): Gen[R, A] =
    Gen(ZStream.fromZIO(effect.map(Sample.noShrink)))

  /**
   * Constructs a generator from an effect that constructs a sample.
   */
  def fromZIOSample[R, A](effect: ZIO[R, Nothing, Sample[R, A]]): Gen[R, A] =
    Gen(ZStream.fromZIO(effect))

  /**
   * A generator of integers inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def int(min: Int, max: Int): Gen[Has[Random], Int] =
    Gen.fromZIOSample {
      if (min > max) UIO.die(new IllegalArgumentException("invalid bounds"))
      else {
        val effect =
          if (max < Int.MaxValue) nextIntBetween(min, max + 1)
          else if (min > Int.MinValue) nextIntBetween(min - 1, max).map(_ + 1)
          else nextInt
        effect.map(Sample.shrinkIntegral(min))
      }
    }

  /**
   *  A generator of strings that can be encoded in the ISO-8859-1 character set.
   */
  val iso_8859_1: Gen[Has[Random] with Has[Sized], String] =
    chunkOf(anyByte).map(chunk => new String(chunk.toArray, StandardCharsets.ISO_8859_1))

  /**
   * A sized generator that uses a uniform distribution of size values. A large
   * number of larger sizes will be generated.
   */
  def large[R <: Has[Random] with Has[Sized], A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] =
    size.flatMap(max => int(min, max)).flatMap(f)

  def listOf[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, List[A]] =
    small(listOfN(_)(g))

  def listOf1[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, ::[A]] =
    for {
      h <- g
      t <- small(n => listOfN(n - 1 max 0)(g))
    } yield ::(h, t)

  /**
   * A generator of lists whose size falls within the specified bounds.
   */
  def listOfBounded[R <: Has[Random], A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, List[A]] =
    bounded(min, max)(listOfN(_)(g))

  def listOfN[R <: Has[Random], A](n: Int)(g: Gen[R, A]): Gen[R, List[A]] =
    List.fill(n)(g).foldRight[Gen[R, List[A]]](const(Nil))((a, gen) => a.crossWith(gen)(_ :: _))

  /**
   * A generator of long values in the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def long(min: Long, max: Long): Gen[Has[Random], Long] =
    Gen.fromZIOSample {
      if (min > max) UIO.die(new IllegalArgumentException("invalid bounds"))
      else {
        val effect =
          if (max < Long.MaxValue) nextLongBetween(min, max + 1L)
          else if (min > Long.MinValue) nextLongBetween(min - 1L, max).map(_ + 1L)
          else nextLong
        effect.map(Sample.shrinkIntegral(min))
      }
    }

  /**
   * A sized generator of maps.
   */
  def mapOf[R <: Has[Random] with Has[Sized], A, B](key: Gen[R, A], value: Gen[R, B]): Gen[R, Map[A, B]] =
    small(mapOfN(_)(key, value))

  /**
   * A sized generator of non-empty maps.
   */
  def mapOf1[R <: Has[Random] with Has[Sized], A, B](key: Gen[R, A], value: Gen[R, B]): Gen[R, Map[A, B]] =
    small(mapOfN(_)(key, value), 1)

  /**
   * A generator of maps of the specified size.
   */
  def mapOfN[R <: Has[Random], A, B](n: Int)(key: Gen[R, A], value: Gen[R, B]): Gen[R, Map[A, B]] =
    setOfN(n)(key).crossWith(listOfN(n)(value))(_.zip(_).toMap)

  /**
   * A generator of maps whose size falls within the specified bounds.
   */
  def mapOfBounded[R <: Has[Random], A, B](min: Int, max: Int)(key: Gen[R, A], value: Gen[R, B]): Gen[R, Map[A, B]] =
    bounded(min, max)(mapOfN(_)(key, value))

  /**
   * A sized generator that uses an exponential distribution of size values.
   * The majority of sizes will be towards the lower end of the range but some
   * larger sizes will be generated as well.
   */
  def medium[R <: Has[Random] with Has[Sized], A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 10.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  /**
   * A constant generator of the empty value.
   */
  val none: Gen[Any, Option[Nothing]] =
    Gen.const(None)

  /**
   * A generator of numeric characters. Shrinks toward '0'.
   */
  val numericChar: Gen[Has[Random], Char] =
    weighted(char(48, 57) -> 10)

  /**
   * A generator of optional values. Shrinks toward `None`.
   */
  def option[R <: Has[Random], A](gen: Gen[R, A]): Gen[R, Option[A]] =
    oneOf(none, gen.map(Some(_)))

  def oneOf[R <: Has[Random], A](as: Gen[R, A]*): Gen[R, A] =
    if (as.isEmpty) empty else int(0, as.length - 1).flatMap(as)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values. Two `A` values will be considered to be equal,
   * and thus will be guaranteed to generate the same `B` value or both be
   * outside the partial function's domain, if they have the same `hashCode`.
   */
  def partialFunction[R <: Has[Random], A, B](gen: Gen[R, B]): Gen[R, PartialFunction[A, B]] =
    partialFunctionWith(gen)(_.hashCode)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values and a hashing function for `A` values. Two `A`
   * values will be considered to be equal, and thus will be guaranteed to
   * generate the same `B` value or both be outside the partial function's
   * domain, if they have have the same hash. This is useful when `A` does not
   * implement `hashCode` in a way that is consistent with equality.
   */
  def partialFunctionWith[R <: Has[Random], A, B](gen: Gen[R, B])(hash: A => Int): Gen[R, PartialFunction[A, B]] =
    functionWith(option(gen))(hash).map(Function.unlift)

  /**
   * A generator of printable characters. Shrinks toward '!'.
   */
  val printableChar: Gen[Has[Random], Char] =
    char(33, 126)

  /**
   * A sized generator of sets.
   */
  def setOf[R <: Has[Random] with Has[Sized], A](gen: Gen[R, A]): Gen[R, Set[A]] =
    small(setOfN(_)(gen))

  /**
   * A sized generator of non-empty sets.
   */
  def setOf1[R <: Has[Random] with Has[Sized], A](gen: Gen[R, A]): Gen[R, Set[A]] =
    small(setOfN(_)(gen), 1)

  /**
   * A generator of sets whose size falls within the specified bounds.
   */
  def setOfBounded[R <: Has[Random], A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, Set[A]] =
    bounded(min, max)(setOfN(_)(g))

  /**
   * A generator of sets of the specified size.
   */
  def setOfN[R <: Has[Random], A](n: Int)(gen: Gen[R, A]): Gen[R, Set[A]] =
    List.fill(n)(gen).foldLeft[Gen[R, Set[A]]](const(Set.empty)) { (acc, gen) =>
      for {
        set  <- acc
        elem <- gen.filterNot(set)
      } yield set + elem
    }

  /**
   * A generator of short values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def short(min: Short, max: Short): Gen[Has[Random], Short] =
    int(min.toInt, max.toInt).map(_.toShort)

  def size: Gen[Has[Sized], Int] =
    Gen.fromZIO(Sized.size)

  /**
   * A sized generator, whose size falls within the specified bounds.
   */
  def sized[R <: Has[Sized], A](f: Int => Gen[R, A]): Gen[R, A] =
    size.flatMap(f)

  /**
   * A sized generator that uses an exponential distribution of size values.
   * The values generated will be strongly concentrated towards the lower end
   * of the range but a few larger values will still be generated.
   */
  def small[R <: Has[Random] with Has[Sized], A](f: Int => Gen[R, A], min: Int = 0): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 25.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  def some[R, A](gen: Gen[R, A]): Gen[R, Option[A]] =
    gen.map(Some(_))

  def string[R <: Has[Random] with Has[Sized]](char: Gen[R, Char]): Gen[R, String] =
    listOf(char).map(_.mkString)

  def string1[R <: Has[Random] with Has[Sized]](char: Gen[R, Char]): Gen[R, String] =
    listOf1(char).map(_.mkString)

  /**
   * A generator of strings whose size falls within the specified bounds.
   */
  def stringBounded[R <: Has[Random]](min: Int, max: Int)(g: Gen[R, Char]): Gen[R, String] =
    bounded(min, max)(stringN(_)(g))

  def stringN[R <: Has[Random]](n: Int)(char: Gen[R, Char]): Gen[R, String] =
    listOfN(n)(char).map(_.mkString)

  /**
   * Lazily constructs a generator. This is useful to avoid infinite recursion
   * when creating generators that refer to themselves.
   */
  def suspend[R, A](gen: => Gen[R, A]): Gen[R, A] =
    fromZIO(ZIO.succeed(gen)).flatten

  /**
   * A generator of throwables.
   */
  val throwable: Gen[Has[Random], Throwable] =
    Gen.const(new Throwable)

  /**
   * A sized generator of collections, where each collection is generated by
   * repeatedly applying a function to an initial state.
   */
  def unfoldGen[R <: Has[Random] with Has[Sized], S, A](s: S)(f: S => Gen[R, (S, A)]): Gen[R, List[A]] =
    small(unfoldGenN(_)(s)(f))

  /**
   * A generator of collections of up to the specified size, where each
   * collection is generated by repeatedly applying a function to an initial
   * state.
   */
  def unfoldGenN[R, S, A](n: Int)(s: S)(f: S => Gen[R, (S, A)]): Gen[R, List[A]] =
    if (n <= 0)
      Gen.const(List.empty)
    else
      f(s).flatMap { case (s, a) => unfoldGenN(n - 1)(s)(f).map(a :: _) }

  /**
   * A generator of uniformly distributed doubles between [0, 1].
   * The shrinker will shrink toward `0`.
   */
  def uniform: Gen[Has[Random], Double] =
    fromZIOSample(nextDouble.map(Sample.shrinkFractional(0.0)))

  /**
   * A constant generator of the unit value.
   */
  val unit: Gen[Any, Unit] =
    const(())

  def vectorOf[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, Vector[A]] =
    listOf(g).map(_.toVector)

  def vectorOf1[R <: Has[Random] with Has[Sized], A](g: Gen[R, A]): Gen[R, Vector[A]] =
    listOf1(g).map(_.toVector)

  /**
   * A generator of vectors whose size falls within the specified bounds.
   */
  def vectorOfBounded[R <: Has[Random], A](min: Int, max: Int)(g: Gen[R, A]): Gen[R, Vector[A]] =
    bounded(min, max)(vectorOfN(_)(g))

  def vectorOfN[R <: Has[Random], A](n: Int)(g: Gen[R, A]): Gen[R, Vector[A]] =
    listOfN(n)(g).map(_.toVector)

  /**
   * A generator which chooses one of the given generators according to their
   * weights. For example, the following generator will generate 90% true and
   * 10% false values.
   * {{{
   * val trueFalse = Gen.weighted((Gen.const(true), 9), (Gen.const(false), 1))
   * }}}
   */
  def weighted[R <: Has[Random], A](gs: (Gen[R, A], Double)*): Gen[R, A] = {
    val sum = gs.map(_._2).sum
    val (map, _) = gs.foldLeft((SortedMap.empty[Double, Gen[R, A]], 0.0)) { case ((map, acc), (gen, d)) =>
      if ((acc + d) / sum > acc / sum) (map.updated((acc + d) / sum, gen), acc + d)
      else (map, acc)
    }
    uniform.flatMap(n => map.rangeImpl(Some(n), None).head._2)
  }

  /**
   * A generator of whitespace characters.
   */
  val whitespaceChars: Gen[Has[Random], Char] =
    Gen.elements((Char.MinValue to Char.MaxValue).filter(_.isWhitespace): _*)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipAll[R, A](gens: Iterable[Gen[R, A]]): Gen[R, List[A]] =
    gens.foldRight[Gen[R, List[A]]](Gen.const(List.empty))(_.zipWith(_)(_ :: _))

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C](gen1: Gen[R, A], gen2: Gen[R, B])(f: (A, B) => C): Gen[R, C] =
    gen1.zipWith(gen2)(f)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C])(f: (A, B, C) => D): Gen[R, D] =
    (gen1 <&> gen2 <&> gen3).map(f.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F](gen1: Gen[R, A], gen2: Gen[R, B], gen3: Gen[R, C], gen4: Gen[R, D])(
    f: (A, B, C, D) => F
  ): Gen[R, F] =
    (gen1 <&> gen2 <&> gen3 <&> gen4).map(f.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F]
  )(
    fn: (A, B, C, D, F) => G
  ): Gen[R, G] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5).map(fn.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G, H](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F],
    gen6: Gen[R, G]
  )(
    fn: (A, B, C, D, F, G) => H
  ): Gen[R, H] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5 <&> gen6).map(fn.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G, H, I](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F],
    gen6: Gen[R, G],
    gen7: Gen[R, H]
  )(
    fn: (A, B, C, D, F, G, H) => I
  ): Gen[R, I] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5 <&> gen6 <&> gen7).map(fn.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G, H, I, J](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F],
    gen6: Gen[R, G],
    gen7: Gen[R, H],
    gen8: Gen[R, I]
  )(
    fn: (A, B, C, D, F, G, H, I) => J
  ): Gen[R, J] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5 <&> gen6 <&> gen7 <&> gen8).map(fn.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G, H, I, J, K](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F],
    gen6: Gen[R, G],
    gen7: Gen[R, H],
    gen8: Gen[R, I],
    gen9: Gen[R, J]
  )(
    fn: (A, B, C, D, F, G, H, I, J) => K
  ): Gen[R, K] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5 <&> gen6 <&> gen7 <&> gen8 <&> gen9).map(fn.tupled)

  /**
   * Zips the specified generators together pairwise. The new generator will
   * generate elements as long as any generator is generating elements, running
   * the other generators multiple times if necessary.
   */
  def zipN[R, A, B, C, D, F, G, H, I, J, K, L](
    gen1: Gen[R, A],
    gen2: Gen[R, B],
    gen3: Gen[R, C],
    gen4: Gen[R, D],
    gen5: Gen[R, F],
    gen6: Gen[R, G],
    gen7: Gen[R, H],
    gen8: Gen[R, I],
    gen9: Gen[R, J],
    gen10: Gen[R, K]
  )(
    fn: (A, B, C, D, F, G, H, I, J, K) => L
  ): Gen[R, L] =
    (gen1 <&> gen2 <&> gen3 <&> gen4 <&> gen5 <&> gen6 <&> gen7 <&> gen8 <&> gen9 <&> gen10).map(fn.tupled)

  /**
   * Restricts an integer to the specified range.
   */
  private def clamp(n: Int, min: Int, max: Int): Int =
    if (n < min) min
    else if (n > max) max
    else n

  private val defaultShrinker: Any => ZStream[Any, Nothing, Nothing] =
    _ => ZStream.empty
}
