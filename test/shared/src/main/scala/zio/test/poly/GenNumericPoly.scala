/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
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
import zio.test.{Gen, Sized}
import zio.{Has, Random, ZTraceElement}

/**
 * `GenNumericPoly` provides evidence that instances of `Gen[T]` and
 * `Numeric[T]` exist for some concrete but unknown type `T`.
 */
trait GenNumericPoly extends GenOrderingPoly {
  val numT: Numeric[T]
  override final val ordT: Ordering[T] = numT
}

object GenNumericPoly {

  /**
   * Constructs an instance of `GenIntegralPoly` using the specified `Gen`
   * and `Numeric` instances, existentially hiding the underlying type.
   */
  def apply[A](gen: Gen[Has[Random] with Has[Sized], A], num: Numeric[A]): GenNumericPoly =
    new GenNumericPoly {
      type T = A
      val genT = gen
      val numT = num
    }

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for bytes.
   */
  def byte(implicit trace: ZTraceElement): GenNumericPoly =
    GenIntegralPoly.byte

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for
   * characters.
   */
  def char(implicit trace: ZTraceElement): GenNumericPoly =
    GenIntegralPoly.char

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for doubles.
   */
  def double(implicit trace: ZTraceElement): GenNumericPoly =
    GenFractionalPoly.double

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for floats.
   */
  def float(implicit trace: ZTraceElement): GenNumericPoly =
    GenFractionalPoly.float

  /**
   * A generator of polymorphic values constrainted to have a `Numeric`
   * instance.
   */
  def genNumericPoly(implicit trace: ZTraceElement): Gen[Has[Random], GenNumericPoly] =
    Gen.elements(
      byte,
      char,
      double,
      float,
      int,
      long,
      short
    )

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for
   * integers.
   */
  def int(implicit trace: ZTraceElement): GenNumericPoly =
    GenIntegralPoly.int

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for longs.
   */
  def long(implicit trace: ZTraceElement): GenNumericPoly =
    GenIntegralPoly.long

  /**
   * Provides evidence that instances of `Gen` and `Numeric` exist for shorts.
   */
  def short(implicit trace: ZTraceElement): GenNumericPoly =
    GenIntegralPoly.long
}
