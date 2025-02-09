package zio

import zio.test.Assertion._
import zio.test._

object NonEmptyChunkSpec extends ZIOBaseSpec {

  lazy val genChunk: Gen[Any, Chunk[Int]] = Gen.chunkOf(genInt)

  lazy val genInt: Gen[Any, Int] = Gen.int(-10, 10)

  lazy val genIntFunction: Gen[Any, Any => Int] = Gen.function(genInt)

  lazy val genIntFunction2: Gen[Any, (Any, Any) => Int] = Gen.function2(genInt)

  lazy val genNonEmptyChunk: Gen[Any, NonEmptyChunk[Int]] = Gen.chunkOf1(genInt)

  lazy val genNonEmptyChunkFunction: Gen[Any, Any => NonEmptyChunk[Int]] =
    Gen.function(genNonEmptyChunk)

  def spec = suite("NonEmptyChunkSpec")(
    test("+") {
      check(genNonEmptyChunk, genInt)((as, a) => assert((as :+ a).toChunk)(equalTo(as.toChunk :+ a)))
    },
    suite("++")(
      test("Chunk with NonEmptyChunk") {
        check(genChunk, genNonEmptyChunk)((as, bs) => assert((as ++ bs).toChunk)(equalTo(as ++ bs.toChunk)))
      },
      test("NonEmptyChunk with Chunk") {
        check(genNonEmptyChunk, genChunk)((as, bs) => assert((as ++ bs).toChunk)(equalTo(as.toChunk ++ bs)))
      },
      test("NonEmptyChunk with NonEmptyChunk") {
        check(genNonEmptyChunk, genNonEmptyChunk) { (as, bs) =>
          assert((as ++ bs).toChunk)(equalTo(as.toChunk ++ bs.toChunk))
        }
      }
    ),
    test("flatMap") {
      check(genNonEmptyChunk, genNonEmptyChunkFunction) { (as, f) =>
        assert(as.flatMap(f).toChunk)(equalTo(as.toChunk.flatMap(a => f(a).toChunk)))
      }
    },
    test("groupBy") {
      check(genNonEmptyChunk, genIntFunction)((as, f) =>
        assert(as.groupBy(f).map { case (k, v) => (k, v.toChunk) })(equalTo(as.toChunk.groupBy(f)))
      )
    },
    test("groupMap") {
      check(genNonEmptyChunk, genIntFunction, genIntFunction)((as, f, g) =>
        assert(as.groupMap(f)(g).map { case (k, v) => (k, v.toChunk) })(equalTo(as.toChunk.groupBy(f).map {
          case (k, v) =>
            (k, v.map(g))
        }))
      )
    },
    test("map") {
      check(genNonEmptyChunk, genIntFunction)((as, f) => assert(as.map(f).toChunk)(equalTo(as.toChunk.map(f))))
    },
    test("reduceMapLeft") {
      check(genNonEmptyChunk, genIntFunction, genIntFunction2) { (as, map, reduce) =>
        val actual   = as.reduceMapLeft(map)(reduce)
        val expected = as.tail.foldLeft(map(as.head))(reduce)
        assert(actual)(equalTo(expected))
      }
    },
    test("reduceMapRight") {
      check(genNonEmptyChunk, genIntFunction, genIntFunction2) { (as, map, reduce) =>
        val actual   = as.reduceMapRight(map)(reduce)
        val expected = as.init.foldRight(map(as.last))(reduce)
        assert(actual)(equalTo(expected))
      }
    },
    suite("unapplySeq")(
      test("matches a nonempty chunk") {
        val chunk = Chunk(1, 2, 3)
        val actual = chunk match {
          case NonEmptyChunk(x, y, z) => Some((x, y, z))
          case _                      => None
        }
        val expected = Some((1, 2, 3))
        assert(actual)(equalTo(expected))
      },
      test("does not match an empty chunk") {
        val chunk = Chunk.empty
        val actual = chunk match {
          case NonEmptyChunk(x, y, z) => Some((x, y, z))
          case _                      => None
        }
        val expected = None
        assert(actual)(equalTo(expected))
      },
      test("does not match another collection type") {
        val vector = Vector(1, 2, 3)
        val actual = vector match {
          case NonEmptyChunk(x, y, z) => Some((x, y, z))
          case _                      => None
        }
        val expected = None
        assert(actual)(equalTo(expected))
      },
      test("matches a NonEmptyChunk") {
        val nonEmptyChunk = NonEmptyChunk(1, 2, 3)
        val actual = nonEmptyChunk match {
          case NonEmptyChunk(x, y, z) => Some((x, y, z))
          case _                      => None
        }
        val expected = Some((1, 2, 3))
        assert(actual)(equalTo(expected))
      }
    )
  )
}
