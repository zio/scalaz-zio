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

package zio.internal

import java.util.concurrent._
import scala.concurrent.ExecutionContext

/**
 * An executor is responsible for executing actions. Each action is guaranteed
 * to begin execution on a fresh stack frame.
 */
abstract class Executor extends ExecutorPlatformSpecific { self =>

  /**
   * The number of operations a fiber should run before yielding.
   */
  def yieldOpCount: Int

  /**
   * Current sampled execution metrics, if available.
   */
  def metrics: Option[ExecutionMetrics]

  /**
   * Submits an effect for execution.
   */
  def submit(runnable: Runnable): Boolean

  /**
   * Submits an effect for execution or throws.
   */
  final def submitOrThrow(runnable: Runnable): Unit =
    if (!submit(runnable)) throw new RejectedExecutionException(s"Unable to run ${runnable.toString()}")

  /**
   * Views this `Executor` as a Scala `ExecutionContext`.
   */
  @deprecated("use asExecutionContext", "2.0.0")
  lazy val asEC: ExecutionContext =
    asExecutionContext

  /**
   * Views this `Executor` as a Scala `ExecutionContext`.
   */
  lazy val asExecutionContext: ExecutionContext =
    new ExecutionContext {
      override def execute(r: Runnable): Unit =
        if (!submit(r)) throw new RejectedExecutionException("Rejected: " + r.toString)

      override def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace
    }

  /**
   * Views this `Executor` as a Java `Executor`.
   */
  lazy val asJava: java.util.concurrent.Executor =
    command =>
      if (submit(command)) ()
      else throw new java.util.concurrent.RejectedExecutionException

}

object Executor extends DefaultExecutors with Serializable {

  /**
   * Creates an `Executor` from a Scala `ExecutionContext`.
   */
  def fromExecutionContext(yieldOpCount0: Int)(
    ec: ExecutionContext
  ): Executor =
    new Executor {
      def yieldOpCount = yieldOpCount0

      def submit(runnable: Runnable): Boolean =
        try {
          ec.execute(runnable)

          true
        } catch {
          case _: RejectedExecutionException => false
        }

      def metrics = None
    }
}
