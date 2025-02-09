package zio.test

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.TestRandom.{DefaultData, Test => ZRandom}

import java.util.UUID
import scala.util.{Random => SRandom}

object RandomSpec extends ZIOBaseSpec {

  def spec = suite("RandomSpec")(
    test("check clearBooleans")(checkClear(_.nextBoolean())(_.feedBooleans(_: _*))(_.clearBooleans)(_.nextBoolean)),
    test("check clearBytes")(checkClear(nextBytes(1))(_.feedBytes(_: _*))(_.clearBytes)(_.nextBytes(1))),
    test("check clearChars")(
      checkClear(_.nextPrintableChar())(_.feedChars(_: _*))(_.clearChars)(_.nextPrintableChar)
    ),
    test("check clearDoubles")(checkClear(_.nextDouble())(_.feedDoubles(_: _*))(_.clearDoubles)(_.nextDouble)),
    test("check clearFloats")(checkClear(_.nextFloat())(_.feedFloats(_: _*))(_.clearFloats)(_.nextFloat)),
    test("check clearInts")(checkClear(_.nextInt())(_.feedInts(_: _*))(_.clearInts)(_.nextInt)),
    test("check clearLongs")(checkClear(_.nextLong())(_.feedLongs(_: _*))(_.clearLongs)(_.nextLong)),
    test("check clearStrings")(checkClear(_.nextString(1))(_.feedStrings(_: _*))(_.clearStrings)(_.nextString(1))),
    test("check clearUUIDs")(checkClear(nextUUID)(_.feedUUIDs(_: _*))(_.clearUUIDs)(_.nextUUID)),
    test("check feedBooleans")(checkFeed(_.nextBoolean())(_.feedBooleans(_: _*))(_.nextBoolean)),
    test("check feedBytes")(checkFeed(nextBytes(1))(_.feedBytes(_: _*))(_.nextBytes(1))),
    test("check feedChars")(checkFeed(_.nextPrintableChar())(_.feedChars(_: _*))(_.nextPrintableChar)),
    test("check feedDoubles")(checkFeed(_.nextDouble())(_.feedDoubles(_: _*))(_.nextDouble)),
    test("check feedFloats")(checkFeed(_.nextFloat())(_.feedFloats(_: _*))(_.nextFloat)),
    test("check feedInts")(checkFeed(_.nextInt())(_.feedInts(_: _*))(_.nextInt)),
    test("check feedLongs")(checkFeed(_.nextLong())(_.feedLongs(_: _*))(_.nextLong)),
    test("check feedStrings")(checkFeed(_.nextString(1))(_.feedStrings(_: _*))(_.nextString(1))),
    test("check feedUUIDs")(checkFeed(nextUUID)(_.feedUUIDs(_: _*))(_.nextUUID)),
    test("check nextBoolean")(forAllEqual(_.nextBoolean)(_.nextBoolean())),
    test("check nextBytes")(forAllEqualBytes),
    test("check nextDouble")(forAllEqual(_.nextDouble)(_.nextDouble())),
    test("check nextFloat")(forAllEqual(_.nextFloat)(_.nextFloat())),
    test("check nextGaussian")(forAllEqualGaussian),
    test("check nextInt")(forAllEqual(_.nextInt)(_.nextInt())),
    test("check nextLong")(forAllEqual(_.nextLong)(_.nextLong())),
    test("check nextPrintableChar")(forAllEqual(_.nextPrintableChar)(_.nextPrintableChar())),
    test("check nextString")(forAllEqualN(_.nextString(_))(_.nextString(_))),
    test("check nextUUID")(forAllEqual(_.nextUUID)(nextUUID)),
    test("check nextIntBounded")(forAllEqualN(_.nextIntBounded(_))(_.nextInt(_))),
    test("nextIntBounded generates values within the bounds")(forAllBounded(Gen.int)(_.nextIntBounded(_))),
    test("nextLongBounded generates values within the bounds")(forAllBounded(Gen.long)(_.nextLongBounded(_))),
    test("nextDoubleBetween generates doubles within the bounds")(
      forAllBetween(Gen.double)(_.nextDoubleBetween(_, _))
    ),
    test("nextFloatBetween generates floats within the bounds")(
      forAllBetween(Gen.float)(_.nextFloatBetween(_, _))
    ),
    test("nextIntBetween generates integers within the bounds")(forAllBetween(Gen.int)(_.nextIntBetween(_, _))),
    test("nextLongBetween generates longs within the bounds")(forAllBetween(Gen.long)(_.nextLongBetween(_, _))),
    test("shuffle")(forAllEqualShuffle(_.shuffle(_))(_.shuffle(_))),
    test("referential transparency") {
      val test = TestRandom.makeTest(DefaultData)
      ZIO
        .runtime[Any]
        .map { rt =>
          val x = Unsafe
            .unsafe(implicit unsafe =>
              rt.unsafe.run(test.flatMap[Any, Nothing, Int](_.nextInt)).getOrThrowFiberFailure()
            )
          val y = Unsafe
            .unsafe(implicit unsafe =>
              rt.unsafe.run(test.flatMap[Any, Nothing, Int](_.nextInt)).getOrThrowFiberFailure()
            )
          assert(x)(equalTo(y))
        }
    },
    test("check fed ints do not survive repeating tests") {
      for {
        _      <- TestRandom.setSeed(5)
        value  <- Random.nextInt
        value2 <- Random.nextInt
        _      <- TestRandom.feedInts(1, 2)
      } yield assert(value)(equalTo(-1157408321)) && assert(value2)(equalTo(758500184))
    } @@ exceptJS(nonFlaky),
    test("getting the seed and setting the seed is an identity") {
      check(Gen.long) { seed =>
        for {
          _        <- TestRandom.setSeed(seed)
          newSeed  <- TestRandom.getSeed
          value    <- Random.nextInt
          _        <- TestRandom.setSeed(newSeed)
          newValue <- Random.nextInt
        } yield assert(newSeed)(equalTo(seed & ((1L << 48) - 1))) &&
          assert(newValue)(equalTo(value))
      }
    },
    test("chunks of bytes are properly handled") {
      for {
        _     <- TestRandom.feedBytes(Chunk(1, 2, 3, 4, 5).map(_.toByte))
        bytes <- Random.nextBytes(2)
      } yield assert(bytes)(equalTo(Chunk(1, 2).map(_.toByte)))
    }
  )

