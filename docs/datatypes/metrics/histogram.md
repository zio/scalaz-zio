---
id: histogram
title: "Histogram"
---

A `Histogram` is a metric representing a collection of numerical with the distribution of the cumulative values over time. A typical use of this metric would be to track the time to serve requests.

Histograms allow visualizing not only the value of the quantity being measured but its distribution. **Histograms are constructed with user-specified boundaries which describe the buckets to aggregate values into**.

## Internals

In a histogram, we assign the incoming samples to pre-defined buckets. So each data point increases the count for the bucket that it falls into, and then the individual samples are discarded. As histograms are bucketed, we can aggregate data across multiple instances. Histograms are a typical way to measure percentiles. We can look at bucket counts to estimate a specific percentile.

A histogram observes _Double_ values and counts the observed values in buckets. Each bucket is defined by an upper boundary, and the count for a bucket with the upper boundary `b` increases by `1` if an observed value `v` is less or
equal to `b`.

As a consequence, all buckets that have a boundary `b1` with b1 > b will increase by `1` after observing `v`.

A histogram also keeps track of the overall count of observed values, and the sum of all observed values.

By definition, the last bucket is always defined as `Double.MaxValue`, so that the count of observed values in the last bucket is always equal to the overall count of observed values within the histogram.

The mental model for histogram is inspired from [Prometheus](https://prometheus.io/docs/concepts/metric_types/#histogram).

## API

To define a histogram aspect, the API requires that the boundaries for the histogram are specified when creating the aspect.

**`observeHistogram`** — Create a histogram that can be applied to effects producing `Double` values. The values will be counted as outlined above. 

```scala
def observeHistogram(name: String, boundaries: Chunk[Double], tags: MetricLabel*): Histogram[Double]
```

**`observeHistogramWith`** — Create a histogram that can be applied to effects producing values `v` of `A`. The values `f(v)` will be counted as outlined above. 

```scala
def observeHistogramWith[A](name: String, boundaries: Chunk[Double], tags: MetricLabel*)(
  f: A => Double
): Histogram[A]
```

## Use Cases

Histogram measures the frequency of value observations that fall into specific _pre-defined buckets_. For example, we can measure the request duration of an HTTP request using histograms. Rather than storing every duration for every request, the histogram will make an approximation by storing the frequency of requests that fall into pre-defined particular buckets.

Thus, histograms are the best choice in these situations:
- When we want to observe many values and then later want to calculate the percentile of observed values
- When we can estimate the range of values upfront, as the histogram put the observations into pre-defined buckets
- When accuracy is not so important, and we don't want the exact values because of the lossy nature of bucketing data in histograms
- When we need to aggregate histograms across multiple instances

Some examples of histogram use cases:
- Request Latency
- Response Time

## Examples

Create a histogram with 12 buckets: `0..100` in steps of `10` and `Double.MaxValue`. It can be applied to effects yielding a `Double`:

```scala mdoc:silent:nest
import zio._
val histogram =
  ZIOMetric.observeHistogram("histogram", ZIOMetric.Histogram.Boundaries.linear(0, 10, 11))
```

Now we can apply the histogram to effects producing `Double`:

```scala mdoc:silent:nest
Random.nextDoubleBetween(0.0d, 120.0d) @@ histogram
```
