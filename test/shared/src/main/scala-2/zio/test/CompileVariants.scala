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

import zio.internal.stacktracer.SourceLocation
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.{Trace, UIO, ZIO}

trait CompileVariants {

  /**
   * Returns either `Right` if the specified string type checks as valid Scala
   * code or `Left` with an error message otherwise. Dies with a runtime
   * exception if specified string cannot be parsed or is not a known value at
   * compile time.
   */
  final def typeCheck(code: String): UIO[Either[String, Unit]] =
    macro Macros.typeCheck_impl

  /**
   * Checks the assertion holds for the given value.
   */
  def assertTrue(expr: Boolean, exprs: Boolean*): TestResult = macro SmartAssertMacros.assert_impl
  def assertTrue(expr: Boolean): TestResult = macro SmartAssertMacros.assertOne_impl

  /**
   * Checks the assertion holds for the given value.
   */
  def assert[A](expr: => A)(assertion: Assertion[A]): TestResult =
    macro Macros.new_assert_impl

  /**
   * Checks the assertion holds for the given effectfully-computed value.
   */
  def assertZIO[R, E, A](effect: ZIO[R, E, A])(assertion: Assertion[A])(implicit
    trace: Trace,
    sourceLocation: SourceLocation
  ): ZIO[R, E, TestResult] =
    Assertion.smartAssertZIO(effect)(assertion)

  private[zio] def showExpression[A](expr: => A): String = macro Macros.showExpression_impl
}

/**
 * Proxy methods to call package private methods from the macro
 */
object CompileVariants {

  def newAssertProxy[A](value: => A, codeString: String, assertionString: String)(
    assertion: Assertion[A]
  )(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    zio.test.assertImpl(value, Some(codeString), Some(assertionString))(assertion)

  def assertZIOProxy[R, E, A](effect: ZIO[R, E, A])(
    assertion: Assertion[A]
  )(implicit trace: Trace, sourceLocation: SourceLocation): ZIO[R, E, TestResult] =
    zio.test.assertZIOImpl(effect)(assertion)
}
