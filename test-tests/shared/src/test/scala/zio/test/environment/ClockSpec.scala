package zio.test.environment

import zio.Clock._
import zio.Duration._
import zio._
import zio.stream._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio.test.environment.TestClock._

import java.time.{OffsetDateTime, ZoneId}
import java.util.concurrent.TimeUnit

object ClockSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] =
    suite("ClockSpec")(
      test("sleep does not require passage of clock time") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- adjust(11.hours)
          result <- ref.get
        } yield assert(result)(isTrue)
      } @@ forked @@ nonFlaky,
      test("sleep delays effect until time is adjusted") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- adjust(9.hours)
          result <- ref.get
        } yield assert(result)(isFalse)
      } @@ forked @@ nonFlaky,
      test("sleep correctly handles multiple sleeps") {
        for {
          ref    <- Ref.make("")
          _      <- ref.update(_ + "World!").delay(3.hours).fork
          _      <- ref.update(_ + "Hello, ").delay(1.hours).fork
          _      <- adjust(4.hours)
          result <- ref.get
        } yield assert(result)(equalTo("Hello, World!"))
      } @@ forked @@ nonFlaky,
      test("sleep correctly handles new set time") {
        for {
          ref    <- Ref.make(false)
          _      <- ref.set(true).delay(10.hours).fork
          _      <- setTime(11.hours)
          result <- ref.get
        } yield assert(result)(isTrue)
      } @@ forked @@ nonFlaky,
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
      test("setDateTime correctly sets currentDateTime") {
        for {
          expected <- UIO.succeed(OffsetDateTime.now(ZoneId.of("UTC+9")))
          _        <- setDateTime(expected)
          actual   <- Clock.currentDateTime
        } yield assert(actual.toInstant.toEpochMilli)(equalTo(expected.toInstant.toEpochMilli))
      },
      test("setTime correctly sets nanotime") {
        for {
          _    <- setTime(1.millis)
          time <- Clock.nanoTime
        } yield assert(time)(equalTo(1.millis.toNanos))
      },
      test("setTime correctly sets currentTime") {
        for {
          _    <- setTime(1.millis)
          time <- currentTime(TimeUnit.NANOSECONDS)
        } yield assert(time)(equalTo(1.millis.toNanos))
      },
      test("setTime correctly sets currentDateTime") {
        for {
          _    <- TestClock.setTime(1.millis)
          time <- currentDateTime
        } yield assert(time.toInstant.toEpochMilli)(equalTo(1.millis.toMillis))
      },
      test("setTime does not produce sleeps") {
        for {
          _      <- setTime(1.millis)
          sleeps <- sleeps
        } yield assert(sleeps)(isEmpty)
      },
      test("setTimeZone correctly sets timeZone") {
        setTimeZone(ZoneId.of("UTC+10")) *>
          assertM(timeZone)(equalTo(ZoneId.of("UTC+10")))
      },
      test("setTimeZone does not produce sleeps") {
        setTimeZone(ZoneId.of("UTC+11")) *>
          assertM(sleeps)(isEmpty)
      },
      test("timeout example from TestClock documentation works correctly") {
        val example = for {
          fiber  <- ZIO.sleep(5.minutes).timeout(1.minute).fork
          _      <- TestClock.adjust(1.minute)
          result <- fiber.join
        } yield result == None
        assertM(example)(isTrue)
      } @@ forked @@ nonFlaky,
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
        assertM(example)(isTrue)
      } @@ forked @@ nonFlaky,
      test("clock time is always 0 at the start of a test that repeats")(
        for {
          clockTime <- currentTime(TimeUnit.NANOSECONDS)
          _         <- sleep(2.nanos).fork
          _         <- adjust(3.nanos)
        } yield assert(clockTime)(equalTo(0.millis.toNanos))
      ) @@ forked @@ nonFlaky(3),
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
      test("zipWithLatest example from documentation") {
        val s1 = Stream.iterate(0)(_ + 1).fixed(100.milliseconds)
        val s2 = Stream.iterate(0)(_ + 1).fixed(70.milliseconds)
        val s3 = s1.zipWithLatest(s2)((_, _))

        for {
          q      <- Queue.unbounded[(Int, Int)]
          _      <- s3.foreach(q.offer).fork
          fiber  <- ZIO.collectAll(ZIO.replicate(4)(q.take)).fork
          _      <- TestClock.adjust(1.second)
          result <- fiber.join
        } yield assert(result)(equalTo(List(0 -> 0, 0 -> 1, 1 -> 1, 1 -> 2)))
      }
    )
}
