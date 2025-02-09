/*
 * Copyright 2018-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

private[zio] final class RingBufferPow2[A](val requestedCapacity: Int)
    extends RingBuffer[A](RingBuffer.nextPow2(requestedCapacity)) {
  protected def posToIdx(pos: Long, capacity: Int): Int =
    (pos & (capacity - 1).toLong).toInt
}

private[zio] object RingBufferPow2 {
  def apply[A](requestedCapacity: Int): RingBufferPow2[A] = {
    // NOTE: Do not use `assert` as the compiler removes it in releases
    require(requestedCapacity > 0)

    new RingBufferPow2(requestedCapacity)
  }
}
