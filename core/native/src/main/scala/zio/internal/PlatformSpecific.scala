/*
 * Copyright 2017-2022 John A. De Goes and the ZIO Contributors
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

import java.util.{HashMap, HashSet, Map => JMap, Set => JSet}

private[zio] trait PlatformSpecific {

  /**
   * Adds a shutdown hook that executes the specified action on shutdown.
   *
   * This is currently a no-op on Scala Native.
   */
  final def addShutdownHook(action: () => Unit)(implicit unsafe: zio.Unsafe): Unit =
    blackhole(action)

  /**
   * Adds a signal handler for the specified signal (e.g. "INFO"). This method
   * never fails even if adding the handler fails.
   *
   * This is currently a no-op on Scala Native.
   */
  final def addSignalHandler(signal: String, action: () => Unit)(implicit unsafe: zio.Unsafe): Unit = {
    blackhole(signal)
    blackhole(action)
  }

  /**
   * Exits the application with the specified exit code.
   */
  final def exit(code: Int)(implicit unsafe: zio.Unsafe): Unit =
    blackhole(code)

  /**
   * Returns the name of the thread group to which this thread belongs. This is
   * a side-effecting method.
   */
  final def getCurrentThreadGroup()(implicit unsafe: zio.Unsafe): String = ""

  /**
   * Returns whether the current platform is ScalaJS.
   */
  final val isJS = false

  /**
   * Returns whether the currently platform is the JVM.
   */
  final val isJVM = false

  /**
   * Returns whether the currently platform is Scala Native.
   */
  final val isNative = true

  final def newWeakSet[A]()(implicit unsafe: zio.Unsafe): JSet[A] = new HashSet[A]()

  final def newConcurrentSet[A]()(implicit unsafe: zio.Unsafe): JSet[A] = new HashSet[A]()

  final def newConcurrentWeakSet[A]()(implicit unsafe: zio.Unsafe): JSet[A] = new HashSet[A]()

  final def newWeakHashMap[A, B]()(implicit unsafe: zio.Unsafe): JMap[A, B] = new HashMap[A, B]()

  final def newWeakReference[A](value: A)(implicit unsafe: zio.Unsafe): () => A = { () => value }

  private def blackhole(a: Any): Unit = {
    val _ = a
    ()
  }
}
