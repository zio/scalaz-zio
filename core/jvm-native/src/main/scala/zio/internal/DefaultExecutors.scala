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

package zio.internal

import zio.Unsafe
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.{RejectedExecutionException, ThreadPoolExecutor}

private[zio] abstract class DefaultExecutors {
  final def makeDefault(): zio.Executor =
    makeDefault(false)

  final def makeDefault(autoBlocking: Boolean): zio.Executor =
    new ZScheduler(autoBlocking)

  final def fromThreadPoolExecutor(
    es: ThreadPoolExecutor
  ): zio.Executor =
    new zio.Executor {
      private[this] def metrics0 = new ExecutionMetrics {
        def concurrency: Int = es.getMaximumPoolSize()

        def capacity: Int = {
          val queue = es.getQueue()

          val remaining = queue.remainingCapacity()

          if (remaining == Int.MaxValue) remaining
          else remaining + queue.size
        }

        def size: Int = es.getQueue().size

        def workersCount: Int = es.getPoolSize()

        def enqueuedCount: Long = es.getTaskCount()

        def dequeuedCount: Long = enqueuedCount - size.toLong
      }

      def metrics(implicit unsafe: Unsafe) = Some(metrics0)

      def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
        try {
          es.execute(runnable)

          true
        } catch {
          case _: RejectedExecutionException => false
        }
    }
}
