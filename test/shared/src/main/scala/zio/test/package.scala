/*
 * Copyright 2019-2022 John A. De Goes and the ZIO Contributors
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

package zio

import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.ZChannel.{ChildExecutorDecision, UpstreamPullRequest, UpstreamPullStrategy}
import zio.stream.{ZChannel, ZSink, ZStream}
import zio.test.Spec.LabeledCase

import scala.language.implicitConversions

/**
 * _ZIO Test_ is a featherweight testing library for effectful programs.
 *
 * The library imagines every spec as an ordinary immutable value, providing
 * tremendous potential for composition. Thanks to tight integration with ZIO,
 * specs can use resources (including those requiring disposal), have well-
 * defined linear and parallel semantics, and can benefit from a host of ZIO
 * combinators.
 *
 * {{{
 *   import zio.test._
 *   import zio.Clock.nanoTime
 *   import Assertion.isGreaterThan
 *
 *   object MyTest extends DefaultRunnableSpec {
 *     def spec = suite("clock")(
 *       test("time is non-zero") {
 *         for {
 *           time <- Live.live(nanoTime)
 *         } yield assertTrue(time >= 0)
 *       }
 *     )
 *   }
 * }}}
 */
package object test extends CompileVariants {
  type TestEnvironment = Annotations with Live with Sized with TestConfig

  object TestEnvironment {
    val any: ZLayer[TestEnvironment, Nothing, TestEnvironment] =
      ZLayer.environment[TestEnvironment](Tracer.newTrace)
    val live: ZLayer[Clock with Console with System with Random, Nothing, TestEnvironment] = {
      implicit val trace = Tracer.newTrace
      Annotations.live ++
        Live.default ++
        Sized.live(100) ++
        ((Live.default ++ Annotations.live) >>> TestClock.default) ++
        TestConfig.live(100, 100, 200, 1000) ++
        (Live.default >>> TestConsole.debug) ++
        TestRandom.deterministic ++
        TestSystem.default

    }
  }

  val liveEnvironment: Layer[Nothing, Clock with Console with System with Random] = {
    implicit val trace = Trace.empty
    ZLayer.succeedEnvironment(
      ZEnvironment[Clock, Console, System, Random](
        Clock.ClockLive,
        Console.ConsoleLive,
        System.SystemLive,
        Random.RandomLive
      )
    )
  }

  val testEnvironment: ZLayer[Any, Nothing, TestEnvironment] = {
    implicit val trace = Tracer.newTrace
    liveEnvironment >>> TestEnvironment.live
  }

  /**
   * Provides an effect with the "real" environment as opposed to the test
   * environment. This is useful for performing effects such as timing out
   * tests, accessing the real time, or printing to the real console.
   */
  def live[R <: Live, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    Live.live(zio)

  /**
   * Retrieves the `TestClock` service for this test.
   */
  def testClock(implicit trace: Trace): UIO[TestClock] =
    testClockWith(ZIO.succeedNow)

  /**
   * Retrieves the `TestClock` service for this test and uses it to run the
   * specified workflow.
   */
  def testClockWith[R, E, A](f: TestClock => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestClock]].get))

  /**
   * Retrieves the `TestConsole` service for this test.
   */
  def testConsole(implicit trace: Trace): UIO[TestConsole] =
    testConsoleWith(ZIO.succeedNow)

  /**
   * Retrieves the `TestConsole` service for this test and uses it to run the
   * specified workflow.
   */
  def testConsoleWith[R, E, A](f: TestConsole => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestConsole]].get))

  /**
   * Retrieves the `TestRandom` service for this test.
   */
  def testRandom(implicit trace: Trace): UIO[TestRandom] =
    testRandomWith(ZIO.succeedNow)

  /**
   * Retrieves the `TestRandom` service for this test and uses it to run the
   * specified workflow.
   */
  def testRandomWith[R, E, A](f: TestRandom => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestRandom]].get))

  /**
   * Retrieves the `TestSystem` service for this test.
   */
  def testSystem(implicit trace: Trace): UIO[TestSystem] =
    testSystemWith(ZIO.succeedNow)

  /**
   * Retrieves the `TestSystem` service for this test and uses it to run the
   * specified workflow.
   */
  def testSystemWith[R, E, A](f: TestSystem => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestSystem]].get))

  /**
   * Transforms this effect with the specified function. The test environment
   * will be provided to this effect, but the live environment will be provided
   * to the transformation function. This can be useful for applying
   * transformations to an effect that require access to the "real" environment
   * while ensuring that the effect itself uses the test environment.
   *
   * {{{
   *   withLive(test)(_.timeout(duration))
   * }}}
   */
  def withLive[R <: Live, E, E1, A, B](
    zio: ZIO[R, E, A]
  )(f: ZIO[R, E, A] => ZIO[R, E1, B])(implicit trace: Trace): ZIO[R with Live, E1, B] =
    Live.withLive(zio)(f)

  /**
   * A `TestAspectAtLeast[R]` is a `TestAspect` that requires at least an `R` in
   * its environment.
   */
  type TestAspectAtLeastR[-R] = TestAspect[Nothing, R, Nothing, Any]

  /**
   * A `TestAspectPoly` is a `TestAspect` that is completely polymorphic, having
   * no requirements on error or environment.
   */
  type TestAspectPoly = TestAspect[Nothing, Any, Nothing, Any]

  /**
   * A `TestReporter[E]` is capable of reporting test results with error type
   * `E`.
   */
  type TestReporter[-E] = (Duration, ExecutionEvent) => URIO[TestLogger, Unit]

  object TestReporter {

    /**
     * TestReporter that does nothing
     */
    val silent: TestReporter[Any] = (_, _) => ZIO.unit
  }

  /**
   * A `ZTest[R, E]` is an effectfully produced test that requires an `R` and
   * may fail with an `E`.
   */
  type ZTest[-R, +E] = ZIO[R, TestFailure[E], TestSuccess]

  object ZTest {

    /**
     * Builds a test with an effectual assertion.
     */
    def apply[R, E](label: String, assertion: => ZIO[R, E, TestResult])(implicit
      trace: Trace
    ): ZIO[R, TestFailure[E], TestSuccess] =
      ZIO
        .suspendSucceed(assertion)
        .daemonChildren
        .ensuringChildren { children =>
          ZIO.foreach(children) { child =>
            val warning =
              s"Warning: ZIO Test is attempting to interrupt fiber " +
                s"${child.id} forked in test $label due to automatic, " +
                "supervision, but interruption has taken more than 10 " +
                "seconds to complete. This may indicate a resource leak. " +
                "Make sure you are not forking a fiber in an " +
                "uninterruptible region."
            for {
              fiber <- ZIO
                         .logWarning(warning)
                         .delay(10.seconds)
                         .provideEnvironment(ZEnvironment(Clock.ClockLive))
                         .interruptible
                         .forkDaemon
              _ <- (child.interrupt *> fiber.interrupt).forkDaemon
            } yield ()
          }
        }
        .foldCauseZIO(
          cause => ZIO.fail(TestFailure.Runtime(cause)),
          assert =>
            if (assert.isFailure)
              ZIO.fail(TestFailure.Assertion(assert))
            else
              ZIO.succeedNow(TestSuccess.Succeeded())
        )
  }

  private[zio] def assertImpl[A](
    value: => A,
    codeString: Option[String] = None,
    assertionString: Option[String] = None
  )(assertion: Assertion[A])(implicit trace: Trace): TestResult =
    Assertion.smartAssert(value, codeString, assertionString)(assertion)

  /**
   * Checks the assertion holds for the given effectfully-computed value.
   */
  private[zio] def assertZIOImpl[R, E, A](
    effect: ZIO[R, E, A],
    codeString: Option[String] = None,
    assertionString: Option[String] = None
  )(
    assertion: Assertion[A]
  )(implicit trace: Trace): ZIO[R, E, TestResult] =
    effect.map { value =>
      assertImpl(value, codeString, assertionString)(assertion)
    }

  /**
   * Asserts that the given test was completed.
   */
  def assertCompletes(implicit trace: Trace): TestResult =
    assertImpl(true)(Assertion.isTrue)

  /**
   * Asserts that the given test was completed.
   */
  def assertCompletesZIO(implicit trace: Trace): UIO[TestResult] =
    ZIO.succeed(assertCompletes)

  /**
   * Asserts that the given test was never completed.
   */
  def assertNever(message: String)(implicit trace: Trace): TestResult =
    assertImpl(true)(Assertion.equalTo(false)) ?? message

  /**
   * Checks the test passes for "sufficient" numbers of samples from the given
   * random variable.
   */
  def check[R <: TestConfig, A, In](rv: Gen[R, A])(test: A => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    TestConfig.samples.flatMap(n =>
      checkStream(rv.sample.forever.collectSome.take(n.toLong))(a => checkConstructor(test(a)))
    )

  /**
   * A version of `check` that accepts two random variables.
   */
  def check[R <: TestConfig, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `check` that accepts three random variables.
   */
  def check[R <: TestConfig, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3)(test.tupled)

  /**
   * A version of `check` that accepts four random variables.
   */
  def check[R <: TestConfig, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)

  /**
   * A version of `check` that accepts five random variables.
   */
  def check[R <: TestConfig, A, B, C, D, F, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(test.tupled)

  /**
   * A version of `check` that accepts six random variables.
   */
  def check[R <: TestConfig, A, B, C, D, F, G, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(test.tupled)

  /**
   * Checks the test passes for all values from the given finite, deterministic
   * generator. For non-deterministic or infinite generators use `check` or
   * `checkN`.
   */
  def checkAll[R <: TestConfig, A, In](rv: Gen[R, A])(test: A => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkStream(rv.sample.collectSome)(a => checkConstructor(test(a)))

  /**
   * A version of `checkAll` that accepts two random variables.
   */
  def checkAll[R <: TestConfig, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `checkAll` that accepts three random variables.
   */
  def checkAll[R <: TestConfig, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3)(test.tupled)

  /**
   * A version of `checkAll` that accepts four random variables.
   */
  def checkAll[R <: TestConfig, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)

  /**
   * A version of `checkAll` that accepts five random variables.
   */
  def checkAll[R <: TestConfig, A, B, C, D, F, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(test.tupled)

  /**
   * A version of `checkAll` that accepts six random variables.
   */
  def checkAll[R <: TestConfig, A, B, C, D, F, G, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G]
  )(
    test: (A, B, C, D, F, G) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(test.tupled)

  /**
   * Checks in parallel the effectual test passes for all values from the given
   * random variable. This is useful for deterministic `Gen` that
   * comprehensively explore all possibilities in a given domain.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, In](rv: Gen[R, A], parallelism: Int)(
    test: A => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkStreamPar(rv.sample.collectSome, parallelism)(a => checkConstructor(test(a)))

  /**
   * A version of `checkAllPar` that accepts two random variables.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B], parallelism: Int)(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts three random variables.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, B, C, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    parallelism: Int
  )(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts four random variables.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, B, C, D, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    parallelism: Int
  )(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts five random variables.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, B, C, D, F, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    parallelism: Int
  )(
    test: (A, B, C, D, F) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts six random variables.
   */
  def checkAllPar[R <: TestConfig, R1 <: R, E, A, B, C, D, F, G, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    parallelism: Int
  )(
    test: (A, B, C, D, F, G) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6, parallelism)(test.tupled)

  /**
   * Checks the test passes for the specified number of samples from the given
   * random variable.
   */
  def checkN(n: Int): CheckVariants.CheckN =
    new CheckVariants.CheckN(n)

  def sinkLayer(console: Console): ZLayer[Any, Nothing, ExecutionEventSink] =
    sinkLayerWithConsole(console)(Trace.empty)

  def sinkLayerWithConsole(console: Console)(implicit
    trace: Trace
  ): ZLayer[Any, Nothing, ExecutionEventSink] =
    TestLogger.fromConsole(
      console
    ) >>> ExecutionEventPrinter.live >>> TestOutput.live >>> ExecutionEventSink.live

  /**
   * A `Runner` that provides a default testable environment.
   */
  val defaultTestRunner: TestRunner[TestEnvironment, Any] = {
    implicit val trace = Trace.empty
    TestRunner(
      TestExecutor.default(
        Scope.default >>> testEnvironment,
        (Scope.default >+> testEnvironment) ++ ZIOAppArgs.empty,
        sinkLayer(Console.ConsoleLive),
        _ => ZIO.unit // There is no EventHandler available here, so we can't do much.
      )
    )
  }

  /**
   * Creates a failed test result with the specified runtime cause.
   */
  def failed[E](cause: Cause[E])(implicit trace: Trace): ZIO[Any, TestFailure[E], Nothing] =
    ZIO.fail(TestFailure.Runtime(cause))

  /**
   * Creates an ignored test result.
   */
  val ignored: UIO[TestSuccess] =
    ZIO.succeedNow(TestSuccess.Ignored())

  /**
   * Passes platform specific information to the specified function, which will
   * use that information to create a test. If the platform is neither ScalaJS
   * nor the JVM, an ignored test result will be returned.
   */
  def platformSpecific[R, E, A](js: => A, jvm: => A)(f: A => ZTest[R, E]): ZTest[R, E] =
    if (TestPlatform.isJS) f(js)
    else if (TestPlatform.isJVM) f(jvm)
    else ignored

  /**
   * Builds a suite containing a number of other specs.
   */
  def suite[In](label: String)(specs: In*)(implicit
    suiteConstructor: SuiteConstructor[In],
    trace: Trace
  ): Spec[suiteConstructor.OutEnvironment, suiteConstructor.OutError] =
    Spec.labeled(
      label,
      if (specs.isEmpty) Spec.empty
      else if (specs.length == 1) {
        wrapIfLabelledCase(specs.head)
      } else Spec.multiple(Chunk.fromIterable(specs).map(spec => suiteConstructor(spec)))
    )

  // Ensures we render suite label when we have an individual Labeled test case
  private def wrapIfLabelledCase[In](spec: In)(implicit suiteConstructor: SuiteConstructor[In], trace: Trace) =
    spec match {
      case Spec(LabeledCase(_, _)) =>
        Spec.multiple(Chunk(suiteConstructor(spec)))
      case _ => suiteConstructor(spec)
    }

  /**
   * Builds a spec with a single test.
   */
  def test[In](label: String)(assertion: => In)(implicit
    testConstructor: TestConstructor[Nothing, In],
    trace: Trace
  ): testConstructor.Out =
    testConstructor(label)(assertion)

  /**
   * Passes version specific information to the specified function, which will
   * use that information to create a test. If the version is neither Scala 3
   * nor Scala 2, an ignored test result will be returned.
   */
  def versionSpecific[R, E, A](scala3: => A, scala2: => A)(f: A => ZTest[R, E]): ZTest[R, E] =
    if (TestVersion.isScala3) f(scala3)
    else if (TestVersion.isScala2) f(scala2)
    else ignored

  object CheckVariants {

    final class CheckN(private val n: Int) extends AnyVal {
      def apply[R <: TestConfig, A, In](rv: Gen[R, A])(test: A => In)(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkStream(rv.sample.forever.collectSome.take(n.toLong))(a => checkConstructor(test(a)))
      def apply[R <: TestConfig, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
        test: (A, B) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2)(test.tupled)
      def apply[R <: TestConfig, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
        test: (A, B, C) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3)(test.tupled)
      def apply[R <: TestConfig, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
        test: (A, B, C, D) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)
      def apply[R <: TestConfig, A, B, C, D, F, In](
        rv1: Gen[R, A],
        rv2: Gen[R, B],
        rv3: Gen[R, C],
        rv4: Gen[R, D],
        rv5: Gen[R, F]
      )(
        test: (A, B, C, D, F) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(test.tupled)
      def apply[R <: TestConfig, A, B, C, D, F, G, In](
        rv1: Gen[R, A],
        rv2: Gen[R, B],
        rv3: Gen[R, C],
        rv4: Gen[R, D],
        rv5: Gen[R, F],
        rv6: Gen[R, G]
      )(
        test: (A, B, C, D, F, G) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(test.tupled)
    }
  }

  private def checkStream[R, R1 <: R, E, A](stream: ZStream[R, Nothing, Sample[R, A]])(
    test: A => ZIO[R1, E, TestResult]
  )(implicit trace: Trace): ZIO[R1 with TestConfig, E, TestResult] =
    TestConfig.shrinks.flatMap {
      shrinkStream {
        stream.zipWithIndex.mapZIO { case (initial, index) =>
          initial.foreach(input =>
            test(input)
              .map(_.setGenFailureDetails(GenFailureDetails(initial.value, input, index)))
              .either
          )
        }
      }
    }

  private def shrinkStream[R, R1 <: R, E, A](
    stream: ZStream[R1, Nothing, Sample[R1, Either[E, TestResult]]]
  )(maxShrinks: Int)(implicit trace: Trace): ZIO[R1 with TestConfig, E, TestResult] =
    stream
      .dropWhile(!_.value.fold(_ => true, _.isFailure)) // Drop until we get to a failure
      .take(1)                                          // Get the first failure
      .flatMap(_.shrinkSearch(_.fold(_ => true, _.isFailure)).take(maxShrinks.toLong + 1))
      .run(ZSink.collectAll[Either[E, TestResult]]) // Collect all the shrunken values
      .flatMap { shrinks =>
        // Get the "last" failure, the smallest according to the shrinker:
        shrinks
          .filter(_.fold(_ => true, _.isFailure))
          .lastOption
          .fold[ZIO[R, E, TestResult]](
            ZIO.succeedNow(assertCompletes)
          )(ZIO.fromEither(_))
      }

  private def checkStreamPar[R, R1 <: R, E, A](stream: ZStream[R, Nothing, Sample[R, A]], parallelism: Int)(
    test: A => ZIO[R1, E, TestResult]
  )(implicit trace: Trace): ZIO[R1 with TestConfig, E, TestResult] =
    TestConfig.shrinks.flatMap {
      shrinkStream {
        stream.zipWithIndex
          .mapZIOPar(parallelism) { case (initial, index) =>
            initial.foreach { input =>
              test(input)
                .map(_.setGenFailureDetails(GenFailureDetails(initial.value, input, index)))
                .either
            // convert test failures to failures to terminate parallel tests on first failure
            }.flatMap(sample => sample.value.fold(_ => ZIO.fail(sample), _ => ZIO.succeed(sample)))
          // move failures back into success channel for shrinking logic
          }
          .catchAll(ZStream.succeed(_))
      }
    }

  private[test] def flatMapStream[R, R1 <: R, A, B](
    stream: ZStream[R, Nothing, Option[A]]
  )(f: A => ZStream[R1, Nothing, Option[B]])(implicit trace: Trace): ZStream[R1, Nothing, Option[B]] =
    new ZStream(
      stream
        .rechunk(1)
        .channel
        .concatMapWithCustom[R1, Any, Any, Any, Nothing, Chunk[Either[Boolean, B]], Any, Any](as =>
          as.map {
            case Some(a) =>
              f(a)
                .rechunk(1)
                .map {
                  case None    => Left(true)
                  case Some(a) => Right(a)
                }
                .channel
            case None =>
              ZStream(Left(false)).channel
          }.fold(ZChannel.unit)(_ *> _)
        )(
          g = (_, _) => (),
          h = (_, _) => (),
          onPull = (request: UpstreamPullRequest[Chunk[Option[A]]]) =>
            request match {
              case UpstreamPullRequest.Pulled(chunk) =>
                chunk.headOption.flatten match {
                  case Some(_) => UpstreamPullStrategy.PullAfterNext(None)
                  case None =>
                    UpstreamPullStrategy.PullAfterAllEnqueued(None)
                }
              case UpstreamPullRequest.NoUpstream(activeDownstreamCount) =>
                UpstreamPullStrategy.PullAfterAllEnqueued[Chunk[Either[Boolean, B]]](
                  if (activeDownstreamCount > 0)
                    Some(Chunk(Left(false)))
                  else
                    None
                )
            },
          onEmit = (chunk: Chunk[Either[Boolean, B]]) => {
            chunk.headOption match {
              case Some(Left(true)) => ChildExecutorDecision.Yield
              case _                => ChildExecutorDecision.Continue
            }
          }
        )
    ).filter(_ != Left(true)).map {
      case Left(_)      => None
      case Right(value) => Some(value)
    }

  /**
   * An implementation of `ZStream#merge` that supports breadth first search.
   */
  private[test] def mergeStream[R, A](
    left: ZStream[R, Nothing, Option[A]],
    right: ZStream[R, Nothing, Option[A]]
  )(implicit trace: Trace): ZStream[R, Nothing, Option[A]] =
    flatMapStream(ZStream(Some(left), Some(right)))(identity)

  implicit final class TestLensOptionOps[A](private val self: TestLens[Option[A]]) extends AnyVal {

    /**
     * Transforms an [[scala.Option]] to its `Some` value `A`, otherwise fails
     * if it is a `None`.
     */
    def some: TestLens[A] = throw SmartAssertionExtensionError()
  }

  implicit final class TestLensEitherOps[E, A](private val self: TestLens[Either[E, A]]) extends AnyVal {

    /**
     * Transforms an [[scala.Either]] to its [[scala.Left]] value `E`, otherwise
     * fails if it is a [[scala.Right]].
     */
    def left: TestLens[E] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[scala.Either]] to its [[scala.Right]] value `A`,
     * otherwise fails if it is a [[scala.Left]].
     */
    def right: TestLens[A] = throw SmartAssertionExtensionError()
  }

  implicit final class TestLensExitOps[E, A](private val self: TestLens[Exit[E, A]]) extends AnyVal {

    /**
     * Transforms an [[Exit]] to a [[scala.Throwable]] if it is a `die`,
     * otherwise fails.
     */
    def die: TestLens[Throwable] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[Exit]] to its failure type (`E`) if it is a `fail`,
     * otherwise fails.
     */
    def failure: TestLens[E] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[Exit]] to its success type (`A`) if it is a `succeed`,
     * otherwise fails.
     */
    def success: TestLens[A] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[Exit]] to its underlying [[Cause]] if it has one,
     * otherwise fails.
     */
    def cause: TestLens[Cause[E]] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[Exit]] to a boolean value representing whether or not it
     * was interrupted.
     */
    def interrupted: TestLens[Boolean] = throw SmartAssertionExtensionError()
  }

  implicit final class TestLensCauseOps[E](private val self: TestLens[Cause[E]]) extends AnyVal {

    /**
     * Transforms a [[Cause]] to a [[scala.Throwable]] if it is a `die`,
     * otherwise fails.
     */
    def die: TestLens[Throwable] = throw SmartAssertionExtensionError()

    /**
     * Transforms a [[Cause]] to its failure type (`E`) if it is a `fail`,
     * otherwise fails.
     */
    def failure: TestLens[E] = throw SmartAssertionExtensionError()

    /**
     * Transforms a [[Cause]] to a boolean value representing whether or not it
     * was interrupted.
     */
    def interrupted: TestLens[Boolean] = throw SmartAssertionExtensionError()
  }

  implicit final class TestLensAnyOps[A](private val self: TestLens[A]) extends AnyVal {

    /**
     * Always returns true as long the chain of preceding transformations has
     * succeeded.
     *
     * {{{
     *   val option: Either[Int, Option[String]] = Right(Some("Cool"))
     *   assertTrue(option.is(_.right.some.anything)) // returns true
     *   assertTrue(option.is(_.left.anything)) // will fail because of `.left`.
     * }}}
     */
    def anything: TestLens[Boolean] = throw SmartAssertionExtensionError()

    /**
     * Transforms a value of some type into the given `Subtype` if possible,
     * otherwise fails.
     *
     * {{{
     *   sealed trait CustomError
     *   case class Explosion(blastRadius: Int) extends CustomError
     *   case class Melting(degrees: Double) extends CustomError
     *   case class Fulminating(wow: Boolean) extends CustomError
     *
     *   val error: CustomError = Melting(100)
     *   assertTrue(option.is(_.subtype[Melting]).degrees > 10) // succeeds
     *   assertTrue(option.is(_.subtype[Explosion]).blastRadius == 12) // fails
     * }}}
     */
    def subtype[Subtype <: A]: TestLens[Subtype] = throw SmartAssertionExtensionError()

    /**
     * Transforms a value with the given [[CustomAssertion]]
     */
    def custom[B](customAssertion: CustomAssertion[A, B]): TestLens[B] = {
      val _ = customAssertion
      throw SmartAssertionExtensionError()
    }
  }

  implicit final class SmartAssertionOps[A](private val self: A) extends AnyVal {

    /**
     * This extension method can only be called inside of the `assertTrue`
     * method. It will transform the value using the given [[TestLens]].
     *
     * {{{
     *   val option: Either[Int, Option[String]] = Right(Some("Cool"))
     *   assertTrue(option.is(_.right.some) == "Cool") // returns true
     *   assertTrue(option.is(_.left) < 100) // will fail because of `.left`.
     * }}}
     */
    def is[B](f: TestLens[A] => TestLens[B]): B = {
      val _ = f
      throw SmartAssertionExtensionError()
    }
  }

  implicit final class TestResultZIOOps[R, E](private val self: ZIO[R, E, TestResult]) extends AnyVal {
    def &&[R1 <: R, E1 >: E](that: => ZIO[R1, E1, TestResult])(implicit trace: Trace): ZIO[R1, E1, TestResult] =
      self.zipWith(that)(_ && _)
    def ||[R1 <: R, E1 >: E](that: => ZIO[R1, E1, TestResult])(implicit trace: Trace): ZIO[R1, E1, TestResult] =
      self.zipWith(that)(_ || _)
  }
}
