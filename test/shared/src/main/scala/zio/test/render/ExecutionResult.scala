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

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.{SuiteId, TestAnnotationMap}
import zio.test.render.ExecutionResult.Status._
import zio.test.render.ExecutionResult.{ResultType, Status}
import zio.test.render.LogLine.Line

case class ExecutionResult(
  resultType: ResultType,
  label: String,
  status: Status,
  offset: Int,
  annotations: List[TestAnnotationMap],
  streamingLines: List[Line],
  summaryLines: List[Line],
  duration: Option[Long]
) {
  self =>

  def &&(that: ExecutionResult): ExecutionResult =
    (self.status, that.status) match {
      case (Started, _)     => that
      case (Ignored, _)     => that
      case (_, Ignored)     => self
      case (Failed, Failed) => self.copy(streamingLines = self.streamingLines ++ that.streamingLines.tail)
      case (Passed, _)      => that
      case (_, Passed)      => self
    }

  def ||(that: ExecutionResult): ExecutionResult =
    (self.status, that.status) match {
      case (Started, _)     => that
      case (Ignored, _)     => that
      case (_, Ignored)     => self
      case (Failed, Failed) => self.copy(streamingLines = self.streamingLines ++ that.streamingLines.tail)
      case (Passed, _)      => self
      case (_, Passed)      => that
    }

  def unary_! : ExecutionResult =
    self.status match {
      case Started => self
      case Ignored => self
      case Failed  => self.copy(status = Passed)
      case Passed  => self.copy(status = Failed)
    }

  def withAnnotations(annotations: List[TestAnnotationMap]): ExecutionResult =
    self.copy(annotations = annotations)
}
object ExecutionResult {
  def withoutSummarySpecificOutput(
    resultType: ResultType,
    label: String,
    status: Status,
    offset: Int,
    annotations: List[TestAnnotationMap],
    lines: List[Line],
    duration: Option[Long]
  ): ExecutionResult =
    ExecutionResult(
      resultType,
      label,
      status,
      offset,
      annotations,
      lines,
      lines, // Re-uses lines when we don't have summary-specific output,
      duration
    )

  sealed abstract class Status
  object Status {
    case object Started extends Status
    case object Failed  extends Status
    case object Passed  extends Status
    case object Ignored extends Status
  }

  sealed abstract class ResultType
  object ResultType {
    case object Test  extends ResultType
    case object Suite extends ResultType
    case object Other extends ResultType
  }
}
