---
id: jvm
title: "JVM Metrics"
---

ZIO has built-in support for collecting JVM Metrics. These metrics are a direct port of the JVM metrics provided by the [Prometheus Java Hotspot library](https://github.com/prometheus/client_java/tree/master/simpleclient_hotspot) and compatible with that library.

There are five categories of JVM metrics. Let's look at them one by one:

- Buffer Pools
    - `jvm_buffer_pool_used_bytes` — Used bytes of a given JVM buffer pool.
    - `jvm_buffer_pool_capacity_bytes` — Bytes capacity of a given JVM buffer pool.
    - `jvm_buffer_pool_used_buffers` — Used buffers of a given JVM buffer pool.
- Class Loading
    - `jvm_classes_loaded` — The number of classes that are currently loaded in the JVM
    - `jvm_classes_loaded_total` — The total number of classes that have been loaded since the JVM has started execution
    - `jvm_classes_unloaded_total` — The total number of classes that have been unloaded since the JVM has started
      execution
- Garbage Collector
    - `jvm_gc_collection_seconds_sum` — Time spent in a given JVM garbage collector in seconds.
    - `jvm_gc_collection_seconds_count`
- Memory Allocation
    - `jvm_memory_pool_allocated_bytes_total` — Total bytes allocated in a given JVM memory pool. Only updated after GC, not continuously.
- Memory Pools
    - `jvm_memory_bytes_used` — Used bytes of a given JVM memory area.
    - `jvm_memory_bytes_committed` — Committed (bytes) of a given JVM memory area.
    - `jvm_memory_bytes_max` — Max (bytes) of a given JVM memory area.
    - `jvm_memory_bytes_init` — Initial bytes of a given JVM memory area.
    - `jvm_memory_pool_bytes_used` — Used bytes of a given JVM memory pool.
    - `jvm_memory_pool_bytes_committed` — Committed bytes of a given JVM memory pool.
    - `jvm_memory_pool_bytes_max` — Max bytes of a given JVM memory pool.
    - `jvm_memory_pool_bytes_init` — Initial bytes of a given JVM memory pool.
- Standard
    - `process_cpu_seconds_total` — Total user and system CPU time spent in seconds.
    - `process_start_time_seconds` — Start time of the process since unix epoch in seconds.
    - `process_open_fds` — Number of open file descriptors.
    - `process_max_fds` — Maximum number of open file descriptors.
    - `process_virtual_memory_bytes` — Virtual memory size in bytes.
    - `process_resident_memory_bytes` — Resident memory size in bytes.
- Thread
    - `jvm_threads_current` — Current thread count of a JVM.
    - `jvm_threads_daemon` — Daemon thread count of a JVM.
    - `jvm_threads_peak` — Peak thread count of a JVM.
    - `jvm_threads_started_total` — Started thread count of a JVM.
    - `jvm_threads_deadlocked` — Cycles of JVM-threads that are in deadlock waiting to acquire object monitors or ownable synchronizers.
    - `jvm_threads_deadlocked_monitor` — Cycles of JVM-threads that are in deadlock waiting to acquire object monitors.
    - `jvm_threads_state` — Current count of threads by state.
- Version Info
    - `jvm_info`
        - `version` — java.runtime.version 
        - `vendor` — java.vm.vendor
        - `runtime` — java.runtime.name

## Collecting Metrics

### Collecting Inside a ZIO Application

JVM Metrics are collection of the following ZIO services:
- BufferPools
- ClassLoading
- GarbageCollector
- MemoryAllocation
- MemoryPools
- Standard
- Thread
- VersionInfo

All of these services are available in the `zio.metrics.jvm` package. Each service has a `live` implementation that can be used to collect metrics, or we can use all of them at once with by providing `DefaultJvmMetrics.live` layer to our application.

### Collecting as a Sidecar to a ZIO Application

ZIO JVM metrics have built-in applications that collect the JVM metrics. They can be composed with other ZIO applications as a _sidecar_. By doing so, we are able to collect JVM metrics without modifying our main ZIO application. They will be executed as a daemon alongside the main app:

```scala mdoc:passthrough
import utils._

printSource("examples/jvm/src/main/scala/zio/examples/metrics/JvmMetricAppExample.scala")
```