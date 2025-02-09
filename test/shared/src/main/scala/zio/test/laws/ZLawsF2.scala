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
import zio.test.{Gen, TestResult, check}
import zio.{URIO, ZIO}
import zio.Trace

object ZLawsF2 {

  /**
   * `ZLawsF2` for Divariant type constructors.
   */
  abstract class Divariant[-CapsF[_[-_, +_]], -CapsLeft[_], -CapsRight[_], -R] { self =>

    /**
     * Test that values of type `F[+_,-_]` satisfy the laws using the specified
     * function to construct a generator of `F[A,B]` values given a generator of
     * `B` values.
     */
    def run[R1 <: R, F[-_, +_]: CapsF, A: CapsLeft, B: CapsRight](
      genF: GenF2[R1, F],
      gen: Gen[R1, B]
    )(implicit trace: Trace): ZIO[R1, Nothing, TestResult]

    /**
     * Combine these laws with the specified laws to produce a set of laws that
     * require both sets of laws to be satisfied.
     */
    def +[CapsF1[x[-_, +_]] <: CapsF[x], CapsLeft1[x] <: CapsLeft[x], CapsRight1[x] <: CapsRight[x], R1 <: R](
      that: Divariant[CapsF1, CapsLeft1, CapsRight1, R1]
    ): Divariant[CapsF1, CapsLeft1, CapsRight1, R1] =
      Divariant.Both(self, that)
  }

  object Divariant {

    private final case class Both[-CapsBothF[_[-_, +_]], -CapsLeft[_], -CapsRight[_], -R](
      left: Divariant[CapsBothF, CapsLeft, CapsRight, R],
      right: Divariant[CapsBothF, CapsLeft, CapsRight, R]
    ) extends Divariant[CapsBothF, CapsLeft, CapsRight, R] {

      override final def run[R1 <: R, F[-_, +_]: CapsBothF, A: CapsLeft, B: CapsRight](
        genF: GenF2[R1, F],
        gen: Gen[R1, B]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] = {
        val lhs: ZIO[R1, Nothing, TestResult] = left.run(genF, gen)
        val rhs: ZIO[R1, Nothing, TestResult] = right.run(genF, gen)
        lhs.zipWith(rhs)(_ && _)
      }
    }

    /**
     * Constructs a law from a pure function taking one parameterized value and
     * two functions that can be composed.
     */
    abstract class ComposeLaw[-CapsBothF[_[-_, +_]], -Caps[_]](label: String)
        extends Divariant[CapsBothF, Caps, Caps, Any] {
      self =>
      def apply[F[-_, +_]: CapsBothF, A: Caps, B: Caps, A1: Caps, A2: Caps](
        fa: F[A, B],
        f: A => A1,
        g: A1 => A2
      ): TestResult

      final def run[R, F[-_, +_]: CapsBothF, A: Caps, B: Caps, A1: Caps, A2: Caps](
        genF: GenF2[R, F],
        genB: Gen[R, B],
        genA1: Gen[R, A1],
        genA2: Gen[R, A2]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(
          genF[R, A, B](genB),
          Gen.function[R, A, A1](genA1),
          Gen.function[R, A1, A2](genA2)
        )(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from a pure function taking a single parameter.
     */
    abstract class Law1[-CapsBothF[_[-_, +_]], -CapsLeft[_], -CapsRight[_]](label: String)
        extends Divariant[CapsBothF, CapsLeft, CapsRight, Any] { self =>
      def apply[F[-_, +_]: CapsBothF, A: CapsLeft, B: CapsRight](fa: F[A, B]): TestResult

      final def run[R, F[-_, +_]: CapsBothF, A: CapsLeft, B: CapsRight](
        genF: GenF2[R, F],
        gen: Gen[R, B]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF[R, A, B](gen))(apply(_).label(label))
    }
  }
}
