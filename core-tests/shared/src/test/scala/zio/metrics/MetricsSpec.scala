package zio.metrics

import zio.metrics.MetricKeyType.Histogram
import zio.test.assertTrue
import zio.{Chunk, ZIO, ZIOBaseSpec, durationInt}

object MetricsSpec extends ZIOBaseSpec {
  private val labels = Set(MetricLabel("x", "a"), MetricLabel("y", "b"))

  private val counterName   = "test_counter"
  private val gaugeName     = "test_gauge"
  private val frequencyName = "test_frequency"
  private val histogramName = "test_histogram"
  private val summaryName   = "test_summary"

  private val counter   = Metric.counter(counterName, "description1").tagged(labels).fromConst(1L)
  private val gauge     = Metric.gauge(gaugeName, "description2").tagged(labels)
  private val frequency = Metric.frequency(frequencyName).tagged(labels)
  private val histogram =
    Metric.histogram(histogramName, Histogram.Boundaries.fromChunk(Chunk(1.0, 2.0, 3.0))).tagged(labels)
  private val summary =
    Metric.summary(summaryName, 1.minute, 10, 0.0, Chunk(0.1, 0.5, 0.9)).tagged(labels)

  def spec = suite("CurrentMetrics")(
    test("Metrics prettyPrint should correctly process empty Metric set") {
      for {
        currentMetrics <- ZIO.succeed(Metrics(Set.empty))
        str            <- currentMetrics.prettyPrint
      } yield assertTrue(str == "")
    },
    test("should be pretty printed correctly") {
      for {
        _ <- ZIO.succeed(1.0) @@ counter @@ gauge @@ histogram @@ summary
        _ <- ZIO.succeed(3.0) @@ counter @@ gauge @@ histogram @@ summary
        _ <- ZIO.succeed("strValue1") @@ frequency
        _ <- ZIO.succeed("strValue2") @@ frequency
        _ <- ZIO.succeed("strValue1") @@ frequency
        // set of metrics in the current snapshot may include metrics from other tests
        allMetricsSnapshot <- ZIO.metrics
        testSnapshot <-
          ZIO.succeed(
            Metrics(
              allMetricsSnapshot.metrics.filter(m =>
                Set(counterName, gaugeName, frequencyName, histogramName, summaryName).contains(m.metricKey.name)
              )
            )
          )
        str <- testSnapshot.prettyPrint
      } yield assertTrue(
        str == s"""test_counter(description1)  tags[x: a, y: b]  Counter[${2.0}]
                  |test_frequency              tags[x: a, y: b]  Frequency[(strValue2 -> 1), (strValue1 -> 2)]
                  |test_gauge(description2)    tags[x: a, y: b]  Gauge[${3.0}]
                  |test_histogram              tags[x: a, y: b]  Histogram[buckets: [(${1.0} -> 1), (${2.0} -> 1), (${3.0} -> 2), (${1.7976931348623157e308} -> 2)], count: [2], min: [${1.0}], max: [${3.0}], sum: [${4.0}]]
                  |test_summary                tags[x: a, y: b]  Summary[quantiles: [(0.1 -> Some(${1.0})), (0.5 -> Some(${1.0})), (0.9 -> Some(${3.0}))], count: [2], min: [${1.0}], max: [${3.0}], sum: [${4.0}]]""".stripMargin
      )
    }
  )

}
