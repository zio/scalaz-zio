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

package zio.internal

import zio.Fiber.Dump
import zio.Fiber.Status.{Done, Running, Suspended}
import zio.{Fiber, FiberId, RuntimeFlag, RuntimeFlags, UIO, ZIO, Trace}
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] object FiberRenderer {
  def prettyPrint(dump: Fiber.Dump)(implicit trace: Trace): UIO[String] =
    ZIO.succeed(unsafePrettyPrint(dump, System.currentTimeMillis()))

  private def unsafePrettyPrint(dump: Fiber.Dump, now: Long): String = {
    val totalMillis = (now - dump.fiberId.startTimeMillis)
    val millis      = totalMillis % 1000
    val seconds     = totalMillis / 1000L
    val minutes     = seconds / 60L
    val hours       = minutes / 60L

    val name = "\"" + dump.fiberId.threadName + "\""
    val lifeMsg = (if (hours == 0) "" else s"${hours}h ") +
      (if (hours == 0 && minutes == 0) "" else s"${minutes}m ") +
      (if (hours == 0 && minutes == 0 && seconds == 0) "" else s"${seconds}s ") +
      (s"${millis}ms")
    val waitMsg = dump.status match {
      case Suspended(_, _, blockingOn) =>
        if (blockingOn ne FiberId.None) "waiting on " + s"#${blockingOn.ids.mkString(", ")}" else ""
      case _ => ""
    }
    val statMsg = renderStatus(dump.status)

    s"""
       |${name} ($lifeMsg) $waitMsg
       |\tStatus: $statMsg
       |${dump.trace.prettyPrint}
       |""".stripMargin
  }

  private def renderFlags(runtimeFlags: RuntimeFlags): String =
    RuntimeFlags.toSet(runtimeFlags).mkString("(", ", ", ")")

  private def renderTrace(trace: Trace): String =
    if (trace == Trace.empty) "<no trace>" else trace.toString()

  private def renderStatus(status: Fiber.Status): String =
    status match {
      case Done => "Done"
      case Running(runtimeFlags, trace0) =>
        val flags = renderFlags(runtimeFlags)
        val trace = renderTrace(trace0)
        s"Running(${flags}, ${trace})"
      case Suspended(runtimeFlags, trace0, blockingOn) =>
        val flags = renderFlags(runtimeFlags)
        val trace = renderTrace(trace0)
        s"Suspended($flags, $trace)"
    }

}
