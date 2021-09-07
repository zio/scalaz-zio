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

package zio

import zio.internal.Scheduler
import zio.{DurationSyntax => _}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.scalanative.loop._

private[zio] trait ClockPlatformSpecific {
  private[zio] val globalScheduler = new Scheduler {
    import Scheduler.CancelToken

    private[this] val ConstFalse = () => false

    override def schedule(task: Runnable, duration: Duration): CancelToken = (duration: @unchecked) match {
      case zio.Duration.Infinity => ConstFalse
      case zio.Duration.Finite(nanos) =>
        var completed = false

        val handle = Timer.timeout(FiniteDuration(nanos, TimeUnit.NANOSECONDS)) { () =>
          completed = true

          task.run()
        }
        () => {
          handle.clear()
          !completed
        }
    }
  }
}
