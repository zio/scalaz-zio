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

package zio.test

import zio.Random._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.{Stream, ZStream}
import zio.{Chunk, NonEmptyChunk, Random, Trace, UIO, URIO, ZIO, Zippable}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.immutable.SortedMap
import scala.math.Numeric.DoubleIsFractional

/**
 * A `Gen[R, A]` represents a generator of values of type `A`, which requires an
 * environment `R`. Generators may be random or deterministic.
 */
final case class Gen[-R, +A](sample: ZStream[R, Nothing, Sample[R, A]]) { self =>

  /**
   * A symbolic alias for `concat`.
   */
  def ++[R1 <: R, A1 >: A](that: Gen[R1, A1])(implicit trace: Trace): Gen[R1, A1] =
    self.concat(that)

  /**
   * A symbolic alias for `zip`.
   */
  def <*>[R1 <: R, B](
    that: Gen[R1, B]
  )(implicit zippable: Zippable[A, B], trace: Trace): Gen[R1, zippable.Out] =
    self.zip(that)

  /**
   * Concatenates the specified deterministic generator with this determinstic
   * generator, resulting in a deterministic generator that generates the values
   * from this generator and then the values from the specified generator.
   */
  def concat[R1 <: R, A1 >: A](that: Gen[R1, A1])(implicit trace: Trace): Gen[R1, A1] =
    Gen(self.sample ++ that.sample)

  /**
   * Maps the values produced by this generator with the specified partial
   * function, discarding any values the partial function is not defined at.
   */
  def collect[B](pf: PartialFunction[A, B])(implicit trace: Trace): Gen[R, B] =
    self.flatMap { a =>
      pf.andThen(Gen.const(_)).applyOrElse[A, Gen[Any, B]](a, _ => Gen.empty)
    }

  /**
   * Filters the values produced by this generator, discarding any values that
   * do not meet the specified predicate. Using `filter` can reduce test
   * performance, especially if many values must be discarded. It is recommended
   * to use combinators such as `map` and `flatMap` to create generators of the
   * desired values instead.
   *
   * {{{
   * val evens: Gen[Any, Int] = Gen.int.map(_ * 2)
   * }}}
   */
  def filter(f: A => Boolean)(implicit trace: Trace): Gen[R, A] =
    self.flatMap(a => if (f(a)) Gen.const(a) else Gen.empty)

  /**
   * Filters the values produced by this generator, discarding any values that
   * do not meet the specified effectual predicate. Using `filterZIO` can reduce
   * test performance, especially if many values must be discarded. It is
   * recommended to use combinators such as `map` and `flatMap` to create
   * generators of the desired values instead.
   *
   * {{{
   * val evens: Gen[Any, Int] = Gen.int.map(_ * 2)
   * }}}
   */
  def filterZIO[R1 <: R](f: A => ZIO[R1, Nothing, Boolean])(implicit trace: Trace): Gen[R1, A] =
    self.flatMap(a => Gen.fromZIO(f(a)).flatMap(p => if (p) Gen.const(a) else Gen.empty))

  /**
   * Filters the values produced by this generator, discarding any values that
   * meet the specified predicate.
   */
  def filterNot(f: A => Boolean)(implicit trace: Trace): Gen[R, A] =
    filter(a => !f(a))

  def withFilter(f: A => Boolean)(implicit trace: Trace): Gen[R, A] = filter(f)

  def flatMap[R1 <: R, B](f: A => Gen[R1, B])(implicit trace: Trace): Gen[R1, B] =
    Gen {
      self.sample.flatMap { sample =>
        val values  = f(sample.value).sample
        val shrinks = Gen(sample.shrink).flatMap(f).sample
        values.map(_.flatMap(Sample(_, shrinks)))
      }
    }

  def flatten[R1 <: R, B](implicit ev: A <:< Gen[R1, B], trace: Trace): Gen[R1, B] =
    flatMap(ev)

  def map[B](f: A => B)(implicit trace: Trace): Gen[R, B] =
    Gen(sample.map(_.map(f)))

  /**
   * Maps an effectual function over a generator.
   */
  def mapZIO[R1 <: R, B](f: A => ZIO[R1, Nothing, B])(implicit trace: Trace): Gen[R1, B] =
    Gen(sample.mapZIO(_.foreach(f)))

  /**
   * Discards the shrinker for this generator.
   */
  def noShrink(implicit trace: Trace): Gen[R, A] =
    reshrink(Sample.noShrink)

  /**
   * Discards the shrinker for this generator and applies a new shrinker by
   * mapping each value to a sample using the specified function. This is useful
   * when the process to shrink a value is simpler than the process used to
   * generate it.
   */
  def reshrink[R1 <: R, B](f: A => Sample[R1, B])(implicit trace: Trace): Gen[R1, B] =
    Gen(sample.map(sample => f(sample.value)))

  /**
   * Sets the size parameter for this generator to the specified value.
   */
  def resize(size: Int)(implicit trace: Trace): Gen[R, A] =
    Sized.withSizeGen(size)(self)

  /**
   * Runs the generator and collects all of its values in a list.
   */
  def runCollect(implicit trace: Trace): ZIO[R, Nothing, List[A]] =
    sample.map(_.value).runCollect.map(_.toList)

  /**
   * Repeatedly runs the generator and collects the specified number of values
   * in a list.
   */
  def runCollectN(n: Int)(implicit trace: Trace): ZIO[R, Nothing, List[A]] =
    sample.map(_.value).forever.take(n.toLong).runCollect.map(_.toList)

  /**
   * Runs the generator returning the first value of the generator.
   */
  def runHead(implicit trace: Trace): ZIO[R, Nothing, Option[A]] =
    sample.map(_.value).runHead

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements.
   */
  def zip[R1 <: R, B](
    that: Gen[R1, B]
  )(implicit zippable: Zippable[A, B], trace: Trace): Gen[R1, zippable.Out] =
    self.zipWith(that)(zippable.zip(_, _))

  /**
   * Composes this generator with the specified generator to create a cartesian
   * product of elements with the specified function.
   */
  def zipWith[R1 <: R, B, C](that: Gen[R1, B])(f: (A, B) => C)(implicit trace: Trace): Gen[R1, C] =
    self.flatMap(a => that.map(b => f(a, b)))
}

