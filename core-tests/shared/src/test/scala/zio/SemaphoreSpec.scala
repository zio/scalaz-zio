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
        permits   <- semaphore.available
      } yield assertTrue(permits == 0L)
    },
    test("withPermitsScoped releases same number of permits") {
      for {
        semaphore <- Semaphore.make(2L)
        _         <- ZIO.scoped(semaphore.withPermitsScoped(2))
        permits   <- semaphore.available
      } yield assertTrue(permits == 2L)
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
    } @@ timeout(10.seconds),
    test("withPermits can trigger multiple effects on release") {
      for {
        semaphore <- Semaphore.make(2)
        prom1     <- Promise.make[Nothing, Unit]
        prom2     <- Promise.make[Nothing, Unit]
        fiber     <- semaphore.withPermits(2)(ZIO.never).fork
        _         <- semaphore.withPermit(prom1.succeed(()) *> ZIO.never).fork
        _         <- semaphore.withPermit(prom2.succeed(()) *> ZIO.never).fork
        _         <- fiber.interrupt
        _         <- prom1.await &> prom2.await
      } yield assertCompletes
    },
    test("withPermit acquire resets properly on interrupt") {
      for {
        semaphore    <- Semaphore.make(1L)
        delayPromise <- Promise.make[Nothing, Unit]
        ready        <- Promise.make[Nothing, Unit]
        fiber        <- semaphore.withPermit(ready.succeed(()) *> delayPromise.await).fork
        _            <- ready.await
        waiters      <- ZIO.replicateZIO(10)(semaphore.withPermit(ZIO.never).fork)
        _            <- waiters.head.interrupt
        available1   <- semaphore.available
        _            <- delayPromise.succeed(())
        _            <- ZIO.foreachDiscard(waiters.tail)(_.interrupt) &> fiber.join
        available2   <- semaphore.available
      } yield assertTrue(available1 == 0, available2 == 1L)
    } @@ jvm(nonFlaky),
    test("interrupted withPermit with partial permits releases correct number") {
      for {
        semaphore  <- Semaphore.make(3L)
        promise    <- Promise.make[Nothing, Unit]
        fiber1     <- semaphore.withPermits(2)(promise.await).fork
        fiber2     <- semaphore.withPermits(4)(ZIO.never).fork
        _          <- promise.succeed(()) *> fiber1.join
        available1 <- semaphore.available
        _          <- fiber2.interrupt
        available2 <- semaphore.available
      } yield assertTrue(available1 == 0, available2 == 3)
    }
  ) @@ exceptJS(nonFlaky)
}
