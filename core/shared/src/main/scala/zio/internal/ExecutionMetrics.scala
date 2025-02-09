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

abstract class ExecutionMetrics {

  /**
   * The concurrency level of the executor.
   */
  def concurrency: Int

  /**
   * The capacity of the executor.
   */
  def capacity: Int

  /**
   * The number of tasks remaining to be executed.
   */
  def size: Int

  /**
   * The number of tasks that have been enqueued, over all time.
   */
  def enqueuedCount: Long

  /**
   * The number of tasks that have been dequeued, over all time.
   */
  def dequeuedCount: Long

  /**
   * The number of current live worker threads.
   */
  def workersCount: Int
}