object Gen extends GenZIO with FunctionVariants with TimeVariants {

  /**
   * A generator of alpha characters.
   */
  def alphaChar(implicit trace: Trace): Gen[Any, Char] =
    weighted(char(65, 90) -> 26, char(97, 122) -> 26)

  /**
   * A generator of alphanumeric characters. Shrinks toward '0'.
   */
  def alphaNumericChar(implicit trace: Trace): Gen[Any, Char] =
    weighted(char(48, 57) -> 10, char(65, 90) -> 26, char(97, 122) -> 26)

  /**
   * A generator of alphanumeric strings. Shrinks towards the empty string.
   */
  def alphaNumericString(implicit trace: Trace): Gen[Any, String] =
    Gen.string(alphaNumericChar)

  /**
   * A generator of alphanumeric strings whose size falls within the specified
   * bounds.
   */
  def alphaNumericStringBounded(min: Int, max: Int)(implicit
    trace: Trace
  ): Gen[Any, String] =
    Gen.stringBounded(min, max)(alphaNumericChar)

  /**
   * A generator of US-ASCII characters. Shrinks toward '0'.
   */
  def asciiChar(implicit trace: Trace): Gen[Any, Char] =
    Gen.oneOf(Gen.char('\u0000', '\u007F'))

  /**
   * A generator US-ASCII strings. Shrinks towards the empty string.
   */
  def asciiString(implicit trace: Trace): Gen[Any, String] =
    Gen.string(Gen.asciiChar)

