package zio.test.environment

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.test.environment.TestRandom.{DefaultData, Test => ZRandom}

import scala.util.{Random => SRandom}

object RandomSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] = suite("RandomSpec")(
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
    test("check feedBooleans")(checkFeed(_.nextBoolean())(_.feedBooleans(_: _*))(_.nextBoolean)),
    test("check feedBytes")(checkFeed(nextBytes(1))(_.feedBytes(_: _*))(_.nextBytes(1))),
    test("check feedChars")(checkFeed(_.nextPrintableChar())(_.feedChars(_: _*))(_.nextPrintableChar)),
    test("check feedDoubles")(checkFeed(_.nextDouble())(_.feedDoubles(_: _*))(_.nextDouble)),
    test("check feedFloats")(checkFeed(_.nextFloat())(_.feedFloats(_: _*))(_.nextFloat)),
    test("check feedInts")(checkFeed(_.nextInt())(_.feedInts(_: _*))(_.nextInt)),
    test("check feedLongs")(checkFeed(_.nextLong())(_.feedLongs(_: _*))(_.nextLong)),
    test("check feedStrings")(checkFeed(_.nextString(1))(_.feedStrings(_: _*))(_.nextString(1))),
    test("check nextBoolean")(forAllEqual(_.nextBoolean)(_.nextBoolean())),
    test("check nextBytes")(forAllEqualBytes),
    test("check nextDouble")(forAllEqual(_.nextDouble)(_.nextDouble())),
    test("check nextFloat")(forAllEqual(_.nextFloat)(_.nextFloat())),
    test("check nextGaussian")(forAllEqualGaussian),
    test("check nextInt")(forAllEqual(_.nextInt)(_.nextInt())),
    test("check nextLong")(forAllEqual(_.nextLong)(_.nextLong())),
    test("check nextPrintableChar")(forAllEqual(_.nextPrintableChar)(_.nextPrintableChar())),
    test("check nextString")(forAllEqualN(_.nextString(_))(_.nextString(_))),
    test("check nextIntBounded")(forAllEqualN(_.nextIntBounded(_))(_.nextInt(_))),
    test("nextIntBounded generates values within the bounds")(forAllBounded(Gen.anyInt)(_.nextIntBounded(_))),
    test("nextLongBounded generates values within the bounds")(forAllBounded(Gen.anyLong)(_.nextLongBounded(_))),
    test("nextDoubleBetween generates doubles within the bounds")(
      forAllBetween(Gen.anyDouble)(_.nextDoubleBetween(_, _))
    ),
    test("nextFloatBetween generates floats within the bounds")(
      forAllBetween(Gen.anyFloat)(_.nextFloatBetween(_, _))
    ),
    test("nextIntBetween generates integers within the bounds")(forAllBetween(Gen.anyInt)(_.nextIntBetween(_, _))),
    test("nextLongBetween generates longs within the bounds")(forAllBetween(Gen.anyLong)(_.nextLongBetween(_, _))),
    test("shuffle")(forAllEqualShuffle(_.shuffle(_))(_.shuffle(_))),
    test("referential transparency") {
      val test = TestRandom.makeTest(DefaultData)
      ZIO
        .runtime[Any]
        .map { rt =>
          val x = rt.unsafeRun(test.flatMap[Any, Nothing, Int](_.nextInt))
          val y = rt.unsafeRun(test.flatMap[Any, Nothing, Int](_.nextInt))
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
    } @@ nonFlaky,
    test("getting the seed and setting the seed is an identity") {
      checkM(Gen.anyLong) { seed =>
        for {
          _        <- TestRandom.setSeed(seed)
          newSeed  <- TestRandom.getSeed
          value    <- Random.nextInt
          _        <- TestRandom.setSeed(newSeed)
          newValue <- Random.nextInt
        } yield assert(newSeed)(equalTo(seed & ((1L << 48) - 1))) &&
          assert(newValue)(equalTo(value))
      }
    }
  )

  def checkClear[A, B <: Has[Random]](generate: SRandom => A)(feed: (ZRandom, List[A]) => UIO[Unit])(
    clear: ZRandom => UIO[Unit]
  )(extract: ZRandom => UIO[A]): URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong) { seed =>
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

  def checkFeed[A, B >: Has[Random]](generate: SRandom => A)(
    feed: (ZRandom, List[A]) => UIO[Unit]
  )(extract: ZRandom => UIO[A]): URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        values     <- ZIO.succeed(List.fill(100)(generate(sRandom)))
        _          <- feed(testRandom, values)
        results    <- UIO.foreach(List.range(0, 100))(_ => extract(testRandom))
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

  def forAllEqual[A](
    f: ZRandom => UIO[A]
  )(g: SRandom => A): URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- UIO.foreach(List.fill(100)(()))(_ => f(testRandom))
        expected   <- ZIO.succeed(List.fill(100)(g(sRandom)))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualBytes: URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong) { seed =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- UIO.foreach(List.range(0, 100))(testRandom.nextBytes(_))
        expected <- ZIO.succeed(List.range(0, 100).map(new Array[Byte](_)).map { arr =>
                      sRandom.nextBytes(arr)
                      Chunk.fromArray(arr)
                    })
      } yield assert(actual)(equalTo(expected))
    }

  def forAllEqualGaussian: URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong) { seed =>
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
  )(g: (SRandom, Int) => A): URIO[Has[Random] with Has[TestConfig], TestResult] =
    checkM(Gen.anyLong, Gen.int(1, 100)) { (seed, size) =>
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
  )(g: (SRandom, List[Int]) => List[Int]): ZIO[Has[Random] with Has[Sized] with Has[TestConfig], Nothing, TestResult] =
    checkM(Gen.anyLong, Gen.listOf(Gen.anyInt)) { (seed, testList) =>
      for {
        sRandom    <- ZIO.succeed(new SRandom(seed))
        testRandom <- TestRandom.makeTest(DefaultData)
        _          <- testRandom.setSeed(seed)
        actual     <- f(testRandom, testList)
        expected   <- ZIO.succeed(g(sRandom, testList))
      } yield assert(actual)(equalTo(expected))
    }

  def forAllBounded[A: Numeric](gen: Gen[Has[Random], A])(
    next: (Random, A) => UIO[A]
  ): URIO[Has[Random] with Has[TestConfig], TestResult] = {
    val num = implicitly[Numeric[A]]
    import num._
    checkM(gen.map(num.abs(_))) { upper =>
      for {
        testRandom <- ZIO.environment[Has[Random]].map(_.get[Random])
        nextRandom <- next(testRandom, upper)
      } yield assert(nextRandom)(isWithin(zero, upper))
    }
  }

  def forAllBetween[A: Numeric](gen: Gen[Has[Random], A])(
    between: (Random, A, A) => UIO[A]
  ): URIO[Has[Random] with Has[TestConfig], TestResult] = {
    val num = implicitly[Numeric[A]]
    import num._
    val genMinMax = for {
      value1 <- gen
      value2 <- gen if (value1 != value2)
    } yield if (value2 > value1) (value1, value2) else (value2, value1)
    checkM(genMinMax) { case (min, max) =>
      for {
        testRandom <- ZIO.environment[Has[Random]].map(_.get[Random])
        nextRandom <- between(testRandom, min, max)
      } yield assert(nextRandom)(isGreaterThanEqualTo(min)) &&
        assert(nextRandom)(isLessThan(max))
    }
  }
}
