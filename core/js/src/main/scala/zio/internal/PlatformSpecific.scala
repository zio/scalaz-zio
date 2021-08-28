/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

package zio.internal

import zio.internal.stacktracer.{Tracer, ZTraceElement}
import zio.internal.tracing.TracingConfig
import zio.{Cause, Fiber, FiberRef, LogLevel, LogSpan, Supervisor}

import java.util.{HashMap, HashSet, Map => JMap, Set => JSet}
import scala.concurrent.ExecutionContext
import scala.scalajs.js.Dynamic.{global => jsglobal}

private[internal] trait PlatformSpecific {

  /**
   * Adds a shutdown hook that executes the specified action on shutdown.
   */
  def addShutdownHook(action: () => Unit): Unit = {
    val _ = action
  }

  /**
   * A Runtime with settings suitable for benchmarks, specifically with Tracing
   * and auto-yielding disabled.
   *
   * Tracing adds a constant ~2x overhead on FlatMaps, however, it's an
   * optional feature and it's not valid to compare the performance of ZIO with
   * enabled Tracing with effect types _without_ a comparable feature.
   */
  lazy val benchmark: Platform = makeDefault(Int.MaxValue).copy(reportFailure = _ => (), tracing = Tracing.disabled)

  /**
   * The default platform, configured with settings designed to work well for
   * mainstream usage. Advanced users should consider making their own platform
   * customized for specific application requirements.
   */
  lazy val default: Platform = makeDefault()

  /**
   * The default number of operations the ZIO runtime should execute before
   * yielding to other fibers.
   */
  final val defaultYieldOpCount = 2048

  /**
   * Returns the name of the thread group to which this thread belongs. This
   * is a side-effecting method.
   */
  val getCurrentThreadGroup: String = ""

  /**
   * A `Platform` created from Scala's global execution context.
   */
  lazy val global: Platform = fromExecutionContext(ExecutionContext.global)

  /**
   * Creates a platform from an `Executor`.
   */
  final def fromExecutor(executor0: Executor): Platform = {
    val blockingExecutor = executor0

    val executor = executor0

    val fatal = (_: Throwable) => false

    val logger: ZLogger[Unit] =
      (
        trace: ZTraceElement,
        fiberId: Fiber.Id,
        level: LogLevel,
        message: () => String,
        context: Map[FiberRef.Runtime[_], AnyRef],
        spans: List[LogSpan]
      ) => {
        try {
          // TODO: Improve output & use console.group for spans, etc.
          val line = ZLogger.defaultFormatter(trace, fiberId, level, message, context, spans)

          if (level == LogLevel.Fatal) jsglobal.console.error(line)
          else if (level == LogLevel.Error) jsglobal.console.error(line)
          else if (level == LogLevel.Debug) jsglobal.console.debug(line)
          else jsglobal.console.log(line)

          ()
        } catch {
          case t if !fatal(t) => ()
        }
      }

    val reportFatal = (t: Throwable) => {
      t.printStackTrace()
      throw t
    }

    val reportFailure = (cause: Cause[Any]) => if (cause.isDie) println(cause.prettyPrint)

    val tracing = Tracing(Tracer.Empty, TracingConfig.disabled)

    val supervisor = Supervisor.none

    val enableCurrentFiber = false

    Platform(
      blockingExecutor,
      executor,
      tracing,
      fatal,
      reportFatal,
      reportFailure,
      supervisor,
      enableCurrentFiber,
      logger
    )
  }

  /**
   * Creates a Platform from an execution context.
   */
  final def fromExecutionContext(ec: ExecutionContext, yieldOpCount: Int = 2048): Platform =
    fromExecutor(Executor.fromExecutionContext(yieldOpCount)(ec))

  /**
   * Returns whether the current platform is ScalaJS.
   */
  val isJS = true

  /**
   * Returns whether the currently platform is the JVM.
   */
  val isJVM = false

  /**
   * Returns whether the currently platform is Scala Native.
   */
  val isNative = false

  /**
   * Makes a new default platform. This is a side-effecting method.
   */
  final def makeDefault(yieldOpCount: Int = defaultYieldOpCount): Platform =
    fromExecutor(Executor.fromExecutionContext(yieldOpCount)(ExecutionContext.global))

  final def newWeakSet[A](): JSet[A] = new HashSet[A]()

  final def newConcurrentSet[A](): JSet[A] = new HashSet[A]()

  final def newConcurrentWeakSet[A](): JSet[A] = new HashSet[A]()

  final def newWeakHashMap[A, B](): JMap[A, B] = new HashMap[A, B]()

  final def newWeakReference[A](value: A): () => A = { () => value }
}
