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

import zio._
import zio.metrics._

private[zio] class ConcurrentMetricHooksPlatformSpecific extends ConcurrentMetricHooks {
  def counter(key: MetricKey.Counter): MetricHook.Counter = {
    var sum = 0.0

    MetricHook(v => sum += v, () => MetricState.Counter(sum), v => sum += v)
  }

  def gauge(key: MetricKey.Gauge, startAt: Double): MetricHook.Gauge = {
    var value = startAt

    MetricHook(v => value = v, () => MetricState.Gauge(value), v => value += v)
  }

  def histogram(key: MetricKey.Histogram): MetricHook.Histogram = {
    val bounds     = key.keyType.boundaries.values
    val values     = Array.ofDim[Long](bounds.length + 1)
    val boundaries = Array.ofDim[Double](bounds.length)
    var count      = 0L
    var sum        = 0.0
    var size       = bounds.length
    var min        = Double.MaxValue
    var max        = Double.MinValue

    bounds.sorted.zipWithIndex.foreach { case (n, i) => boundaries(i) = n }

    // Insert the value into the right bucket with a binary search
    val update = (value: Double) => {
      var from = 0
      var to   = size
      while (from != to) {
        val mid      = from + (to - from) / 2
        val boundary = boundaries(mid)
        if (value <= boundary) to = mid else from = mid

        // The special case when to / from have a distance of one
        if (to == from + 1) {
          if (value <= boundaries(from)) to = from else from = to
        }
      }
      values(from) = values(from) + 1
      count += 1
      sum += value
      if (value < min) min = value
      if (value > max) max = value
      ()
    }

    def getBuckets(): Chunk[(Double, Long)] = {
      val builder   = ChunkBuilder.make[(Double, Long)]()
      var i         = 0
      var cumulated = 0L
      while (i != size) {
        val boundary = boundaries(i)
        val value    = values(i)
        cumulated += value
        builder += boundary -> cumulated
        i += 1
      }
      builder.result()
    }

    MetricHook(
      update,
      () => MetricState.Histogram(getBuckets(), count, min, max, sum),
      update
    )
  }

  def summary(key: MetricKey.Summary): MetricHook.Summary = {
    import key.keyType.{maxSize, maxAge, error, quantiles}

    val values = Array.ofDim[(java.time.Instant, Double)](maxSize)
    var head   = 0
    var count  = 0L
    var sum    = 0.0
    var min    = Double.MaxValue
    var max    = Double.MinValue

    val sortedQuantiles: Chunk[Double] = quantiles.sorted(DoubleOrdering)

    def getCount(): Long = count

    def getMin(): Double = min

    def getMax(): Double = max

    def getSum(): Double = sum

    // Just before the Snapshot we filter out all values older than maxAge
    def snapshot(now: java.time.Instant): Chunk[(Double, Option[Double])] = {
      val builder = ChunkBuilder.make[Double]()

      // If the buffer is not full yet it contains valid items at the 0..last indices
      // and null values at the rest of the positions.
      // If the buffer is already full then all elements contains a valid measurement with timestamp.
      // At any given point in time we can enumerate all the non-null elements in the buffer and filter
      // them by timestamp to get a valid view of a time window.
      // The order does not matter because it gets sorted before passing to calculateQuantiles.

      for (idx <- 0 until maxSize) {
        val item = values(idx)
        if (item ne null) {
          val (t, v) = item
          val age    = Duration.fromInterval(t, now)
          if (!age.isNegative && age.compareTo(maxAge) <= 0) {
            builder += v
          }
        }
      }

      zio.internal.metrics.calculateQuantiles(sortedQuantiles, builder.result().sorted(DoubleOrdering))
    }

    // Assuming that the instant of observed values is continuously increasing
    // While Observing we cut off the first sample if we have already maxSize samples
    def observe(value: Double, t: java.time.Instant): Unit = {
      if (maxSize > 0) {
        head = head + 1 // TODO: Should `head` start at -1???
        val target = head % maxSize
        values(target) = (t, value)
      }

      count += 1
      sum += value
      if (value < min) min = value
      if (value > max) max = value
      ()
    }

    MetricHook(
      t => observe(t._1, t._2),
      () =>
        MetricState.Summary(
          error,
          snapshot(java.time.Instant.now()),
          getCount(),
          getMin(),
          getMax(),
          getSum()
        ),
      t => observe(t._1, t._2)
    )
  }

  def frequency(key: MetricKey.Frequency): MetricHook.Frequency = {
    var count  = 0L
    val values = new java.util.HashMap[String, Long]()

    val update = (word: String) => {
      count += 1
      var slotCount = Option(values.get(word)).getOrElse(0L)
      values.put(word, slotCount + 1)
      ()
    }

    def snapshot(): Map[String, Long] = {
      val builder = scala.collection.mutable.Map[String, Long]()
      val it      = values.entrySet().iterator()
      while (it.hasNext()) {
        val e = it.next()
        builder.update(e.getKey(), e.getValue().longValue())
      }

      builder.toMap
    }

    MetricHook(update, () => MetricState.Frequency(snapshot()), update)
  }
}