  /**
   * A generator of big decimals inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   *
   * The values generated will have a precision equal to the precision of the
   * difference between `max` and `min`.
   */
  def bigDecimal(min: BigDecimal, max: BigDecimal)(implicit trace: Trace): Gen[Any, BigDecimal] =
    if (min > max)
      Gen.fromZIO(ZIO.die(new IllegalArgumentException("invalid bounds")))
    else {
      val difference = max - min
      val decimals   = difference.scale max 0
      val bigInt     = (difference * BigDecimal(10).pow(decimals)).toBigInt
      Gen.bigInt(0, bigInt).map(bigInt => min + BigDecimal(bigInt) / BigDecimal(10).pow(decimals))
    }

  /**
   * A generator of [[java.math.BigDecimal]] inside the specified range: [start,
   * end]. The shrinker will shrink toward the lower end of the range
   * ("smallest").
   *
   * The values generated will have a precision equal to the precision of the
   * difference between `max` and `min`.
   * @see
   *   See [[bigDecimal]] for implementation.
   */
  def bigDecimalJava(min: BigDecimal, max: BigDecimal)(implicit trace: Trace): Gen[Any, java.math.BigDecimal] =
    Gen.bigDecimal(min, max).map(_.underlying)

  /**
   * A generator of big integers inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def bigInt(min: BigInt, max: BigInt)(implicit trace: Trace): Gen[Any, BigInt] =
    Gen.fromZIOSample {
      if (min > max) ZIO.die(new IllegalArgumentException("invalid bounds"))
      else if (min == max) ZIO.succeed(Sample.noShrink(min))
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
   * A generator of [[java.math.BigInteger]] inside the specified range: [start,
   * end]. The shrinker will shrink toward the lower end of the range
   * ("smallest").
   * @see
   *   See [[bigInt]] for implementation.
   */
  def bigIntegerJava(min: BigInt, max: BigInt)(implicit trace: Trace): Gen[Any, java.math.BigInteger] =
    Gen.bigInt(min, max).map(_.underlying)

  /**
   * A generator of booleans. Shrinks toward 'false'.
   */
  def boolean(implicit trace: Trace): Gen[Any, Boolean] =
    elements(false, true)

  /**
   * A generator whose size falls within the specified bounds.
   */
  def bounded[R, A](min: Int, max: Int)(f: Int => Gen[R, A])(implicit trace: Trace): Gen[R, A] =
    int(min, max).flatMap(f)

