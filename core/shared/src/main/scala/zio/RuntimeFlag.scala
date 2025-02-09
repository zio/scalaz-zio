/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

package zio

/**
 * A [[RuntimeFlag]] is a flag that can be set to enable or disable a particular
 * feature of the ZIO runtime.
 */
sealed trait RuntimeFlag {
  def index: Int

  def mask: Int

  def notMask: Int
}

object RuntimeFlag {
  val all: Set[RuntimeFlag] =
    Set(
      Interruption,
      CurrentFiber,
      OpLog,
      OpSupervision,
      RuntimeMetrics,
      FiberRoots,
      WindDown,
      CooperativeYielding,
      WorkStealing,
      EagerShiftBack
    )

  /**
   * The interruption flag determines whether or not the ZIO runtime system will
   * interrupt a fiber.
   */
  case object Interruption extends RuntimeFlag {
    final val index   = 0
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The current fiber flag determines whether or not the ZIO runtime system
   * will store the current fiber inside a `ThreadLocal` whenever a fiber begins
   * executing on a thread. Use of this flag will negatively impact performance,
   * but is essential where interop with ThreadLocal is required.
   */
  case object CurrentFiber extends RuntimeFlag {
    final val index   = 1
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The op log flag determines whether or not the ZIO runtime system will
   * attempt to log all operations of the ZIO runtime. Use of this flag will
   * negatively impact performance and generate massive volumes of ultra-fine
   * debug logs. Only recommended for debugging.
   */
  case object OpLog extends RuntimeFlag {
    final val index   = 2
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The op supervision flag determines whether or not the IO runtime system
   * will supervise all operations of the ZIO runtime. Use of this flag will
   * negatively impact performance, but is required for some operations, such as
   * profiling.
   */
  case object OpSupervision extends RuntimeFlag {
    final val index   = 3
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The runtime metrics flag determines whether or not the ZIO runtime system
   * will collect metrics about the ZIO runtime. Use of this flag will have a
   * very small negative impact on performance, but generates very helpful
   * operational insight into running ZIO applications that can be exported to
   * Prometheus or other tools via ZIO Metrics.
   */
  case object RuntimeMetrics extends RuntimeFlag {
    final val index   = 4
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The fiber roots flag determines whether or not the ZIO runtime system will
   * keep track of all fiber roots. Use of this flag will negatively impact
   * performance, but is required in order for fiber dumps functionality.
   */
  case object FiberRoots extends RuntimeFlag {
    final val index   = 5
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The wind down flag determines whether the ZIO runtime system will execute
   * effects in wind-down mode. In wind-down mode, even if interruption is
   * enabled and a fiber has been interrupted, the fiber will continue its
   * execution uninterrupted.
   */
  case object WindDown extends RuntimeFlag {
    final val index   = 6
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The cooperative yielding flag determines whether the ZIO runtime will yield
   * to another fiber while executing long-running effects or continuously
   * forking fibers.
   *
   * Disabling this flag is highly discouraged but it is necessary for cases
   * where fine-grained control over fiber scheduling is required.
   */
  case object CooperativeYielding extends RuntimeFlag {
    final val index   = 7
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * The work stealing flag determines whether threads running fibers about to
   * asynchronously suspend will first attempt to steal work before suspending.
   */
  case object WorkStealing extends RuntimeFlag {
    final val index   = 8
    final val mask    = 1 << index
    final val notMask = ~mask
  }

  /**
   * Determines whether the ZIO runtime will eagerly shift back execution to the
   * default executor following (enabled) or minimize context shifts by
   * continuing execution on the same thread as long as possible (disabled).
   *
   * Enabling this flag can positively or negatively affect performance
   * depending on the specific characteristics of the application. For more info
   * on this
   * [[https://blog.pierre-ricadat.com/tuning-zio-for-high-performance#heading-executor-override refer to this blog post.]]
   */
  case object EagerShiftBack extends RuntimeFlag {
    final val index   = 9
    final val mask    = 1 << index
    final val notMask = ~mask
  }
}
