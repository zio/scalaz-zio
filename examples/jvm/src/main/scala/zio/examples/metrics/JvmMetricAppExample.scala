package zio.examples.metrics

import zio._
import zio.http._
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.connectors.{MetricsConfig, prometheus}
import zio.metrics.jvm.DefaultJvmMetrics

object JvmMetricAppExample extends ZIOAppDefault {
  private val httpApp =
    Routes(
      Method.GET / "metrics" ->
        handler(ZIO.serviceWithZIO[PrometheusPublisher](_.get.map(Response.text)))
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
      ZLayer.succeed(MetricsConfig(5.seconds)),

      // Default JVM Metrics
      DefaultJvmMetrics.live.unit
    )
}
