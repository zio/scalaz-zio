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

package zio

import zio.internal.stacktracer.{SourceLocation, Tracer}
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.{ZChannel, ZSink, ZStream}
import zio.test.ReporterEventRenderer.ConsoleEventRenderer
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
 *
 *   object MyTest extends ZIOSpecDefault {
 *     def spec = suite("clock")(
 *       test("time is non-zero") {
 *         for {
 *           time <- Live.live(nanoTime)
 *         } yield assertTrue(time >= 0L)
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
        ((Live.default ++ Annotations.live) >>> TestConsole.debug) ++
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

  private def testFiberRefGen(implicit trace: Trace): ULayer[Unit] =
    ZLayer.scoped(FiberRef.currentFiberIdGenerator.locallyScoped(FiberId.Gen.Monotonic))

  val testEnvironment: ZLayer[Any, Nothing, TestEnvironment] = {
    implicit val trace = Tracer.newTrace
    liveEnvironment >>> (TestEnvironment.live ++ testFiberRefGen)
  }

  /**
   * Provides an effect with the "real" environment as opposed to the test
   * environment. This is useful for performing effects such as timing out
   * tests, accessing the real time, or printing to the real console.
   */
  def live[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    Live.live(zio)

  /**
   * Retrieves the `TestClock` service for this test.
   */
  def testClock(implicit trace: Trace): UIO[TestClock] =
    testClockWith(ZIO.succeed(_))

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
    testConsoleWith(ZIO.succeed(_))

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
    testRandomWith(ZIO.succeed(_))

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
    testSystemWith(ZIO.succeed(_))

  /**
   * Retrieves the `TestSystem` service for this test and uses it to run the
   * specified workflow.
   */
  def testSystemWith[R, E, A](f: TestSystem => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    DefaultServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestSystem]].get))

  /**
   * Retrieves the `Annotations` service for this test.
   */
  def annotations(implicit trace: Trace): UIO[Annotations] =
    annotationsWith(ZIO.succeed(_))

  /**
   * Retrieves the `Annotations` service for this test and uses it to run the
   * specified workflow.
   */
  def annotationsWith[R, E, A](f: Annotations => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    TestServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[Annotations]].get))

  /**
   * Retrieves the `Live` service for this test.
   */
  def live(implicit trace: Trace): UIO[Live] =
    liveWith(ZIO.succeed(_))

  /**
   * Retrieves the `Live` service for this test and uses it to run the specified
   * workflow.
   */
  def liveWith[R, E, A](f: Live => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    TestServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[Live]].get))

  /**
   * Retrieves the `Sized` service for this test.
   */
  def sized(implicit trace: Trace): UIO[Sized] =
    sizedWith(ZIO.succeed(_))

  /**
   * Retrieves the `Sized` service for this test and uses it to run the
   * specified workflow.
   */
  def sizedWith[R, E, A](f: Sized => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    TestServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[Sized]].get))

  /**
   * Retrieves the `TestConfig` service for this test.
   */
  def testConfig(implicit trace: Trace): UIO[TestConfig] =
    testConfigWith(ZIO.succeed(_))

  /**
   * Retrieves the `TestConfig` service for this test and uses it to run the
   * specified workflow.
   */
  def testConfigWith[R, E, A](f: TestConfig => ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    TestServices.currentServices.getWith(services => f(services.asInstanceOf[ZEnvironment[TestConfig]].get))

  /**
   * Executes the specified workflow with the specified implementation of the
   * annotations service.
   */
  def withAnnotations[R, E, A <: Annotations, B](annotations: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    TestServices.currentServices.locallyWith(_.add(annotations))(zio)

  /**
   * Sets the implementation of the annotations service to the specified value
   * and restores it to its original value when the scope is closed.
   */
  def withAnnotationsScoped[A <: Annotations](
    annotations: => A
  )(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    TestServices.currentServices.locallyScopedWith(_.add(annotations))

  /**
   * Executes the specified workflow with the specified implementation of the
   * config service.
   */
  def withTestConfig[R, E, A <: TestConfig, B](testConfig: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    TestServices.currentServices.locallyWith(_.add(testConfig))(zio)

  /**
   * Sets the implementation of the config service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withTestConfigScoped[A <: TestConfig](
    testConfig: => A
  )(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    TestServices.currentServices.locallyScopedWith(_.add(testConfig))

  /**
   * Executes the specified workflow with the specified implementation of the
   * sized service.
   */
  def withSized[R, E, A <: Sized, B](sized: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    TestServices.currentServices.locallyWith(_.add(sized))(zio)

  /**
   * Sets the implementation of the sized service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withSizedScoped[A <: Sized](sized: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    TestServices.currentServices.locallyScopedWith(_.add(sized))

  /**
   * Executes the specified workflow with the specified implementation of the
   * live service.
   */
  def withLive[R, E, A <: Live, B](live: => A)(
    zio: => ZIO[R, E, B]
  )(implicit tag: Tag[A], trace: Trace): ZIO[R, E, B] =
    TestServices.currentServices.locallyWith(_.add(live))(zio)

  /**
   * Sets the implementation of the live service to the specified value and
   * restores it to its original value when the scope is closed.
   */
  def withLiveScoped[A <: Live](live: => A)(implicit tag: Tag[A], trace: Trace): ZIO[Scope, Nothing, Unit] =
    TestServices.currentServices.locallyScopedWith(_.add(live))

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
  def withLive[R, E, E1, A, B](
    zio: ZIO[R, E, A]
  )(f: ZIO[R, E, A] => ZIO[R, E1, B])(implicit trace: Trace): ZIO[R, E1, B] =
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
      for {
        promise <- Promise.make[TestFailure[E], TestSuccess]
        child <- ZIO
                   .suspendSucceed(assertion)
                   .foldCauseZIO(
                     cause =>
                       cause.dieOption match {
                         case Some(TestResult.Exit(assert)) => ZIO.fail(TestFailure.Assertion(assert))
                         case _                             => ZIO.fail(TestFailure.Runtime(cause))
                       },
                     assert =>
                       if (assert.isFailure)
                         ZIO.fail(TestFailure.Assertion(assert))
                       else
                         ZIO.succeed(TestSuccess.Succeeded())
                   )
                   .intoPromise(promise)
                   .forkDaemon
        result <- promise.await
        _      <- child.inheritAll
        _ <- ZIO.whenDiscard(child.isAlive()) {
               for {
                 fiber <- ZIO.logWarning {
                            val quotedLabel = "\"" + label + "\""
                            s"Warning: ZIO Test is attempting to interrupt fiber " +
                              s"${child.id} forked in test $quotedLabel due to automatic, " +
                              "supervision, but interruption has taken more than 10 " +
                              "seconds to complete. This may indicate a resource leak. " +
                              "Make sure you are not forking a fiber in an " +
                              "uninterruptible region."
                          }
                            .delay(10.seconds)
                            .withClock(Clock.ClockLive)
                            .interruptible
                            .forkDaemon
                            .onExecutor(Runtime.defaultExecutor)
                 _ <- (child.interrupt *> fiber.interrupt).forkDaemon.onExecutor(Runtime.defaultExecutor)
               } yield ()
             }
      } yield result
  }

  private[zio] def assertImpl[A](
    value: => A,
    codeString: Option[String] = None,
    assertionString: Option[String] = None
  )(assertion: Assertion[A])(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
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
  )(implicit trace: Trace, sourceLocation: SourceLocation): ZIO[R, E, TestResult] =
    effect.map { value =>
      assertImpl(value, codeString, assertionString)(assertion)
    }

  /**
   * Asserts that the given test was completed.
   */
  def assertCompletes(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    assertImpl(true)(Assertion.isTrue)

  /**
   * Asserts that the given test was completed.
   */
  def assertCompletesZIO(implicit trace: Trace, sourceLocation: SourceLocation): UIO[TestResult] =
    ZIO.succeed(assertCompletes)

  /**
   * Asserts that the given test was never completed.
   */
  def assertNever(message: String)(implicit trace: Trace, sourceLocation: SourceLocation): TestResult =
    assertImpl(true)(Assertion.equalTo(false)) ?? message

  /**
   * Checks the test passes for "sufficient" numbers of samples from the given
   * random variable.
   */
  def check[R <: ZAny, A, In](rv: Gen[R, A])(test: A => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    TestConfig.samples.flatMap(n => checkStream(rv.sample.forever.take(n.toLong))(a => checkConstructor(test(a))))

  /**
   * A version of `check` that accepts two random variables.
   */
  def check[R <: ZAny, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `check` that accepts three random variables.
   */
  def check[R <: ZAny, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3)(test.tupled)

  /**
   * A version of `check` that accepts four random variables.
   */
  def check[R <: ZAny, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)

  /**
   * A version of `check` that accepts five random variables.
   */
  def check[R <: ZAny, A, B, C, D, F, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(test.tupled)

  /**
   * A version of `check` that accepts six random variables.
   */
  def check[R <: ZAny, A, B, C, D, F, G, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(test.tupled)

  /**
   * A version of `check` that accepts seven random variables.
   */
  def check[R <: ZAny, A, B, C, D, F, G, H, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H]
  )(
    test: (A, B, C, D, F, G, H) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7)(test.tupled)

  /**
   * A version of `check` that accepts eight random variables.
   */
  def check[R <: ZAny, A, B, C, D, F, G, H, I, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    rv8: Gen[R, I]
  )(
    test: (A, B, C, D, F, G, H, I) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    check(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7 <*> rv8)(test.tupled)

  /**
   * Checks the test passes for all values from the given finite, deterministic
   * generator. For non-deterministic or infinite generators use `check` or
   * `checkN`.
   */
  def checkAll[R <: ZAny, A, In](rv: Gen[R, A])(test: A => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkStream(rv.sample)(a => checkConstructor(test(a)))

  /**
   * A version of `checkAll` that accepts two random variables.
   */
  def checkAll[R <: ZAny, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2)(test.tupled)

  /**
   * A version of `checkAll` that accepts three random variables.
   */
  def checkAll[R <: ZAny, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3)(test.tupled)

  /**
   * A version of `checkAll` that accepts four random variables.
   */
  def checkAll[R <: ZAny, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)

  /**
   * A version of `checkAll` that accepts five random variables.
   */
  def checkAll[R <: ZAny, A, B, C, D, F, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F]
  )(
    test: (A, B, C, D, F) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5)(test.tupled)

  /**
   * A version of `checkAll` that accepts six random variables.
   */
  def checkAll[R <: ZAny, A, B, C, D, F, G, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6)(test.tupled)

  /**
   * A version of `checkAll` that accepts seven random variables.
   */
  def checkAll[R <: ZAny, A, B, C, D, F, G, H, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H]
  )(
    test: (A, B, C, D, F, G, H) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7)(test.tupled)

  /**
   * A version of `checkAll` that accepts eight random variables.
   */
  def checkAll[R <: ZAny, E, A, B, C, D, F, G, H, I, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    rv8: Gen[R, I]
  )(
    test: (A, B, C, D, F, G, H, I) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAll(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7 <*> rv8)(test.tupled)

  /**
   * Checks in parallel the effectual test passes for all values from the given
   * random variable. This is useful for deterministic `Gen` that
   * comprehensively explore all possibilities in a given domain.
   */
  def checkAllPar[R <: ZAny, E, A, In](rv: Gen[R, A], parallelism: Int)(
    test: A => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkStreamPar(rv.sample, parallelism)(a => checkConstructor(test(a)))

  /**
   * A version of `checkAllPar` that accepts two random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B], parallelism: Int)(
    test: (A, B) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts three random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    parallelism: Int
  )(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts four random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, D, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    parallelism: Int
  )(
    test: (A, B, C, D) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts five random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, D, F, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts six random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, D, F, G, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts six random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, D, F, G, H, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    parallelism: Int
  )(
    test: (A, B, C, D, F, G, H) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7, parallelism)(test.tupled)

  /**
   * A version of `checkAllPar` that accepts six random variables.
   */
  def checkAllPar[R <: ZAny, E, A, B, C, D, F, G, H, I, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    rv8: Gen[R, I],
    parallelism: Int
  )(
    test: (A, B, C, D, F, G, H, I) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkAllPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7 <*> rv8, parallelism)(test.tupled)

  /**
   * Checks the test passes for the specified number of samples from the given
   * random variable.
   */
  def checkN(n: Int): CheckVariants.CheckN =
    new CheckVariants.CheckN(n)

  /**
   * Checks in parallel the test passes for "sufficient" numbers of samples from
   * the given random variable.
   */
  def checkPar[R <: ZAny, A, In](rv: Gen[R, A], parallelism: Int)(test: A => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    TestConfig.samples.flatMap(n =>
      checkStreamPar(rv.sample.forever.take(n.toLong), parallelism)(a => checkConstructor(test(a)))
    )

  /**
   * A version of `checkPar` that accepts two random variables.
   */
  def checkPar[R <: ZAny, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B], parallelism: Int)(test: (A, B) => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts three random variables.
   */
  def checkPar[R <: ZAny, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], parallelism: Int)(
    test: (A, B, C) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts four random variables.
   */
  def checkPar[R <: ZAny, A, B, C, D, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    parallelism: Int
  )(test: (A, B, C, D) => In)(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3 <*> rv4, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts five random variables.
   */
  def checkPar[R <: ZAny, A, B, C, D, F, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts six random variables.
   */
  def checkPar[R <: ZAny, A, B, C, D, F, G, In](
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
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts seven random variables.
   */
  def checkPar[R <: ZAny, A, B, C, D, F, G, H, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    parallelism: Int
  )(
    test: (A, B, C, D, F, G, H) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7, parallelism)(test.tupled)

  /**
   * A version of `checkPar` that accepts eight random variables.
   */
  def checkPar[R <: ZAny, A, B, C, D, F, G, H, I, In](
    rv1: Gen[R, A],
    rv2: Gen[R, B],
    rv3: Gen[R, C],
    rv4: Gen[R, D],
    rv5: Gen[R, F],
    rv6: Gen[R, G],
    rv7: Gen[R, H],
    rv8: Gen[R, I],
    parallelism: Int
  )(
    test: (A, B, C, D, F, G, H, I) => In
  )(implicit
    checkConstructor: CheckConstructor[R, In],
    sourceLocation: SourceLocation,
    trace: Trace
  ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
    checkPar(rv1 <*> rv2 <*> rv3 <*> rv4 <*> rv5 <*> rv6 <*> rv7 <*> rv8, parallelism)(test.tupled)

  /**
   * A `Runner` that provides a default testable environment.
   */
  lazy val defaultTestRunner: TestRunner[TestEnvironment, Any] = {
    implicit val trace = Trace.empty
    TestRunner(
      TestExecutor.default(
        testEnvironment,
        Scope.default ++ testEnvironment,
        ExecutionEventSink.live(Console.ConsoleLive, ConsoleEventRenderer),
        ZTestEventHandler.silent // The default test runner handles its own events, writing their output to the provided sink.
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
    ZIO.succeed(TestSuccess.Ignored())(Trace.empty)

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
    sourceLocation: SourceLocation,
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
    sourceLocation: SourceLocation,
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
      def apply[R <: ZAny, A, In](rv: Gen[R, A])(test: A => In)(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkStream(rv.sample.forever.take(n.toLong))(a => checkConstructor(test(a)))
      def apply[R <: ZAny, A, B, In](rv1: Gen[R, A], rv2: Gen[R, B])(
        test: (A, B) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2)(test.tupled)
      def apply[R <: ZAny, A, B, C, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C])(
        test: (A, B, C) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3)(test.tupled)
      def apply[R <: ZAny, A, B, C, D, In](rv1: Gen[R, A], rv2: Gen[R, B], rv3: Gen[R, C], rv4: Gen[R, D])(
        test: (A, B, C, D) => In
      )(implicit
        checkConstructor: CheckConstructor[R, In],
        trace: Trace
      ): ZIO[checkConstructor.OutEnvironment, checkConstructor.OutError, TestResult] =
        checkN(n)(rv1 <*> rv2 <*> rv3 <*> rv4)(test.tupled)
      def apply[R <: ZAny, A, B, C, D, F, In](
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
      def apply[R <: ZAny, A, B, C, D, F, G, In](
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
  )(implicit trace: Trace): ZIO[R1, E, TestResult] =
    testConfigWith { testConfig =>
      val flag = Ref.unsafe.make(false)(Unsafe.unsafe)
      warningEmptyGen(flag) *> shrinkStream {
        stream.zipWithIndex.mapZIO { case (initial, index) =>
          flag.set(true) *> initial.foreach(input =>
            (test(input) @@ testConfig.checkAspect)
              .map(_.setGenFailureDetails(GenFailureDetails(initial.value, input, index)))
              .either
          )
        }
      }(testConfig.shrinks)
    }

  private def shrinkStream[R, R1 <: R, E, A](
    stream: ZStream[R1, Nothing, Sample[R1, Either[E, TestResult]]]
  )(maxShrinks: Int)(implicit trace: Trace): ZIO[R1, E, TestResult] =
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
            ZIO.succeed(assertCompletes)
          )(ZIO.fromEither(_))
      }

  private def checkStreamPar[R, R1 <: R, E, A](stream: ZStream[R, Nothing, Sample[R, A]], parallelism: Int)(
    test: A => ZIO[R1, E, TestResult]
  )(implicit trace: Trace): ZIO[R1, E, TestResult] =
    testConfigWith { testConfig =>
      shrinkStream {
        stream.zipWithIndex
          .mapZIOPar(parallelism) { case (initial, index) =>
            initial.foreach { input =>
              (test(input) @@ testConfig.checkAspect)
                .map(_.setGenFailureDetails(GenFailureDetails(initial.value, input, index)))
                .either
              // convert test failures to failures to terminate parallel tests on first failure
            }.flatMap(sample => sample.value.fold(_ => ZIO.fail(sample), _ => ZIO.succeed(sample)))
          // move failures back into success channel for shrinking logic
          }
          .catchAll(ZStream.succeed(_))
      }(testConfig.shrinks)
    }

  private def warningEmptyGen(flag: Ref[Boolean])(implicit trace: Trace): UIO[Unit] =
    Live
      .live(ZIO.logWarning(warning).unlessZIODiscard(flag.get).delay(5.seconds))
      .interruptible
      .fork
      .onExecutor(Runtime.defaultExecutor)
      .unit

  private val warning =
    "Warning: A check / checkAll / checkN generator did not produce any test cases, " +
      "which may result in the test hanging. Ensure that the provided generator is producing values."

  implicit final class TestLensOptionOps[A](private val self: TestLens[Option[A]]) extends AnyVal {

    /**
     * Transforms an [[scala.Option]] to its `Some` value `A`, otherwise fails
     * if it is a `None`.
     */
    def some: TestLens[A] = throw SmartAssertionExtensionError()
  }

  implicit final class TestLensTryOps[A](private val self: TestLens[scala.util.Try[A]]) extends AnyVal {

    /**
     * Transforms an [[scala.util.Try]] to its [[scala.util.Success]] value `A`,
     * otherwise fails if it is a [[scala.util.Failure]].
     */
    def success: TestLens[A] = throw SmartAssertionExtensionError()

    /**
     * Transforms an [[scala.util.Try]] to a [[scala.Throwable]] if it is a
     * [[scala.util.Failure]], otherwise fails.
     */
    def failure: TestLens[Throwable] = throw SmartAssertionExtensionError()
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
