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

package zio.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * `GenFailureDetails` keeps track of relevant information related to a failure
 * in a generative test.
 */
sealed abstract class GenFailureDetails {
  type Value

  val initialInput: Value
  val shrunkenInput: Value
  val iterations: Long
}

object GenFailureDetails {
  def apply[A](initialInput0: A, shrunkenInput0: A, iterations0: Long): GenFailureDetails =
    new GenFailureDetails {
      type Value = A

      val initialInput: Value  = initialInput0
      val shrunkenInput: Value = shrunkenInput0
      val iterations: Long     = iterations0
    }
}
