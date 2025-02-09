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
import zio.{URIO, ZIO, Trace}

/**
 * `ZLaws[CapsF, Caps, R]` describes a set of laws that a parameterized type
 * `F[A]` with capabilities `CapsF` is expected to satisfy with respect to all
 * types `A` that have capabilities `Caps`. Laws can be run by providing a
 * `GenF` that is capable of generating `F[A]` values given a generator of `A`
 * values and a generator of values of some type `A`. Laws can be combined using
 * `+` to produce a set of laws that require both sets of laws to be satisfied.
 */
object ZLawsF {

  /**
   * `ZLawsF` for covariant type constructors.
   */
  abstract class Covariant[-CapsF[_[+_]], -Caps[_], -R] { self =>

    /**
     * Test that values of type `F[+_]` satisfy the laws using the specified
     * function to construct a generator of `F[A]` values given a generator of
     * `A` values.
     */
    def run[R1 <: R, F[+_]: CapsF, A: Caps](
      genF: GenF[R1, F],
      gen: Gen[R1, A]
    )(implicit trace: Trace): ZIO[R1, Nothing, TestResult]

    /**
     * Combine these laws with the specified laws to produce a set of laws that
     * require both sets of laws to be satisfied.
     */
    def +[CapsF1[x[+_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Covariant[CapsF1, Caps1, R1]
    ): Covariant[CapsF1, Caps1, R1] =
      Covariant.Both(self, that)
  }

  object Covariant {

    private final case class Both[-CapsF[_[+_]], -Caps[_], -R](
      left: Covariant[CapsF, Caps, R],
      right: Covariant[CapsF, Caps, R]
    ) extends Covariant[CapsF, Caps, R] {
      final def run[R1 <: R, F[+_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        left.run(genF, gen).zipWith(right.run(genF, gen))(_ && _)
    }

    /**
     * Constructs a law from a pure function taking one parameterized value and
     * two functions that can be composed.
     */
    abstract class ComposeLaw[-CapsF[_[+_]], -Caps[_]](label: String) extends Covariant[CapsF, Caps, Any] { self =>
      def apply[F[+_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], f: A => B, g: B => C): TestResult
      final def run[R, F[+_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), Gen.function(gen), Gen.function(gen))(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from a parameterized value wrapped in two additional
     * layers that can be flattened.
     */
    abstract class FlattenLaw[-CapsF[_[+_]], -Caps[_]](label: String) extends Covariant[CapsF, Caps, Any] { self =>
      def apply[F[+_]: CapsF, A: Caps](fffa: F[F[F[A]]]): TestResult
      final def run[R, F[+_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(genF(genF(gen))))(apply(_).label(label))
    }

    /**
     * Constructs a law from a pure function taking a single parameter.
     */
    abstract class Law1[-CapsF[_[+_]], -Caps[_]](label: String) extends Covariant[CapsF, Caps, Any] { self =>
      def apply[F[+_]: CapsF, A: Caps](fa: F[A]): TestResult
      final def run[R, F[+_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen))(apply(_).label(label))
    }

    /**
     * Constructs a law from an effectual function taking a single parameter.
     */
    abstract class Law1ZIO[-CapsF[_[+_]], -Caps[_], -R](label: String) extends Covariant[CapsF, Caps, R] { self =>
      def apply[F[+_]: CapsF, A: Caps](fa: F[A]): URIO[R, TestResult]
      final def run[R1 <: R, F[+_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen))(apply(_).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking two parameters.
     */
    abstract class Law2[-CapsF[_[+_]], -Caps[_]](label: String) extends Covariant[CapsF, Caps, Any] { self =>
      def apply[F[+_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): TestResult
      final def run[R, F[+_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking two parameters.
     */
    abstract class Law2ZIO[-CapsF[_[+_]], -Caps[_], -R](label: String) extends Covariant[CapsF, Caps, R] { self =>
      def apply[F[+_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): URIO[R, TestResult]
      final def run[R1 <: R, F[+_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking three parameters.
     */
    abstract class Law3[-CapsF[_[+_]], -Caps[_]](label: String) extends Covariant[CapsF, Caps, Any] { self =>
      def apply[F[+_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): TestResult
      final def run[R, F[+_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking three parameters.
     */
    abstract class Law3ZIO[-CapsF[_[+_]], -Caps[_], -R](label: String) extends Covariant[CapsF, Caps, R] { self =>
      def apply[F[+_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): URIO[R, TestResult]
      final def run[R1 <: R, F[+_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).map(_.label(label)))
    }
  }

  /**
   * `ZLawsF` for contravariant type constructors.
   */
  abstract class Contravariant[-CapsF[_[-_]], -Caps[_], -R] { self =>

    /**
     * Test that values of type `F[+_]` satisfy the laws using the specified
     * function to construct a generator of `F[A]` values given a generator of
     * `A` values.
     */
    def run[R1 <: R, F[-_]: CapsF, A: Caps](
      genF: GenF[R1, F],
      gen: Gen[R1, A]
    )(implicit trace: Trace): ZIO[R1, Nothing, TestResult]

    /**
     * Combine these laws with the specified laws to produce a set of laws that
     * require both sets of laws to be satisfied.
     */
    def +[CapsF1[x[-_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Contravariant[CapsF1, Caps1, R1]
    ): Contravariant[CapsF1, Caps1, R1] =
      Contravariant.Both(self, that)
  }

  object Contravariant {

    private final case class Both[-CapsF[_[-_]], -Caps[_], -R](
      left: Contravariant[CapsF, Caps, R],
      right: Contravariant[CapsF, Caps, R]
    ) extends Contravariant[CapsF, Caps, R] {
      final def run[R1 <: R, F[-_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        left.run(genF, gen).zipWith(right.run(genF, gen))(_ && _)
    }

    /**
     * Constructs a law from a pure function taking one parameterized value and
     * two functions that can be composed.
     */
    abstract class ComposeLaw[-CapsF[_[-_]], -Caps[_]](label: String) extends Contravariant[CapsF, Caps, Any] {
      self =>
      def apply[F[-_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], f: B => A, g: C => B): TestResult
      final def run[R, F[-_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), Gen.function[R, A, A](gen), Gen.function[R, A, A](gen))(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from a pure function taking a single parameter.
     */
    abstract class Law1[-CapsF[_[-_]], -Caps[_]](label: String) extends Contravariant[CapsF, Caps, Any] { self =>
      def apply[F[-_]: CapsF, A: Caps](fa: F[A]): TestResult
      final def run[R, F[-_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen))(apply(_).label(label))
    }

    /**
     * Constructs a law from an effectual function taking a single parameter.
     */
    abstract class Law1ZIO[-CapsF[_[-_]], -Caps[_], -R](label: String) extends Contravariant[CapsF, Caps, R] { self =>
      def apply[F[-_]: CapsF, A: Caps](fa: F[A]): URIO[R, TestResult]
      final def run[R1 <: R, F[-_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen))(apply(_).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking two parameters.
     */
    abstract class Law2[-CapsF[_[-_]], -Caps[_]](label: String) extends Contravariant[CapsF, Caps, Any] { self =>
      def apply[F[-_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): TestResult
      final def run[R, F[-_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking two parameters.
     */
    abstract class Law2ZIO[-CapsF[_[-_]], -Caps[_], -R](label: String) extends Contravariant[CapsF, Caps, R] { self =>
      def apply[F[-_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): URIO[R, TestResult]
      final def run[R1 <: R, F[-_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking three parameters.
     */
    abstract class Law3[-CapsF[_[-_]], -Caps[_]](label: String) extends Contravariant[CapsF, Caps, Any] { self =>
      def apply[F[-_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): TestResult
      final def run[R, F[-_]: CapsF, A: Caps](
        genF: GenF[R, F],
        gen: Gen[R, A]
      )(implicit trace: Trace): URIO[R, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking three parameters.
     */
    abstract class Law3ZIO[-CapsF[_[-_]], -Caps[_], -R](label: String) extends Contravariant[CapsF, Caps, R] { self =>
      def apply[F[-_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): URIO[R, TestResult]
      final def run[R1 <: R, F[-_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).map(_.label(label)))
    }
  }

  /**
   * `ZLawsF` for invariant type constructors.
   */
  abstract class Invariant[-CapsF[_[_]], -Caps[_], -R] { self =>

    /**
     * Test that values of type `F[+_]` satisfy the laws using the specified
     * function to construct a generator of `F[A]` values given a generator of
     * `A` values.
     */
    def run[R1 <: R, F[_]: CapsF, A: Caps](
      genF: GenF[R1, F],
      gen: Gen[R1, A]
    )(implicit trace: Trace): ZIO[R1, Nothing, TestResult]

    /**
     * Combine these laws with the specified laws to produce a set of laws that
     * require both sets of laws to be satisfied.
     */
    def +[CapsF1[x[_]] <: CapsF[x], Caps1[x] <: Caps[x], R1 <: R](
      that: Invariant[CapsF1, Caps1, R1]
    ): Invariant[CapsF1, Caps1, R1] =
      Invariant.Both(self, that)
  }

  object Invariant {

    private final case class Both[-CapsF[_[_]], -Caps[_], -R](
      left: Invariant[CapsF, Caps, R],
      right: Invariant[CapsF, Caps, R]
    ) extends Invariant[CapsF, Caps, R] {
      final def run[R1 <: R, F[_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        left.run(genF, gen).zipWith(right.run(genF, gen))(_ && _)
    }

    /**
     * Constructs a law from a pure function taking a single parameter.
     */
    abstract class Law1[-CapsF[_[_]], -Caps[_]](label: String) extends Invariant[CapsF, Caps, Any] { self =>
      def apply[F[_]: CapsF, A: Caps](fa: F[A]): TestResult
      final def run[R, F[_]: CapsF, A: Caps](genF: GenF[R, F], gen: Gen[R, A])(implicit
        trace: Trace
      ): URIO[R, TestResult] =
        check(genF(gen))(apply(_).label(label))
    }

    /**
     * Constructs a law from an effectual function taking a single parameter.
     */
    abstract class Law1ZIO[-CapsF[_[_]], -Caps[_], -R](label: String) extends Invariant[CapsF, Caps, R] { self =>
      def apply[F[_]: CapsF, A: Caps](fa: F[A]): URIO[R, TestResult]
      final def run[R1 <: R, F[_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen))(apply(_).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking two parameters.
     */
    abstract class Law2[-CapsF[_[_]], -Caps[_]](label: String) extends Invariant[CapsF, Caps, Any] { self =>
      def apply[F[_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): TestResult
      final def run[R, F[_]: CapsF, A: Caps](genF: GenF[R, F], gen: Gen[R, A])(implicit
        trace: Trace
      ): URIO[R, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking two parameters.
     */
    abstract class Law2ZIO[-CapsF[_[_]], -Caps[_], -R](label: String) extends Invariant[CapsF, Caps, R] { self =>
      def apply[F[_]: CapsF, A: Caps, B: Caps](fa: F[A], fb: F[B]): URIO[R, TestResult]
      final def run[R1 <: R, F[_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen))(apply(_, _).map(_.label(label)))
    }

    /**
     * Constructs a law from a pure function taking three parameters.
     */
    abstract class Law3[-CapsF[_[_]], -Caps[_]](label: String) extends Invariant[CapsF, Caps, Any] { self =>
      def apply[F[_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): TestResult
      final def run[R, F[_]: CapsF, A: Caps](genF: GenF[R, F], gen: Gen[R, A])(implicit
        trace: Trace
      ): URIO[R, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).label(label))
    }

    /**
     * Constructs a law from an effectual function taking three parameters.
     */
    abstract class Law3ZIO[-CapsF[_[_]], -Caps[_], -R](label: String) extends Invariant[CapsF, Caps, R] { self =>
      def apply[F[_]: CapsF, A: Caps, B: Caps, C: Caps](fa: F[A], fb: F[B], fc: F[C]): URIO[R, TestResult]
      final def run[R1 <: R, F[_]: CapsF, A: Caps](
        genF: GenF[R1, F],
        gen: Gen[R1, A]
      )(implicit trace: Trace): ZIO[R1, Nothing, TestResult] =
        check(genF(gen), genF(gen), genF(gen))(apply(_, _, _).map(_.label(label)))
    }
  }
}