  /**
   * A generator of bytes. Shrinks toward '0'.
   */
  def byte(implicit trace: Trace): Gen[Any, Byte] =
    fromZIOSample {
      nextIntBounded(Byte.MaxValue - Byte.MinValue + 1)
        .map(r => (Byte.MinValue + r).toByte)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of byte values inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def byte(min: Byte, max: Byte)(implicit trace: Trace): Gen[Any, Byte] =
    int(min.toInt, max.toInt).map(_.toByte)

  /**
   * A generator of characters. Shrinks toward '0'.
   */
  def char(implicit trace: Trace): Gen[Any, Char] =
    fromZIOSample {
      nextIntBounded(Char.MaxValue - Char.MinValue + 1)
        .map(r => (Char.MinValue + r).toChar)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of character values inside the specified range: [start, end].
   * The shrinker will shrink toward the lower end of the range ("smallest").
   */
  def char(min: Char, max: Char)(implicit trace: Trace): Gen[Any, Char] =
    int(min.toInt, max.toInt).map(_.toChar)

  /**
   * A sized generator of chunks.
   */
  def chunkOf[R, A](g: Gen[R, A])(implicit trace: Trace): Gen[R, Chunk[A]] =
    listOf(g).map(Chunk.fromIterable)

  /**
   * A sized generator of non-empty chunks.
   */
  def chunkOf1[R, A](g: Gen[R, A])(implicit
    trace: Trace
  ): Gen[R, NonEmptyChunk[A]] =
    listOf1(g).map { case h :: t => NonEmptyChunk.fromIterable(h, t) }

  /**
   * A generator of chunks whose size falls within the specified bounds.
   */
  def chunkOfBounded[R, A](min: Int, max: Int)(g: Gen[R, A])(implicit
    trace: Trace
  ): Gen[R, Chunk[A]] =
    bounded(min, max)(chunkOfN(_)(g))

  /**
   * A generator of chunks of the specified size.
   */
  def chunkOfN[R, A](n: Int)(g: Gen[R, A])(implicit trace: Trace): Gen[R, Chunk[A]] =
    listOfN(n)(g).map(Chunk.fromIterable)

  /**
   * Composes the specified generators to create a cartesian product of elements
   * with the specified function.
   */
  def collectAll[R, A](gens: Iterable[Gen[R, A]])(implicit trace: Trace): Gen[R, List[A]] =
    Gen.suspend {

      def loop(gens: List[Gen[R, A]], as: List[A]): Gen[R, List[A]] =
        gens match {
          case gen :: gens => gen.flatMap(a => loop(gens, a :: as))
          case Nil         => Gen.const(as.reverse)
        }

      loop(gens.toList, Nil)
    }

  /**
   * Combines the specified deterministic generators to return a new
   * deterministic generator that generates all of the values generated by the
   * specified generators.
   */
  def concatAll[R, A](gens: => Iterable[Gen[R, A]])(implicit trace: Trace): Gen[R, A] =
    Gen.suspend(gens.foldLeft[Gen[R, A]](Gen.empty)(_ ++ _))

  /**
   * A constant generator of the specified value.
   */
  def const[A](a: => A)(implicit trace: Trace): Gen[Any, A] =
    Gen(ZStream.succeed(Sample.noShrink(a)))

  /**
   * A constant generator of the specified sample.
   */
  def constSample[R, A](sample: => Sample[R, A])(implicit trace: Trace): Gen[R, A] =
    fromZIOSample(ZIO.succeed(sample))

  /**
   * A generator of currency.
   */
  def currency(implicit trace: Trace): Gen[Any, java.util.Currency] =
    elements(java.util.Currency.getAvailableCurrencies.asScala.toSeq: _*)

  /**
   * A generator of doubles. Shrinks toward '0'.
   */
  def double(implicit trace: Trace): Gen[Any, Double] =
    fromZIOSample(nextDouble.map(Sample.shrinkFractional(0f)))

  /**
   * A generator of double values inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def double(min: Double, max: Double)(implicit trace: Trace): Gen[Any, Double] =
    if (min > max)
      Gen.fromZIO(ZIO.die(new IllegalArgumentException("invalid bounds")))
    else
      uniform.map { r =>
        val n = min + r * (max - min)
        if (n < max) n else Math.nextAfter(max, Double.NegativeInfinity)
      }

  def either[R, A, B](left: Gen[R, A], right: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, Either[A, B]] =
    oneOf(left.map(Left(_)), right.map(Right(_)))

  def elements[A](as: A*)(implicit trace: Trace): Gen[Any, A] =
    if (as.isEmpty) empty else int(0, as.length - 1).map(as)

  def empty(implicit trace: Trace): Gen[Any, Nothing] =
    Gen(ZStream.empty)

  /**
   * A generator of exponentially distributed doubles with mean `1`. The
   * shrinker will shrink toward `0`.
   */
  def exponential(implicit trace: Trace): Gen[Any, Double] =
    uniform.map(n => -math.log(1 - n))

  /**
   * Constructs a deterministic generator that only generates the specified
   * fixed values.
   */
  def fromIterable[R, A](
    as: Iterable[A],
    shrinker: A => ZStream[R, Nothing, A] = defaultShrinker
  )(implicit trace: Trace): Gen[R, A] =
    Gen(ZStream.fromIterable(as).map(a => Sample.unfold(a)(a => (a, shrinker(a)))))

  /**
   * Constructs a generator from a function that uses randomness. The returned
   * generator will not have any shrinking.
   */
  final def fromRandom[A](f: Random => UIO[A])(implicit trace: Trace): Gen[Any, A] =
    fromRandomSample(f(_).map(Sample.noShrink))

  /**
   * Constructs a generator from a function that uses randomness to produce a
   * sample.
   */
  final def fromRandomSample[R, A](f: Random => UIO[Sample[R, A]])(implicit
    trace: Trace
  ): Gen[R, A] =
    fromZIOSample(ZIO.randomWith(f))

  /**
   * Constructs a generator from an effect that constructs a value.
   */
  def fromZIO[R, A](effect: URIO[R, A])(implicit trace: Trace): Gen[R, A] =
    fromZIOSample(effect.map(Sample.noShrink))

  /**
   * Constructs a generator from an effect that constructs a sample.
   */
  def fromZIOSample[R, A](effect: ZIO[R, Nothing, Sample[R, A]])(implicit trace: Trace): Gen[R, A] =
    Gen(ZStream.fromZIO(effect))

  /**
   * A generator of floats. Shrinks toward '0'.
   */
  def float(implicit trace: Trace): Gen[Any, Float] =
    fromZIOSample(nextFloat.map(Sample.shrinkFractional(0f)))

  /**
   * A generator of hex chars(0-9,a-f,A-F).
   */
  def hexChar(implicit trace: Trace): Gen[Any, Char] = weighted(
    char('\u0030', '\u0039') -> 10,
    char('\u0041', '\u0046') -> 6,
    char('\u0061', '\u0066') -> 6
  )

  /**
   * A generator of lower hex chars(0-9, a-f).
   */
  def hexCharLower(implicit trace: Trace): Gen[Any, Char] =
    weighted(
      char('\u0030', '\u0039') -> 10,
      char('\u0061', '\u0066') -> 6
    )

  /**
   * A generator of upper hex chars(0-9, A-F).
   */
  def hexCharUpper(implicit trace: Trace): Gen[Any, Char] =
    weighted(
      char('\u0030', '\u0039') -> 10,
      char('\u0041', '\u0046') -> 6
    )

  /**
   * A generator of integers. Shrinks toward '0'.
   */
  def int(implicit trace: Trace): Gen[Any, Int] =
    fromZIOSample(nextInt.map(Sample.shrinkIntegral(0)))

  /**
   * A generator of integers inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def int(min: Int, max: Int)(implicit trace: Trace): Gen[Any, Int] =
    Gen.fromZIOSample {
      if (min > max) ZIO.die(new IllegalArgumentException("invalid bounds"))
      else {
        val effect =
          if (max < Int.MaxValue) nextIntBetween(min, max + 1)
          else if (min > Int.MinValue) nextIntBetween(min - 1, max).map(_ + 1)
          else nextInt
        effect.map(Sample.shrinkIntegral(min))
      }
    }

  /**
   * A generator of strings that can be encoded in the ISO-8859-1 character set.
   */
  def iso_8859_1(implicit trace: Trace): Gen[Any, String] =
    chunkOf(byte).map(chunk => new String(chunk.toArray, StandardCharsets.ISO_8859_1))

  /**
   * A sized generator that uses a uniform distribution of size values. A large
   * number of larger sizes will be generated.
   */
  def large[R, A](f: Int => Gen[R, A], min: Int = 0)(implicit
    trace: Trace
  ): Gen[R, A] =
    size.flatMap(max => int(min, max)).flatMap(f)

  /**
   * A sized generator of lists.
   */
  def listOf[R, A](g: Gen[R, A])(implicit trace: Trace): Gen[R, List[A]] =
    small(listOfN(_)(g))

  /**
   * A sized generator of non-empty lists.
   */
  def listOf1[R, A](g: Gen[R, A])(implicit trace: Trace): Gen[R, ::[A]] =
    for {
      h <- g
      t <- small(n => listOfN(n - 1 max 0)(g))
    } yield ::(h, t)

  /**
   * A generator of lists whose size falls within the specified bounds.
   */
  def listOfBounded[R, A](min: Int, max: Int)(g: Gen[R, A])(implicit
    trace: Trace
  ): Gen[R, List[A]] =
    bounded(min, max)(listOfN(_)(g))

  /**
   * A generator of lists of the specified size.
   */
  def listOfN[R, A](n: Int)(g: Gen[R, A])(implicit trace: Trace): Gen[R, List[A]] =
    collectAll(List.fill(n)(g))

  /**
   * A generator of longs. Shrinks toward '0'.
   */
  def long(implicit trace: Trace): Gen[Any, Long] =
    fromZIOSample(nextLong.map(Sample.shrinkIntegral(0L)))

  /**
   * A generator of long values in the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def long(min: Long, max: Long)(implicit trace: Trace): Gen[Any, Long] =
    Gen.fromZIOSample {
      if (min > max) ZIO.die(new IllegalArgumentException("invalid bounds"))
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
  def mapOf[R, A, B](key: Gen[R, A], value: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, Map[A, B]] =
    listOf(key.zip(value)).map(_.toMap)

  /**
   * A sized generator of non-empty maps.
   */
  def mapOf1[R, A, B](key: Gen[R, A], value: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, Map[A, B]] =
    listOf1(key.zip(value)).map(_.toMap)

  /**
   * A generator of maps of the specified size.
   */
  def mapOfN[R, A, B](n: Int)(key: Gen[R, A], value: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, Map[A, B]] =
    setOfN(n)(key).zipWith(listOfN(n)(value))(_.zip(_).toMap)

  /**
   * A generator of maps whose size falls within the specified bounds.
   */
  def mapOfBounded[R, A, B](min: Int, max: Int)(key: Gen[R, A], value: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, Map[A, B]] =
    mapOfN(min)(key, value).zipWith(listOfBounded(0, max - min)(key.zip(value)))(_ ++ _)

  /**
   * A sized generator that uses an exponential distribution of size values. The
   * majority of sizes will be towards the lower end of the range but some
   * larger sizes will be generated as well.
   */
  def medium[R, A](f: Int => Gen[R, A], min: Int = 0)(implicit
    trace: Trace
  ): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 10.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  /**
   * A constant generator of the empty value.
   */
  def none(implicit trace: Trace): Gen[Any, Option[Nothing]] =
    Gen.const(None)

  /**
   * A generator of numeric characters. Shrinks toward '0'.
   */
  def numericChar(implicit trace: Trace): Gen[Any, Char] =
    weighted(char(48, 57) -> 10)

  /**
   * A generator of optional values. Shrinks toward `None`.
   */
  def option[R, A](gen: Gen[R, A])(implicit trace: Trace): Gen[R, Option[A]] =
    oneOf(none, gen.map(Some(_)))

  def oneOf[R, A](as: Gen[R, A]*)(implicit trace: Trace): Gen[R, A] =
    if (as.isEmpty) empty else int(0, as.length - 1).flatMap(as)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values. Two `A` values will be considered to be equal, and
   * thus will be guaranteed to generate the same `B` value or both be outside
   * the partial function's domain, if they have the same `hashCode`.
   */
  def partialFunction[R, A, B](gen: Gen[R, B])(implicit
    trace: Trace
  ): Gen[R, PartialFunction[A, B]] =
    partialFunctionWith(gen)(_.hashCode)

  /**
   * Constructs a generator of partial functions from `A` to `B` given a
   * generator of `B` values and a hashing function for `A` values. Two `A`
   * values will be considered to be equal, and thus will be guaranteed to
   * generate the same `B` value or both be outside the partial function's
   * domain, if they have have the same hash. This is useful when `A` does not
   * implement `hashCode` in a way that is consistent with equality.
   */
  def partialFunctionWith[R, A, B](gen: Gen[R, B])(hash: A => Int)(implicit
    trace: Trace
  ): Gen[R, PartialFunction[A, B]] =
    functionWith(option(gen))(hash).map(Function.unlift)

  /**
   * A generator of printable characters. Shrinks toward '!'.
   */
  def printableChar(implicit trace: Trace): Gen[Any, Char] =
    char(33, 126)

  /**
   * A sized generator of sets.
   */
  def setOf[R, A](gen: Gen[R, A])(implicit trace: Trace): Gen[R, Set[A]] =
    listOf(gen).map(_.toSet)

  /**
   * A sized generator of non-empty sets.
   */
  def setOf1[R, A](gen: Gen[R, A])(implicit trace: Trace): Gen[R, Set[A]] =
    listOf1(gen).map(_.toSet)

  /**
   * A generator of sets whose size falls within the specified bounds.
   */
  def setOfBounded[R, A](min: Int, max: Int)(g: Gen[R, A])(implicit
    trace: Trace
  ): Gen[R, Set[A]] =
    setOfN(min)(g).zipWith(listOfBounded(0, max - min)(g))(_ ++ _)

  /**
   * A generator of sets of the specified size.
   */
  def setOfN[R, A](n: Int)(gen: Gen[R, A])(implicit trace: Trace): Gen[R, Set[A]] =
    List.fill(n)(gen).foldLeft[Gen[R, Set[A]]](const(Set.empty)) { (acc, gen) =>
      for {
        set  <- acc
        elem <- gen.filterNot(set)
      } yield set + elem
    }

  /**
   * A generator of shorts. Shrinks toward '0'.
   */
  def short(implicit trace: Trace): Gen[Any, Short] =
    fromZIOSample {
      nextIntBounded(Short.MaxValue - Short.MinValue + 1)
        .map(r => (Short.MinValue + r).toShort)
        .map(Sample.shrinkIntegral(0))
    }

  /**
   * A generator of short values inside the specified range: [start, end]. The
   * shrinker will shrink toward the lower end of the range ("smallest").
   */
  def short(min: Short, max: Short)(implicit trace: Trace): Gen[Any, Short] =
    int(min.toInt, max.toInt).map(_.toShort)

  def size(implicit trace: Trace): Gen[Any, Int] =
    Gen.fromZIO(Sized.size)

  /**
   * A sized generator, whose size falls within the specified bounds.
   */
  def sized[R, A](f: Int => Gen[R, A])(implicit trace: Trace): Gen[R, A] =
    size.flatMap(f)

  /**
   * A sized generator that uses an exponential distribution of size values. The
   * values generated will be strongly concentrated towards the lower end of the
   * range but a few larger values will still be generated.
   */
  def small[R, A](f: Int => Gen[R, A], min: Int = 0)(implicit
    trace: Trace
  ): Gen[R, A] = {
    val gen = for {
      max <- size
      n   <- exponential
    } yield clamp(math.round(n * max / 25.0).toInt, min, max)
    gen.reshrink(Sample.shrinkIntegral(min)).flatMap(f)
  }

  def some[R, A](gen: Gen[R, A])(implicit trace: Trace): Gen[R, Option[A]] =
    gen.map(Some(_))

  /**
   * A generator of strings. Shrinks towards the empty string.
   */
  def string(implicit trace: Trace): Gen[Any, String] =
    Gen.string(Gen.unicodeChar)

  /**
   * A sized generator of strings.
   */
  def string[R](char: Gen[R, Char])(implicit trace: Trace): Gen[R, String] =
    listOf(char).map(_.mkString)

  /**
   * A sized generator of non-empty strings.
   */
  def string1[R](char: Gen[R, Char])(implicit trace: Trace): Gen[R, String] =
    listOf1(char).map(_.mkString)

  /**
   * A generator of strings whose size falls within the specified bounds.
   */
  def stringBounded[R](min: Int, max: Int)(g: Gen[R, Char])(implicit
    trace: Trace
  ): Gen[R, String] =
    bounded(min, max)(stringN(_)(g))

  /**
   * A generator of strings of the specified size.
   */
  def stringN[R](n: Int)(char: Gen[R, Char])(implicit trace: Trace): Gen[R, String] =
    listOfN(n)(char).map(_.mkString)

  /**
   * Lazily constructs a generator. This is useful to avoid infinite recursion
   * when creating generators that refer to themselves.
   */
  def suspend[R, A](gen: => Gen[R, A])(implicit trace: Trace): Gen[R, A] =
    fromZIO(ZIO.succeed(gen)).flatten

  /**
   * A generator of throwables.
   */
  def throwable(implicit trace: Trace): Gen[Any, Throwable] =
    Gen.const(new Throwable)

  /**
   * A sized generator of collections, where each collection is generated by
   * repeatedly applying a function to an initial state.
   */
  def unfoldGen[R, S, A](s: S)(f: S => Gen[R, (S, A)])(implicit
    trace: Trace
  ): Gen[R, List[A]] =
    small(unfoldGenN(_)(s)(f))

  /**
   * A generator of collections of up to the specified size, where each
   * collection is generated by repeatedly applying a function to an initial
   * state.
   */
  def unfoldGenN[R, S, A](n: Int)(s: S)(f: S => Gen[R, (S, A)])(implicit trace: Trace): Gen[R, List[A]] = {

    def loop(n: Int, s: S, as: List[A]): Gen[R, List[A]] =
      if (n <= 0)
        Gen.const(as.reverse)
      else
        f(s).flatMap { case (s, a) => loop(n - 1, s, a :: as) }

    loop(n, s, Nil)
  }

  /**
   * A generator of Unicode characters. Shrinks toward '0'.
   */
  def unicodeChar(implicit trace: Trace): Gen[Any, Char] =
    Gen.oneOf(Gen.char('\u0000', '\uD7FF'), Gen.char('\uE000', '\uFFFD'))

  /**
   * A generator of uniformly distributed doubles between [0, 1]. The shrinker
   * will shrink toward `0`.
   */
  def uniform(implicit trace: Trace): Gen[Any, Double] =
    fromZIOSample(nextDouble.map(Sample.shrinkFractional(0.0)))

  /**
   * A constant generator of the unit value.
   */
  def unit(implicit trace: Trace): Gen[Any, Unit] =
    const(())

  /**
   * A generator of universally unique identifiers. The returned generator will
   * not have any shrinking.
   */
  def uuid(implicit trace: Trace): Gen[Any, UUID] =
    Gen.fromZIO(nextUUID)

  /**
   * A sized generator of vectors.
   */
  def vectorOf[R, A](g: Gen[R, A])(implicit trace: Trace): Gen[R, Vector[A]] =
    listOf(g).map(_.toVector)

  /**
   * A sized generator of non-empty vectors.
   */
  def vectorOf1[R, A](g: Gen[R, A])(implicit trace: Trace): Gen[R, Vector[A]] =
    listOf1(g).map(_.toVector)

  /**
   * A generator of vectors whose size falls within the specified bounds.
   */
  def vectorOfBounded[R, A](min: Int, max: Int)(g: Gen[R, A])(implicit
    trace: Trace
  ): Gen[R, Vector[A]] =
    bounded(min, max)(vectorOfN(_)(g))

  /**
   * A generator of vectors of the specified size.
   */
  def vectorOfN[R, A](n: Int)(g: Gen[R, A])(implicit trace: Trace): Gen[R, Vector[A]] =
    listOfN(n)(g).map(_.toVector)

  /**
   * A generator which chooses one of the given generators according to their
   * weights. For example, the following generator will generate 90% true and
   * 10% false values.
   * {{{
   * val trueFalse = Gen.weighted((Gen.const(true), 9), (Gen.const(false), 1))
   * }}}
   */
  def weighted[R, A](gs: (Gen[R, A], Double)*)(implicit trace: Trace): Gen[R, A] = {
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
  def whitespaceChars(implicit trace: Trace): Gen[Any, Char] =
    Gen.elements((Char.MinValue to Char.MaxValue).filter(_.isWhitespace): _*)

  /**
   * Restricts an integer to the specified range.
   */
  private def clamp(n: Int, min: Int, max: Int): Int =
    if (n < min) min
    else if (n > max) max
    else n

  private val defaultShrinker: Any => ZStream[Any, Nothing, Nothing] =
    _ => ZStream.empty(Trace.empty)
}
