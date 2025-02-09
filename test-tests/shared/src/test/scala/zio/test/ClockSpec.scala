package zio.test

import zio.Clock._
import zio.Duration._
import zio._
import zio.internal._
import zio.stream._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test.TestClock._

import java.time.temporal.ChronoUnit
import java.time.{Instant, ZoneId}
import java.util.concurrent.{RejectedExecutionException, TimeUnit}

object ClockSpec extends ZIOBaseSpec {
  override def aspects: Chunk[TestAspectAtLeastR[TestEnvironment]] =
    if (TestPlatform.isJVM || TestPlatform.isNative) Chunk(TestAspect.timeout(180.seconds))
    else Chunk(TestAspect.sequential, TestAspect.timeout(180.seconds))

  def spec =
    suite("ClockSpec")(
      test("sleep does not require passage of clock time") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- adjust(11.hours)
          result <- ref.get
        } yield assert(result)(isTrue)
      } @@ forked @@ exceptJS(nonFlaky),
      test("sleep delays effect until time is adjusted") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- adjust(9.hours)
          result <- ref.get
        } yield assert(result)(isFalse)
      } @@ forked @@ exceptJS(nonFlaky),
      test("sleep correctly handles multiple sleeps") {
        for {
          ref    <- Ref.make("")
          _      <- ref.update(_ + "World!").delay(3.hours).fork
          _      <- ref.update(_ + "Hello, ").delay(1.hours).fork
          _      <- adjust(4.hours)
          result <- ref.get
        } yield assert(result)(equalTo("Hello, World!"))
      } @@ forked @@ exceptJS(nonFlaky),
      test("sleep correctly handles new set time") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- setTime(Instant.EPOCH.plus(11.hours))
          result <- ref.get
        } yield assert(result)(isTrue)
      } @@ forked @@ exceptJS(nonFlaky),
      test("adjust correctly advances nanotime") {
        for {
          time1 <- nanoTime
          _     <- adjust(1.millis)
          time2 <- nanoTime
        } yield assert(fromNanos(time2 - time1))(equalTo(1.millis))
      },
      test("adjust correctly advances currentTime") {
        for {
          time1 <- currentTime(TimeUnit.NANOSECONDS)
          _     <- adjust(1.millis)
          time2 <- currentTime(TimeUnit.NANOSECONDS)
        } yield assert(time2 - time1)(equalTo(1.millis.toNanos))
      },
      test("adjust correctly advances currentTime using ChronoUnit") {
        for {
          time1 <- currentTime(ChronoUnit.NANOS)
          _     <- adjust(1.millis)
          time2 <- currentTime(ChronoUnit.NANOS)
        } yield assert(time2 - time1)(equalTo(1.millis.toNanos))
      },
      test("adjust correctly advances currentDateTime") {
        for {
          time1 <- currentDateTime
          _     <- adjust(1.millis)
          time2 <- currentDateTime
        } yield assert((time2.toInstant.toEpochMilli - time1.toInstant.toEpochMilli))(equalTo(1L))
      },
      test("adjust does not produce sleeps") {
        for {
          _      <- adjust(1.millis)
          sleeps <- sleeps
        } yield assert(sleeps)(isEmpty)
      },
      test("setInstant correctly sets currentDateTime") {
        for {
          expected <- ZIO.succeed(Instant.now)
          _        <- setTime(expected)
          actual   <- Clock.currentDateTime
        } yield assert(actual.toInstant.toEpochMilli)(equalTo(expected.toEpochMilli))
      },
      test("setInstant correctly sets nanotime") {
        for {
          _    <- setTime(Instant.EPOCH.plus(1.millis))
          time <- Clock.nanoTime
        } yield assert(time)(equalTo(1.millis.toNanos))
      },
      test("setInstant correctly sets currentTime") {
        for {
          _    <- setTime(Instant.EPOCH.plus(1.millis))
          time <- currentTime(TimeUnit.NANOSECONDS)
        } yield assert(time)(equalTo(1.millis.toNanos))
      },
      test("setInstant correctly sets currentDateTime") {
        for {
          _    <- TestClock.setTime(Instant.EPOCH.plus(1.millis))
          time <- currentDateTime
        } yield assert(time.toInstant.toEpochMilli)(equalTo(1.millis.toMillis))
      },
      test("setInstant does not produce sleeps") {
        for {
          _      <- setTime(Instant.EPOCH.plus(1.millis))
          sleeps <- sleeps
        } yield assert(sleeps)(isEmpty)
      },
      test("setTimeZone correctly sets timeZone") {
        setTimeZone(ZoneId.of("UTC+10")) *>
          assertZIO(timeZone)(equalTo(ZoneId.of("UTC+10")))
      },
      test("setTimeZone does not produce sleeps") {
        setTimeZone(ZoneId.of("UTC+11")) *>
          assertZIO(sleeps)(isEmpty)
      },
      test("timeout example from TestClock documentation works correctly") {
        val example = for {
          fiber  <- ZIO.sleep(5.minutes).timeout(1.minute).fork
          _      <- TestClock.adjust(1.minute)
          result <- fiber.join
        } yield result == None
        assertZIO(example)(isTrue)
      } @@ forked @@ exceptJS(nonFlaky),
      test("recurrence example from TestClock documentation works correctly") {
        val example = for {
          q <- Queue.unbounded[Unit]
          _ <- q.offer(()).delay(60.minutes).forever.fork
          a <- q.poll.map(_.isEmpty)
          _ <- TestClock.adjust(60.minutes)
          b <- q.take.as(true)
          c <- q.poll.map(_.isEmpty)
          _ <- TestClock.adjust(60.minutes)
          d <- q.take.as(true)
          e <- q.poll.map(_.isEmpty)
        } yield a && b && c && d && e
        assertZIO(example)(isTrue)
      } @@ forked @@ exceptJS(nonFlaky),
      test("clock time is always 0 at the start of a test that repeats")(
        for {
          clockTime <- currentTime(TimeUnit.NANOSECONDS)
          _         <- sleep(2.nanos).fork
          _         <- adjust(3.nanos)
        } yield assert(clockTime)(equalTo(0.millis.toNanos))
      ) @@ forked @@ exceptJS(nonFlaky(3)),
      test("TestClock interacts correctly with Scheduled.fixed") {
        for {
          latch    <- Promise.make[Nothing, Unit]
          ref      <- Ref.make(3)
          countdown = ref.updateAndGet(_ - 1).flatMap(n => latch.succeed(()).when(n == 0))
          _        <- countdown.repeat(Schedule.fixed(2.seconds)).delay(1.second).fork
          _        <- TestClock.adjust(5.seconds)
          _        <- latch.await
        } yield assertCompletes
      },
      test("adjustments to time are visible on other fibers") {
        for {
          promise <- Promise.make[Nothing, Unit]
          effect   = adjust(1.second) *> Clock.currentTime(TimeUnit.SECONDS)
          result  <- (effect <* promise.succeed(())) <&> (promise.await *> effect)
        } yield assert(result)(equalTo((1L, 2L)))
      },
      test("zipLatest example from documentation") {
        val s1 = ZStream.iterate(0)(_ + 1).schedule(Schedule.fixed(100.milliseconds))
        val s2 = ZStream.iterate(0)(_ + 1).schedule(Schedule.fixed(70.milliseconds))
        val s3 = s1.zipLatest(s2)

        for {
          q      <- Queue.unbounded[(Int, Int)]
          _      <- s3.foreach(q.offer).fork
          fiber  <- ZIO.collectAll(ZIO.replicate(4)(q.take)).fork
          _      <- TestClock.adjust(1.second)
          result <- fiber.join
        } yield assert(result)(equalTo(List(0 -> 0, 0 -> 1, 1 -> 1, 1 -> 2)))
      },
      test("adjustWith runs the specified effect and advances the clock") {
        val zio = ZIO.sleep(1.hour)
        for {
          _ <- TestClock.adjustWith(1.hour)(zio)
        } yield assertCompletes
      },
      test("creates warning fiber in its own scope") {
        for {
          _ <- ZIO.unit raceFirst Clock.instant
        } yield assertCompletes
      }.provideLayer(scopedExecutor) @@ exceptJS(nonFlaky)
    )

  class ScopedExecutor extends Executor {
    @volatile private var closed = false
    def close(): Unit =
      closed = true
    def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] =
      None
    def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
      if (closed) throw new RejectedExecutionException
      else Runtime.defaultExecutor.submit(runnable)
  }

  val scopedExecutor: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      for {
        executor <- ZIO.acquireRelease(ZIO.succeed(new ScopedExecutor))(executor => ZIO.succeed(executor.close()))
        _        <- ZIO.addFinalizer(ZIO.yieldNow)
        _        <- ZIO.onExecutorScoped(executor)
      } yield ()
    }

}
