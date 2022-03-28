package zio.metrics.jvm

import com.github.ghik.silencer.silent

import zio._
import zio.metrics._

import java.lang.management.{BufferPoolMXBean, ManagementFactory}

import scala.collection.JavaConverters._

final case class BufferPools(
  bufferPoolUsedBytes: PollingMetric[Any, Throwable, Chunk[MetricState.Gauge]],
  bufferPoolCapacityBytes: PollingMetric[Any, Throwable, Chunk[MetricState.Gauge]],
  bufferPoolUsedBuffers: PollingMetric[Any, Throwable, Chunk[MetricState.Gauge]]
)

object BufferPools {
  @silent("JavaConverters")
  val live: ZLayer[JvmMetricsSchedule, Throwable, Reloadable[BufferPools]] =
    ZLayer.scoped {
      for {
        bufferPoolMXBeans <- ZIO.attempt(ManagementFactory.getPlatformMXBeans(classOf[BufferPoolMXBean]).asScala)
        bufferPoolUsedBytes = PollingMetric.collectAll(bufferPoolMXBeans.map { mxBean =>
                                PollingMetric(
                                  Metric
                                    .gauge("jvm_buffer_pool_used_bytes")
                                    .tagged("pool", mxBean.getName)
                                    .contramap[Long](_.toDouble),
                                  ZIO.attempt(mxBean.getMemoryUsed)
                                )
                              })
        bufferPoolCapacityBytes = PollingMetric.collectAll(bufferPoolMXBeans.map { mxBean =>
                                    PollingMetric(
                                      Metric
                                        .gauge("jvm_buffer_pool_capacity_bytes")
                                        .tagged("pool", mxBean.getName)
                                        .contramap[Long](_.toDouble),
                                      ZIO.attempt(mxBean.getTotalCapacity)
                                    )
                                  })
        bufferPoolUsedBuffers = PollingMetric.collectAll(bufferPoolMXBeans.map { mxBean =>
                                  PollingMetric(
                                    Metric
                                      .gauge("jvm_buffer_pool_used_buffers")
                                      .tagged("pool", mxBean.getName)
                                      .contramap[Long](_.toDouble),
                                    ZIO.attempt(mxBean.getCount)
                                  )
                                })

        schedule <- ZIO.service[JvmMetricsSchedule]
        _        <- bufferPoolUsedBytes.launch(schedule.updateMetrics)
        _        <- bufferPoolCapacityBytes.launch(schedule.updateMetrics)
        _        <- bufferPoolUsedBuffers.launch(schedule.updateMetrics)
      } yield BufferPools(bufferPoolUsedBytes, bufferPoolCapacityBytes, bufferPoolUsedBuffers)
    }.reloadableAutoFromConfig[JvmMetricsSchedule](config => config.get.reloadDynamicMetrics)
}
