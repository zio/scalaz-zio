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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

sealed trait MetricKeyType {
  type In
  type Out
}
object MetricKeyType {
  type WithIn[T] = MetricKeyType {
    type In = T
  }

  type Counter = Counter.type

  case object Counter extends MetricKeyType {
    type In  = Double
    type Out = MetricState.Counter
  }

  type Frequency = Frequency.type
  case object Frequency extends MetricKeyType {
    type In  = String
    type Out = MetricState.Frequency
  }

  type Gauge = Gauge.type
  case object Gauge extends MetricKeyType {
    type In  = Double
    type Out = MetricState.Gauge
  }

  final case class Histogram(
    boundaries: Histogram.Boundaries
  ) extends MetricKeyType {
    type In  = Double
    type Out = MetricState.Histogram
  }

  object Histogram {
    final case class Boundaries(values: Chunk[Double])

    object Boundaries {

      def fromChunk(chunk: Chunk[Double]): Boundaries =
        Boundaries((chunk.filter(_ > 0) ++ Chunk(Double.MaxValue)).distinct)

      /**
       * A helper method to create histogram bucket boundaries for a histogram
       * with linear increasing values
       */
      def linear(start: Double, width: Double, count: Int): Boundaries =
        fromChunk(Chunk.fromArray(0.until(count).map(i => start + i * width).toArray))

      /**
       * A helper method to create histogram bucket boundaries for a histogram
       * with exponentially increasing values
       */
      def exponential(start: Double, factor: Double, count: Int): Boundaries =
        fromChunk(Chunk.iterate(start, count)(_ * factor))
    }
  }

  final case class Summary(
    maxAge: Duration,
    maxSize: Int,
    error: Double,
    quantiles: Chunk[Double]
  ) extends MetricKeyType {
    type In  = (Double, java.time.Instant)
    type Out = MetricState.Summary
  }
}
