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

package zio.test.render

import zio.internal.ansi.AnsiStringOps
import zio.internal.macros.StringUtils.StringOps
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test._
import zio.test.render.ExecutionResult.Status.{Failed, Ignored, Passed}
import zio.test.render.ExecutionResult.{ResultType, Status}
import zio.test.render.LogLine.{Fragment, Line, Message}
import zio.{Cause, _}

trait TestRenderer {
  final def render(reporterEvent: ExecutionEvent, includeCause: Boolean)(implicit trace: Trace): Seq[String] =
    renderOutput(renderEvent(reporterEvent, includeCause))

  def renderEvent(event: ExecutionEvent, includeCause: Boolean)(implicit trace: Trace): Seq[ExecutionResult]

  def renderSummary(summary: Summary): String
  protected def renderOutput(results: Seq[ExecutionResult])(implicit trace: Trace): Seq[String]

  def testCaseOutput(
    labels: List[String],
    results: Either[TestFailure[Any], TestSuccess],
    includeCause: Boolean,
    annotations: TestAnnotationMap
  )(implicit
    trace: Trace
  ): (List[Line], List[Line]) = {
    val depth = labels.length - 1
    val label = labels.last

    val renderedResult = results match {
      case Right(TestSuccess.Succeeded(_)) =>
        Some(
          rendered(
            ResultType.Test,
            label,
            Passed,
            depth,
            fr(labels.last) + renderAnnotationsFrag(List(annotations), TestAnnotationRenderer.default)
          )
        )
      case Right(TestSuccess.Ignored(_)) =>
        Some(
          rendered(
            ResultType.Test,
            label,
            Ignored,
            depth,
            warn(label).toLine + renderAnnotationsFrag(List(annotations), TestAnnotationRenderer.default)
          )
        )
      case Left(TestFailure.Assertion(result, _)) =>
        val flatLabel = labels.map(_.red).mkString(" / ".red.faint)
        result.failures.map { result =>
          renderedWithSummary(
            ResultType.Test,
            label,
            Failed,
            depth,
            renderFailure(label, depth, result, annotations).lines.toList,
            renderFailure(flatLabel, depth, result, annotations).lines.toList // Fully-qualified label
          )
        }

      case Left(TestFailure.Runtime(cause, _)) =>
        Some(
          renderRuntimeCause(
            cause,
            labels,
            depth,
            includeCause
          )
        )
    }
    (renderedResult.map(r => r.streamingLines).getOrElse(Nil), renderedResult.map(r => r.summaryLines).getOrElse(Nil))
  }

  def renderAssertFailure(
    result: TestResult,
    labels: List[String],
    depth: Int,
    annotations: TestAnnotationMap
  ): ExecutionResult = {
    val streamingLabel           = labels.lastOption.getOrElse("Top-level defect prevented test execution")
    val summaryLabel             = labels.mkString(" - ")
    val streamingRenderedFailure = renderFailure(streamingLabel, depth, result.result, annotations).lines.toList
    val summaryRenderedFailure   = renderFailure(summaryLabel, depth, result.result, annotations).lines.toList
    renderedWithSummary(
      ResultType.Test,
      streamingLabel,
      Failed,
      depth,
      streamingRenderedFailure,
      summaryRenderedFailure
    )
  }

  def renderRuntimeCause[E](cause: Cause[E], labels: List[String], depth: Int, includeCause: Boolean)(implicit
    trace: Trace
  ): ExecutionResult = {
    val streamingLabel = labels.lastOption.getOrElse("Top-level defect prevented test execution")
    val summaryLabel   = labels.mkString(" - ")

    val failureDetails =
      Seq(renderFailureLabel(streamingLabel, depth)) ++ Seq(renderCause(cause, depth))
        .filter(_ => includeCause)
        .flatMap(_.lines)

    val summaryFailureDetails =
      Seq(renderFailureLabel(summaryLabel, depth)) ++ Seq(renderCause(cause, depth))
        .filter(_ => includeCause)
        .flatMap(_.lines)

    renderedWithSummary(
      ResultType.Test,
      streamingLabel,
      Failed,
      depth,
      failureDetails.toList,
      summaryFailureDetails.toList
    )
  }

  def renderAssertionResult(assertionResult: TestTrace[Boolean], offset: Int): Message =
    try {
      val failures = FailureCase.fromTrace(assertionResult, Chunk.empty)
      failures
        .map(fc =>
          renderGenFailureDetails(assertionResult.getGenFailureDetails, offset) ++
            Message(renderFailureCase(fc, offset, None))
        )
        .foldLeft(Message.empty)(_ ++ _)
    } catch {
      case e: VirtualMachineError => throw e
      case e: Throwable           => renderCause(Cause.die(e), offset)(Trace.empty)
    }

