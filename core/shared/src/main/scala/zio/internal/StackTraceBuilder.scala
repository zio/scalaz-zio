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

package zio.internal

import zio._

import scala.collection.mutable.ArrayBuilder
import scala.reflect.ClassTag

private[zio] class StackTraceBuilder private () { self =>
  private var last: Trace = null.asInstanceOf[Trace]
  private val builder     = new ArrayBuilder.ofRef()(ClassTag.AnyRef.asInstanceOf[ClassTag[Trace]])

  def +=(trace: Trace): Unit =
    if ((trace ne null) && (trace ne Trace.empty) && (trace ne last)) {
      builder += trace
      last = trace
    }

  def clear(): Unit = {
    builder.clear()
    last = null.asInstanceOf[Trace]
  }

  def result(): Chunk[Trace] =
    Chunk.fromArray(builder.result())
}

private[zio] object StackTraceBuilder {
  def make()(unsafe: Unsafe): StackTraceBuilder = new StackTraceBuilder()
}
