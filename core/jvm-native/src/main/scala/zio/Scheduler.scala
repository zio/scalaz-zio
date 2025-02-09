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

import zio.Scheduler.CancelToken
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

abstract class Scheduler {
  def asScheduledExecutorService: ScheduledExecutorService
  def schedule(task: Runnable, duration: Duration)(implicit unsafe: Unsafe): CancelToken
}

object Scheduler {
  type CancelToken = () => Boolean

  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler =
    new Scheduler {
      val ConstFalse = () => false

      def asScheduledExecutorService: ScheduledExecutorService =
        service

      def schedule(task: Runnable, duration: Duration)(implicit unsafe: Unsafe): CancelToken =
        (duration: @unchecked) match {
          case Duration.Infinity => ConstFalse
          case d if d.isZero || d.isNegative =>
            task.run()
            ConstFalse
          case d =>
            val future = service.schedule(
              task,
              d.toNanos,
              TimeUnit.NANOSECONDS
            )

            () => future.cancel(true)
        }
    }
}
