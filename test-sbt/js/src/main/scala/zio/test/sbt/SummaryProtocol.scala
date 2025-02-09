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

package zio.test.sbt

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.Summary
import zio.test.render.ConsoleRenderer

/**
 * The ScalaJS SBT Runner uses strings to send and receive summaries. To
 * transmit structured Summary data, we need to serialize to and from strings.
 */
object SummaryProtocol {
  def serialize(summary: Summary): String =
    List(
      summary.success.toString,
      summary.fail.toString,
      summary.ignore.toString,
      summary.failureDetails
    ).map(escape).mkString("\t")

  def deserialize(s: String): Option[Summary] =
    s.split('\t').map(unescape) match {
      case Array(success, fail, ignore, summary) =>
        Some(Summary(success.toInt, fail.toInt, ignore.toInt, summary))

      case _ => None
    }

  def escape(token: String): String   = token.replace("\t", "\\t")
  def unescape(token: String): String = token.replace("\\t", "\t")
}
