---
id: monitor-a-zio-application-using-zio-built-in-metric-system
title: "Tutorial: How to Monitor a ZIO Application Using ZIO's Built-in Metric System?"
sidebar_label: "Monitoring a ZIO Application Using ZIO's Built-in Metric System"
---

## Introduction

ZIO has a built-in metric system that allows us to monitor the performance of our application. This is very useful for debugging and tuning our application. In this tutorial, we are going to learn how to add metrics to our application and then how to connect our application to one of the metric backends, e.g. Prometheus.

## Running the Example

To access the code examples, you can clone the [ZIO Quickstarts](http://github.com/zio/zio-quickstarts) project:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-restful-webservice-metrics
```

And finally, run the application using sbt:

```bash
$ sbt run
```

Alternatively, to enable hot-reloading and prevent port binding issues, you can use:

```bash
sbt reStart
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Trying a Simple Example

Before going to apply the metrics in our application, let's try a simple example:

```scala mdoc:compile-only
import zio._
import zio.metrics.Metric

object MainApp extends ZIOAppDefault {
  private val count = Metric.counterInt("fib_call_total").fromConst(1)

  def fib(n: Int): ZIO[Any, Nothing, Int] =
    if (n <= 1) ZIO.succeed(1)
    else for {
      a <- fib(n - 1) @@ count
      b <- fib(n - 2) @@ count
    } yield a + b

  def run =
    for {
      i <- Console.readLine("Please enter a number to calculate fibonacci: ").mapAttempt(_.toInt)
      n <- fib(i) @@ count
      _ <- Console.printLine(s"fib($i) = $n")
      c <- count.value
      _ <- ZIO.debug(s"number of fib calls to calculate fib($i): ${c.count}")
    } yield ()
}
```

In this example, we are going to calculate the Fibonacci number for a given number. We also count the number of times we call the `fib` function using the `count` metric. Finally, we will print the value of the metric as a debug message.

This is a pedagogical example of how to use metrics. In real life, we will probably want to poll the metrics using a web API and feed them to a monitoring system, e.g. Prometheus. In the following sections, we will learn how to do that by applying the metrics to our RESTful web service.

## Built-in ZIO HTTP Metrics

ZIO HTTP has built-in support for metrics. We can attach metrics middleware to our HTTP application using the `@@` syntax:

```scala mdoc:invisible
import zio._
import zio.schema._

case class User(name: String, age: Int)

object User {
  implicit val schema: Schema[User] = DeriveSchema.gen[User]
}

trait UserRepo {
  def register(user: User): Task[String]

  def lookup(id: String): Task[Option[User]]

  def users: Task[List[User]]
}

object UserRepo {
  def register(user: User): ZIO[UserRepo, Throwable, String] =
    ZIO.serviceWithZIO[UserRepo](_.register(user))

  def lookup(id: String): ZIO[UserRepo, Throwable, Option[User]] =
    ZIO.serviceWithZIO[UserRepo](_.lookup(id))

  def users: ZIO[UserRepo, Throwable, List[User]] =
    ZIO.serviceWithZIO[UserRepo](_.users)
}
```

```scala mdoc:silent
import zio._
import zio.http._
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec


object UserRoutes {

  def apply(): Routes[UserRepo, Response] =
    Routes(
      Method.GET / "users" -> handler {
        UserRepo.users.foldZIO(
          e =>
            ZIO
              .logError(s"Failed to retrieve users. $e") *>
              ZIO.fail(Response.internalServerError("Cannot retrieve users!")),
          users =>
            ZIO
              .log(
                s"Retrieved users successfully: response length=${users.length}"
              )
              .as(Response(body = Body.from(users)))
        )
      }
    ) @@ Middleware.metrics()
}
```

The `metrics` middleware is attached to all the routes in the `UserRoutes`. Currently, it only counts the number of requests to the `/users` endpoint. We can add more routes to the `UserRoutes` and all of them will be counted by the `metrics` middleware.

After adding the metrics to routes, it is time to serve the metrics as a RESTful API. Before that, let's add the required dependencies to our project.

## Dependencies

In the following sections, we are going to utilize the `zio-metrics-connector` module from the ZIO ZMX project and also provide metrics as a REST API. So let's add the following dependency to our project:

```scala
libraryDependencies += "dev.zio" %% "zio-metrics-connectors"            % "@ZIO_METRICS_CONNECTORS_VERSION@"
libraryDependencies += "dev.zio" %% "zio-metrics-connectors-prometheus" % "@ZIO_METRICS_CONNECTORS_VERSION@"
```

This module provides various connectors for metrics backend, e.g. Prometheus.

## Serving Prometheus Metrics

The following snippet shows how to provide an HTTP endpoint that exposes the metrics as a REST API for Prometheus:

```scala mdoc:invisible:reset

```

```scala mdoc:invisible
import zio._
import zio.http._

object GreetingRoutes {
  def apply() = Routes.empty
}

object DownloadRoutes {
  def apply() = Routes.empty
}

object CounterRoutes {
  def apply() = Routes.empty
}

object UserRoutes {
  def apply() = Routes.empty
}

object InmemoryUserRepo {
  val layer = ZLayer.empty
}
```

```scala mdoc:silent
import zio.http._
import zio._
import zio.metrics.connectors.prometheus.PrometheusPublisher

object PrometheusPublisherRoutes {
  def apply(): Routes[PrometheusPublisher, Nothing] = {
    Routes(
      Method.GET / "metrics" ->
        handler(
          ZIO.serviceWithZIO[PrometheusPublisher](_.get.map(Response.text))
        )
    )
  }
}
```

Next, we need to add the `PrometheusPublisherRoutes` HTTP App to our application:

```scala mdoc:silent
import zio._
import zio.http._
import zio.metrics.connectors.{MetricsConfig, prometheus}

object MainApp extends ZIOAppDefault {
  private val metricsConfig = ZLayer.succeed(MetricsConfig(1.seconds))

  def run =
    Server.serve(
      GreetingRoutes() ++ DownloadRoutes() ++ CounterRoutes() ++ UserRoutes() ++ PrometheusPublisherRoutes()
    ).provide(
      Server.default,

      // An layer responsible for storing the state of the `counterApp`
      ZLayer.fromZIO(Ref.make(0)),

      // To use the persistence layer, provide the `PersistentUserRepo.layer` layer instead
      InmemoryUserRepo.layer,

      // configs for metric backends
      metricsConfig,

      // The prometheus reporting layer
      prometheus.publisherLayer,
      prometheus.prometheusLayer,
    )
}
```

## Testing the Metrics

Now that we have the metrics as a REST API, we can test them. Let's run the application and then send some requests to the application as below:

```bash
$ curl -i http://localhost:8080/users -d '{"name": "John", "age": 42}'
$ curl -i http://localhost:8080/users -d '{"name": "Jane", "age": 43}'
$ curl -i http://localhost:8080/users
```

If we fetch the metrics from the "/metrics" endpoint, we will see the metrics in the Prometheus format, like below:

```bash
$ curl -i http://localhost:8080/metrics
HTTP/1.1 200 OK
content-type: text/plain
date: Tue, 30 Apr 2024 18:58:26 GMT
content-length: 4801

# TYPE http_concurrent_requests_total gauge
# HELP http_concurrent_requests_total
http_concurrent_requests_total{method="GET",path="/users",} 0.0 1714503503829
# TYPE http_concurrent_requests_total gauge
# HELP http_concurrent_requests_total
http_concurrent_requests_total{method="POST",path="/users",} 0.0 1714503503829
# TYPE http_request_duration_seconds histogram
# HELP http_request_duration_seconds
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.005",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.01",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.025",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.05",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.075",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.1",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.25",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.5",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="0.75",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="1.0",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="2.5",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="5.0",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="7.5",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="10.0",} 2.0 1714503503829
http_request_duration_seconds_bucket{method="POST",path="/users",status="200",le="+Inf",} 2.0 1714503503829

http_request_duration_seconds_sum{method="POST",path="/users",status="200",} 0.100570365 1714503503829
http_request_duration_seconds_count{method="POST",path="/users",status="200",} 2.0 1714503503829
http_request_duration_seconds_min{method="POST",path="/users",status="200",} 0.00120463 1714503503829
http_request_duration_seconds_max{method="POST",path="/users",status="200",} 0.099365735 1714503503829
# TYPE http_request_duration_seconds histogram
# HELP http_request_duration_seconds
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.005",} 0.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.01",} 0.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.025",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.05",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.075",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.1",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.25",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.5",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="0.75",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="1.0",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="2.5",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="5.0",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="7.5",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="10.0",} 1.0 1714503503829
http_request_duration_seconds_bucket{method="GET",path="/users",status="200",le="+Inf",} 1.0 1714503503829

http_request_duration_seconds_sum{method="GET",path="/users",status="200",} 0.017157212 1714503503829
http_request_duration_seconds_count{method="GET",path="/users",status="200",} 1.0 1714503503829
http_request_duration_seconds_min{method="GET",path="/users",status="200",} 0.017157212 1714503503829
http_request_duration_seconds_max{method="GET",path="/users",status="200",} 0.017157212 1714503503829
# TYPE http_requests_total counter
# HELP http_requests_total
http_requests_total{method="POST",path="/users",status="200",} 2.0 1714503503829
# TYPE http_requests_total counter
# HELP http_requests_total
http_requests_total{method="GET",path="/users",status="200",} 1.0 1714503503829⏎
```

Now that we have the metrics as a REST API, we can add this endpoint to our Prometheus server to fetch the metrics periodically.

## Conclusion

In this tutorial, we have learned how to define metrics and apply them to our application. We have also learned how to provide the metrics as a REST API which then can be polled by a Prometheus server.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) on GitHub.
