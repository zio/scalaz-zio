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
package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * [[LogLevel]] represents the log level associated with an individual logging
 * operation. Log levels are used both to describe the granularity (or
 * importance) of individual log statements, as well as to enable tuning
 * verbosity of log output.
 *
 * @param ordinal
 *   The priority of the log message. Larger values indicate higher priority.
 * @param label
 *   A label associated with the log level.
 * @param syslog
 *   The syslog severity level of the log level.
 *
 * [[LogLevel]] values are ZIO aspects, and therefore can be used with aspect
 * syntax.
 * {{{
 * myEffect @@ LogLevel.Info
 * }}}
 */
final case class LogLevel(ordinal: Int, label: String, syslog: Int)
    extends ZIOAspect[Nothing, Any, Nothing, Any, Nothing, Any] { self =>

  def <(that: LogLevel): Boolean =
    self.ordinal < that.ordinal

  def <=(that: LogLevel): Boolean =
    self.ordinal <= that.ordinal

  def >=(that: LogLevel): Boolean =
    self.ordinal >= that.ordinal

  def >(that: LogLevel): Boolean =
    self.ordinal > that.ordinal

  def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: Nothing <: Any](zio: ZIO[R, E, A])(implicit
    trace: Trace
  ): ZIO[R, E, A] =
    FiberRef.currentLogLevel.locally(self)(zio)
}
object LogLevel {
  val All: LogLevel     = LogLevel(Int.MinValue, "ALL", 0)
  val Fatal: LogLevel   = LogLevel(50000, "FATAL", 2)
  val Error: LogLevel   = LogLevel(40000, "ERROR", 3)
  val Warning: LogLevel = LogLevel(30000, "WARN", 4)
  val Info: LogLevel    = LogLevel(20000, "INFO", 6)
  val Debug: LogLevel   = LogLevel(10000, "DEBUG", 7)
  val Trace: LogLevel   = LogLevel(0, "TRACE", 7)
  val None: LogLevel    = LogLevel(Int.MaxValue, "OFF", 7)

  val levels: Set[LogLevel] = Set(
    LogLevel.All,
    LogLevel.Trace,
    LogLevel.Debug,
    LogLevel.Info,
    LogLevel.Warning,
    LogLevel.Error,
    LogLevel.Fatal,
    LogLevel.None
  )

  implicit val orderingLogLevel: Ordering[LogLevel] = Ordering.by(_.ordinal)
}
