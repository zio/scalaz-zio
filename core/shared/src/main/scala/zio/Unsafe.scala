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
package zio

/**
 * A marker interface used to indicate that a method is side-effecting, partial,
 * or potentially type unsafe, such that it might throw a ClassCastException if
 * used improperly. This marker interface is useful for certain low-level ZIO
 * methods, to differentiate them from the higher-level methods, which are
 * always pure, total, and type-safe.
 *
 * {{{
 * import Unsafe.unsafe
 *
 * unsafe { ... }
 * }}}
 */
sealed trait Unsafe extends Serializable

object Unsafe extends Unsafe with UnsafeVersionSpecific { self =>
  private[zio] val unsafe: Unsafe = self

  def unsafe[A](f: Unsafe => A): A =
    f(self)

  @deprecated("use unsafe", "2.0.1")
  def unsafeCompat[A](f: Unsafe => A): A =
    f(self)
}
