package zio.metrics.jvm

import zio._

/**
 * JVM metrics, compatible with the prometheus-hotspot library, with
 * configurable schedule
 */
trait DefaultJvmMetrics {
  protected def jvmMetricsSchedule: ULayer[JvmMetricsSchedule]

  /** A ZIO application that periodically updates the JVM metrics */
  lazy val app: ZIOAppDefault = new ZIOAppDefault {
    override val bootstrap: ZLayer[ZIOAppArgs, Any, Any]         = live
    override def run: ZIO[Environment with ZIOAppArgs, Any, Any] = ZIO.unit
  }

  /**
   * Layer that starts collecting the same JVM metrics as the Prometheus Java
   * client's default exporters
   */
  lazy val live: ZLayer[
    Any,
    Throwable,
    Reloadable[BufferPools]
      with ClassLoading
      with GarbageCollector
      with MemoryAllocation
      with MemoryPools
      with Standard
      with Thread
      with VersionInfo
  ] =
    jvmMetricsSchedule >>>
      (BufferPools.live ++
        ClassLoading.live ++
        GarbageCollector.live ++
        MemoryAllocation.live ++
        MemoryPools.live ++
        Standard.live ++
        Thread.live ++
        VersionInfo.live)
}

/** JVM metrics, compatible with the prometheus-hotspot library */
object DefaultJvmMetrics extends DefaultJvmMetrics {
  override protected def jvmMetricsSchedule: ULayer[JvmMetricsSchedule] = JvmMetricsSchedule.default
}
