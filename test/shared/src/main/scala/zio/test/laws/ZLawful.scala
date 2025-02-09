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

package zio.test.laws

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * `ZLawful[Caps, R]` describes a capability that is expected to satisfy a set
 * of laws. Lawful instances can be combined using `+` to describe a set of
 * capabilities and all of the laws that those capabilities are expected to
 * satisfy.
 *
 * {{{
 * trait Equal[-A] {
 *   def equal(a1: A, a2: A): Boolean
 * }
 *
 * object Equal extends Lawful[Equal] {
 *   val laws = ???
 * }
 * }}}
 */
trait ZLawful[-Caps[_], -R] { self =>
  def laws: ZLaws[Caps, R]

  def +[Caps1[x] <: Caps[x], R1 <: R](that: ZLawful[Caps1, R1]): ZLawful[Caps1, R1] =
    new ZLawful[Caps1, R1] {
      val laws = self.laws + that.laws
    }
}
