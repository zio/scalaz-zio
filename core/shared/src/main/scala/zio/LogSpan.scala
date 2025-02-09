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

final case class LogSpan(label: String, startTime: Long) {
  private[zio] def renderInto(sb: StringBuilder, now: Long)(implicit unsafe: Unsafe): Unit = {
    if (label.indexOf(' ') < 0) sb.append(label)
    else {
      sb.append('"')
      sb.append(label)
      sb.append('"')
    }

    sb.append('=')
    sb.append(now - startTime)
    sb.append("ms")

    ()
  }

  def render(now: Long): String = {
    val sb = new StringBuilder()

    renderInto(sb, now)(Unsafe)

    sb.toString()
  }
}
