package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object SemaphoreSpec extends ZIOBaseSpec {
  override def spec = suite("SemaphoreSpec")(
    test("withPermit automatically releases the permit if the effect is interrupted") {
      for {
        promise   <- Promise.make[Nothing, Unit]
        semaphore <- Semaphore.make(1)
        effect     = semaphore.withPermit(promise.succeed(()) *> ZIO.never)
        fiber     <- effect.fork
        _         <- promise.await
        _         <- fiber.interrupt
        permits   <- semaphore.available
      } yield assert(permits)(equalTo(1L))
    },
    test("withPermit acquire is interruptible") {
      for {
        semaphore <- Semaphore.make(0L)
        effect     = semaphore.withPermit(ZIO.unit)
        fiber     <- effect.fork
        _         <- fiber.interrupt
      } yield assertCompletes
    },
    test("withPermitsScoped releases same number of permits") {
      for {
        semaphore <- Semaphore.make(2L)
        _         <- ZIO.scoped(semaphore.withPermitsScoped(2))
        permits   <- semaphore.available
      } yield assertTrue(permits == 2L)
    },
    test("tryAcquire should succeed when a permit is available") {
      for {
        sem <- Semaphore.make(1L)
        res <- sem.tryAcquire
      } yield assert(res)(isTrue)
    },
    test("tryAcquireN should acquire permits if enough are available") {
      for {
        sem <- Semaphore.make(5L)
        res <- sem.tryAcquireN(3L)
      } yield assert(res)(isTrue)
    },
    test("tryAcquireN should fail if not enough permits are available") {
      for {
        sem <- Semaphore.make(2L)
        res <- sem.tryAcquireN(3L)
      } yield assert(res)(isFalse)
    },
    test("tryAcquireN should decrease the permit count when successful") {
      for {
        sem   <- Semaphore.make(5L)
        _     <- sem.tryAcquireN(3L)
        avail <- sem.available
      } yield assert(avail)(equalTo(2L))
    },
    test("tryAcquireN should not change permit count when unsuccessful") {
      for {
        sem   <- Semaphore.make(2L)
        _     <- sem.tryAcquireN(3L)
        avail <- sem.available
      } yield assert(avail)(equalTo(2L))
    },
    test("awaiting returns the count of waiting fibers") {
      for {
        semaphore    <- Semaphore.make(1)
        promise      <- Promise.make[Nothing, Unit]
        _            <- ZIO.foreachDiscard(1 to 11)(_ => semaphore.withPermit(promise.await).fork)
        waitingStart <- semaphore.awaiting.repeatUntil(_ == 10)
        _            <- promise.succeed(())
        waitingEnd   <- semaphore.awaiting.repeatUntil(_ == 0)
      } yield assertTrue(waitingStart == 10, waitingEnd == 0)
    } @@ timeout(10.seconds)
  ) @@ exceptJS(nonFlaky)
}
