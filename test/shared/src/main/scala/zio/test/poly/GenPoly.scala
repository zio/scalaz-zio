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
 * `GenPoly` provides evidence that an instance of `Gen[T]` exists for some
 * concrete but unknown type `T`. Subtypes of `GenPoly` provide additional
 * constraints on the type of `T`, such as that an instance of `Ordering[T]` or
 * `Numeric[T]` exists. Users can also extend `GenPoly` to add their own
 * constraints.
 *
 * This allows construction of polymorphic generators where the the type is
 * known to satisfy certain constraints even though the type itself is unknown.
 *
 * For instance, consider the following generalized algebraic data type:
 *
 * {{{
 * sealed trait Expr[+A] extends Product with Serializable
 *
 * final case class Value[+A](value: A) extends Expr[A]
 * final case class Mapping[A, +B](expr: Expr[A], f: A => B) extends Expr[B]
 * }}}
 *
 * We would like to test that for any expression we can fuse two mappings. We
 * want to create instances of `Expr` that reflect the full range of values that
 * an `Expr` can take, including multiple layers of nested mappings and mappings
 * between different types.
 *
 * Since we do not need any constraints on the generated types we can simply use
 * `GenPoly`. `GenPoly` includes a convenient generator in its companion object,
 * `genPoly`, that generates instances of 40 different types including primitive
 * types and various collections.
 *
 * Using it we can define polymorphic generators for expressions:
 *
 * {{{
 * def genValue(t: GenPoly): Gen[Any, Expr[t.T]] =
 *   t.genT.map(Value(_))
 *
 * def genMapping(t: GenPoly): Gen[Any, Expr[t.T]] =
 *   Gen.suspend {
 *     GenPoly.genPoly.flatMap { t0 =>
 *       genExpr(t0).flatMap { expr =>
 *         val genFunction: Gen[Any, t0.T => t.T] = Gen.function(t.genT)
 *         val genExpr1: Gen[Any, Expr[t.T]]      = genFunction.map(f => Mapping(expr, f))
 *         genExpr1
 *       }
 *     }
 *   }
 *
 * def genExpr(t: GenPoly): Gen[Any, Expr[t.T]] =
 *   Gen.oneOf(genMapping(t), genValue(t))
 * }}}
 *
 * Finally, we can test our property:
 *
 * {{{
 * test("map fusion") {
 *   check(GenPoly.genPoly.flatMap(genExpr(_))) { expr =>
 *     assert(eval(fuse(expr)))(equalTo(eval(expr)))
 *   }
 * }
 * }}}
 *
 * This will generate expressions with multiple levels of nesting and
 * polymorphic mappings between different types, making sure that the types line
 * up for each mapping. This provides a higher level of confidence in properties
 * than testing with a monomorphic value.
 *
 * Inspired by Erik Osheim's presentation "Galaxy Brain: type-dependence and
 * state-dependence in property-based testing"
 * [[http://plastic-idolatry.com/erik/oslo2019.pdf]].
 */
trait GenPoly {
  type T
  val genT: Gen[Any, T]
}

object GenPoly {

  /**
   * Constructs an instance of `TypeWith` using the specified value,
   * existentially hiding the underlying type.
   */
  def apply[A](gen: Gen[Any, A]): GenPoly =
    new GenPoly {
      type T = A
      val genT = gen
    }

  /**
   * Provides evidence that instances of `Gen` and a `Ordering` exist for
   * booleans.
   */
  def boolean(implicit trace: Trace): GenPoly =
    GenOrderingPoly(Gen.boolean, Ordering.Boolean)

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for bytes.
   */
  def byte(implicit trace: Trace): GenPoly =
    GenIntegralPoly.byte

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for
   * characters.
   */
  def char(implicit trace: Trace): GenPoly =
    GenIntegralPoly.char

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for doubles.
   */
  def double(implicit trace: Trace): GenPoly =
    GenFractionalPoly.double

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for floats.
   */
  def float(implicit trace: Trace): GenPoly =
    GenFractionalPoly.float

  def genPoly(implicit trace: Trace): Gen[Any, GenPoly] =
    GenOrderingPoly.genOrderingPoly

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for
   * integers.
   */
  def int(implicit trace: Trace): GenPoly =
    GenIntegralPoly.int

  /**
   * Provides evidence that instances of `Gen[List[T]]` and `Ordering[List[T]]`
   * exist for any type for which `Gen[T]` and `Ordering[T]` exist.
   */
  def list(poly: GenPoly)(implicit trace: Trace): GenPoly =
    GenPoly(Gen.listOf(poly.genT))

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for longs.
   */
  def long(implicit trace: Trace): GenPoly =
    GenIntegralPoly.long

  /**
   * Provides evidence that instances of `Gen[Option[T]]` and
   * `Ordering[Option[T]]` exist for any type for which `Gen[T]` and
   * `Ordering[T]` exist.
   */
  def option(poly: GenPoly)(implicit trace: Trace): GenPoly =
    GenPoly(Gen.option(poly.genT))

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for shorts.
   */
  def short(implicit trace: Trace): GenPoly =
    GenIntegralPoly.long

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for strings.
   */
  def string(implicit trace: Trace): GenPoly =
    GenOrderingPoly(Gen.string, Ordering.String)

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for the unit
   * value.
   */
  def unit(implicit trace: Trace): GenPoly =
    GenOrderingPoly(Gen.unit, Ordering.Unit)

  /**
   * Provides evidence that instances of `Gen[Vector[T]]` and
   * `Ordering[Vector[T]]` exist for any type for which `Gen[T]` and
   * `Ordering[T]` exist.
   */
  def vector(poly: GenPoly)(implicit trace: Trace): GenPoly =
    GenPoly(Gen.vectorOf(poly.genT))
}
