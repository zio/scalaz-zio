---
id: tracing
title: "Introduction to Tracing in ZIO"
sidebar_label: "Tracing"
---

Although logs and metrics are useful for understanding the behavior of individual services, they are not enough to provide a complete overview of the lifetime of a request in a distributed system.

In a distributed system, a request can span multiple services and each service can make multiple requests to other services to fulfill the request. In such a scenario, we need to have a way to track the lifetime of a request across multiple services to diagnose what services are the bottlenecks and where the request is spending most of its time.

ZIO Telemetry supports tracing through the OpenTelemetry API. To learn more about tracing, please refer to the [ZIO Telemetry documentation](https://zio.dev/zio-telemetry/opentracing).
