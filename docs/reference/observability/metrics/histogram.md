---
id: histogram
title: "Histogram"
---

A `Histogram` is a metric representing a collection of numerical with the distribution of the cumulative values over time. They organize a range of measurements into distinct intervals, known as buckets, and record the frequency of measurements falling within each bucket. 

Histograms allow representing not only the value of the quantity being measured but its distribution. They are representation of the distribution of a dataset, which organizes the data into buckets and display the frequency or count of data points within each bucket. 

## Internals

In a histogram, we assign the incoming samples to pre-defined buckets. So each data point increases the count for the bucket that it falls into, and then the individual samples are discarded. As histograms are bucketed, we can aggregate data across multiple instances. Histograms are a typical way to measure percentiles. We can look at bucket counts to estimate a specific percentile.

A histogram observes _Double_ values and counts the observed values in buckets. Each bucket is defined by an upper boundary, and the count for a bucket with the upper boundary `b` increases by `1` if an observed value `v` is less or
equal to `b`.

As a consequence, all buckets that have a boundary `b1` with `b1 > b` will increase by `1` after observing `v`.

A histogram also keeps track of the overall count of observed values, and the sum of all observed values.

By definition, the last bucket is always defined as `Double.MaxValue`, so that the count of observed values in the last bucket is always equal to the overall count of observed values within the histogram.

The mental model for histogram is inspired from [Prometheus](https://prometheus.io/docs/concepts/metric_types/#histogram).

## API

```scala
object Metric {
  def histogram(
      name: String,
      boundaries: Histogram.Boundaries
    ): Histogram[Double] = ???
  
  def timer(
      name: String,
      description: String,
      chronoUnit: ChronoUnit
    ): Metric[MetricKeyType.Histogram, Duration, MetricState.Histogram] = ???
  
  def timer(
      name: String,
      chronoUnit: ChronoUnit,
      boundaries: Chunk[Double]
    ): Metric[MetricKeyType.Histogram, Duration, MetricState.Histogram] = ???
}
```

## Use Cases

Histograms are widely used in software metrics for various purposes. They are useful in analyzing the performance of software systems. They can represent metrics such as **response times**, **latencies**, or **throughput**. By visualizing the distribution of these metrics in a histogram, developers can identify **performance bottlenecks**, **outliers**, or **variations**. This information aids in optimizing code, infrastructure, and system configurations to improve overall performance.

Histogram measures the frequency of value observations that fall into specific _pre-defined buckets_. For example, we can measure the request duration of an HTTP request using histograms. Rather than storing every duration for every request, the histogram will make an approximation by storing the frequency of requests that fall into pre-defined particular buckets.

Thus, histograms are the best choice in these situations:

- When we want to observe many values and then later want to calculate the percentile of observed values
- When we can estimate the range of values upfront, as the histogram put the observations into pre-defined buckets
- When accuracy is not so important, and we don't want the exact values because of the lossy nature of bucketing data in histograms
- When we need to aggregate histograms across multiple instances

## Examples

### Histogram With Linear Buckets

Create a histogram with 12 buckets: `0..100` in steps of `10` and `Double.MaxValue`. It can be applied to effects yielding a `Double`:

```scala mdoc:silent:nest
import zio._
import zio.metrics._

val histogram =
  Metric.histogram("histogram", MetricKeyType.Histogram.Boundaries.linear(0, 10, 11))
```

Now we can apply the histogram to effects producing `Double`:

```scala mdoc:silent:nest
import zio._
import zio.metrics._

Random.nextDoubleBetween(0.0d, 120.0d) @@ histogram
```

### Timer Metric

Here is an example of adding timer metric to track workflow durations:

```scala mdoc:compile-only
import zio._
import zio.metrics._

import java.time.temporal.ChronoUnit

object Example extends ZIOAppDefault {

  def workflow = ZIO.succeed(42)

  def randomDelay =
    for {
      i <- Random.nextLongBetween(1L, 10)
      _ <- ZIO.sleep(Duration.fromMillis(i))
    } yield ()
    
  val timer =
    Metric.timer(
      name = "timer",
      chronoUnit = ChronoUnit.MILLIS,
      boundaries = Chunk.iterate(1.0, 10)(_ + 1.0)
    )

  val run = ((workflow <* randomDelay) @@ timer.trackDuration).repeatN(99)
}
``` 

If we add prometheus layer, we expose the metrics which is something like this:

```csv
# TYPE timer histogram
# HELP timer
timer_bucket{time_unit="millis",le="1.0",} 6.0 1686581577320
timer_bucket{time_unit="millis",le="2.0",} 16.0 1686581577320
timer_bucket{time_unit="millis",le="3.0",} 27.0 1686581577320
timer_bucket{time_unit="millis",le="4.0",} 41.0 1686581577320
timer_bucket{time_unit="millis",le="5.0",} 49.0 1686581577320
timer_bucket{time_unit="millis",le="6.0",} 60.0 1686581577320
timer_bucket{time_unit="millis",le="7.0",} 70.0 1686581577320
timer_bucket{time_unit="millis",le="8.0",} 85.0 1686581577320
timer_bucket{time_unit="millis",le="9.0",} 99.0 1686581577320
timer_bucket{time_unit="millis",le="10.0",} 99.0 1686581577320
timer_bucket{time_unit="millis",le="+Inf",} 100.0 1686581577320

timer_sum{time_unit="millis",} 603.0 1686581577320
timer_count{time_unit="millis",} 100.0 1686581577320
timer_min{time_unit="millis",} 1.0 1686581577320
timer_max{time_unit="millis",} 66.0 1686581577320⏎
```

This Prometheus result represents a histogram metric called "timer" with a time unit of milliseconds. The histogram provides information about the distribution of workflow durations.

The histogram is divided into multiple buckets, each representing a range of workflow durations. The "le" label indicates the upper bound of each bucket. The values next to each bucket indicate the count or frequency of measurements falling within that bucket. 

For instance, `"timer_bucket{time_unit="millis",le="5.0",} 49.0"` means there are 49 measurements with duration less than or equal to 5.0 millisecond.
