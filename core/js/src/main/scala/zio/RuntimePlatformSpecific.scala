/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import zio.internal.IsFatal
import zio.stacktracer.TracingImplicits.disableAutoTrace

import scala.scalajs.js.Dynamic.{global => jsglobal}

private[zio] trait RuntimePlatformSpecific {

  final val defaultExecutor: Executor =
    Executor.fromExecutionContext(MacrotaskExecutor)

  final val defaultBlockingExecutor: Executor =
    defaultExecutor

  final val defaultFatal: IsFatal =
    IsFatal.empty

  final val defaultReportFatal: Throwable => Nothing =
    (t: Throwable) => {
      t.printStackTrace()
      throw t
    }

  final val defaultLoggers: Set[ZLogger[String, Any]] = {
    val logger: ZLogger[String, Unit] =
      (
        trace: Trace,
        fiberId: FiberId,
        level: LogLevel,
        message: () => String,
        cause: Cause[Any],
        context: FiberRefs,
        spans: List[LogSpan],
        annotations: Map[String, String]
      ) => {
        try {
          // TODO: Improve output & use console.group for spans, etc.
          val line = ZLogger.default(trace, fiberId, level, message, cause, context, spans, annotations)

          if (level == LogLevel.Fatal) jsglobal.console.error(line)
          else if (level == LogLevel.Error) jsglobal.console.error(line)
          else if (level == LogLevel.Debug) jsglobal.console.debug(line)
          else jsglobal.console.log(line)

          ()
        } catch {
          case t => ()
        }
      }

    Set(logger.filterLogLevel(_ >= LogLevel.Info))
  }

  final val defaultSupervisor: Supervisor[Any] =
    Supervisor.none
}
