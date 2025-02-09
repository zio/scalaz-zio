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
import zio.{Random, Trace}

import scala.annotation.tailrec

/**
 * `GenOrderingPoly` provides evidence that instances of `Gen[T]` and
 * `Ordering[T]` exist for some concrete but unknown type `T`.
 */
trait GenOrderingPoly extends GenPoly {
  val ordT: Ordering[T]
}

object GenOrderingPoly {

  /**
   * Constructs an instance of `GenOrderingPoly` using the specified `Gen` and
   * `Ordering` instances, existentially hiding the underlying type.
   */
  def apply[A](gen: Gen[Any, A], ord: Ordering[A]): GenOrderingPoly =
    new GenOrderingPoly {
      type T = A
      val genT = gen
      val ordT = ord
    }

  /**
   * Provides evidence that instances of `Gen` and a `Ordering` exist for
   * booleans.
   */
  def boolean(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.boolean, Ordering.Boolean)

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for bytes.
   */
  def byte(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.byte

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for
   * characters.
   */
  def char(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.char

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for doubles.
   */
  def double(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.double

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for floats.
   */
  def float(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.float

  def genOrderingPoly(implicit trace: Trace): Gen[Any, GenOrderingPoly] = {
    val primitives = Gen.elements(
      boolean,
      byte,
      char,
      int,
      double,
      float,
      long,
      short,
      string,
      unit
    )
    val constructors = Gen.elements(list _, option _, vector _)
    val collections = for {
      constructor <- constructors
      primitive   <- primitives
    } yield constructor(primitive)
    Gen.weighted(primitives -> 10, collections -> 3)
  }

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for
   * integers.
   */
  def int(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.int

  /**
   * Provides evidence that instances of `Gen[List[T]]` and `Ordering[List[T]]`
   * exist for any type for which `Gen[T]` and `Ordering[T]` exist.
   */
  def list(poly: GenOrderingPoly)(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.listOf(poly.genT), ListOrdering(poly.ordT))

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for longs.
   */
  def long(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.long

  /**
   * Provides evidence that instances of `Gen[Option[T]]` and
   * `Ordering[Option[T]]` exist for any type for which `Gen[T]` and
   * `Ordering[T]` exist.
   */
  def option(poly: GenOrderingPoly)(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.option(poly.genT), Ordering.Option(poly.ordT))

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for shorts.
   */
  def short(implicit trace: Trace): GenOrderingPoly =
    GenNumericPoly.long

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for strings.
   */
  def string(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.string, Ordering.String)

  /**
   * Provides evidence that instances of `Gen` and `Ordering` exist for the unit
   * value.
   */
  def unit(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.unit, Ordering.Unit)

  /**
   * Provides evidence that instances of `Gen[Vector[T]]` and
   * `Ordering[Vector[T]]` exist for any type for which `Gen[T]` and
   * `Ordering[T]` exist.
   */
  def vector(poly: GenOrderingPoly)(implicit trace: Trace): GenOrderingPoly =
    GenOrderingPoly(Gen.vectorOf(poly.genT), VectorOrdering(poly.ordT))

  /**
   * Derives an `Ordering[List[A]]` given an `Ordering[A]`.
   */
  private implicit def ListOrdering[A: Ordering]: Ordering[List[A]] = {

    @tailrec
    def loop[A: Ordering](left: List[A], right: List[A]): Int =
      (left, right) match {
        case (Nil, Nil) => 0
        case (Nil, _)   => -1
        case (_, Nil)   => +1
        case ((h1 :: t1), (h2 :: t2)) =>
          val compare = Ordering[A].compare(h1, h2)
          if (compare == 0) loop(t1, t2) else compare
      }

    (l, r) => loop(l, r)
  }

  /**
   * Derives an `Ordering[Vector[A]]` given an `Ordering[A]`.
   */
  private implicit def VectorOrdering[A: Ordering]: Ordering[Vector[A]] =
    (l, r) => {
      val j = l.length
      val k = r.length

      def loop(i: Int): Int =
        if (i == j && i == k) 0
        else if (i == j) -1
        else if (i == k) +1
        else {
          val compare = Ordering[A].compare(l(i), r(i))
          if (compare == 0) loop(i + 1) else compare
        }

      loop(0)
    }
}
