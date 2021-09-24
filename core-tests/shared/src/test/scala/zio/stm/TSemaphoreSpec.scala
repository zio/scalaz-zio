package zio.stm

import zio._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object TSemaphoreSpec extends ZIOBaseSpec {
  override def spec: ZSpec[Environment, Failure] = suite("TSemaphore")(
    suite("factories")(
      test("make") {
        checkM(Gen.long(1L, Int.MaxValue)) { expected =>
          val actual = for {
            sem <- TSemaphore.make(expected)
            cap <- sem.available
          } yield cap

          assertM(actual.commit)(equalTo(expected))
        }
      }
    ),
    suite("acquire and release")(
      test("acquiring and releasing a permit should not change the availability") {
        checkM(Gen.long(1L, Int.MaxValue)) { expected =>
          val actual = for {
            sem <- TSemaphore.make(expected)
            _   <- sem.acquire *> sem.release
            cap <- sem.available
          } yield cap
          assertM(actual.commit)(equalTo(expected))
        }
      },
      test("used capacity must be equal to the # of acquires minus # of releases") {
        checkM(usedCapacityGen) { case (capacity, acquire, release) =>
          val actual = for {
            sem <- TSemaphore.make(capacity)
            _   <- repeat(sem.acquire)(acquire) *> repeat(sem.release)(release)
            cap <- sem.available
          } yield cap

          val usedCapacity = acquire - release
          assertM(actual.commit)(equalTo(capacity - usedCapacity))
        }
      },
      test("acquireN/releaseN(n) is acquire/release repeated N times") {
        checkM(Gen.long(1, 100)) { capacity =>
          def acquireRelease(
            sem: TSemaphore
          )(acq: Long => STM[Nothing, Unit])(rel: Long => STM[Nothing, Unit]): STM[Nothing, (Long, Long)] =
            for {
              _            <- acq(capacity)
              usedCapacity <- sem.available
              _            <- rel(capacity)
              freeCapacity <- sem.available
            } yield (usedCapacity, freeCapacity)

          STM.atomically {
            for {
              sem              <- TSemaphore.make(capacity)
              acquireReleaseN   = acquireRelease(sem)(sem.acquireN)(sem.releaseN)
              acquireReleaseRep = acquireRelease(sem)(repeat(sem.acquire))(repeat(sem.release))
              resN             <- acquireReleaseN
              resRep           <- acquireReleaseRep
            } yield assert(resN)(equalTo(resRep)) && assert(resN)(equalTo((0L, capacity)))
          }
        }
      },
      test("withPermit automatically releases the permit if the effect is interrupted") {
        for {
          promise   <- Promise.make[Nothing, Unit]
          semaphore <- TSemaphore.make(1).commit
          effect     = semaphore.withPermit(promise.succeed(()) *> ZIO.never)
          fiber     <- effect.fork
          _         <- promise.await
          _         <- fiber.interrupt
          permits   <- semaphore.permits.get.commit
        } yield assert(permits)(equalTo(1L))
      } @@ nonFlaky,
      test("withPermit acquire is interruptible") {
        for {
          semaphore <- TSemaphore.make(0L).commit
          effect     = semaphore.withPermit(ZIO.unit)
          fiber     <- effect.fork
          _         <- fiber.interrupt
        } yield assertCompletes
      } @@ nonFlaky
    )
  )

  private def repeat[E, A](stm: STM[E, A])(n: Long): STM[E, A] = n match {
    case x if x < 1 => STM.die(new Throwable("n must be greater than 0"))
    case 1          => stm
    case x          => stm *> repeat(stm)(x - 1)
  }

  private val usedCapacityGen: Gen[Has[Random], (Long, Long, Long)] = for {
    capacity <- Gen.long(1L, 1000)
    acquire  <- Gen.long(1L, capacity)
    release  <- Gen.long(1L, acquire)
  } yield (capacity, acquire, release)
}
