package zio

import zio.ZIOAspect._
import zio.internal.metrics.metricRegistry
import zio.metrics.MetricKeyType.Histogram
import zio.metrics._
import zio.test._

import java.time.temporal.ChronoUnit

object MetricSpec extends ZIOBaseSpec {

  private val labels1 = Set(MetricLabel("x", "a"), MetricLabel("y", "b"))

  def spec = suite("Metric")(
    suite("Counter")(
      test("custom increment as aspect") {
        val c = Metric.counter("c1").tagged(labels1).fromConst(1L)

        for {
          _     <- ZIO.unit @@ c
          _     <- ZIO.unit @@ c
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(2.0))
      },
      test("direct increment") {
        val c = Metric.counter("c2").tagged(labels1)

        for {
          _     <- c.increment
          _     <- c.increment
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(2.0))
      },
      test("custom increment by value as aspect") {
        val c = Metric.counter("c3").tagged(labels1)

        for {
          _     <- ZIO.succeed(10L) @@ c
          _     <- ZIO.succeed(5L) @@ c
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(15.0))
      },
      test("count") {
        for {
          _     <- ZIO.unit @@ Metric.counter("c5").tagged(labels1).contramap[Unit](_ => 1)
          _     <- ZIO.unit @@ Metric.counter("c5").tagged(labels1).contramap[Unit](_ => 1)
          state <- Metric.counter("c5").tagged(labels1).value
        } yield assertTrue(
          state == MetricState.Counter(2.0)
        )
      },
      test("countValue") {
        for {
          _     <- ZIO.succeed(10L) @@ Metric.counter("c6").tagged(labels1)
          _     <- ZIO.succeed(5L) @@ Metric.counter("c6").tagged(labels1)
          state <- Metric.counter("c6").tagged(labels1).value
        } yield assertTrue(
          state == MetricState.Counter(15.0),
          state.count == 15.0
        )
      },
      test("countValueWith") {
        val c = Metric.counter("c7").tagged(labels1).contramap[String](_.length.toLong)
        for {
          _     <- ZIO.succeed("hello") @@ c
          _     <- ZIO.succeed("!") @@ c
          state <- c.value
        } yield assertTrue(
          state == MetricState.Counter(6.0),
          state.count == 6.0
        )
      },
      test("countErrors") {
        val c = Metric.counter("c8").contramap[Unit](_ => 1)

        for {
          _     <- (ZIO.unit @@ c *> ZIO.fail("error") @@ c).ignore
          state <- c.value
        } yield assertTrue(
          state == MetricState.Counter(1.0),
          state.count == 1.0
        )
      },
      test("count + taggedWith") {
        val base = Metric
          .counter("c10")
          .tagged(MetricLabel("static", "0"))
          .contramap[String](_ => 1)

        val c = base.taggedWith[String](string => Set(MetricLabel("dyn", string)))

        for {
          _     <- ZIO.succeed("hello") @@ c
          _     <- ZIO.succeed("!") @@ c
          _     <- ZIO.succeed("!") @@ c
          state <- base.tagged(MetricLabel("dyn", "!")).value
        } yield assertTrue(state == MetricState.Counter(2.0))
      },
      test("trackAll") {
        val base = Metric.counter("c11")
        val c    = base.trackAll(1)

        for {
          _     <- ZIO.succeed("tracked") @@ c
          _     <- ZIO.succeed("tracked") @@ c
          _     <- (ZIO.fail("tracked") @@ c).ignore
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(3.0),
          state.count == 3.0
        )
      },
      test("trackSuccess") {
        val base = Metric.counter("c12").contramap[String](_ => 1)
        val c    = base.trackSuccess

        for {
          _     <- ZIO.succeed("tracked") @@ c
          _     <- ZIO.succeed("tracked") @@ c
          _     <- (ZIO.fail("not tracked") @@ c).ignore
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackSuccessWith") {
        val base = Metric.counter("c13")
        val c    = base.trackSuccessWith[String](_ => 1)

        for {
          _     <- ZIO.succeed("tracked") @@ c
          _     <- ZIO.succeed("tracked") @@ c
          _     <- (ZIO.fail("not tracked") @@ c).ignore
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackError") {
        val base = Metric.counter("c14").contramap[String](_ => 1)
        val c    = base.trackError

        for {
          _     <- ZIO.succeed("not tracked") @@ c
          _     <- (ZIO.fail("tracked") @@ c).ignore
          _     <- (ZIO.fail("tracked") @@ c).ignore
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackErrorWith") {
        val base = Metric.counter("c15")
        val c    = base.trackErrorWith[String](_ => 1)

        for {
          _     <- ZIO.succeed("not tracked") @@ c
          _     <- (ZIO.fail("tracked") @@ c).ignore
          _     <- (ZIO.fail("tracked") @@ c).ignore
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackDefect") {
        val base = Metric.counter("c16").contramap[Throwable](_ => 1)
        val c    = base.trackDefect

        for {
          _     <- ZIO.succeed("not tracked") @@ c
          _     <- (ZIO.dieMessage("tracked") @@ c).exit
          _     <- (ZIO.dieMessage("tracked") @@ c).exit
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackDefectWith") {
        val base = Metric.counter("c17")
        val c    = base.trackDefectWith(_ => 1)

        for {
          _     <- ZIO.succeed("not tracked") @@ c
          _     <- (ZIO.dieMessage("tracked") @@ c).exit
          _     <- (ZIO.dieMessage("tracked") @@ c).exit
          state <- base.value
        } yield assertTrue(
          state == MetricState.Counter(2.0),
          state.count == 2.0
        )
      },
      test("trackDuration") {
        val base = Metric.counter("c18").contramap[Duration](_.toMillis)
        val c    = base.trackDuration

        for {
          _     <- TestClock.adjust(1.milliseconds) @@ c
          state <- base.value
        } yield assertTrue(
          state.count > 0.0
        )
      },
      test("trackDurationWith") {
        val base = Metric.counter("c19")
        val c    = base.trackDurationWith(_.toMillis)

        for {
          _     <- TestClock.adjust(1.milliseconds) @@ c
          state <- base.value
        } yield assertTrue(
          state.count > 0.0
        )
      }
    ),
    suite("Gauge")(
      test("custom set as aspect") {
        val g = Metric.gauge("g1").tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ g
          _     <- ZIO.succeed(3.0) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("direct increment") {
        val g = Metric.gauge("g2").tagged(labels1)

        for {
          _     <- g.update(1.0)
          _     <- g.update(3.0)
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("custom adjust as aspect") {
        val g = Metric.gauge("g3").tagged(labels1)

        for {
          _     <- ZIO.succeed(10.0) @@ g
          _     <- ZIO.succeed(5.0) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(5.0))
      },
      test("direct adjust") {
        val g = Metric.gauge("g4").tagged(labels1)

        for {
          _     <- g.update(10.0)
          _     <- g.update(5.0)
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(5.0))
      },
      test("setGauge") {
        val g5 = Metric.gauge("g5").tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ g5
          _     <- ZIO.succeed(3.0) @@ g5
          state <- g5.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("setGaugeWith") {
        val g = Metric.gauge("g7").tagged(labels1).contramap[Int](_.toDouble)
        for {
          _     <- ZIO.succeed(1) @@ g
          _     <- ZIO.succeed(3) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("increment gauge") {
        val g = Metric.gauge("g8").tagged(labels1).contramap[Int](_.toDouble)
        for {
          _     <- ZIO.collectAllPar(Chunk.fill(100)(g.increment))
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(100.0))
      }
    ),
    suite("Histogram")(
      test("custom observe as aspect") {
        val h = Metric.histogram("h1", Histogram.Boundaries.linear(0, 1.0, 10)).tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ h
          _     <- ZIO.succeed(3.0) @@ h
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("direct observe") {
        val h = Metric.histogram("h2", Histogram.Boundaries.linear(0, 1.0, 10)).tagged(labels1)

        for {
          _     <- h.update(1.0)
          _     <- h.update(3.0)
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("observeHistogram") {
        val h = Metric
          .histogram("h4", Histogram.Boundaries.linear(0, 1.0, 10))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ h
          _     <- ZIO.succeed(3.0) @@ h
          state <- h.value
        } yield assertTrue(
          state.count == 2L,
          state.sum == 4.0,
          state.min == 1.0,
          state.max == 3.0
        )
      },
      test("observeHistogramWith") {
        val h = Metric
          .histogram("h5", Histogram.Boundaries.linear(0, 1.0, 10))
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        for {
          _     <- ZIO.succeed("x") @@ h
          _     <- ZIO.succeed("xyz") @@ h
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("observeHistogramWith + taggedWith") {
        val boundaries = Histogram.Boundaries.linear(0, 1.0, 10)

        val base = Metric
          .histogram("h6", boundaries)
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        val h = base.taggedWith[String](s => Set(MetricLabel("dyn", s)))

        for {
          _  <- ZIO.succeed("x") @@ h
          _  <- ZIO.succeed("xyz") @@ h
          r0 <- base.value
          r1 <- base.tagged(MetricLabel("dyn", "x")).value
          r2 <- base.tagged(MetricLabel("dyn", "xyz")).value
        } yield assertTrue(r0.count == 0L, r1.count == 1L, r2.count == 1L)
      },
      test("linear boundaries") {
        val expected = Chunk(10, 20, 30, 40, 50, 60, 70, 80, 90).map(_.toDouble) :+ Double.MaxValue
        val actual   = Histogram.Boundaries.linear(0, 10, 10).values
        assertTrue(actual == expected)
      },
      test("exponential boundaries") {
        val expected = Chunk(1, 2, 4, 8, 16, 32, 64, 128, 256, 512).map(_.toDouble) :+ Double.MaxValue
        val actual   = Histogram.Boundaries.exponential(1, 2, 10).values
        assertTrue(actual == expected)
      },
      test("custom boundaries without zero and negative values") {
        val expected = Chunk(10, 20).map(_.toDouble) :+ Double.MaxValue
        val actual   = Histogram.Boundaries.fromChunk(Chunk(-10, 0, 10, 20)).values
        assertTrue(actual == expected)
      }
    ),
    suite("Summary")(
      test("custom observe as aspect") {
        val s = Metric
          .summary("s1", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ s
          _     <- ZIO.succeed(3.0) @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("direct observe") {
        val s = Metric
          .summary("s2", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- s.update(1.0)
          _     <- s.update(3.0)
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("observeSummary") {
        val s = Metric
          .summary("s3", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ s
          _     <- ZIO.succeed(3.0) @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("observeSummaryWith") {
        val s = Metric
          .summary("s4", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        for {
          _     <- ZIO.succeed("x") @@ s
          _     <- ZIO.succeed("xyz") @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0, state.min == 1.0, state.max == 3.0)
      },
      test("observeSummaryWith + taggedWith") {
        val s0 = Metric
          .summary("s6", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        val s = s0.taggedWith[String](s => Set(MetricLabel("dyn", s)))

        for {
          _  <- ZIO.succeed("x") @@ s
          _  <- ZIO.succeed("xyz") @@ s
          r0 <- s0.value
          r1 <- s0.tagged(MetricLabel("dyn", "x")).value
          r2 <- s0.tagged(MetricLabel("dyn", "xyz")).value
        } yield assertTrue(r0.count == 0L, r1.count == 1L, r2.count == 1L)
      }
    ),
    suite("Frequency")(
      test("custom observe as aspect") {
        val sc = Metric
          .frequency("sc1")
          .tagged(labels1)

        for {
          _     <- ZIO.succeed("hello") @@ sc
          _     <- ZIO.succeed("hello") @@ sc
          _     <- ZIO.succeed("world") @@ sc
          state <- sc.value
        } yield assertTrue(
          state.occurrences.toSet == Set("hello" -> 2L, "world" -> 1L)
        )
      },
      test("direct observe") {
        val sc = Metric
          .frequency("sc2")
          .tagged(labels1)

        for {
          _     <- sc.update("hello")
          _     <- sc.update("hello")
          _     <- sc.update("world")
          state <- sc.value
        } yield assertTrue(
          state.occurrences.toSet == Set("hello" -> 2L, "world" -> 1L)
        )
      },
      test("occurrences") {
        val sc = Metric
          .frequency("sc3")
          .tagged(labels1)

        for {
          _     <- ZIO.succeed("hello") @@ sc
          _     <- ZIO.succeed("hello") @@ sc
          _     <- ZIO.succeed("world") @@ sc
          state <- sc.value
        } yield assertTrue(
          state.occurrences.toSet == Set("hello" -> 2L, "world" -> 1L)
        )
      },
      test("occurrencesWith") {
        val sc = Metric
          .frequency("sc4")
          .tagged(labels1)
          .contramap[Int](_.toString)

        for {
          _     <- ZIO.succeed(1) @@ sc
          _     <- ZIO.succeed(1) @@ sc
          _     <- ZIO.succeed(100) @@ sc
          state <- sc.value
        } yield assertTrue(state.occurrences.toSet == Set("1" -> 2L, "100" -> 1L))
      },
      test("occurrences + taggedWith") {
        val sc0 = Metric
          .frequency("sc6")
          .tagged(labels1)

        val sc = sc0.taggedWith[String](s => Set(MetricLabel("dyn", s)))

        for {
          _  <- ZIO.succeed("hello") @@ sc
          _  <- ZIO.succeed("hello") @@ sc
          _  <- ZIO.succeed("world") @@ sc
          r0 <- sc0.value
          r1 <- sc0.tagged(MetricLabel("dyn", "hello")).value
          r2 <- sc0.tagged(MetricLabel("dyn", "world")).value
        } yield assertTrue(
          r0.occurrences.toSet.isEmpty,
          r1.occurrences.toSet == Set("hello" -> 2L),
          r2.occurrences.toSet == Set("world" -> 1L)
        )
      }
    ),
    test("tags are a region setting") {
      val counter = Metric.counter("counter")
      for {
        _     <- counter.increment @@ tagged("key" -> "value")
        state <- counter.tagged(MetricLabel("key", "value")).value
      } yield assertTrue(state == MetricState.Counter(1L))
    },
    test("timer") {
      val timer               = Metric.timer("timer", ChronoUnit.MILLIS)
      val timerWithBoundaries = Metric.timer("timer", ChronoUnit.MILLIS, Chunk(0.1, 0.2, 0.3))
      for {
        _ <- ZIO.unit @@ timer.trackDuration
        _ <- ZIO.unit @@ timerWithBoundaries.trackDuration
      } yield assertCompletes
    },
    test("timer with duration smaller than the unit") {
      val timer = Metric.timer("timer", ChronoUnit.SECONDS)
      for {
        _         <- TestClock.adjust(1.milliseconds) @@ timer.trackDuration
        histogram <- timer.value
      } yield assertTrue(histogram.sum > 0d)
    },
    test("metrics with description") {
      val name = "counterName"

      val counter1 = Metric.counter(name)
      val counter2 = Metric.counter(name, "description1")
      val counter3 = Metric.counter(name, "description2")

      for {
        _        <- counter1.update(1L)
        _        <- counter2.update(1L)
        _        <- counter3.update(1L)
        r1       <- counter1.value
        r2       <- counter2.value
        r3       <- counter3.value
        snapshot <- ZIO.succeed(Unsafe.unsafe(implicit unsafe => metricRegistry.snapshot()))
        pair1    <- ZIO.fromOption(snapshot.find(_.metricKey == MetricKey.counter(name)))
        pair2    <- ZIO.fromOption(snapshot.find(_.metricKey == MetricKey.counter(name, "description1")))
        pair3    <- ZIO.fromOption(snapshot.find(_.metricKey == MetricKey.counter(name, "description2")))
      } yield assertTrue(
        r1 == MetricState.Counter(1.0),
        r2 == MetricState.Counter(1.0),
        r3 == MetricState.Counter(1.0),
        pair1.metricState == MetricState.Counter(1.0),
        pair1.metricKey.description.isEmpty,
        pair2.metricState == MetricState.Counter(1.0),
        pair2.metricKey.description.contains("description1"),
        pair2.metricKey.toString == "MetricKey(counterName,Counter,Set(),Some(description1))",
        pair3.metricState == MetricState.Counter(1.0),
        pair3.metricKey.description.contains("description2")
      )
    },
    test("trackDurationWith propagates regional tags") {
      val metric = Metric.timer("timer", ChronoUnit.MILLIS)
      val aspect = metric.trackDuration
      for {
        _     <- ZIO.tagged("key", "value")(ZIO.unit @@ aspect)
        value <- metric.tagged("key", "value").value
        count  = value.count
      } yield assertTrue(count == 1L)
    }
  )
}
