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
package zio.metrics

import zio.Unsafe

final case class MetricPair[Type <: MetricKeyType { type Out = Out0 }, Out0](
  metricKey: MetricKey[Type],
  metricState: MetricState[Out0]
)

object MetricPair {
  private type Lossy[Out0] = MetricPair[MetricKeyType { type Out = Out0 }, Out0]

  type Untyped = Lossy[Any]

  private[zio] def make[Type <: MetricKeyType](
    metricKey: MetricKey[Type],
    metricState: MetricState[_]
  )(implicit unsafe: Unsafe): MetricPair.Untyped = {
    type Out0  = Any
    type Type0 = MetricKeyType { type Out = Out0 }

    val metricKey2   = metricKey.asInstanceOf[MetricKey[Type0]]
    val metricState2 = metricState.asInstanceOf[MetricState[Out0]]

    MetricPair(metricKey2, metricState2).asInstanceOf[Untyped]
  }

}
