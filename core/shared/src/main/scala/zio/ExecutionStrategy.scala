/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * Describes a strategy for evaluating multiple effects, potentially in
 * parallel. There are three possible execution strategies: `Sequential`,
 * `Parallel`, and `ParallelN`.
 */
sealed abstract class ExecutionStrategy

object ExecutionStrategy {

  /**
   * Execute effects sequentially.
   */
  case object Sequential extends ExecutionStrategy

  /**
   * Execute effects in parallel.
   */
  case object Parallel extends ExecutionStrategy

  /**
   * Execute effects in parallel, up to the specified number of concurrent
   * fibers.
   */
  final case class ParallelN(n: Int) extends ExecutionStrategy
}
