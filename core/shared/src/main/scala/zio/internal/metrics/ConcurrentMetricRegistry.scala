package zio.internal.metrics

import zio._
import zio.metrics._

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec

private[zio] class ConcurrentMetricRegistry {

  private val listenersRef: AtomicReference[Array[MetricListener]] =
    new AtomicReference[Array[MetricListener]](Array.empty[MetricListener])

  private val map: ConcurrentHashMap[MetricKey[MetricKeyType], MetricHook.Root] =
    new ConcurrentHashMap[MetricKey[MetricKeyType], MetricHook.Root]()

  def snapshot()(implicit unsafe: Unsafe): Set[MetricPair.Untyped] = {
    val iterator = map.entrySet().iterator()
    val result   = scala.collection.mutable.Set[MetricPair.Untyped]()
    while (iterator.hasNext) {
      val value = iterator.next()
      val key   = value.getKey()
      val hook  = value.getValue()
      result.add(MetricPair.make(key, hook.get()))
    }
    result.toSet
  }

  def get[Type <: MetricKeyType](
    key: MetricKey[Type]
  )(implicit unsafe: Unsafe): MetricHook[key.keyType.In, key.keyType.Out] = {
    type Result = MetricHook[key.keyType.In, key.keyType.Out]

    var hook0: MetricHook[_, zio.metrics.MetricState.Untyped] = map.get(key)

    if (hook0 eq null) {
      (key.keyType match {
        case MetricKeyType.Counter             => getCounter(key.asInstanceOf[MetricKey.Counter])
        case MetricKeyType.Frequency           => getSetCount(key.asInstanceOf[MetricKey.Frequency])
        case MetricKeyType.Gauge               => getGauge(key.asInstanceOf[MetricKey.Gauge])
        case MetricKeyType.Histogram(_)        => getHistogram(key.asInstanceOf[MetricKey.Histogram])
        case MetricKeyType.Summary(_, _, _, _) => getSummary(key.asInstanceOf[MetricKey.Summary])
      }).asInstanceOf[Result]
    } else hook0.asInstanceOf[Result]
  }

  def remove[Type <: MetricKeyType](key: MetricKey[Type])(implicit unsafe: Unsafe): Boolean =
    map.remove(key) ne null

  @tailrec
  final def addListener(listener: MetricListener)(implicit unsafe: Unsafe): Unit = {
    val oldListeners = listenersRef.get()
    val newListeners = oldListeners :+ listener
    if (!listenersRef.compareAndSet(oldListeners, newListeners)) addListener(listener)
    else ()
  }

  @tailrec
  final def removeListener(listener: MetricListener)(implicit unsafe: Unsafe): Unit = {
    val oldListeners = listenersRef.get()
    val newListeners = oldListeners.filter(_ ne listener)
    if (!listenersRef.compareAndSet(oldListeners, newListeners)) removeListener(listener)
    else ()
  }

  private[zio] def notifyListeners[T](
    key: MetricKey[MetricKeyType.WithIn[T]],
    value: T,
    eventType: MetricEventType
  )(implicit trace: Trace, unsafe: Unsafe): Unit = {
    val listeners = listenersRef.get()
    val len       = listeners.length

    if (len > 0) {
      var i = 0
      key.keyType match {
        case MetricKeyType.Gauge =>
          eventType match {
            case MetricEventType.Modify =>
              while (i < len) {
                listeners(i).modifyGauge(key.asInstanceOf[MetricKey.Gauge], value.asInstanceOf[Double])
                i = i + 1
              }
            case MetricEventType.Update =>
              while (i < len) {
                listeners(i).updateGauge(key.asInstanceOf[MetricKey.Gauge], value.asInstanceOf[Double])
                i = i + 1
              }
          }
        case MetricKeyType.Histogram(_) =>
          while (i < len) {
            listeners(i).updateHistogram(key.asInstanceOf[MetricKey.Histogram], value.asInstanceOf[Double])
            i = i + 1
          }
        case MetricKeyType.Frequency =>
          while (i < len) {
            listeners(i).updateFrequency(key.asInstanceOf[MetricKey.Frequency], value.asInstanceOf[String])
            i = i + 1
          }
        case MetricKeyType.Summary(_, _, _, _) =>
          while (i < len) {
            val (v, instant) = value.asInstanceOf[(Double, Instant)]
            listeners(i).updateSummary(key.asInstanceOf[MetricKey.Summary], v, instant)
            i = i + 1
          }
        case MetricKeyType.Counter =>
          while (i < len) {
            listeners(i).updateCounter(key.asInstanceOf[MetricKey.Counter], value.asInstanceOf[Double])
            i = i + 1
          }
      }
    }
  }

  private def getCounter(key: MetricKey.Counter)(implicit unsafe: Unsafe): MetricHook.Counter = {
    var value = map.get(key)
    if (value eq null) {
      val counter = ConcurrentMetricHooks.counter(key)
      map.putIfAbsent(key, counter)
      value = map.get(key)
    }
    value.asInstanceOf[MetricHook.Counter]
  }

  private def getGauge(key: MetricKey.Gauge)(implicit unsafe: Unsafe): MetricHook.Gauge = {
    var value = map.get(key)
    if (value eq null) {
      val gauge = ConcurrentMetricHooks.gauge(key, 0.0)
      map.putIfAbsent(key, gauge)
      value = map.get(key)
    }
    value.asInstanceOf[MetricHook.Gauge]
  }

  private def getHistogram(key: MetricKey.Histogram)(implicit unsafe: Unsafe): MetricHook.Histogram = {
    var value = map.get(key)
    if (value eq null) {
      val histogram =
        ConcurrentMetricHooks.histogram(key)
      map.putIfAbsent(key, histogram)
      value = map.get(key)
    }
    value.asInstanceOf[MetricHook.Histogram]
  }

  private def getSummary(
    key: MetricKey.Summary
  )(implicit unsafe: Unsafe): MetricHook.Summary = {
    var value = map.get(key)
    if (value eq null) {
      val summary = ConcurrentMetricHooks.summary(key)
      map.putIfAbsent(key, summary)
      value = map.get(key)
    }
    value.asInstanceOf[MetricHook.Summary]
  }

  private def getSetCount(key: MetricKey.Frequency)(implicit unsafe: Unsafe): MetricHook.Frequency = {
    var value = map.get(key)
    if (value eq null) {
      val frequency = ConcurrentMetricHooks.frequency(key)
      map.putIfAbsent(key, frequency)
      value = map.get(key)
    }
    value.asInstanceOf[MetricHook.Frequency]
  }
}
