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

import scala.annotation.implicitAmbiguous

/**
 * A value of type `CanFail[E]` provides implicit evidence that an effect with
 * error type `E` can fail, that is, that `E` is not equal to `Nothing`.
 */
sealed abstract class CanFail[-E]

object CanFail extends CanFail[Any] {

  implicit def canFail[E]: CanFail[E] = CanFail

  // Provide multiple ambiguous values so an implicit CanFail[Nothing] cannot be found.
  @implicitAmbiguous(
    "This error handling operation assumes your effect can fail. However, " +
      "your effect has Nothing for the error type, which means it cannot " +
      "fail, so there is no need to handle the failure. To find out which " +
      "method you can use instead of this operation, please see the " +
      "reference chart at: https://zio.dev/can_fail"
  )
  implicit val canFailAmbiguous1: CanFail[Nothing] = CanFail
  implicit val canFailAmbiguous2: CanFail[Nothing] = CanFail
}
