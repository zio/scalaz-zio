/*
 * Copyright 2023-2024 John A. De Goes and the ZIO Contributors
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

import java.util.concurrent.{Executor, Executors}
import java.lang.reflect.{InvocationTargetException, Method}

object LoomSupport {
  def newVirtualThreadPerTaskExecutor(): Option[Executor] =
    if (!Platform.hasGreenThreads) return None
    else {
      try {
        val newExecutor = classOf[Executors].getMethod("newVirtualThreadPerTaskExecutor")

        Some(newExecutor.invoke(null).asInstanceOf[Executor])
      } catch {
        case e: NoSuchMethodException => throw LoomNotAvailableException("Loom API not available", e)
      }
    }

  def createVirtualThread(runnable: Runnable): Boolean =
    if (Platform.hasGreenThreads) {
      try {
        val startVirtualThread: Method = classOf[Thread].getMethod("startVirtualThread", classOf[Runnable])

        startVirtualThread.invoke(null, runnable.asInstanceOf[AnyRef])

        true
      } catch {
        case e: NoSuchMethodException => throw LoomNotAvailableException("Loom API not available", e)
      }
    } else false

  final case class LoomNotAvailableException(message: String, cause: Throwable) extends RuntimeException(message, cause)
}
