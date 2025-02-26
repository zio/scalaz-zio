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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.{PrintStream, PrintWriter}

/**
 * Represents a failure in a fiber. This could be caused by some non-
 * recoverable error, such as a defect or system error, by some typed error, or
 * by interruption (or combinations of all of the above).
 *
 * This class is used to wrap ZIO failures into something that can be thrown, to
 * better integrate with Scala exception handling.
 */
final case class FiberFailure(cause: Cause[Any]) extends Throwable(null, null, true, false) {
  override def getMessage: String =
    cause.unified.headOption.fold("<unknown>")(_.message)

  override def getStackTrace: Array[StackTraceElement] =
    cause.trace.toJava.toArray

  override def getCause: Throwable =
    cause.dieOption.orElse(cause.failureOption.collect { case t: Throwable => t }).orNull

  // Note: unlike standard Java exceptions, this includes the stack trace.
  override def toString: String =
    cause.prettyPrint

  override def printStackTrace(s: PrintStream): Unit =
    cause.prettyPrintWith(s.println)(Unsafe.unsafe)

  override def printStackTrace(s: PrintWriter): Unit =
    cause.prettyPrintWith(s.println)(Unsafe.unsafe)

  def fillSuppressed()(implicit unsafe: Unsafe): Unit =
    if (getSuppressed.length == 0)
      for (unified <- cause.unified.iterator.drop(1))
        addSuppressed(unified.toThrowable)
}
