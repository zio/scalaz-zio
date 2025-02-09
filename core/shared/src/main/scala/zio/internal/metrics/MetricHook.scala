/*
 * Copyright 2022-2024 John A. De Goes and the ZIO Contributors
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

package zio.internal.metrics

private[zio] final case class MetricHook[-In, +Out](
  update: In => Unit,
  get: () => Out,
  modify: In => Unit
)

private[zio] object MetricHook {
  import zio.metrics.MetricState

  type Root    = MetricHook[_, MetricState.Untyped]
  type Untyped = MetricHook[_, _]

  type Counter   = MetricHook[Double, MetricState.Counter]
  type Gauge     = MetricHook[Double, MetricState.Gauge]
  type Histogram = MetricHook[Double, MetricState.Histogram]
  type Summary   = MetricHook[(Double, java.time.Instant), MetricState.Summary]
  type Frequency = MetricHook[String, MetricState.Frequency]
}
