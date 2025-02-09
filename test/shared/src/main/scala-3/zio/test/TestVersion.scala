/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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
 * `TestVersion` provides information about the Scala version tests are being
 * run on to enable platform specific test configuration.
 */
object TestVersion {

  /**
   * Returns whether the current Scala version is Scala 2.
   */
  val isScala2: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 2.12.
   */
  val isScala212: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 2.13.
   */
  val isScala213: Boolean = false

  /**
   * Returns whether the current Scala version is Scala 3.
   */
  val isScala3: Boolean = true
}
