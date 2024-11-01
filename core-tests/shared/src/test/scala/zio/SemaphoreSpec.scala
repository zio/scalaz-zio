package zio

import zio._
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
    } @@ exceptJS(nonFlaky),
    test("withPermit acquire is interruptible") {
      for {
        semaphore <- Semaphore.make(0L)
        effect     = semaphore.withPermit(ZIO.unit)
        fiber     <- effect.fork
        _         <- fiber.interrupt
      } yield assertCompletes
    } @@ exceptJS(nonFlaky),
    test("withPermitsScoped releases same number of permits") {
      for {
        semaphore <- Semaphore.make(2L)
        _         <- ZIO.scoped(semaphore.withPermitsScoped(2))
        permits   <- semaphore.available
      } yield assertTrue(permits == 2L)
    },
    test("awaiting returns the count of waiting fibers") {
      for {
        semaphore <- Semaphore.make(1)
        promise   <- Promise.make[Nothing, Unit]
        _         <- semaphore.withPermit(promise.await).fork
        _         <- ZIO.foreach(List.fill(10)(()))(_ => semaphore.withPermit(ZIO.unit).fork)
        waitingStart <- semaphore.awaiting
                          .flatMap(awaiting => if (awaiting < 10) ZIO.fail(awaiting) else ZIO.succeed(awaiting))
                          .retryUntil(_ == 10)
                          .catchAll(n => ZIO.succeed(n))
        _ <- promise.succeed(())

        waitingEnd <- semaphore.awaiting
                        .flatMap(awaiting => if (awaiting > 0) ZIO.fail(awaiting) else ZIO.succeed(awaiting))
                        .retryUntil(_ == 0)
                        .catchAll(n => ZIO.succeed(n))
      } yield assertTrue(waitingStart == 10) && assertTrue(waitingEnd == 0)
    }
  )
}
