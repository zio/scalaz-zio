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

import zio.{Duration, Trace}
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.time.Instant

sealed case class Summary(
  success: Int,
  fail: Int,
  ignore: Int,
  failureDetails: String,
  duration: Duration = Duration.Zero
) { self =>
  import Summary.Interval

  final def add(executionEvent: ExecutionEvent)(implicit trace: Trace): Summary =
    SummaryBuilder.buildSummary(executionEvent, self)

  final def add(that: Summary): Summary =
    Summary(
      self.success + that.success,
      self.fail + that.fail,
      self.ignore + that.ignore,
      self.failureDetails +
        (if (that.failureDetails.trim.isEmpty)
           ""
         else
           "\n" + that.failureDetails),
      self.interval <> that.interval
    )

  def interval: Interval =
    Interval.empty

  final def status: Summary.Status =
    if (failureDetails.trim.isEmpty)
      Summary.Success
    else
      Summary.Failure

  final def timed(start: Instant, end: Instant): Summary =
    Summary(
      self.success,
      self.fail,
      self.ignore,
      self.failureDetails,
      Interval.finite(start, end)
    )

  def total: Int =
    success + fail + ignore
}

object Summary {

  def apply(
    success: Int,
    fail: Int,
    ignore: Int,
    failureDetails: String,
    interval0: Interval
  ): Summary =
    new Summary(success, fail, ignore, failureDetails, interval0.duration) {
      override def interval: Interval = interval0
    }

  val empty: Summary =
    Summary(0, 0, 0, "")

  sealed trait Interval { self =>

    final def <>(that: Interval): Interval =
      (self, that) match {
        case (self, Interval.Empty) => self
        case (Interval.Empty, that) => that
        case (Interval.Finite(start1, end1), Interval.Finite(start2, end2)) =>
          val start = if (start1.isBefore(start2)) start1 else start2
          val end   = if (end1.isAfter(end2)) end1 else end2
          Interval.Finite(start, end)
      }

    final def duration: Duration =
      self match {
        case Interval.Empty              => Duration.Zero
        case Interval.Finite(start, end) => Duration.fromInterval(start, end)
      }
  }

  object Interval {

    val empty: Interval =
      Empty

    def finite(start: Instant, end: Instant): Interval =
      Finite(start, end)

    private case object Empty                                     extends Interval
    private final case class Finite(start: Instant, end: Instant) extends Interval
  }

  sealed trait Status
  object Success extends Status
  object Failure extends Status
}