  def renderFailureCase(failureCase: FailureCase, offset: Int, testLabel: Option[String]): Chunk[Line] =
    failureCase match {
      case FailureCase(errorMessage, codeString, location, path, _, nested, _, customLabel) =>
        val errorMessageLines =
          Chunk.fromIterable(errorMessage.lines) match {
            case head +: tail =>
              (error("✗ ") +: head) +: tail.map(error("  ") +: _)
            case _ => Chunk.empty
          }

        val labelLines = Chunk.fromIterable(customLabel.map(label => Line.fromString(label.bold.yellow)))

        val result =
          errorMessageLines ++ labelLines ++
            Chunk(Line.fromString(testLabel.fold(codeString)(l => s"""$codeString ?? "$l""""))) ++
            nested.flatMap(renderFailureCase(_, offset, None)).map(_.withOffset(1)) ++
            Chunk.fromIterable(
              path.filterNot(t => t._1.unstyled == t._2.unstyled).flatMap { case (label, value) =>
                Chunk.fromIterable(value.split("\n").map(Fragment(_).toLine)) match {
                  case head +: lines => (dim(s"${label.trim} = ") +: head) +: lines
                  case _             => Vector.empty
                }
              }
            ) ++
            Chunk(detail(s"at $location ").toLine)

        result.map(_.withOffset(offset + 1))
    }

  def renderCause(cause: Cause[Any], offset: Int)(implicit trace: Trace): Message = {
    val defects = cause.defects
    val timeouts = defects.collect { case TestTimeoutException(message) =>
      Message(message)
    }
    val remaining =
      cause.stripSomeDefects { case TestTimeoutException(_) =>
        true
      }
    val prefix = timeouts.foldLeft(Message.empty)(_ ++ _)

    remaining match {
      case Some(remainingCause) =>
        prefix ++ Message(
          remainingCause.prettyPrint
            .split("\n")
            .map(s => withOffset(offset + 1)(Line.fromString(s)))
            .toVector
        )
      case None =>
        prefix
    }
  }

  private def renderFailure(
    label: String,
    offset: Int,
    details: TestTrace[Boolean],
    annotations: TestAnnotationMap
  ): Message =
    withOffset(offset)(
      renderFailureLabel(label, offset) + renderAnnotationsFrag(List(annotations), TestAnnotationRenderer.default)
    ) +: renderAssertionResult(details, offset) :+ Line.empty

  private def renderAnnotationsFrag(
    annotations: List[TestAnnotationMap],
    annotationRenderer: TestAnnotationRenderer
  ): Fragment =
    annotations match {
      case annotations :: ancestors =>
        val rendered = annotationRenderer.run(ancestors, annotations)
        if (rendered.isEmpty)
          Fragment("")
        else
          Fragment(rendered.mkString(" - ", ", ", ""))
      case Nil =>
        Fragment("")
    }

  def renderFailureLabel(label: String, offset: Int): Line =
    withOffset(offset)(error("- " + label).toLine)

  private def renderGenFailureDetails(failureDetails: Option[GenFailureDetails], offset: Int): Message =
    failureDetails match {
      case Some(details) =>
        val shrunken = PrettyPrint(details.shrunkenInput)
        val initial  = PrettyPrint(details.initialInput)
        val renderShrunken = withOffset(offset + 1)(
          Fragment(
            s"Test failed after ${details.iterations + 1} iteration${if (details.iterations > 0) "s" else ""} with input: "
          ) +
            error(shrunken)
        )
        if (initial == shrunken) renderShrunken.toMessage
        else
          renderShrunken + withOffset(offset + 1)(
            Fragment(s"Original input before shrinking was: ") + error(initial)
          )
      case None => Message.empty
    }

  def rendered(
    caseType: ResultType,
    label: String,
    result: Status,
    offset: Int,
    lines: Line*
  ): ExecutionResult =
    ExecutionResult(caseType, label, result, offset, Nil, lines.toList, lines.toList, None)

  def renderedWithSummary(
    caseType: ResultType,
    label: String,
    result: Status,
    offset: Int,
    lines: List[Line],
    summaryLines: List[Line]
  ): ExecutionResult =
    ExecutionResult(caseType, label, result, offset, Nil, lines, summaryLines, None)
}
