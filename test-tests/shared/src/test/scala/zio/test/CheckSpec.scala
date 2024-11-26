package zio.test

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.{Chunk, Random, Ref, ZIO}

object CheckSpec extends ZIOBaseSpec {

  def spec = suite("CheckSpec")(
    test("check is polymorphic in error type") {
      check(Gen.int(1, 100)) { n =>
        for {
          _ <- ZIO.attempt(())
          r <- Random.nextIntBounded(n)
        } yield assert(r)(isLessThan(n))
      }
    },
    test("effectual properties can be tested") {
      check(Gen.int(1, 100)) { n =>
        for {
          r <- Random.nextIntBounded(n)
        } yield assert(r)(isLessThan(n))
      }
    },
    test("error in check is test failure") {
      check(Gen.int(1, 100)) { n =>
        for {
          _ <- ZIO.fail("fail")
          r <- Random.nextIntBounded(n)
        } yield assert(r)(isLessThan(n))
      }
    } @@ failing,
    test("overloaded check methods work") {
      check(Gen.int, Gen.int, Gen.int)((x, y, z) => assert((x + y) + z)(equalTo(x + (y + z))))
    },
    test("max shrinks is respected") {
      val gen = Gen.listOfN(10)(Gen.int(-10, 10))
      for {
        ref <- Ref.make(0)
        _ <- check(gen <*> gen) { _ =>
               for {
                 _ <- ref.update(_ + 1)
                 p <- Random.nextIntBounded(10).map(_ != 0)
               } yield assert(p)(isTrue)
             }
        result <- ref.get
      } yield assert(result)(isLessThan(1200))
    },
    test("tests can be written in property based style") {
      val chunkWithLength = for {
        n      <- Gen.int(1, 100)
        i      <- Gen.int(0, n - 1)
        vector <- Gen.vectorOfN(n)(Gen.int(0, 100))
        chunk   = Chunk.fromIterable(vector)
      } yield (chunk, i)
      check(chunkWithLength) { case (chunk, i) =>
        assert(chunk.apply(i))(equalTo(chunk.toList.apply(i)))
      }
    },
    test("tests with filtered generators terminate") {
      check(Gen.int.filter(_ > 0), Gen.int.filter(_ > 0))((a, b) => assert(a)(equalTo(b)))
    } @@ failing,
    test("failing tests contain gen failure details") {
      check(Gen.int)(a => assert(a)(isGreaterThan(0))).map {
        _.failures match {
          case Some(result) =>
            result.getGenFailureDetails.fold(false)(_.shrunkenInput == 0)
          case _ => false
        }
      }.map(assert(_)(isTrue))
    },
    test("implication works correctly") {
      check(Gen.listOf1(Gen.int(-10, 10))) { ns =>
        val nss      = ns.sorted
        val nonEmpty = assert(nss)(hasSize(isGreaterThan(0)))
        val sorted   = assert(nss.zip(nss.tail).exists { case (a, b) => a > b })(isFalse)
        nonEmpty ==> sorted
      }
    },
    test("check effect type is correctly inferred") {
      check(Gen.unit) { _ =>
        for {
          _ <- Random.nextInt
        } yield assertCompletes
      }
    },
    test("checkAllPar effect type is correctly inferred") {
      checkAllPar(Gen.int, 2) { _ =>
        assertZIO(ZIO.unit)(equalTo(()))
      }
    },
    test("i9303") {
      checkAllPar(Gen.int, 4)(_ => assertCompletes)
    } @@ jvm(nonFlaky(1000))
  )
}
