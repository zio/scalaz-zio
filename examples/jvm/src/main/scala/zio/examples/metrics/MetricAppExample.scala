package zio.examples.metrics

import zio._
import zio.http._
import zio.metrics.Metric
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.connectors.{MetricsConfig, prometheus}

object MetricAppExample extends ZIOAppDefault {
  def memoryUsage: ZIO[Any, Nothing, Double] = {
    import java.lang.Runtime._
    ZIO
      .succeed(getRuntime.totalMemory() - getRuntime.freeMemory())
      .map(_ / (1024.0 * 1024.0)) @@ Metric.gauge("memory_usage")
  }

  private val httpApp =
    Routes(
      Method.GET / "metrics" ->
        handler(ZIO.serviceWithZIO[PrometheusPublisher](_.get.map(Response.text))),
      Method.GET / "foo" -> handler {
        for {
          _    <- memoryUsage
          time <- Clock.currentDateTime
        } yield Response.text(s"$time\t/foo API called")
      }
    )

  override def run = Server
    .serve(httpApp)
    .provide(
      // ZIO Http default server layer, default port: 8080
      Server.default,
      // The prometheus reporting layer
      prometheus.prometheusLayer,
      prometheus.publisherLayer,
      // Interval for polling metrics
      ZLayer.succeed(MetricsConfig(5.seconds))
    )
}
