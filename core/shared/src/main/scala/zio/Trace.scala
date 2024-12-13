/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

object Trace {

  def apply(location: String, file: String, line: Int): Trace =
    Tracer.instance(location, file, line)

  def equalIgnoreLocation(left: Trace, right: Trace): Boolean = {
    val leftParsed = parseOrNull(left)
    if (leftParsed eq null) return false

    val rightParsed = parseOrNull(right)
    if (rightParsed eq null) return false

    val leftFile  = leftParsed._2
    val leftLine  = leftParsed._3
    val rightFile = rightParsed._2
    val rightLine = rightParsed._3

    leftFile == rightFile && leftLine == rightLine
  }

  val empty: Trace = Tracer.instance.empty

  def fromJava(stackTraceElement: StackTraceElement): Trace =
    Trace(
      stackTraceElement.getClassName + "." + stackTraceElement.getMethodName,
      stackTraceElement.getFileName,
      stackTraceElement.getLineNumber
    )

  def toJava(trace: Trace): Option[StackTraceElement] = {
    val parsed = parseOrNull(trace)
    if (parsed eq null) None
    else {
      val location = parsed._1
      val file     = parsed._2
      val line     = parsed._3

      val last = location.lastIndexOf('.')

      val (before, after) = if (last < 0) ("", "." + location) else location.splitAt(last)

      Some(new StackTraceElement(before, after.drop(1), file, line))
    }
  }

  def unapply(trace: Trace): Option[(String, String, Int)] = Option(parseOrNull(trace))

  private[zio] def parseOrNull(trace: Trace): (String, String, Int) = Tracer.instance.parseOrNull(trace)

}
