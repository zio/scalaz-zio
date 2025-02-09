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

package zio.test.poly

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.Gen
import zio.Trace

/**
 * `GenFractionalPoly` provides evidence that instances of `Gen[T]` and
 * `Fractional[T]` exist for some concrete but unknown type `T`.
 */
trait GenFractionalPoly extends GenNumericPoly {
  override val numT: Fractional[T]
}

object GenFractionalPoly {

  /**
   * Constructs an instance of `GenFractionalPoly` using the specified `Gen` and
   * `Fractional` instances, existentially hiding the underlying type.
   */
  def apply[A](gen: Gen[Any, A], num: Fractional[A]): GenFractionalPoly =
    new GenFractionalPoly {
      type T = A
      val genT = gen
      val numT = num
    }

  /**
   * Provides evidence that instances of `Gen` and `Fractional` exist for
   * doubles.
   */
  def double(implicit trace: Trace): GenFractionalPoly =
    GenFractionalPoly(Gen.double, Numeric.DoubleIsFractional)

  /**
   * Provides evidence that instances of `Gen` and `Fractional` exist for
   * floats.
   */
  def float(implicit trace: Trace): GenFractionalPoly =
    GenFractionalPoly(Gen.float, Numeric.FloatIsFractional)

  /**
   * A generator of polymorphic values constrainted to have a `Fractional`
   * instance.
   */
  def genFractionalPoly(implicit trace: Trace): Gen[Any, GenFractionalPoly] =
    Gen.elements(double, float)
}
