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

import zio.internal.{Blocking, IsFatal}
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] trait RuntimePlatformSpecific {

  final val defaultExecutor: Executor =
    Executor.makeDefault(autoBlocking = false)

  final val defaultBlockingExecutor: Executor =
    Blocking.blockingExecutor

  final val defaultFatal: IsFatal =
    IsFatal.empty

  final val defaultLoggers: Set[ZLogger[String, Any]] =
    Set(ZLogger.default.map(println(_)).filterLogLevel(_ >= LogLevel.Info))

  final val defaultReportFatal: Throwable => Nothing =
    (t: Throwable) => {
      t.printStackTrace()
      throw t
    }

  final val defaultSupervisor: Supervisor[Any] =
    Supervisor.none

  def enableAutoBlockingExecutor(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.suspend {
      Runtime.setExecutor(Executor.makeDefault(autoBlocking = true))
    }
}
