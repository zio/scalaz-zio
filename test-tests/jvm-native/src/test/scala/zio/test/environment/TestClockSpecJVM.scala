package zio.test.environment

import zio._
import zio.test.Assertion._
import zio.test.TestAspect.{nonFlaky, timeout}
import zio.test.{TestClock, ZIOBaseSpec, assert, assertCompletes}

import java.util.concurrent.TimeUnit

object TestClockSpecJVM extends ZIOBaseSpec {

  def spec =
    suite("TestClockSpecJVM")(
      suite("asScheduledExecutorService")(
        test("schedules tasks at fixed rate correctly") {
          for {
            runtime                 <- ZIO.runtime[Any]
            ref                     <- Ref.make[List[Long]](List.empty)
            clock                   <- ZIO.clock
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleAtFixedRate(
                     new Runnable {
                       def run(): Unit =
                         Unsafe.unsafe { implicit unsafe =>
                           runtime.unsafe.run {
                             clock.sleep(2.seconds) *>
                               clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                           }.getOrThrowFiberFailure()
                         }
                     },
                     3,
                     5,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(25.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L, 10L, 15L, 20L, 25L)))
        },
        test("does not allow tasks to pile up") {
          for {
            runtime                 <- ZIO.runtime[Any]
            ref                     <- Ref.make[List[Long]](List.empty)
            clock                   <- ZIO.clock
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleAtFixedRate(
                     new Runnable {
                       def run(): Unit =
                         Unsafe.unsafe { implicit unsafe =>
                           runtime.unsafe.run {
                             clock.sleep(5.seconds) *>
                               clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                           }.getOrThrowFiberFailure()
                         }
                     },
                     3,
                     2,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(28.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(8L, 13L, 18L, 23L, 28L)))
        },
        test("schedules tasks with fixed delay correctly") {
          for {
            runtime                 <- ZIO.runtime[Any]
            ref                     <- Ref.make[List[Long]](List.empty)
            clock                   <- ZIO.clock
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            _ <- ZIO.succeed {
                   scheduledExecutorService.scheduleWithFixedDelay(
                     new Runnable {
                       def run(): Unit =
                         Unsafe.unsafe { implicit unsafe =>
                           runtime.unsafe.run {
                             clock.sleep(2.seconds) *>
                               clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _))
                           }.getOrThrowFiberFailure()
                         }
                     },
                     3,
                     5,
                     TimeUnit.SECONDS
                   )
                 }
            _      <- TestClock.adjust(33.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L, 12L, 19L, 26L, 33L)))
        },
        test("allows scheduled tasks to be interrupted") {
          for {
            runtime                 <- ZIO.runtime[Any]
            ref                     <- Ref.make[List[Long]](List.empty)
            clock                   <- ZIO.clock
            scheduler               <- ZIO.blocking(Clock.scheduler)
            scheduledExecutorService = scheduler.asScheduledExecutorService
            promise                 <- Promise.make[Nothing, Unit]
            future <- ZIO.succeed {
                        scheduledExecutorService.scheduleAtFixedRate(
                          new Runnable {
                            def run(): Unit =
                              Unsafe.unsafe { implicit unsafe =>
                                runtime.unsafe.run {
                                  (promise.succeed(()) *> clock.sleep(2.seconds) *>
                                    clock.currentTime(TimeUnit.SECONDS).flatMap(now => ref.update(now :: _)))
                                }.getOrThrowFiberFailure()
                              }
                          },
                          3,
                          5,
                          TimeUnit.SECONDS
                        )
                      }
            _      <- TestClock.adjust(3.seconds)
            _      <- promise.await
            _      <- TestClock.adjust(4.seconds)
            _      <- ZIO.succeed(future.cancel(false))
            _      <- TestClock.adjust(11.seconds)
            values <- ref.get
          } yield assert(values.reverse)(equalTo(List(5L)))
        }
      ),
      suite("adjust")(
        test("is thread safe") {
          (TestClock.adjust(1.second) &> TestClock.adjust(1.second)).as(assertCompletes)
        } @@ timeout(10.seconds)
      )
    ) @@ nonFlaky(20)
}