  def checkClear[A, B <: Random](generate: SRandom => A)(feed: (ZRandom, List[A]) => UIO[Unit])(
    clear: ZRandom => UIO[Unit]
  )(extract: ZRandom => UIO[A]): UIO[TestResult] =
    check(Gen.long) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        value      <- ZIO.succeed(generate(sRandom))
        _          <- feed(testRandom, List(value))
        _          <- clear(testRandom)
        random     <- extract(testRandom)
        expected   <- ZIO.succeed(generate(new SRandom(seed)))
      } yield assert(random)(equalTo(expected))
    }

  def checkFeed[A, B >: Random](generate: SRandom => A)(
    feed: (ZRandom, List[A]) => UIO[Unit]
  )(extract: ZRandom => UIO[A]): UIO[TestResult] =
    check(Gen.long) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        values     <- ZIO.succeed(List.fill(100)(generate(sRandom)))
        _          <- feed(testRandom, values)
        results    <- ZIO.foreach(List.range(0, 100))(_ => extract(testRandom))
        random     <- extract(testRandom)
        expected   <- ZIO.succeed(generate(new SRandom(seed)))
      } yield {
        assert(results)(equalTo(values)) &&
        assert(random)(equalTo(expected))
      }
    }

  def nextBytes(n: Int)(random: SRandom): Chunk[Byte] = {
    val arr = new Array[Byte](n)
    random.nextBytes(arr)
    Chunk.fromArray(arr)
  }

  def nextUUID(random: SRandom): UUID = {
    val mostSigBits  = random.nextLong()
    val leastSigBits = random.nextLong()
    new UUID(
      (mostSigBits & ~0x0000f000) | 0x00004000,
      (leastSigBits & ~(0xc0000000L << 32)) | (0x80000000L << 32)
    )
  }

  def forAllEqual[A](
    f: ZRandom => UIO[A]
  )(g: SRandom => A): UIO[TestResult] =
    check(Gen.long) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- ZIO.foreach(List.fill(100)(()))(_ => f(testRandom))
        expected   <- ZIO.succeed(List.fill(100)(g(sRandom)))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualBytes: UIO[TestResult] =
    check(Gen.long) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- ZIO.foreach(List.range(0, 100))(testRandom.nextBytes(_))
        expected <- ZIO.succeed(List.range(0, 100).map(new Array[Byte](_)).map { arr =>
                      sRandom.nextBytes(arr)
                      Chunk.fromArray(arr)
                    })
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualGaussian: UIO[TestResult] =
    check(Gen.long) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- testRandom.nextGaussian
        expected   <- ZIO.succeed(sRandom.nextGaussian())
      } yield assert(actual)(approximatelyEquals(expected, 0.01))
    }

  def forAllEqualN[A](
    f: (ZRandom, Int) => UIO[A]
  )(g: (SRandom, Int) => A): UIO[TestResult] =
    check(Gen.long, Gen.int(1, 100)) { (seed, size) =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- f(testRandom, size)
        expected   <- ZIO.succeed(g(sRandom, size))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualShuffle(
    f: (ZRandom, List[Int]) => UIO[List[Int]]
  )(g: (SRandom, List[Int]) => List[Int]): ZIO[Any, Nothing, TestResult] =
    check(Gen.long, Gen.listOf(Gen.int)) { (seed, testList) =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- f(testRandom, testList)
        expected   <- ZIO.succeed(g(sRandom, testList))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllBounded[A: Numeric](gen: Gen[Any, A])(
    next: (Random, A) => UIO[A]
  ): UIO[TestResult] = {
    val num = implicitly[Numeric[A]]
    import num._
    check(gen.map(num.abs(_))) { upper =>
      for {
        testRandom <- ZIO.random
        nextRandom <- next(testRandom, upper)
      } yield assert(nextRandom)(isWithin(zero, upper))
    }
  }

  def forAllBetween[A: Numeric](gen: Gen[Any, A])(
    between: (Random, A, A) => UIO[A]
  ): UIO[TestResult] = {
    val num = implicitly[Numeric[A]]
    import num._
    val genMinMax = for {
      value1 <- gen
      value2 <- gen if (value1 != value2)
    } yield if (value2 > value1) (value1, value2) else (value2, value1)
    check(genMinMax) { case (min, max) =>
      for {
        testRandom <- ZIO.random
        nextRandom <- between(testRandom, min, max)
      } yield assert(nextRandom)(isGreaterThanEqualTo(min)) &&
        assert(nextRandom)(isLessThan(max))
    }
  }
}
