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
 * `ZLaws[Caps, R]` represents a set of laws that values with capabilities
 * `Caps` are expected to satisfy. Laws can be run by providing a generator of
 * values of a type `A` with the required capabilities to return a test result.
 * Laws can be combined using `+` to produce a set of laws that require both
 * sets of laws to be satisfied.
 */
abstract class ZLaws[-Caps[_], -R] { self =>

  /**
   * Test that values of type `A` satisfy the laws using the specified
   * generator.
   */
  def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
    trace: Trace
  ): ZIO[R1, Nothing, TestResult]

  /**
   * Combine these laws with the specified laws to produce a set of laws that
   * require both sets of laws to be satisfied.
   */
  def +[Caps1[x] <: Caps[x], R1 <: R](that: ZLaws[Caps1, R1]): ZLaws[Caps1, R1] =
    ZLaws.Both(self, that)
}

object ZLaws {

  private final case class Both[-Caps[_], -R](left: ZLaws[Caps, R], right: ZLaws[Caps, R]) extends ZLaws[Caps, R] {
    final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
      trace: Trace
    ): ZIO[R1, Nothing, TestResult] =
      left.run(gen).zipWith(right.run(gen))(_ && _)
  }

  /**
   * Constructs a law from a pure function taking a single parameter.
   */
  abstract class Law1[-Caps[_]](label: String) extends ZLaws[Caps, Any] { self =>
    def apply[A: Caps](a1: A): TestResult
    final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): URIO[R, TestResult] =
      check(gen)(apply(_).??(label))
  }

  /**
   * Constructs a law from an effectual function taking a single parameter.
   */
  abstract class Law1ZIO[-Caps[_], -R](label: String) extends ZLaws[Caps, R] { self =>
    def apply[A: Caps](a1: A): URIO[R, TestResult]
    final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
      trace: Trace
    ): ZIO[R1, Nothing, TestResult] =
      check(gen)(apply(_).map(_.??(label)))
  }

  /**
   * Constructs a law from a pure function taking two parameters.
   */
  abstract class Law2[-Caps[_]](label: String) extends ZLaws[Caps, Any] { self =>
    def apply[A: Caps](a1: A, a2: A): TestResult
    final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): URIO[R, TestResult] =
      check(gen, gen)(apply(_, _).label(label))
  }

  /**
   * Constructs a law from an effectual function taking two parameters.
   */
  abstract class Law2ZIO[-Caps[_], -R](label: String) extends ZLaws[Caps, R] { self =>
    def apply[A: Caps](a1: A, a2: A): URIO[R, TestResult]
    final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
      trace: Trace
    ): ZIO[R1, Nothing, TestResult] =
      check(gen, gen)(apply(_, _).map(_.label(label)))
  }

  /**
   * Constructs a law from a pure function taking three parameters.
   */
  abstract class Law3[-Caps[_]](label: String) extends ZLaws[Caps, Any] { self =>
    def apply[A: Caps](a1: A, a2: A, a3: A): TestResult
    final def run[R, A: Caps](gen: Gen[R, A])(implicit trace: Trace): URIO[R, TestResult] =
      check(gen, gen, gen)(apply(_, _, _).label(label))
  }

  /**
   * Constructs a law from an effectual function taking three parameters.
   */
  abstract class Law3ZIO[-Caps[_], -R](label: String) extends ZLaws[Caps, R] { self =>
    def apply[A: Caps](a1: A, a2: A, a3: A): URIO[R, TestResult]
    final def run[R1 <: R, A: Caps](gen: Gen[R1, A])(implicit
      trace: Trace
    ): ZIO[R1, Nothing, TestResult] =
      check(gen, gen, gen)(apply(_, _, _).map(_.label(label)))
  }
}
