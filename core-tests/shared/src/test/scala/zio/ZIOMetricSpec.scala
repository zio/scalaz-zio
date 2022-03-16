package zio

import zio.metrics._
import zio.metrics.MetricKeyType.Histogram
import zio.test._

object ZIOMetricSpec extends ZIOBaseSpec {

  private val labels1 = Set(MetricLabel("x", "a"), MetricLabel("y", "b"))

  def spec = suite("ZIOMetric")(
    suite("Counter")(
      test("custom increment as aspect") {
        val c = ZIOMetric.counter("c1").tagged(labels1).fromConst(1L)

        for {
          _     <- ZIO.unit @@ c
          _     <- ZIO.unit @@ c
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(2.0))
      },
      test("direct increment") {
        val c = ZIOMetric.counter("c2").tagged(labels1)

        for {
          _     <- c.increment
          _     <- c.increment
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(2.0))
      },
      test("custom increment by value as aspect") {
        val c = ZIOMetric.counter("c3").tagged(labels1)

        for {
          _     <- ZIO.succeed(10L) @@ c
          _     <- ZIO.succeed(5L) @@ c
          state <- c.value
        } yield assertTrue(state == MetricState.Counter(15.0))
      },
      test("count") {
        for {
          _     <- ZIO.unit @@ ZIOMetric.counter("c5").tagged(labels1).contramap[Unit](_ => 1)
          _     <- ZIO.unit @@ ZIOMetric.counter("c5").tagged(labels1).contramap[Unit](_ => 1)
          state <- ZIOMetric.counter("c5").tagged(labels1).value
        } yield assertTrue(
          state == MetricState.Counter(2.0)
        )
      },
      test("countValue") {
        for {
          _     <- ZIO.succeed(10L) @@ ZIOMetric.counter("c6").tagged(labels1)
          _     <- ZIO.succeed(5L) @@ ZIOMetric.counter("c6").tagged(labels1)
          state <- ZIOMetric.counter("c6").tagged(labels1).value
        } yield assertTrue(
          state == MetricState.Counter(15.0),
          state.count == 15.0
        )
      },
      test("countValueWith") {
        val c = ZIOMetric.counter("c7").tagged(labels1).contramap[String](_.length.toLong)
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
        val c = ZIOMetric.counter("c8").contramap[Unit](_ => 1)

        for {
          _     <- (ZIO.unit @@ c *> ZIO.fail("error") @@ c).ignore
          state <- c.value
        } yield assertTrue(
          state == MetricState.Counter(1.0),
          state.count == 1.0
        )
      },
      test("count + taggedWith") {
        val base = ZIOMetric
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
      }
    ),
    suite("Gauge")(
      test("custom set as aspect") {
        val g = ZIOMetric.gauge("g1").tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ g
          _     <- ZIO.succeed(3.0) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("direct increment") {
        val g = ZIOMetric.gauge("g2").tagged(labels1)

        for {
          _     <- g.update(1.0)
          _     <- g.update(3.0)
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("custom adjust as aspect") {
        val g = ZIOMetric.gauge("g3").tagged(labels1)

        for {
          _     <- ZIO.succeed(10.0) @@ g
          _     <- ZIO.succeed(5.0) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(5.0))
      },
      test("direct adjust") {
        val g = ZIOMetric.gauge("g4").tagged(labels1)

        for {
          _     <- g.update(10.0)
          _     <- g.update(5.0)
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(5.0))
      },
      test("setGauge") {
        val g5 = ZIOMetric.gauge("g5").tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ g5
          _     <- ZIO.succeed(3.0) @@ g5
          state <- g5.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      },
      test("setGaugeWith") {
        val g = ZIOMetric.gauge("g7").tagged(labels1).contramap[Int](_.toDouble)
        for {
          _     <- ZIO.succeed(1) @@ g
          _     <- ZIO.succeed(3) @@ g
          state <- g.value
        } yield assertTrue(state == MetricState.Gauge(3.0))
      }
    ),
    suite("Histogram")(
      test("custom observe as aspect") {
        val h = ZIOMetric.histogram("h1", Histogram.Boundaries.linear(0, 1.0, 10)).tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ h
          _     <- ZIO.succeed(3.0) @@ h
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("direct observe") {
        val h = ZIOMetric.histogram("h2", Histogram.Boundaries.linear(0, 1.0, 10)).tagged(labels1)

        for {
          _     <- h.update(1.0)
          _     <- h.update(3.0)
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("observeDurations") {
        val h =
          ZIOMetric
            .histogram("h3", Histogram.Boundaries.linear(0, 1.0, 10))
            .tagged(labels1)
            .contramap[Duration](_.toMillis.toDouble / 1000.0)

        for {
          // NOTE: observeDurations always uses real clock
          start  <- ZIO.attempt(java.lang.System.nanoTime())
          _      <- (Clock.sleep(1.second) @@ h.trackDuration).provide(Clock.live)
          _      <- (Clock.sleep(3.seconds) @@ h.trackDuration).provide(Clock.live)
          end    <- ZIO.attempt(java.lang.System.nanoTime())
          elapsed = (end - start) / 1e9
          state  <- h.value
        } yield assertTrue(
          state.count == 2L,
          state.sum > 3.9,
          state.sum <= elapsed
        )
      },
      test("observeHistogram") {
        val h = ZIOMetric
          .histogram("h4", Histogram.Boundaries.linear(0, 1.0, 10))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ h
          _     <- ZIO.succeed(3.0) @@ h
          state <- h.value
        } yield assertTrue(
          state.count == 2L,
          state.sum == 4.0
        )
      },
      test("observeHistogramWith") {
        val h = ZIOMetric
          .histogram("h5", Histogram.Boundaries.linear(0, 1.0, 10))
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        for {
          _     <- ZIO.succeed("x") @@ h
          _     <- ZIO.succeed("xyz") @@ h
          state <- h.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("observeHistogramWith + taggedWith") {
        val boundaries = Histogram.Boundaries.linear(0, 1.0, 10)

        val base = ZIOMetric
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
      }
    ),
    suite("Summary")(
      test("custom observe as aspect") {
        val s = ZIOMetric
          .summary("s1", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ s
          _     <- ZIO.succeed(3.0) @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("direct observe") {
        val s = ZIOMetric
          .summary("s2", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- s.update(1.0)
          _     <- s.update(3.0)
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("observeSummary") {
        val s = ZIOMetric
          .summary("s3", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)

        for {
          _     <- ZIO.succeed(1.0) @@ s
          _     <- ZIO.succeed(3.0) @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("observeSummaryWith") {
        val s = ZIOMetric
          .summary("s4", 1.minute, 10, 0.0, Chunk(0.0, 1.0, 10.0))
          .tagged(labels1)
          .contramap[String](_.length.toDouble)

        for {
          _     <- ZIO.succeed("x") @@ s
          _     <- ZIO.succeed("xyz") @@ s
          state <- s.value
        } yield assertTrue(state.count == 2L, state.sum == 4.0)
      },
      test("observeSummaryWith + taggedWith") {
        val s0 = ZIOMetric
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
        val sc = ZIOMetric
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
        val sc = ZIOMetric
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
        val sc = ZIOMetric
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
        val sc = ZIOMetric
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
        val sc0 = ZIOMetric
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
    )
  )
}
