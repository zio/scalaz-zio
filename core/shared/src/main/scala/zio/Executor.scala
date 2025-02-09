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

import zio.internal.{DefaultExecutors, ExecutionMetrics}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent._
import scala.concurrent.ExecutionContext

/**
 * An executor is responsible for executing actions. Each action is guaranteed
 * to begin execution on a fresh stack frame.
 */
abstract class Executor extends ExecutorPlatformSpecific { self =>

  /**
   * Current sampled execution metrics, if available.
   */
  def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics]

  /**
   * Submits an effect for execution.
   */
  def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean

  /**
   * Views this `Executor` as a Scala `ExecutionContext`.
   */
  lazy val asExecutionContext: ExecutionContext =
    new ExecutionContext {
      override def execute(r: Runnable): Unit =
        if (!submit(r)(Unsafe.unsafe)) throw new RejectedExecutionException("Rejected: " + r.toString)

      override def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace
    }

  /**
   * Views this `Executor` as a Java `Executor`.
   */
  lazy val asJava: java.util.concurrent.Executor =
    command =>
      if (submit(command)(Unsafe.unsafe)) ()
      else throw new java.util.concurrent.RejectedExecutionException

  /**
   * Submits an effect for execution and signals that the current fiber is ready
   * to yield.
   *
   * '''NOTE''': The implementation of this method in the ZScheduler will
   * attempt to run the runnable on the current thread if the current worker's
   * queues are empty. This leads to improved performance as we avoid
   * unnecessary parking/un-parking of threads.
   */
  def submitAndYield(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
    submit(runnable)

  /**
   * Submits an effect for execution and signals that the current fiber is ready
   * to yield or throws.
   *
   * @see
   *   [[submitAndYield]] for an explanation of the implementation in
   *   ZScheduler.
   */
  final def submitAndYieldOrThrow(runnable: Runnable)(implicit unsafe: Unsafe): Unit =
    if (!submitAndYield(runnable)) throw new RejectedExecutionException(s"Unable to run ${runnable.toString()}")

  /**
   * Submits an effect for execution or throws.
   */
  final def submitOrThrow(runnable: Runnable)(implicit unsafe: Unsafe): Unit =
    if (!submit(runnable)) throw new RejectedExecutionException(s"Unable to run ${runnable.toString()}")

  private[zio] def stealWork(depth: Int): Boolean =
    false
}

object Executor extends DefaultExecutors with Serializable {
  def fromJavaExecutor(executor: java.util.concurrent.Executor): Executor =
    new Executor {
      override def metrics(implicit unsafe: Unsafe): Option[ExecutionMetrics] = None

      override def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
        try {
          executor.execute(runnable)

          true
        } catch {
          case t: RejectedExecutionException => false
        }
    }

  /**
   * Creates an `Executor` from a Scala `ExecutionContext`.
   */
  def fromExecutionContext(ec: ExecutionContext): Executor =
    new Executor {

      def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean =
        try {
          ec.execute(runnable)

          true
        } catch {
          case _: RejectedExecutionException => false
        }

      def metrics(implicit unsafe: Unsafe) = None
    }
}
