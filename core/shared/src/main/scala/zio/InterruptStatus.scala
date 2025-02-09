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

/**
 * The [[InterruptStatus]] of a fiber determines whether or not it can be
 * interrupted. The status can change over time in different regions.
 *
 * If a fiber is interruptible but in wind-down mode, then it cannot be
 * interrupted no matter what. The [[InterruptStatus]] of a fiber reflects only
 * whether it is within an interruptible or uninterruptible region, regardless
 * of wind-down mode.
 */
sealed abstract class InterruptStatus(val isInterruptible: Boolean) extends Serializable with Product {
  final def isUninterruptible: Boolean = !isInterruptible

  private[zio] final def toBoolean: Boolean = isInterruptible
}
object InterruptStatus {
  def interruptible: InterruptStatus   = Interruptible
  def uninterruptible: InterruptStatus = Uninterruptible

  /**
   * Indicates the fiber can be interrupted right now.
   */
  case object Interruptible extends InterruptStatus(true)

  /**
   * Indicates the fiber cannot be interrupted right now.
   */
  case object Uninterruptible extends InterruptStatus(false)

  private[zio] def fromBoolean(b: Boolean): InterruptStatus = if (b) Interruptible else Uninterruptible
}
