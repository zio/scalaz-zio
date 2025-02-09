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

import scala.runtime.ScalaRunTime

/**
 * A `MetricKey` is a unique key associated with each metric. The key is based
 * on a combination of the metric type, the name and tags associated with the
 * metric, and any other information to describe a a metric, such as the
 * boundaries of a histogram. In this way, it is impossible to ever create
 * different metrics with conflicting keys.
 */
sealed case class MetricKey[+Type] private (
  name: String,
  keyType: Type,
  tags: Set[MetricLabel] = Set.empty
) { self =>

  def description: Option[String] = None

  def copy[Type2](name: String = name, keyType: Type2 = keyType, tags: Set[MetricLabel] = tags): MetricKey[Type2] =
    new MetricKey(name, keyType, tags) {
      override def description: Option[String] = self.description
    }

  override def hashCode(): Int =
    ScalaRunTime._hashCode((name, keyType, tags, description))

  override def equals(other: Any): Boolean =
    other match {
      case other @ MetricKey(name, keyType, tags)
          if self.name == name && self.keyType == keyType && self.tags == tags &&
            self.description == other.description && other.canEqual(self) =>
        true
      case _ => false
    }

  override def toString: String =
    s"${self.productPrefix}($name,$keyType,$tags,$description)"

  /**
   * Returns a new `MetricKey` with the specified tag appended.
   */
  def tagged(key: String, value: String): MetricKey[Type] =
    tagged(Set(MetricLabel(key, value)))

  /**
   * Returns a new `MetricKey` with the specified tags appended.
   */
  def tagged(extraTag: MetricLabel, extraTags: MetricLabel*): MetricKey[Type] =
    tagged(Set(extraTag) ++ extraTags.toSet)

  /**
   * Returns a new `MetricKey` with the specified tags appended.
   */
  def tagged(extraTags: Set[MetricLabel]): MetricKey[Type] =
    if (extraTags.isEmpty) self else copy(tags = tags ++ extraTags)
}
object MetricKey {
  type Untyped = MetricKey[Any]

  type Counter   = MetricKey[MetricKeyType.Counter]
  type Gauge     = MetricKey[MetricKeyType.Gauge]
  type Histogram = MetricKey[MetricKeyType.Histogram]
  type Summary   = MetricKey[MetricKeyType.Summary]
  type Frequency = MetricKey[MetricKeyType.Frequency]

  import MetricKeyType.Histogram.Boundaries

  /**
   * Creates a metric key for a counter, with the specified name.
   */
  def counter(name: String): Counter =
    MetricKey(name, MetricKeyType.Counter)

  /**
   * Creates a metric key for a counter, with the specified name and
   * description.
   */
  def counter(name: String, description0: String): Counter =
    new MetricKey(name, MetricKeyType.Counter) {
      override val description: Option[String] = Some(description0)
    }

  /**
   * Creates a metric key for a categorical frequency table, with the specified
   * name.
   */
  def frequency(name: String): Frequency =
    MetricKey(name, MetricKeyType.Frequency)

  /**
   * Creates a metric key for a categorical frequency table, with the specified
   * name and description.
   */
  def frequency(name: String, description0: String): Frequency =
    new MetricKey(name, MetricKeyType.Frequency) {
      override val description: Option[String] = Some(description0)
    }

  /**
   * Creates a metric key for a gauge, with the specified name.
   */
  def gauge(name: String): Gauge =
    MetricKey(name, MetricKeyType.Gauge)

  /**
   * Creates a metric key for a gauge, with the specified name and description.
   */
  def gauge(name: String, description0: String): Gauge =
    new MetricKey(name, MetricKeyType.Gauge) {
      override val description: Option[String] = Some(description0)
    }

  /**
   * Creates a metric key for a histogram, with the specified name.
   */
  def histogram(
    name: String,
    boundaries: Boundaries
  ): Histogram =
    MetricKey(name, MetricKeyType.Histogram(boundaries))

  /**
   * Creates a metric key for a histogram, with the specified name and
   * description.
   */
  def histogram(
    name: String,
    description0: String,
    boundaries: Boundaries
  ): Histogram =
    new MetricKey(name, MetricKeyType.Histogram(boundaries)) {
      override val description: Option[String] = Some(description0)
    }

  /**
   * Creates a metric key for a summary, with the specified name.
   */
  def summary(
    name: String,
    maxAge: Duration,
    maxSize: Int,
    error: Double,
    quantiles: Chunk[Double]
  ): Summary =
    MetricKey(name, MetricKeyType.Summary(maxAge, maxSize, error, quantiles))

  /**
   * Creates a metric key for a summary, with the specified name and
   * description.
   */
  def summary(
    name: String,
    description0: String,
    maxAge: Duration,
    maxSize: Int,
    error: Double,
    quantiles: Chunk[Double]
  ): Summary =
    new MetricKey(name, MetricKeyType.Summary(maxAge, maxSize, error, quantiles)) {
      override val description: Option[String] = Some(description0)
    }
}
