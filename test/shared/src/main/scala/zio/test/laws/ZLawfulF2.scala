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

object ZLawfulF2 {

  trait Divariant[-CapsBoth[_[-_, +_]], -CapsLeft[_], -CapsRight[_], -R] { self =>
    def laws: ZLawsF2.Divariant[CapsBoth, CapsLeft, CapsRight, R]

    def +[CapsBoth1[x[-_, +_]] <: CapsBoth[x], CapsLeft1[x] <: CapsLeft[x], CapsRight1[x] <: CapsRight[x], R1 <: R](
      that: Divariant[CapsBoth1, CapsLeft1, CapsRight1, R1]
    ): Divariant[CapsBoth1, CapsLeft1, CapsRight1, R1] =
      new Divariant[CapsBoth1, CapsLeft1, CapsRight1, R1] {
        val laws = self.laws + that.laws
      }
  }
}
