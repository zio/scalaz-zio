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

import zio.Trace
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.ExecutionEvent.{RuntimeFailure, SectionEnd, SectionStart, Test, TestStarted, TopLevelFlush}
import zio.test.render.ConsoleRenderer

object SummaryBuilder {

  def buildSummary(reporterEvent: ExecutionEvent, oldSummary: Summary)(implicit trace: Trace): Summary = {
    val success = countTestResults(reporterEvent) {
      case Right(TestSuccess.Succeeded(_)) => true
      case _                               => false
    }
    val fail = countTestResults(reporterEvent) {
      case Right(_) => false
      case _        => true
    }
    val ignore = countTestResults(reporterEvent) {
      case Right(TestSuccess.Ignored(_)) => true
      case _                             => false
    }
    val failures = extractFailures(reporterEvent)

    val rendered: String =
      ConsoleRenderer
        .renderForSummary(failures.flatMap(ConsoleRenderer.renderEvent(_, true)), TestAnnotationRenderer.silent)
        .mkString("\n")

    val newSummary = Summary(success, fail, ignore, rendered)
    oldSummary.add(newSummary)
  }

  private def countTestResults(
    executedSpec: ExecutionEvent
  )(pred: Either[TestFailure[_], TestSuccess] => Boolean): Int =
    executedSpec match {
      case Test(_, test, _, _, _, _, _) =>
        if (pred(test)) 1 else 0
      case RuntimeFailure(_, _, _, _) =>
        0

      case SectionStart(_, _, _) => 0
      case SectionEnd(_, _, _)   => 0
      case TopLevelFlush(_)      => 0
      case TestStarted(_, _, _, _, _) =>
        0
    }

  private def extractFailures(reporterEvent: ExecutionEvent): Seq[ExecutionEvent] =
    reporterEvent match {
      case Test(_, test, _, _, _, _, _) =>
        test match {
          case Left(_) =>
            Seq(reporterEvent)
          case _ =>
            Seq.empty
        }
      case RuntimeFailure(_, _, _, _) =>
        Seq(reporterEvent)
      case _ =>
        Seq.empty
    }
}
