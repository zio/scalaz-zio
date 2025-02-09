---
id: index
title: "Introduction to ZIO Metrics"
---

In highly concurrent applications, things are interconnected, so maintaining such a setup to run smoothly and without application downtimes is very challenging. 

Imagine we have a complex infrastructure with lots of services. Services are replicated and distributed across our servers. So we have no insight into what happening across our services at the level of errors, response latency, service uptime, etc. With ZIO Metrics, we can capture these different metrics and provide them to a _metric service_ for later investigation.

ZIO supports 5 types of Metrics:

* **[Counter](counter.md)** — The Counter is used for any value that increases over time like _request counts_.
* **[Gauge](gauge.md)** — The gauge is a single numerical value that can go up or down over time like _memory usage_.
* **[Histogram](histogram.md)** — The Histogram is used to track the distribution of a set of observed values across a set of buckets like _request latencies_.
* **[Summary](summary.md)** — The Summary represents a sliding window of a time series along with metrics for certain percentiles of the time series, referred to as quantiles like _request latencies_.
* **[Frequency](frequency.md)** — The Frequency is a metric that counts the number of occurrences of distinct string values.

All ZIO Metrics are defined in the form of ZIO Aspects that can be applied to effects without changing the signature of the effect it is applied to:

```scala mdoc:silent:nest
import zio._
import zio.metrics._

def memoryUsage: ZIO[Any, Nothing, Double] = {
  import java.lang.Runtime._
  ZIO
    .succeed(getRuntime.totalMemory() - getRuntime.freeMemory())
    .map(_ / (1024.0 * 1024.0)) @@ Metric.gauge("memory_usage")
}
```

After adding metrics into our application, whenever we want we can capture snapshots of all metrics recorded by our application, by any of the metric backends supported by [ZIO Metrics Connectors](https://github.com/zio/zio-metrics-connectors) project.

Here is an example of adding a Prometheus connector to our application:

```scala mdoc:passthrough
import utils._

printSource("examples/jvm/src/main/scala/zio/examples/metrics/MetricAppExample.scala")
```

To run this example we need to add following lines to our `build.sbt` file:

```scala

```

ZIO Metrics Connectors currently supports the following backends:

- [Prometheus](https://prometheus.io/)
- [Datadog](https://www.datadoghq.com/)
- [New Relic](https://newrelic.com/)
- [StatsD](https://github.com/statsd/statsd)
