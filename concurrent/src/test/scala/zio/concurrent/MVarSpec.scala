package zio.concurrent

import zio.test.Assertion._
import zio.test._
import zio._

object MVarSpec extends ZIOSpecDefault {
  val spec =
    suite("MVarSpec")(
      test("isEmpty") {
        for {
          v        <- MVar.empty[Unit]
          v2       <- MVar.make(())
          isEmpty1 <- v.isEmpty
          _        <- v.put(())
          isEmpty2 <- v.isEmpty
          isEmpty3 <- v2.isEmpty
          _        <- v2.take
          isEmpty4 <- v2.isEmpty
        } yield assert(isEmpty1)(isTrue) &&
          assert(isEmpty2)(isFalse) &&
          assert(isEmpty3)(isFalse) &&
          assert(isEmpty4)(isTrue)
      },
      test("blocking put and take") {
        for {
          v   <- MVar.empty[Int]
          _   <- ZIO.forkAll((1 to 100).map(i => v.put(i)))
          res <- ZIO.foreach(1 to 100)(_ => v.take)
        } yield assert(res)(hasSameElements(1 to 100))
      },
      test("non-blocking tryPut and tryTake") {
        for {
          v  <- MVar.empty[Unit]
          r1 <- v.tryPut(())
          r2 <- v.tryPut(())
          r3 <- v.tryTake
          r4 <- v.tryTake
        } yield assert(r1)(isTrue) && assert(r2)(isFalse) && assert(r3)(isSome(isUnit)) && assert(r4)(isNone)
      },
      test("swap") {
        for {
          v  <- MVar.make(true)
          r1 <- v.swap(false)
          r2 <- v.take
        } yield assert(r1)(isTrue) && assert(r2)(isFalse)
      },
      test("update") {
        for {
          v  <- MVar.empty[Boolean]
          f  <- v.update(!_).fork
          _  <- v.put(true)
          _  <- f.join
          r2 <- v.take
        } yield assert(r2)(isFalse)
      }
    )
}
