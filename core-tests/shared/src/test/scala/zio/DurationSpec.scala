package zio

import zio.test.Assertion.{equalTo, not}
import zio.test.assert
import java.time.temporal.ChronoUnit
import java.time.{Duration => JavaDuration}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration => ScalaDuration, FiniteDuration => ScalaFiniteDuration}

object DurationSpec extends ZIOBaseSpec {

  def spec = suite("DurationSpec")(
    suite("Make a Duration from positive nanos and check that: ")(
      test("The Duration is Finite") {
        assert(Duration.fromNanos(1) == Duration.Infinity)(equalTo(false))
      },
      test("Multiplying with a negative factor returns Zero") {
        assert(Duration.fromNanos(1) * -1.0)(equalTo(Duration.Zero: Duration))
      },
      test("Its JDK representation is correct") {
        assert(Duration.fromNanos(2345L))(equalTo(JavaDuration.ofNanos(2345L)))
      },
      test("It identifies as 'zero'") {
        assert(Duration.fromNanos(0L).isZero)(equalTo(true))
      },
      test("Creating it with a j.u.c.TimeUnit is identical") {
        assert(Duration(12L, TimeUnit.NANOSECONDS))(equalTo(Duration.fromNanos(12L)))
      },
      test("Creating it with a j.u.c.TimeUnit is identical on negative values") {
        assert(Duration(-12L, TimeUnit.NANOSECONDS))(equalTo(Duration.fromNanos(-12L)))
      },
      test("Creating it with a j.u.c.TimeUnit is identical on big values") {
        assert(Duration(Long.MaxValue, TimeUnit.NANOSECONDS))(equalTo(Duration.fromNanos(Long.MaxValue)))
      },
      test("Creating it with a j.u.c.TimeUnit is identical on big negative values") {
        assert(Duration(Long.MinValue, TimeUnit.NANOSECONDS))(equalTo(Duration.fromNanos(Long.MinValue)))
      },
      test("Creating it with a j.u.c.TimeUnit is identical on big millis values") {
        assert(Duration(Long.MaxValue, TimeUnit.MILLISECONDS))(equalTo(Duration.fromMillis(Long.MaxValue)))
      },
      test("Creating it with a j.t.t.ChronoUnit is identical") {
        assert(Duration(12L, ChronoUnit.NANOS))(equalTo(Duration.fromNanos(12L)))
      },
      test("Creating it with a j.t.t.ChronoUnit is identical on negative values") {
        assert(Duration(-12L, ChronoUnit.NANOS))(equalTo(Duration.fromNanos(-12L)))
      },
      test("Creating it with a j.t.t.ChronoUnit is identical big values") {
        assert(Duration(Long.MaxValue, ChronoUnit.NANOS))(equalTo(Duration.fromNanos(Long.MaxValue)))
      },
      test("Creating it with a j.t.t.ChronoUnit is identical on big negative values") {
        assert(Duration(Long.MinValue, ChronoUnit.NANOS))(equalTo(Duration.fromNanos(Long.MinValue)))
      },
      test("Creating it with a j.t.t.ChronoUnit is identical big millis values") {
        assert(Duration(Long.MaxValue, ChronoUnit.MILLIS))(equalTo(Duration.fromMillis(Long.MaxValue)))
      },
      test("It knows its length in ns") {
        assert(Duration.fromNanos(123L).toNanos)(equalTo(123L))
      },
      test("It knows its length in ms") {
        assert(Duration.fromNanos(123000000L).toMillis)(equalTo(123L))
      },
      test("max(1 ns, 2 ns) is 2 ns") {
        assert(Duration.fromNanos(1L).max(Duration.fromNanos(2L)))(equalTo(Duration.fromNanos(2L)))
      },
      test("min(1 ns, 2 ns) is 1 ns") {
        assert(Duration.fromNanos(1L).min(Duration.fromNanos(2L)))(equalTo(Duration.fromNanos(1L)))
      },
      test("max(2 ns, 1 ns) is 2 ns") {
        assert(Duration.fromNanos(2L).max(Duration.fromNanos(1L)))(equalTo(Duration.fromNanos(2L)))
      },
      test("min(2 ns, 1 ns) is 1 ns") {
        assert(Duration.fromNanos(2L).min(Duration.fromNanos(1L)))(equalTo(Duration.fromNanos(1L)))
      },
      test("10 ns + 20 ns = 30 ns") {
        assert(Duration.fromNanos(10L) + Duration.fromNanos(20L))(equalTo(Duration.fromNanos(30L)))
      },
      test("10 ns * NaN = Infinity") {
        assert(Duration.fromNanos(10L) * Double.NaN)(equalTo(Duration.Infinity: Duration))
      },
      test("10 ns compared to Infinity is -1") {
        assert(Duration.fromNanos(10L) compare Duration.Infinity)(equalTo(-1))
      },
      test("10 ns compared to 10 ns is 0") {
        assert(Duration.fromNanos(10L) compare Duration.fromNanos(10L))(equalTo(0))
      },
      test("+ with positive overflow results in Infinity") {
        assert(Duration.fromNanos(Long.MaxValue - 1) + Duration.fromNanos(42))(equalTo(Duration.Infinity: Duration))
      },
      test("* with negative duration results in zero") {
        assert(Duration.fromNanos(42) * -7)(equalTo(Duration.Zero: Duration))
      },
      test("* with overflow to positive results in Infinity") {
        assert(Duration.fromNanos(Long.MaxValue) * 3)(equalTo(Duration.Infinity: Duration))
      },
      test("* with overflow to negative results in Infinity") {
        assert(Duration.fromNanos(Long.MaxValue) * 2)(equalTo(Duration.Infinity: Duration))
      },
      test("* with factor equal to 0 results in zero") {
        assert(Duration.fromNanos(42) * 0)(equalTo(Duration.Zero: Duration))
      },
      test("* with positive factor less than 1 results in Finite Duration") {
        assert(Duration.fromNanos(42) * 0.5)(equalTo(Duration.fromNanos(21)))
      },
      test("* with factor equal to 1 results in Finite Duration in case of small duration") {
        assert(Duration.fromNanos(42) * 1)(equalTo(Duration.fromNanos(42)))
      },
      test("* with factor equal to 1 results in Finite Duration in case of large duration") {
        assert(Duration.fromNanos(Long.MaxValue) * 1)(equalTo(Duration.fromNanos(Long.MaxValue)))
      },
      test("* results in Finite Duration if the multiplication result is close to max FiniteDuration value") {
        val factor = 1.5
        val nanos  = (Long.MaxValue / 1.9).round
        assert(Duration.fromNanos(nanos) * factor != Duration.Infinity)(equalTo(true))
      },
      test("Folding picks up the correct value") {
        assert(Duration.fromNanos(Long.MaxValue).fold("Infinity", _ => "Finite"))(equalTo("Infinity"))
      },
      test("Durations can be accumulated") {
        val durations = List(1.second, 2.seconds, 3.seconds)
        assert(durations.foldLeft(Duration.Zero)(_ + _))(equalTo(6.seconds))
      }
    ),
    suite("Make a Duration from negative nanos and check that:")(
      test("The Duration is not Zero ") {
        assert(Duration.fromNanos(-1))(not(equalTo(Duration.Zero: Duration)))
      }
    ),
    suite("Take Infinity and check that: ")(
      test("toMillis returns Long.MaxValue nanoseconds in milliseconds") {
        assert(Duration.Infinity.toMillis)(equalTo(Long.MaxValue / 1000000))
      },
      test("toNanos returns Long.MaxValue nanoseconds") {
        assert(Duration.Infinity.toNanos)(equalTo(Long.MaxValue))
      },
      test("Infinity + Infinity = Infinity") {
        assert(Duration.Infinity + Duration.Infinity)(equalTo(Duration.Infinity: Duration))
      },
      test("Infinity + 1 ns = Infinity") {
        assert(Duration.Infinity + Duration.fromNanos(1L))(equalTo(Duration.Infinity: Duration))
      },
      test("1 ns + Infinity = Infinity") {
        assert(Duration.fromNanos(1L) + Duration.Infinity)(equalTo(Duration.Infinity: Duration))
      },
      test("Infinity * 10 = Infinity") {
        assert(Duration.Infinity * 10.0)(equalTo(Duration.Infinity: Duration))
      },
      test("Infinity compared to Infinity is 0") {
        assert(Duration.Infinity compare Duration.Infinity)(equalTo(0))
      },
      test("Infinity compared to 1 ns is 1") {
        assert(Duration.Infinity compare Duration.fromNanos(1L))(equalTo(1))
      },
      test("Infinity is not zero") {
        assert(Duration.Infinity.isZero)(equalTo(false))
      },
      test("It converts into the infinite s.c.d.Duration") {
        assert(Duration.Infinity.asScala)(equalTo(ScalaFiniteDuration(Long.MaxValue, TimeUnit.NANOSECONDS)))
      },
      test("It converts into a Long.MaxValue second-long JDK Duration") {
        assert(Duration.Infinity)(equalTo(JavaDuration.ofNanos(Long.MaxValue)))
      },
      test("Folding picks up the correct value") {
        assert(Duration.Infinity.fold("Infinity", _ => "Finite"))(equalTo("Infinity"))
      },
      test("Infinity * -10 = Zero") {
        assert(Duration.Infinity * -10)(equalTo(Duration.Zero: Duration))
      },
      test("Infinity * 0 = Zero") {
        assert(Duration.Infinity * 0)(equalTo(Duration.Zero: Duration))
      }
    ),
    suite("Make a Scala stdlib s.c.d.Duration and check that: ")(
      test("A negative s.c.d.Duration not converts to Zero") {
        assert(Duration.fromScala(ScalaDuration(-1L, TimeUnit.NANOSECONDS)))(equalTo(Duration.fromNanos(-1): Duration))
      },
      test("The infinite s.c.d.Duration converts to Infinity") {
        assert(Duration.fromScala(ScalaDuration.Inf))(equalTo(Duration.Infinity: Duration))
      },
      test("A positive s.c.d.Duration converts to a Finite") {
        assert(Duration.fromScala(ScalaDuration(1L, TimeUnit.NANOSECONDS)))(equalTo(Duration.fromNanos(1L)))
      }
    ),
    suite("Make a Java stdlib j.t.Duration and check that: ")(
      test("A negative j.t.Duration converts to Zero") {
        assert(Duration.fromJava(JavaDuration.ofNanos(-1L)))(equalTo(Duration.Zero: Duration))
      },
      test("A Long.MaxValue second j.t.Duration converts to Infinity") {
        assert(Duration.fromJava(JavaDuration.ofSeconds(Long.MaxValue)))(equalTo(Duration.Infinity: Duration))
      },
      test(
        "A nano-adjusted Long.MaxValue second j.t.Duration converts to Infinity"
      ) {
        assert(Duration.fromJava(JavaDuration.ofSeconds(Long.MaxValue, 1L)))(equalTo(Duration.Infinity: Duration))
      },
      test(
        "A j.t.Duration constructed from Infinity converts to Infinity"
      ) {
        assert(Duration.fromJava(Duration.Infinity))(equalTo(Duration.Infinity: Duration))
      },
      test(
        "A Long.MaxValue - 1 second j.t.Duration converts to Infinity"
      ) {
        assert(Duration.fromJava(JavaDuration.ofSeconds(Long.MaxValue - 1)))(equalTo(Duration.Infinity: Duration))
      },
      test(
        "A +ve j.t.Duration whose nano conversion overflows converts to Infinity"
      ) {
        assert(Duration.fromJava(JavaDuration.ofNanos(Long.MaxValue).plus(JavaDuration.ofNanos(1L))))(
          equalTo(Duration.Infinity: Duration)
        )
      },
      test(
        "A -ve j.t.Duration whose nano conversion overflows converts to Zero"
      ) {
        assert(Duration.fromJava(JavaDuration.ofNanos(Long.MinValue).minus(JavaDuration.ofNanos(1L))))(
          equalTo(Duration.Zero: Duration)
        )
      },
      test("A positive j.t.Duration converts to a Finite") {
        assert(Duration.fromJava(JavaDuration.ofNanos(1L)))(equalTo(Duration.fromNanos(1L)))
      }
    ),
    suite("Check multiplication with finite durations: ")(
      test("Zero multiplied with zero") {
        assert(Duration.Zero * 0)(equalTo(Duration.Zero: Duration))
      },
      test("Zero multiplied with one") {
        assert(Duration.Zero * 1)(equalTo(Duration.Zero: Duration))
      },
      test("One second multiplied with 60") {
        assert(Duration(1, TimeUnit.SECONDS) * 60)(equalTo(Duration(1, TimeUnit.MINUTES)))
      },
      test("One second multiplied with 60 using ChronoUnit") {
        assert(Duration(1, ChronoUnit.SECONDS) * 60)(equalTo(Duration(1, ChronoUnit.MINUTES)))
      }
    ),
    suite("Render duration:")(
      test(" 0 ns") {
        assert(Duration(0, TimeUnit.NANOSECONDS).render)(equalTo("0 ns"))
      },
      test(" < 1 ms") {
        assert(Duration(23456, TimeUnit.NANOSECONDS).render)(equalTo("23456 ns"))
      },
      test(" 1 ms") {
        assert(Duration(1, TimeUnit.MILLISECONDS).render)(equalTo("1 ms"))
      },
      test(" 1 s") {
        assert(Duration(1, TimeUnit.SECONDS).render)(equalTo("1 s"))
      },
      test(" 2 s 500 ms") {
        assert(Duration(2500, TimeUnit.MILLISECONDS).render)(equalTo("2 s 500 ms"))
      },
      test(" 1 min") {
        assert(Duration(1, TimeUnit.MINUTES).render)(equalTo("1 m"))
      },
      test(" 2 min 30 s") {
        assert(Duration(150, TimeUnit.SECONDS).render)(equalTo("2 m 30 s"))
      },
      test(" 1 h 1 min") {
        assert(Duration(61, TimeUnit.MINUTES).render)(equalTo("1 h 1 m"))
      },
      test(" 3 d 2 h 5 min") {
        assert(Duration(4445, TimeUnit.MINUTES).render)(equalTo("3 d 2 h 5 m"))
      }
    ),
    suite("Render duration using ChronoUnit:")(
      test(" 0 ns") {
        assert(Duration(0, ChronoUnit.NANOS).render)(equalTo("0 ns"))
      },
      test(" < 1 ms") {
        assert(Duration(23456, ChronoUnit.NANOS).render)(equalTo("23456 ns"))
      },
      test(" 1 ms") {
        assert(Duration(1, ChronoUnit.MILLIS).render)(equalTo("1 ms"))
      },
      test(" 1 s") {
        assert(Duration(1, ChronoUnit.SECONDS).render)(equalTo("1 s"))
      },
      test(" 2 s 500 ms") {
        assert(Duration(2500, ChronoUnit.MILLIS).render)(equalTo("2 s 500 ms"))
      },
      test(" 1 min") {
        assert(Duration(1, ChronoUnit.MINUTES).render)(equalTo("1 m"))
      },
      test(" 2 min 30 s") {
        assert(Duration(150, ChronoUnit.SECONDS).render)(equalTo("2 m 30 s"))
      },
      test(" 1 h 1 min") {
        assert(Duration(61, ChronoUnit.MINUTES).render)(equalTo("1 h 1 m"))
      },
      test(" 3 d 2 h 5 min") {
        assert(Duration(4445, ChronoUnit.MINUTES).render)(equalTo("3 d 2 h 5 m"))
      }
    ),
    suite("Long:")(
      test("1L.nano         = fromNanos(1L)") {
        assert(1L.nano)(equalTo(Duration.fromNanos(1L)))
      },
      test("2L.nanos        = fromNanos(2L)") {
        assert(2L.nanos)(equalTo(Duration.fromNanos(2L)))
      },
      test("1L.nanosecond   = fromNanos(1L)") {
        assert(1L.nanosecond)(equalTo(Duration.fromNanos(1L)))
      },
      test("2L.nanoseconds  = fromNanos(2L)") {
        assert(2L.nanoseconds)(equalTo(Duration.fromNanos(2L)))
      },
      test("1L.micro        = fromNanos(1000L)") {
        assert(1L.micro)(equalTo(Duration.fromNanos(1000L)))
      },
      test("2L.micros       = fromNanos(2000L)") {
        assert(2L.micros)(equalTo(Duration.fromNanos(2000L)))
      },
      test("1L.microsecond  = fromNanos(1000L)") {
        assert(1L.microsecond)(equalTo(Duration.fromNanos(1000L)))
      },
      test("2L.microseconds = fromNanos(2000L)") {
        assert(2L.microseconds)(equalTo(Duration.fromNanos(2000L)))
      },
      test("1L.milli        = fromNanos(1000000L)") {
        assert(1L.milli)(equalTo(Duration.fromNanos(1000000L)))
      },
      test("2L.millis       = fromNanos(2000000L)") {
        assert(2L.millis)(equalTo(Duration.fromNanos(2000000L)))
      },
      test("1L.millisecond  = fromNanos(1000000L)") {
        assert(1L.millisecond)(equalTo(Duration.fromNanos(1000000L)))
      },
      test("2L.milliseconds = fromNanos(2000000L)") {
        assert(2L.milliseconds)(equalTo(Duration.fromNanos(2000000L)))
      },
      test("1L.second       = fromNanos(1000000000L)") {
        assert(1L.second)(equalTo(Duration.fromNanos(1000000000L)))
      },
      test("2L.seconds      = fromNanos(2000000000L)") {
        assert(2L.seconds)(equalTo(Duration.fromNanos(2000000000L)))
      },
      test("1L.minute       = fromNanos(60000000000L)") {
        assert(1L.minute)(equalTo(Duration.fromNanos(60000000000L)))
      },
      test("2L.minutes      = fromNanos(120000000000L)") {
        assert(2L.minutes)(equalTo(Duration.fromNanos(120000000000L)))
      },
      test("1L.hour         = fromNanos(3600000000000L)") {
        assert(1L.hour)(equalTo(Duration.fromNanos(3600000000000L)))
      },
      test("2L.hours        = fromNanos(7200000000000L)") {
        assert(2L.hours)(equalTo(Duration.fromNanos(7200000000000L)))
      },
      test("1L.day          = fromNanos(86400000000000L)") {
        assert(1L.day)(equalTo(Duration.fromNanos(86400000000000L)))
      },
      test("2L.days         = fromNanos(172800000000000L)") {
        assert(2L.days)(equalTo(Duration.fromNanos(172800000000000L)))
      }
    ),
    suite("Int:")(
      test("1.nano         = fromNanos(1L)") {
        assert(1.nano)(equalTo(Duration.fromNanos(1L)))
      },
      test("2.nanos        = fromNanos(2L)") {
        assert(2.nanos)(equalTo(Duration.fromNanos(2L)))
      },
      test("1.nanosecond   = fromNanos(1L)") {
        assert(1.nanosecond)(equalTo(Duration.fromNanos(1L)))
      },
      test("2.nanoseconds  = fromNanos(2L)") {
        assert(2.nanos)(equalTo(Duration.fromNanos(2L)))
      },
      test("1.micro        = fromNanos(1000L)") {
        assert(1.micro)(equalTo(Duration.fromNanos(1000L)))
      },
      test("2.micros       = fromNanos(2000L)") {
        assert(2.micros)(equalTo(Duration.fromNanos(2000L)))
      },
      test("1.microsecond  = fromNanos(1000L)") {
        assert(1.microsecond)(equalTo(Duration.fromNanos(1000L)))
      },
      test("2.microseconds = fromNanos(2000L)") {
        assert(2.microseconds)(equalTo(Duration.fromNanos(2000L)))
      },
      test("1.milli        = fromNanos(1000000L)") {
        assert(1.milli)(equalTo(Duration.fromNanos(1000000L)))
      },
      test("2.millis       = fromNanos(2000000L)") {
        assert(2.millis)(equalTo(Duration.fromNanos(2000000L)))
      },
      test("1.millisecond  = fromNanos(1000000L)") {
        assert(1.millisecond)(equalTo(Duration.fromNanos(1000000L)))
      },
      test("2.milliseconds = fromNanos(2000000L)") {
        assert(2.milliseconds)(equalTo(Duration.fromNanos(2000000L)))
      },
      test("1.second       = fromNanos(1000000000L)") {
        assert(1.second)(equalTo(Duration.fromNanos(1000000000L)))
      },
      test("2.seconds      = fromNanos(2000000000L)") {
        assert(2.seconds)(equalTo(Duration.fromNanos(2000000000L)))
      },
      test("1.minute       = fromNanos(60000000000L)") {
        assert(1.minute)(equalTo(Duration.fromNanos(60000000000L)))
      },
      test("2.minutes      = fromNanos(120000000000L)") {
        assert(2.minutes)(equalTo(Duration.fromNanos(120000000000L)))
      },
      test("1.hour         = fromNanos(3600000000000L)") {
        assert(1.hour)(equalTo(Duration.fromNanos(3600000000000L)))
      },
      test("2.hours        = fromNanos(7200000000000L)") {
        assert(2.hours)(equalTo(Duration.fromNanos(7200000000000L)))
      },
      test("1.day          = fromNanos(86400000000000L)") {
        assert(1.day)(equalTo(Duration.fromNanos(86400000000000L)))
      },
      test("2.days         = fromNanos(76800000000000L)") {
        assert(2.days)(equalTo(Duration.fromNanos(172800000000000L)))
      }
    )
  )
}
