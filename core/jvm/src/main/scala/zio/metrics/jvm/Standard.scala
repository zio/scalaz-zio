package zio.metrics.jvm

import zio._
import zio.metrics.Metric.Gauge
import zio.metrics.{Metric, MetricState, PollingMetric}

import java.lang.management.{ManagementFactory, PlatformManagedObject}
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import scala.util.{Failure, Success, Try}

final case class Standard(
  cpuSecondsTotal: PollingMetric[Any, Throwable, MetricState.Gauge],
  processStartTime: PollingMetric[Any, Throwable, MetricState.Gauge],
  openFdCount: PollingMetric[Any, Throwable, MetricState.Gauge],
  maxFdCount: PollingMetric[Any, Throwable, MetricState.Gauge],
  virtualMemorySize: Gauge[Double],
  residentMemorySize: Gauge[Double]
)

object Standard {
  private class MXReflection(getterName: String, obj: PlatformManagedObject) {
    private val cls: Class[_ <: PlatformManagedObject] = obj.getClass
    private val method: Option[Method]                 = findGetter(Try(cls.getMethod(getterName)))

    def isAvailable: Boolean = method.isDefined

    def get(implicit trace: Trace, unsafe: Unsafe): Task[Long] =
      method match {
        case Some(getter) => ZIO.attempt(getter.invoke(obj).asInstanceOf[Long])
        case None =>
          ZIO.fail(new IllegalStateException(s"MXReflection#get called on unavailable metri"))
      }

    private def findGetter(getter: Try[Method]): Option[Method] =
      getter match {
        case Failure(_) =>
          None
        case Success(method) =>
          try {
            val _ = method.invoke(obj).asInstanceOf[Long]
            Some(method)
          } catch {
            case _: IllegalAccessException =>
              var result: Option[Method] = None
              var idx                    = 0
              val ifaces                 = method.getDeclaringClass.getInterfaces

              while (idx < ifaces.length && result.isEmpty) {
                val iface = ifaces(idx)
                result = findGetter(Try(iface.getMethod(getterName)))
                idx = idx + 1
              }

              result
          }
      }
  }

  private def collectMemoryMetricsLinux(
    virtualMemorySize: Gauge[Double],
    residentMemorySize: Gauge[Double]
  ): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      ZIO.readFileInputStream("/proc/self/status").flatMap { stream =>
        stream
          .readAll(8192)
          .catchAll {
            case None        => Exit.emptyChunk
            case Some(error) => ZIO.fail(error)
          }
          .flatMap { bytes =>
            ZIO.attempt(new String(bytes.toArray, StandardCharsets.US_ASCII)).flatMap { raw =>
              ZIO.foreachDiscard(raw.split('\n')) { line =>
                if (line.startsWith("VmSize:")) {
                  virtualMemorySize.set(line.split("\\s+")(1).toDouble * 1024.0)
                } else if (line.startsWith("VmRSS:")) {
                  residentMemorySize.set(line.split("\\s+")(1).toDouble * 1024.0)
                } else {
                  ZIO.unit
                }
              }
            }
          }
      }
    }

  val live: ZLayer[JvmMetricsSchedule, Throwable, Standard] =
    ZLayer.scoped {
      for {
        runtimeMXBean         <- ZIO.attempt(ManagementFactory.getRuntimeMXBean)
        operatingSystemMXBean <- ZIO.attempt(ManagementFactory.getOperatingSystemMXBean)
        getProcessCpuTime      = new MXReflection("getProcessCpuTime", operatingSystemMXBean)
        getOpenFileDescriptorCount =
          new MXReflection("getOpenFileDescriptorCount", operatingSystemMXBean)
        getMaxFileDescriptorCount =
          new MXReflection("getMaxFileDescriptorCount", operatingSystemMXBean)
        isLinux <- ZIO.attempt(operatingSystemMXBean.getName.indexOf("Linux") == 0)
        cpuSecondsTotal =
          PollingMetric(
            Metric.gauge("process_cpu_seconds_total").contramap[Long](_.toDouble / 1.0e09),
            getProcessCpuTime.get(Trace.empty, Unsafe.unsafe)
          )
        processStartTime =
          PollingMetric(
            Metric
              .gauge("process_start_time_seconds")
              .contramap[Long](_.toDouble / 1000.0),
            ZIO.attempt(runtimeMXBean.getStartTime)
          )
        openFdCount =
          PollingMetric(
            Metric
              .gauge("process_open_fds")
              .contramap[Long](_.toDouble),
            getOpenFileDescriptorCount.get(Trace.empty, Unsafe.unsafe)
          )
        maxFdCount =
          PollingMetric(
            Metric
              .gauge("process_max_fds")
              .contramap[Long](_.toDouble),
            getMaxFileDescriptorCount.get(Trace.empty, Unsafe.unsafe)
          )
        virtualMemorySize  = Metric.gauge("process_virtual_memory_bytes")
        residentMemorySize = Metric.gauge("process_resident_memory_bytes")

        schedule <- ZIO.service[JvmMetricsSchedule]
        _        <- cpuSecondsTotal.launch(schedule.updateMetrics)
        _        <- processStartTime.launch(schedule.updateMetrics)
        _        <- openFdCount.launch(schedule.updateMetrics).when(getOpenFileDescriptorCount.isAvailable)
        _        <- maxFdCount.launch(schedule.updateMetrics).when(getMaxFileDescriptorCount.isAvailable)
        _ <-
          collectMemoryMetricsLinux(virtualMemorySize, residentMemorySize)
            .scheduleFork(schedule.updateMetrics)
            .when(isLinux)

      } yield Standard(
        cpuSecondsTotal,
        processStartTime,
        openFdCount,
        maxFdCount,
        virtualMemorySize,
        residentMemorySize
      )
    }
}
