/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

trait TimeoutVariants {

  /**
   * A test aspect that prints a warning to the console when a test takes longer
   * than the specified duration.
   */
  def timeoutWarning(
    duration: Duration
  ): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](
        spec: Spec[R, E]
      )(implicit trace: Trace): Spec[R, E] = {
        def loop(labels: List[String], spec: Spec[R, E]): Spec[R, E] =
          spec.caseValue match {
            case Spec.ExecCase(exec, spec)     => Spec.exec(exec, loop(labels, spec))
            case Spec.LabeledCase(label, spec) => Spec.labeled(label, loop(label :: labels, spec))
            case Spec.ScopedCase(scoped)       => Spec.scoped[R](scoped.map(loop(labels, _)))
            case Spec.MultipleCase(specs) =>
              Spec.multiple(specs.map(loop(labels, _)))
            case Spec.TestCase(test, annotations) =>
              Spec.test(warn(labels, test, duration), annotations)
          }

        loop(Nil, spec)
      }
    }

  private def warn[R, E](
    labels: List[String],
    test: ZTest[R, E],
    duration: Duration
  )(implicit trace: Trace): ZTest[R, E] =
    test.raceWith(Live.withLive(showWarning(labels, duration))(_.delay(duration)))(
      (result, fiber) => fiber.interrupt *> result,
      (_, fiber) => fiber.join
    )

  private def showWarning(
    labels: List[String],
    duration: Duration
  )(implicit trace: Trace): UIO[Unit] =
    ZIO.logWarning(renderWarning(labels, duration))

  private def renderWarning(labels: List[String], duration: Duration): String =
    "Test " + labels.reverse.mkString(" - ") + " has taken more than " + duration.render +
      " to execute. If this is not expected, consider using TestAspect.timeout to timeout runaway tests for faster diagnostics."
}
