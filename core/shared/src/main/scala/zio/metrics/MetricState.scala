/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.metrics

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * A `MetricState` describes the state of a metric. The type parameter of a
 * metric state corresponds to the type of the metric key (MetricKeyType). This
 * phantom type parameter is used to tie keys to their expected states.
 */
sealed trait MetricState[+Type]

object MetricState {
  type Untyped = MetricState[Any]

  final case class Counter(count: Double) extends MetricState[MetricKeyType.Counter]

  final case class Frequency(
    occurrences: Map[String, Long]
  ) extends MetricState[MetricKeyType.Frequency]

  final case class Gauge(value: Double) extends MetricState[MetricKeyType.Gauge]

  final case class Histogram(
    buckets: Chunk[(Double, Long)],
    count: Long,
    min: Double,
    max: Double,
    sum: Double
  ) extends MetricState[MetricKeyType.Histogram]

  final case class Summary(
    error: Double,
    quantiles: Chunk[(Double, Option[Double])],
    count: Long,
    min: Double,
    max: Double,
    sum: Double
  ) extends MetricState[MetricKeyType.Summary]
}
