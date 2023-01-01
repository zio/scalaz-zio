/*
 * Copyright 2019-2023 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio.Clock.ClockLive
import zio._
import zio.test.ReporterEventRenderer.ConsoleEventRenderer

import java.util.concurrent.TimeUnit

/**
 * A `TestRunner[R, E]` encapsulates all the logic necessary to run specs that
 * require an environment `R` and may fail with an error `E`. Test runners
 * require a test executor, a runtime configuration, and a reporter.
 */
final case class TestRunner[R, E](
  executor: TestExecutor[R, E],
  bootstrap: ULayer[TestOutput with ExecutionEventSink] = TestRunner.defaultBootstrap
) { self =>

  val runtime: Runtime[Any] = Runtime.default

  /**
   * Runs the spec, producing the execution results.
   */
  def run(spec: Spec[R, E], defExec: ExecutionStrategy = ExecutionStrategy.ParallelN(4))(implicit
    trace: Trace
  ): UIO[Summary] =
    for {
      start    <- ClockLive.currentTime(TimeUnit.MILLISECONDS)
      summary  <- executor.run(spec, defExec)
      finished <- ClockLive.currentTime(TimeUnit.MILLISECONDS)
      duration  = Duration.fromMillis(finished - start)
    } yield summary.copy(duration = duration)

  trait UnsafeAPI {
    def run(spec: Spec[R, E])(implicit trace: Trace, unsafe: Unsafe): Unit
    def runAsync(spec: Spec[R, E])(k: => Unit)(implicit trace: Trace, unsafe: Unsafe): Unit
    def runSync(spec: Spec[R, E])(implicit trace: Trace, unsafe: Unsafe): Exit[Nothing, Unit]
  }

  val unsafe: UnsafeAPI =
    new UnsafeAPI {

      /**
       * An unsafe, synchronous run of the specified spec.
       */
      def run(spec: Spec[R, E])(implicit trace: Trace, unsafe: Unsafe): Unit =
        runtime.unsafe.run(self.run(spec).provideLayer(bootstrap)).getOrThrowFiberFailure()

      /**
       * An unsafe, asynchronous run of the specified spec.
       */
      def runAsync(spec: Spec[R, E])(k: => Unit)(implicit trace: Trace, unsafe: Unsafe): Unit = {
        val fiber = runtime.unsafe.fork(self.run(spec).provideLayer(bootstrap))
        fiber.unsafe.addObserver {
          case Exit.Success(_) => k
          case Exit.Failure(c) => throw FiberFailure(c)
        }
      }

      /**
       * An unsafe, synchronous run of the specified spec.
       */
      def runSync(spec: Spec[R, E])(implicit trace: Trace, unsafe: Unsafe): Exit[Nothing, Unit] =
        runtime.unsafe.run(self.run(spec).unit.provideLayer(bootstrap))
    }

  private[test] def buildRuntime(implicit
    trace: Trace
  ): ZIO[Scope, Nothing, Runtime[TestOutput with ExecutionEventSink]] =
    bootstrap.toRuntime
}

object TestRunner {
  lazy val defaultBootstrap = {
    implicit val emptyTracer = Trace.empty

    ZLayer.make[TestOutput with ExecutionEventSink](
      ExecutionEventPrinter.live(ConsoleEventRenderer),
      TestLogger.fromConsole(Console.ConsoleLive),
      TestOutput.live,
      ExecutionEventSink.live
    )
  }
}
