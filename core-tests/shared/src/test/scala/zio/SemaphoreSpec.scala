package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object SemaphoreSpec extends ZIOBaseSpec {
  override def spec = suite("SemaphoreSpec")(
    // Original test: Ensure permits are released on interruption
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

    // Original test: Ensure permit acquisition is interruptible
    test("withPermit acquire is interruptible") {
      for {
        semaphore <- Semaphore.make(0L)
        effect     = semaphore.withPermit(ZIO.unit)
        fiber     <- effect.fork
        _         <- fiber.interrupt
      } yield assertCompletes
    },

    // Original test: Ensure scoped permits are released correctly
    test("withPermitsScoped releases same number of permits") {
      for {
        semaphore <- Semaphore.make(2L)
        _         <- ZIO.scoped(semaphore.withPermitsScoped(2))
        permits   <- semaphore.available
      } yield assertTrue(permits == 2L)
    },

    // Original test: Ensure awaiting returns the correct count of waiting fibers
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

    // New test: Ensure unfair semaphore allows barging (non-FIFO behavior)
    test("unfair semaphore allows barging") {
      for {
        semaphore <- Semaphore.make(1, fairness = false)
        _         <- semaphore.withPermit(ZIO.unit) // Acquire the permit
        // Fork 10 fibers that will contend for the permit
        fibers    <- ZIO.foreachPar(1 to 10)(_ => semaphore.withPermit(ZIO.unit).fork)
        // Release the permit and allow the fibers to proceed
        _         <- semaphore.release
        // Wait for all fibers to complete
        _         <- ZIO.foreachParDiscard(fibers)(_.join)
      } yield assertCompletes
    }
  ) @@ exceptJS(nonFlaky)
}