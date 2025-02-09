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
 * `ZLawfulF[CapsF, Caps, R]` describes a set of laws that a parameterized type
 * `F[A]` with capabilities `CapsF` is expected to satisfy with respect to all
 * types `A` that have capabilities `Caps`. Lawful instances can be combined
 * using `+` to describe a set of capabilities and all of the laws that those
 * capabilities are expected to satisfy.
 */
object ZLawfulF {

  /**
   * `ZLawful` for covariant type constructors.
   */
  trait Covariant[-CapsF[_[+_]], -Caps[_], -R] { self =>
    def laws: ZLawsF.Covariant[CapsF, Caps, R]
    def +[CapsF1[x[+_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Covariant[CapsF1, Caps1, R1]
    ): Covariant[CapsF1, Caps1, R1] =
      new Covariant[CapsF1, Caps1, R1] {
        val laws = self.laws + that.laws
      }
  }

  /**
   * `ZLawful` for contravariant type constructors.
   */
  trait Contravariant[-CapsF[_[-_]], -Caps[_], -R] { self =>
    def laws: ZLawsF.Contravariant[CapsF, Caps, R]
    def +[CapsF1[x[-_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Contravariant[CapsF1, Caps1, R1]
    ): Contravariant[CapsF1, Caps1, R1] =
      new Contravariant[CapsF1, Caps1, R1] {
        val laws = self.laws + that.laws
      }
  }

  /**
   * `ZLawful` for invariant type constructors.
   */
  trait Invariant[-CapsF[_[_]], -Caps[_], -R] { self =>
    def laws: ZLawsF.Invariant[CapsF, Caps, R]
    def +[CapsF1[x[_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Invariant[CapsF1, Caps1, R1]
    ): Invariant[CapsF1, Caps1, R1] =
      new Invariant[CapsF1, Caps1, R1] {
        val laws = self.laws + that.laws
      }
  }
}
