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

import zio.internal.LoomSupport.LoomNotAvailableException
import zio.internal.{Blocking, IsFatal, LoomSupport}
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] trait RuntimePlatformSpecific {

  final val defaultExecutor: Executor =
    Executor.makeDefault(autoBlocking = false)

  final val defaultBlockingExecutor: Executor =
    Blocking.blockingExecutor

  final val defaultFatal: IsFatal =
    IsFatal(classOf[VirtualMachineError])

  final val defaultLoggers: Set[ZLogger[String, Any]] =
    Set(ZLogger.default.map(println(_)).filterLogLevel(_ >= LogLevel.Info))

  final val defaultReportFatal: Throwable => Nothing =
    (t: Throwable) => {
      t.printStackTrace()
      try {
        java.lang.System.exit(-1)
        throw t
      } catch { case _: Throwable => throw t }
    }

  final val defaultSupervisor: Supervisor[Any] =
    Supervisor.none

  def enableLoomBasedExecutor(implicit trace: Trace): ZLayer[Any, LoomNotAvailableException, Unit] =
    ZLayer.suspend {
      sharedLoomExecutor.fold(
        ZLayer.fail(_),
        Runtime.setExecutor
      )
    }

  def enableLoomBasedBlockingExecutor(implicit trace: Trace): ZLayer[Any, LoomNotAvailableException, Unit] =
    ZLayer.suspend {
      sharedLoomExecutor.fold(
        ZLayer.fail(_),
        Runtime.setBlockingExecutor
      )
    }

  // a single Loom executor instance that can be shared between blocking and non-blocking fibers
  private lazy val sharedLoomExecutor: Either[LoomNotAvailableException, Executor] =
    LoomSupport.newVirtualThreadPerTaskExecutor() match {
      case None           => Left(LoomNotAvailableException("Loom API not available", null))
      case Some(executor) => Right(Executor.fromJavaExecutor(executor))
    }

  def enableAutoBlockingExecutor(implicit trace: Trace): ZLayer[Any, Nothing, Unit] =
    ZLayer.suspend {
      Runtime.setExecutor(Executor.makeDefault(autoBlocking = true))
    }
}
