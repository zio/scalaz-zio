/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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

import scala.annotation.tailrec

final case class ZTrace(
  fiberId: FiberId,
  stackTrace: Chunk[ZTraceElement]
) { self =>

  def ++(that: ZTrace): ZTrace =
    ZTrace(self.fiberId combine that.fiberId, self.stackTrace ++ that.stackTrace)

  /**
   * Converts the ZIO trace into a Java stack trace, by converting each trace
   * element into a Java stack trace element.
   */
  def toJava: Chunk[StackTraceElement] = {
    val chunkBuilder  = ChunkBuilder.make[StackTraceElement](stackTrace.size)
    val chunkIterator = stackTrace.chunkIterator
    var index         = 0
    var previous      = -1
    while (chunkIterator.hasNextAt(index)) {
      chunkIterator.nextAt(index) match {
        case ZTraceElement.SourceLocation(location, file, line, _) if line != previous =>
          previous = line
          val last            = location.lastIndexOf(".")
          val (before, after) = if (last < 0) ("", "." + location) else location.splitAt(last)
          def stripSlash(file: String): String = {
            val last = file.lastIndexOf("/")
            if (last < 0) file else file.drop(last + 1)
          }
          val stackTraceElement = new StackTraceElement(before, after.drop(1), stripSlash(file), line)
          chunkBuilder += stackTraceElement
        case _ =>
      }
      index += 1
    }
    chunkBuilder.result()
  }
}

object ZTrace {

  lazy val none: ZTrace =
    ZTrace(FiberId.None, Chunk.empty)
}
