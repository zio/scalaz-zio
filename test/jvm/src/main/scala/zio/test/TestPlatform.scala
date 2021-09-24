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

package zio.test

/**
 * `TestPlatform` provides information about the platform tests are being run
 * on to enable platform specific test configuration.
 */
object TestPlatform {

  /**
   * Returns whether the current platform is ScalaJS.
   */
  final val isJS = false

  /**
   * Returns whether the currently platform is the JVM.
   */
  final val isJVM = true

  /**
   * Returns whether the current platform is Scala Native.
   */
  final val isNative = false
}
