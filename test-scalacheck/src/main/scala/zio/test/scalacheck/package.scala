package zio.test

import org.scalacheck.rng.Seed
import org.scalacheck.Gen.Parameters
import org.scalacheck.{Prop, Properties, Test => CheckTest}
import zio._
import zio.test.{ErrorMessage => M}

/**
 * This package provides helpers to integrate *some* ScalaCheck primitives to
 * their ZIO equivalents. Currently available helpers:
 *
 *   - Converting ScalaCheck Generators to ZIO Generators
 *   - Asserting a ScalaCheck `Prop` with ZIO
 *   - Asserting a ScalaCheck `Properties` with ZIO
 *
 * **Generators**
 *
 * This functionality converts legacy ScalaCheck generators to ZIO Test
 * generators to support upgrading to ZIO Test without having to reimplement
 * existing generators. To use it import this module and then call `toGenZIO` on
 * any existing ScalaCheck generator. For example:
 *
 * {{{
 * import org.scalacheck.Arbitrary
 *
 * import zio._
 * import zio.test._
 * import zio.test.scalacheck._
 *
 * val anyInt: Gen[Any, Int] =
 *   Arbitrary.arbitrary[Int].toGenZIO
 * }}}
 *
 * **Asserting ScalaCheck `Prop` and `Properties`**
 *
 * This functionality generates ZIO Assertions from either ScalaCheck `Prop` or
 * `Properties`. This helps with integrating other libraries that provide
 * ScalaCheck properties as helpers, i.e. cats-laws.
 *
 * `Prop` example:
 *
 * {{{
 * import org.scalacheck.Prop
 * import org.scalacheck.Test.{ Parameters => ScalaCheckParameters }
 *
 * import zio._
 * import zio.test._
 * import zio.test.scalacheck._
 *
 * val prop: Prop = Prop.forAll { (n: Int, m: Int) =>
 *   n + m == m + n
 * }
 * val resultDefault: TestResult = prop.assertZIO()
 *
 * val resultWithCustomizations: TestResult =
 *   prop.assertZIO("My Prop Name", ScalaCheckParameters.default.withMaxSize(10))
 * }}}
 *
 * `Properties` example:
 *
 * {{{
 * import org.scalacheck.{ Prop, Properties }
 * import org.scalacheck.Test.{ Parameters => ScalaCheckParameters }
 *
 * import zio._
 * import zio.test._
 * import zio.test.scalacheck._
 *
 * object MyProperties extends Properties("MyProperties") {
 *   property("myProp") = Prop.forAll { (n: Int, m: Int) =>
 *     n + m == m + n
 *   }
 * }
 *
 * * val resultDefault: TestResult = MyProperties.assertZIO()
 *
 * // Beware that we can't provide a custom name here, it will be
 * // taken from the `Properties` name parameter
 * val resultWithCustomizations: TestResult =
 *   MyProperties.assertZIO(ScalaCheckParameters.default.withMaxSize(10))
 * }}}
 */
package object scalacheck {

  implicit final class ScalaCheckGenSyntax[A](private val self: org.scalacheck.Gen[A]) extends AnyVal {

    /**
     * Converts a legacy ScalaCheck `Gen` to a ZIO Test `Gen`.
     */
    def toGenZIO: Gen[Any, A] =
      Gen.fromZIO {
        for {
          long <- Random.nextLong
          size <- Sized.size
          a    <- ZIO.succeed(self.pureApply(Parameters.default.withSize(size), Seed(long)))
        } yield a
      }
  }

  implicit final class ScalaCheckPropSyntax(private val self: Prop) extends AnyVal {

    /**
     * Asserts a ScalaCheck `Prop` and provides a ZIO `TestResult`.
     */
    def assertZIO(
      name: String = "ScalaCheck Prop Assertion",
      testParams: CheckTest.Parameters = CheckTest.Parameters.default
    ): TestResult =
      Assertion.assertion[Prop](name)(prop => CheckTest.check(testParams, prop).passed).run(self)
  }

  implicit final class ScalaCheckPropertiesSyntax(private val self: Properties) extends AnyVal {

    /**
     * Asserts ScalaCheck `Properties` and provides a ZIO `TestResult`.
     */
    def assertZIO(
      testParams: CheckTest.Parameters = CheckTest.Parameters.default
    ): TestResult =
      Assertion(
        TestArrow
          .make[Properties, Boolean] { props =>
            CheckTest
              .checkProperties(testParams, props)
              .map { case (name, result) =>
                TestTrace.boolean(result.passed) {
                  M.text(name) + M.choice("succeeded", "failed")
                }
              }
              .reduce(_ && _)
          }
          .withCode(self.name)
      ).run(self)
  }
}
