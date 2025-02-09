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
  def schedule(task: Runnable, duration: Duration)(implicit unsafe: Unsafe): CancelToken
}

object Scheduler {
  type CancelToken = () => Boolean

  def fromScheduledExecutorService(service: ScheduledExecutorService): Scheduler =
    new Scheduler {
      val ConstFalse = () => false

      override def schedule(task: Runnable, duration: Duration)(implicit unsafe: Unsafe): CancelToken =
        (duration: @unchecked) match {
          case Duration.Infinity => ConstFalse
          case Duration.Zero =>
            task.run()

            ConstFalse
          case Duration.Finite(_) =>
            val future = service.schedule(
              new Runnable {
                def run: Unit =
                  task.run()
              },
              duration.toNanos,
              TimeUnit.NANOSECONDS
            )

            () => future.cancel(true)
        }
    }
}
