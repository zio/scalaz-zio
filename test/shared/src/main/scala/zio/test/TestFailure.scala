/*
 * Copyright 2019-2024 John A. De Goes and the ZIO Contributors
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

package zio.test

import zio.{Cause, ZIO}
import zio.stacktracer.TracingImplicits.disableAutoTrace

sealed abstract class TestFailure[+E] { self =>

  /**
   * Retrieves the annotations associated with this test failure.
   */
  def annotations: TestAnnotationMap

  /**
   * Annotates this test failure with the specified test annotations.
   */
  def annotated(annotations: TestAnnotationMap): TestFailure[E] =
    self match {
      case TestFailure.Assertion(result, _) => TestFailure.Assertion(result, self.annotations ++ annotations)
      case TestFailure.Runtime(cause, _)    => TestFailure.Runtime(cause, self.annotations ++ annotations)
    }

  /**
   * Transforms the error type of this test failure with the specified function.
   */
  def map[E2](f: E => E2): TestFailure[E2] =
    self match {
      case TestFailure.Assertion(result, annotations) => TestFailure.Assertion(result, annotations)
      case TestFailure.Runtime(cause, annotations)    => TestFailure.Runtime(cause.map(f), annotations)
    }
}

object TestFailure {
  final case class Assertion(result: TestResult, annotations: TestAnnotationMap = TestAnnotationMap.empty)
      extends TestFailure[Nothing]
  final case class Runtime[+E](cause: Cause[E], annotations: TestAnnotationMap = TestAnnotationMap.empty)
      extends TestFailure[E]

  /**
   * Constructs an assertion failure with the specified result.
   */
  def assertion(result: TestResult): TestFailure[Nothing] =
    Assertion(result, TestAnnotationMap.empty)

  /**
   * Constructs a runtime failure that dies with the specified `Throwable`.
   */
  def die(t: Throwable): TestFailure[Nothing] =
    failCause(Cause.die(t))

  /**
   * Constructs a runtime failure that fails with the specified error.
   */
  def fail[E](e: E): TestFailure[E] =
    failCause(Cause.fail(e))

  /**
   * Constructs a runtime failure with the specified cause.
   */
  def failCause[E](cause: Cause[E]): TestFailure[E] =
    Runtime(cause, TestAnnotationMap.empty)
}
