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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}

object Blocking {

  val blockingExecutor: zio.Executor =
    zio.Executor.fromThreadPoolExecutor {
      val corePoolSize  = 0
      val maxPoolSize   = Int.MaxValue
      val keepAliveTime = 60000L
      val timeUnit      = TimeUnit.MILLISECONDS
      val workQueue     = new SynchronousQueue[Runnable]()
      val threadFactory = new NamedThreadFactory("zio-default-blocking", true)

      val threadPool = new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveTime,
        timeUnit,
        workQueue,
        threadFactory
      )

      threadPool
    }

  /**
   * Signals to the scheduler that the current thread is about to block. This
   * method is a no-op except when the current thread is a ZScheduler.Worker. In
   * that case, the worker is marked as "blocking" and a new worker is spawned
   * to replace it.
   */
  private[zio] final def signalBlocking(): Unit =
    ZScheduler.markCurrentWorkerAsBlocking()
}
