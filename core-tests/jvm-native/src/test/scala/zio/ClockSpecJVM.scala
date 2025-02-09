package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import java.time.Instant
import java.util.concurrent.TimeUnit

object ClockSpecJVM extends ZIOBaseSpec {

  def spec =
    suite("ClockSpec")(
      test("currentTime has microsecond resolution on JRE >= 9") {
        val unit = TimeUnit.MICROSECONDS
        for {
          a <- Clock.currentTime(unit)
          _ <- ZIO.foreach(1 to 1000)(_ => ZIO.unit) // just pass some time
          b <- Clock.currentTime(unit)
        } yield assert((b - a) % 1000)(not(equalTo(0L)))
      } @@ withLiveClock
      // We might actually have measured exactly one millisecond. In that case we can simply retry.
        @@ TestAspect.flaky
        // This test should only run on JRE >= 9, which is when microsecond precision was introduced.
        // Versions of JREs < 9 started with s"1.${majorVersion}", then with JEP 223 they switched to semantic versioning.
        @@ TestAspect.ifProp("java.version")(!_.startsWith("1.")),
      test("currentTime has correct time") {
        val unit = TimeUnit.MICROSECONDS
        for {
          start  <- ZIO.succeed(Instant.now).map(_.toEpochMilli)
          time   <- Clock.currentTime(unit).map(TimeUnit.MILLISECONDS.convert(_, unit))
          finish <- ZIO.succeed(Instant.now).map(_.toEpochMilli)
        } yield assert(time)(isGreaterThanEqualTo(start) && isLessThanEqualTo(finish))
      } @@ withLiveClock
        @@ TestAspect.nonFlaky
    )
}
