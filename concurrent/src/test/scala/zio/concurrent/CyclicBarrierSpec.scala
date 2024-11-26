package zio.concurrent

import zio.test.Assertion._
import zio.test._
import zio._

object CyclicBarrierSpec extends ZIOSpecDefault {
  private val parties = 100

  val spec =
    suite("CyclicBarrierSpec")(
      test("Construction") {
        for {
          barrier  <- CyclicBarrier.make(parties)
          isBroken <- barrier.isBroken
          waiting  <- barrier.waiting
        } yield assert(barrier.parties)(equalTo(parties)) &&
          assert(isBroken)(equalTo(false)) &&
          assert(waiting)(equalTo(0))
      },
      test("Releases the barrier") {
        for {
          barrier <- CyclicBarrier.make(2)
          f1      <- barrier.await.fork
          _       <- f1.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          f2      <- barrier.await.fork
          ticket1 <- f1.join
          ticket2 <- f2.join
        } yield assert(ticket1)(equalTo(1)) &&
          assert(ticket2)(equalTo(0))
      },
      test("Releases the barrier and performs the action") {
        for {
          promise    <- Promise.make[Nothing, Unit]
          barrier    <- CyclicBarrier.make(2, promise.succeed(()))
          f1         <- barrier.await.fork
          _          <- f1.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          f2         <- barrier.await.fork
          _          <- f1.join
          _          <- f2.join
          isComplete <- promise.isDone
        } yield assert(isComplete)(isTrue)
      },
      test("Releases the barrier and cycles") {
        for {
          barrier <- CyclicBarrier.make(2)
          f1      <- barrier.await.fork
          _       <- f1.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          f2      <- barrier.await.fork
          ticket1 <- f1.join
          ticket2 <- f2.join
          f3      <- barrier.await.fork
          _       <- f3.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          f4      <- barrier.await.fork
          ticket3 <- f3.join
          ticket4 <- f4.join
        } yield assert(ticket1)(equalTo(1)) &&
          assert(ticket2)(equalTo(0)) &&
          assert(ticket3)(equalTo(1)) &&
          assert(ticket4)(equalTo(0))
      },
      test("Breaks on reset") {
        for {
          barrier <- CyclicBarrier.make(parties)
          f1      <- barrier.await.fork
          f2      <- barrier.await.fork
          _       <- f1.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          _       <- f2.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          _       <- barrier.reset
          res1    <- f1.await
          res2    <- f2.await
        } yield assert(res1)(fails(isUnit)) && assert(res2)(fails(isUnit))
      },
      test("Breaks on party interruption") {
        for {
          barrier   <- CyclicBarrier.make(parties)
          f1        <- barrier.await.timeout(1.second).fork
          f2        <- barrier.await.fork
          _         <- f1.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          _         <- f2.status.repeatWhile(!_.isInstanceOf[Fiber.Status.Suspended])
          isBroken1 <- barrier.isBroken
          _         <- TestClock.adjust(1.second)
          isBroken2 <- barrier.isBroken
          res1      <- f1.await
          res2      <- f2.await
        } yield assert(isBroken1)(isFalse) &&
          assert(isBroken2)(isTrue) &&
          assert(res1)(succeeds(isNone)) &&
          assert(res2)(fails(isUnit))
      }
    )

}
