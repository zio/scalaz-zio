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

import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.SortedSet
import zio.test.TestAspectPoly
import zio.System.env
import zio.test.TestAspectAtLeastR

/**
 * A `TestAspect` is an aspect that can be weaved into specs. You can think of
 * an aspect as a polymorphic function, capable of transforming one test into
 * another, possibly enlarging the environment or error type.
 */
abstract class TestAspect[+LowerR, -UpperR, +LowerE, -UpperE] { self =>

  /**
   * Applies the aspect to some tests in the spec, chosen by the provided
   * predicate.
   */
  def some[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E]

  /**
   * An alias for [[all]].
   */
  final def apply[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(implicit
    trace: Trace
  ): Spec[R, E] =
    all(spec)

  /**
   * Applies the aspect to every test in the spec.
   */
  final def all[R >: LowerR <: UpperR, E >: LowerE <: UpperE](spec: Spec[R, E])(implicit
    trace: Trace
  ): Spec[R, E] =
    some[R, E](spec)

  /**
   * Returns a new aspect that represents the sequential composition of this
   * aspect with the specified one.
   */
  final def >>>[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
    that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
  ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
    new TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] {
      def some[R >: LowerR1 <: UpperR1, E >: LowerE1 <: UpperE1](
        spec: Spec[R, E]
      )(implicit trace: Trace): Spec[R, E] =
        that.some(self.some(spec))
    }

  /**
   * Returns a new aspect that represents the sequential composition of this
   * aspect with the specified one.
   */
  final def @@[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
    that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
  ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
    self >>> that

  final def andThen[LowerR1 >: LowerR, UpperR1 <: UpperR, LowerE1 >: LowerE, UpperE1 <: UpperE](
    that: TestAspect[LowerR1, UpperR1, LowerE1, UpperE1]
  ): TestAspect[LowerR1, UpperR1, LowerE1, UpperE1] =
    self >>> that
}
object TestAspect extends TimeoutVariants {

  type CheckAspect = ZIOAspect[Nothing, Any, Nothing, Any, TestResult, TestResult]

  /**
   * An aspect that returns the tests unchanged
   */
  val identity: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec
    }

  /**
   * An aspect that marks tests as ignored.
   */
  val ignore: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.when(false)
    }

  /**
   * Constructs an aspect that runs the specified effect after every test.
   */
  def after[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test.exit
          .zipWith(effect.catchAllCause(cause => ZIO.fail(TestFailure.Runtime(cause))).exit)(_ <* _)
          .unexit
    }

  /**
   * Constructs an aspect that runs the specified effect after all tests.
   */
  def afterAll[R0](effect: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, Nothing, Any] =
    aroundAll(ZIO.unit, effect)

  /**
   * Constructs an aspect that runs the specified effect after all tests if
   * there is at least one failure.
   */
  def afterAllFailure[R0](f: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, Nothing, Any] =
    new TestAspect[Nothing, R0, Nothing, Any] {
      def some[R <: R0, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        Spec.scoped[R](
          Ref.make(false).flatMap { failure =>
            ZIO.acquireRelease(ZIO.unit)(_ => f.whenZIO(failure.get)).as(afterFailure(failure.set(true))(spec))
          }
        )
    }

  /**
   * Constructs an aspect that runs the specified effect after all tests if
   * there are no failures.
   */
  def afterAllSuccess[R0](f: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, Nothing, Any] =
    new TestAspect[Nothing, R0, Nothing, Any] {
      def some[R <: R0, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        Spec.scoped[R](
          Ref.make(true).flatMap { success =>
            ZIO.acquireRelease(ZIO.unit)(_ => f.whenZIO(success.get)).as(afterFailure(success.set(false))(spec))
          }
        )
    }

  /**
   * Constructs an aspect that runs the specified effect after every failed
   * test.
   */
  def afterFailure[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test.tapErrorCause(_ => effect.catchAllCause(cause => ZIO.fail(TestFailure.Runtime(cause))))
    }

  /**
   * Constructs an aspect that runs the specified effect after every successful
   * test.
   */
  def afterSuccess[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test.tap(_ => effect.catchAllCause(cause => ZIO.fail(TestFailure.Runtime(cause))))
    }

  /**
   * Annotates tests with the specified test annotation.
   */
  def annotate[V](key: TestAnnotation[V], value: V): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.annotate(key, value)
    }

  /**
   * Constructs an aspect that evaluates every test between two effects,
   * `before` and `after`, where the result of `before` can be used in `after`.
   */
  def aroundWith[R0, E0, A0](
    before: ZIO[R0, E0, A0]
  )(after: A0 => ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        ZIO.acquireReleaseWith(before.catchAllCause(c => ZIO.fail(TestFailure.Runtime(c))))(after)(_ => test)
    }

  /**
   * A less powerful variant of `around` where the result of `before` is not
   * required by after.
   */
  def around[R0, E0](before: ZIO[R0, E0, Any], after: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    aroundWith(before)(_ => after)

  /**
   * Constructs an aspect that evaluates all tests between two effects, `before`
   * and `after`, where the result of `before` can be used in `after`.
   */
  def aroundAllWith[R0, E0, A0](
    before: ZIO[R0, E0, A0]
  )(after: A0 => ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect[Nothing, R0, E0, Any] {
      def some[R <: R0, E >: E0](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        Spec.scoped[R](
          ZIO.acquireRelease(before)(after).mapError(TestFailure.fail).as(spec)
        )
    }

  /**
   * A less powerful variant of `aroundAll` where the result of `before` is not
   * required by `after`.
   */
  def aroundAll[R0, E0](before: ZIO[R0, E0, Any], after: ZIO[R0, Nothing, Any]): TestAspect[Nothing, R0, E0, Any] =
    aroundAllWith(before)(_ => after)

  /**
   * Constructs an aspect that evaluates every test inside the context of the
   * scoped function.
   */
  def aroundTest[R0, E0](
    scoped: ZIO[Scope with R0, TestFailure[E0], TestSuccess => ZIO[R0, TestFailure[E0], TestSuccess]]
  ): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        ZIO.scoped[R](scoped.flatMap(f => test.flatMap(f)))
    }

  /**
   * Constructs a simple monomorphic aspect that only works with the specified
   * environment and error type.
   */
  def aspect[R0, E0](
    f: ZIO[R0, TestFailure[E0], TestSuccess] => ZIO[R0, TestFailure[E0], TestSuccess]
  ): TestAspect[R0, R0, E0, E0] =
    new TestAspect.PerTest[R0, R0, E0, E0] {
      def perTest[R >: R0 <: R0, E >: E0 <: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        f(test)
    }

  /**
   * Constructs an aspect that runs the specified effect before every test.
   */
  def before[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        effect.catchAllCause(cause => ZIO.fail(TestFailure.failCause(cause))) *> test
    }

  /**
   * Constructs an aspect that runs the specified effect a single time before
   * all tests.
   */
  def beforeAll[R0, E0](effect: ZIO[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    aroundAll(effect, ZIO.unit)

  /**
   * An aspect that runs each test on the blocking threadpool. Useful for tests
   * that contain blocking code
   */
  val blocking: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        ZIO.blocking(test)
    }

  /**
   * An aspect that applies the provided zio aspect to each sample of all checks
   * in the test.
   *
   * i.e.
   * {{{
   * test("example") {
   *   check(Gen.int) { i =>
   *     ZIO.succeed(assert(i, Assertion.equalTo(1)))
   *   }
   * } @@ checks(ZIOAspect.debug)
   * }}}
   *
   * is equivalent to
   *
   * {{{
   * test("example") {
   *   check(Gen.int) { i =>
   *     ZIO.succeed(assert(i, Assertion.equalTo(1))) @@ ZIOAspect.debug
   *   }
   * }
   * }}}
   */
  def checks(aspect: CheckAspect): TestAspectPoly = checksZIO(
    ZIO.succeed(aspect)(Trace.empty)
  )

  /**
   * An aspect that applies the provided zio aspect to each sample of all checks
   * in the test. The aspect will be constructed from the provided effect before
   * each test is run.
   */
  def checksZIO[R, E](
    makeAspect: ZIO[R, E, CheckAspect]
  ): TestAspect[Nothing, R, E, Any] =
    new TestAspect[Nothing, R, E, Any] {
      def some[R1 <: R, E1 >: E](spec: Spec[R1, E1])(implicit trace: Trace): Spec[R1, E1] =
        spec.transform[R1, E1] {
          case Spec.TestCase(oldTest, annotations) =>
            val newTest = makeAspect.mapError(TestFailure.fail).flatMap { aspect =>
              testConfigWith { oldConfig =>
                val newConfig = TestConfig.TestV2(
                  repeats = oldConfig.repeats,
                  retries = oldConfig.retries,
                  samples = oldConfig.samples,
                  shrinks = oldConfig.shrinks,
                  checkAspect = oldConfig.checkAspect >>> aspect
                )
                withTestConfig(newConfig)(oldTest)
              }
            }
            Spec.TestCase(newTest, annotations)
          case c => c
        }
    }

  /**
   * An aspect that runs each test on a separate fiber and prints a fiber dump
   * if the test fails or has not terminated within the specified duration.
   */
  def diagnose(duration: Duration): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](
        spec: Spec[R, E]
      )(implicit trace: Trace): Spec[R, E] = {
        def diagnose[R, E](
          label: String,
          test: ZIO[R, TestFailure[E], TestSuccess]
        ): ZIO[R, TestFailure[E], TestSuccess] =
          test.fork.flatMap { fiber =>
            fiber.join.raceWith[R, TestFailure[E], TestFailure[E], Unit, TestSuccess](Live.live(ZIO.sleep(duration)))(
              (exit, sleepFiber) => dump(label).when(!exit.isSuccess) *> sleepFiber.interrupt *> exit,
              (_, _) => dump(label) *> fiber.join
            )
          }

        def dump[E, A](label: String): UIO[Unit] =
          Annotations.supervisedFibers.flatMap { fibers =>
            Live.live(ZIO.foreachDiscard(fibers) { fiber =>
              for {
                dump <- fiber.dump
                str  <- dump.prettyPrint
                _    <- Console.printLine(str).orDie
              } yield ()
            })
          }

        spec.transform[R, E] {
          case Spec.TestCase(test, annotations) => Spec.TestCase(diagnose("", test), annotations)
          case c                                => c
        }
      }
    }

  /**
   * An aspect that runs each test with the `TestConsole` instance in the
   * environment set to debug mode so that console output is rendered to
   * standard output in addition to being written to the output buffer.
   */
  val debug: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        TestConsole.debug(test)
    }

  /**
   * An aspect that retries a test until success, without limit.
   */
  val eventually: TestAspectPoly = {
    val eventually = new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        test.eventually
    }
    restoreTestEnvironment >>> eventually
  }

  /**
   * An aspect that runs tests on all platforms except ScalaJS.
   */
  val exceptJS: TestAspectPoly =
    if (TestPlatform.isJS) ignore else identity

  /**
   * An aspect that that applies an aspect on all platforms except ScalaJS.
   */
  def exceptJS[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJS) identity else that

  /**
   * An aspect that runs tests on all platforms except the JVM.
   */
  val exceptJVM: TestAspectPoly =
    if (TestPlatform.isJVM) ignore else identity

  /**
   * An aspect that that applies an aspect on all platforms except the JVM.
   */
  def exceptJVM[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJVM) identity else that

  /**
   * An aspect that runs tests on all platforms except ScalaNative.
   */
  val exceptNative: TestAspectPoly =
    if (TestPlatform.isNative) ignore else identity

  /**
   * An aspect that that applies an aspect on all platforms except ScalaNative.
   */
  def exceptNative[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isNative) identity else that

  /**
   * An aspect that runs tests on all versions except Scala 2.
   */
  val exceptScala2: TestAspectPoly =
    if (TestVersion.isScala2) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.12.
   */
  val exceptScala212: TestAspectPoly =
    if (TestVersion.isScala212) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 2.13.
   */
  val exceptScala213: TestAspectPoly =
    if (TestVersion.isScala213) ignore else identity

  /**
   * An aspect that runs tests on all versions except Scala 3.
   */
  val exceptScala3: TestAspectPoly =
    if (TestVersion.isScala3) ignore else identity

  /**
   * An aspect that sets suites to the specified execution strategy, but only if
   * their current strategy is inherited (undefined).
   */
  def executionStrategy(exec: ExecutionStrategy): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        Spec.exec(exec, spec)
    }

  /**
   * An aspect that makes a test that failed for any reason pass. Note that if
   * the test passes this aspect will make it fail.
   */
  val failing: TestAspectPoly =
    failing(_ => true)

  /**
   * An aspect that makes a test that failed for the specified failure pass.
   * Note that the test will fail for other failures and also if it passes
   * correctly.
   */
  def failing[E0](assertion: TestFailure[E0] => Boolean): TestAspect[Nothing, Any, Nothing, E0] =
    new TestAspect.PerTest[Nothing, Any, Nothing, E0] {
      def perTest[R, E <: E0](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        test.foldZIO(
          failure =>
            if (assertion(failure)) ZIO.succeed(TestSuccess.Succeeded())
            else ZIO.fail(TestFailure.die(new RuntimeException("did not fail as expected"))),
          _ => ZIO.fail(TestFailure.die(new RuntimeException("did not fail as expected")))
        )
    }

  /**
   * An aspect that records the state of fibers spawned by the current test in
   * [[TestAnnotation.fibers]]. Applied by default in [[ZIOSpecAbstract]]. This
   * aspect is required for the proper functioning of `TestClock.adjust`.
   */
  lazy val fibers: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] = {
        val acquire = ZIO.succeed(new AtomicReference(SortedSet.empty[Fiber.Runtime[Any, Any]])).tap { ref =>
          Annotations.annotate(TestAnnotation.fibers, Right(Chunk(ref)))
        }
        val release = Annotations.get(TestAnnotation.fibers).flatMap {
          case Right(refs) =>
            ZIO
              .foreach(refs)(ref => ZIO.succeed(ref.get))
              .map(_.foldLeft(SortedSet.empty[Fiber.Runtime[Any, Any]])(_ ++ _).size)
              .tap { n =>
                Annotations.annotate(TestAnnotation.fibers, Left(n))
              }
          case Left(_) => ZIO.unit
        }
        ZIO.acquireReleaseWith(acquire)(_ => release) { ref =>
          Supervisor.fibersIn(ref).flatMap(supervisor => test.supervised(supervisor))
        }
      }
    }

  /**
   * An aspect that retries a test until success, with a default limit, for use
   * with flaky tests.
   */
  val flaky: TestAspectPoly = {
    val flaky = new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        TestConfig.retries.flatMap { n =>
          test.catchAll(_ => test.tapError(_ => Annotations.annotate(TestAnnotation.retried, 1)).retryN(n - 1))
        }
    }
    restoreTestEnvironment >>> flaky
  }

  /**
   * An aspect that retries a test until success, with the specified limit, for
   * use with flaky tests.
   */
  def flaky(n: Int): TestAspectPoly = {
    val flaky = new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test.catchAll(_ => test.tapError(_ => Annotations.annotate(TestAnnotation.retried, 1)).retryN(n - 1))
    }
    restoreTestEnvironment >>> flaky
  }

  /**
   * An aspect that runs each test on its own separate fiber.
   */
  val forked: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        test.fork.flatMap(_.join)
    }

  /**
   * An aspect that provides each test with the specified layer that does not
   * produce any services.
   */
  def fromLayer[R0, E0](layer: ZLayer[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect[Nothing, R0, E0, Any] {
      def some[R <: R0, E >: E0](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.provideSomeLayer[R](layer)
    }

  /**
   * An aspect that provides all tests with a shared version of the specified
   * layer that does not produce any services.
   */
  def fromLayerShared[R0, E0](layer: ZLayer[R0, E0, Any]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect[Nothing, R0, E0, Any] {
      def some[R <: R0, E >: E0](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.provideSomeLayerShared[R](layer)
    }

  /**
   * As aspect that runs each test with the specified `ZIOAspect`.
   */
  def fromZIOAspect[LowerR, UpperR, LowerE, UpperE](
    zioAspect: ZIOAspect[LowerR, UpperR, TestFailure[LowerE], TestFailure[UpperE], TestSuccess, TestSuccess]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    new TestAspect.PerTest[LowerR, UpperR, LowerE, UpperE] {
      def perTest[R >: LowerR <: UpperR, E >: LowerE <: UpperE](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        zioAspect(test)
    }

  /**
   * An aspect that only runs a test if the specified environment variable
   * satisfies the specified assertion.
   */
  def ifEnv(env: String)(assertion: String => Boolean): TestAspectPoly =
    ifEnvOption(env)(_.fold(false)(assertion))

  /**
   * An aspect that only runs a test if the specified environment variable is
   * not set.
   */
  def ifEnvNotSet(env: String): TestAspectPoly =
    ifEnvOption(env)(_.isEmpty)

  /**
   * An aspect that only runs a test if the specified optional environment
   * variable satisfies the specified assertion.
   */
  def ifEnvOption(env: String)(assertion: Option[String] => Boolean): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.whenZIO(Live.live(System.env(env)).orDie.map(assertion))
    }

  /**
   * An aspect that only runs a test if the specified environment variable is
   * set.
   */
  def ifEnvSet(env: String): TestAspectPoly =
    ifEnvOption(env)(_.nonEmpty)

  /**
   * An aspect that only runs a test if the specified Java property satisfies
   * the specified assertion.
   */
  def ifProp(prop: String)(assertion: String => Boolean): TestAspectPoly =
    ifPropOption(prop)(_.fold(false)(assertion))

  /**
   * An aspect that only runs a test if the specified Java property is not set.
   */
  def ifPropNotSet(env: String): TestAspectPoly =
    ifPropOption(env)(_.isEmpty)

  /**
   * An aspect that only runs a test if the specified optional Java property
   * satisfies the specified assertion.
   */
  def ifPropOption(prop: String)(assertion: Option[String] => Boolean): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.whenZIO(Live.live(System.property(prop)).orDie.map(assertion))
    }

  /**
   * An aspect that only runs a test if the specified Java property is set.
   */
  def ifPropSet(prop: String): TestAspectPoly =
    ifPropOption(prop)(_.nonEmpty)

  /**
   * An aspect that applies the specified aspect on ScalaJS.
   */
  def js[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJS) that else identity

  /**
   * An aspect that only runs tests on ScalaJS.
   */
  val jsOnly: TestAspectPoly =
    if (TestPlatform.isJS) identity else ignore

  /**
   * An aspect that applies the specified aspect on the JVM.
   */
  def jvm[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isJVM) that else identity

  /**
   * An aspect that only runs tests on the JVM.
   */
  val jvmOnly: TestAspectPoly =
    if (TestPlatform.isJVM) identity else ignore

  /**
   * An aspect that runs only on operating systems accepted by the specified
   * predicate.
   */
  def os(f: System.OS => Boolean): TestAspectPoly =
    if (f(System.os)) identity else ignore

  /**
   * Runs only on Mac operating systems.
   */
  val mac: TestAspectPoly = os(_.isMac)

  /**
   * An aspect that applies the specified aspect on ScalaNative.
   */
  def native[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestPlatform.isNative) that else identity

  /**
   * An aspect that only runs tests on ScalaNative.
   */
  val nativeOnly: TestAspectPoly =
    if (TestPlatform.isNative) identity else ignore

  /**
   * An aspect that repeats the test a default number of times, ensuring it is
   * stable ("non-flaky"). Stops at the first failure.
   */
  val nonFlaky: TestAspectPoly = {
    val nonFlaky = new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        TestConfig.repeats.flatMap { n =>
          test *> test.tap(_ => Annotations.annotate(TestAnnotation.repeated, 1)).repeatN(n - 1)
        }
    }
    restoreTestEnvironment >>> nonFlaky
  }

  /**
   * An aspect that repeats the test a specified number of times, ensuring it is
   * stable ("non-flaky"). Stops at the first failure.
   */
  def nonFlaky(n: Int): TestAspectPoly = {
    val nonFlaky = new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test *> test.tap(_ => Annotations.annotate(TestAnnotation.repeated, 1)).repeatN(n - 1)
    }
    restoreTestEnvironment >>> nonFlaky
  }

  /**
   * Constructs an aspect that requires a test to not terminate within the
   * specified time.
   */
  def nonTermination(duration: Duration): TestAspectPoly =
    timeout(duration) >>>
      failing {
        case TestFailure.Assertion(_, _) => false
        case TestFailure.Runtime(cause, _) =>
          cause.dieOption match {
            case Some(t) => t.getMessage == s"Timeout of ${duration.render} exceeded."
            case None    => false
          }
      }

  /**
   * Sets the seed of the `TestRandom` instance in the environment to a random
   * value before each test.
   */
  val nondeterministic: TestAspectPoly =
    before(
      Live
        .live(Random.nextLong(Trace.empty))(Trace.empty)
        .flatMap(TestRandom.setSeed(_)(Trace.empty))(Trace.empty)
    )

  /**
   * An aspect that executes the members of a suite in parallel.
   */
  val parallel: TestAspectPoly =
    executionStrategy(ExecutionStrategy.Parallel)

  /**
   * An aspect that executes the members of a suite in parallel, up to the
   * specified number of concurrent fibers.
   */
  def parallelN(n: Int): TestAspectPoly =
    executionStrategy(ExecutionStrategy.ParallelN(n))

  /**
   * An aspect that repeats successful tests according to a schedule.
   */
  def repeat[R0](
    schedule: Schedule[R0, TestSuccess, Any]
  ): TestAspectAtLeastR[R0] = {
    val repeat = new PerTest.AtLeastR[R0] {
      def perTest[R <: R0, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] = {
        val repeatSchedule: Schedule[R0, TestSuccess, TestSuccess] =
          schedule
            .zipRight(Schedule.identity[TestSuccess])
            .tapOutput(_ => Annotations.annotate(TestAnnotation.repeated, 1))
        Live.withLive(test)(_.repeat(repeatSchedule))
      }
    }
    restoreTestEnvironment >>> repeat
  }

  /**
   * An aspect that runs each test with the number of times to repeat tests to
   * ensure they are stable set to the specified value.
   */
  def repeats(n: Int): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        testConfigWith { old =>
          val testConfig = TestConfig.TestV2(
            repeats = n,
            retries = old.retries,
            samples = old.samples,
            shrinks = old.shrinks,
            checkAspect = old.checkAspect
          )
          withTestConfig(testConfig)(test)
        }
    }

  /**
   * An aspect that restores a given [[zio.test.Restorable Restorable]]'s state
   * to its starting state after the test is run. Note that this is only useful
   * when repeating tests.
   */
  def restore(restorable: UIO[Restorable]): TestAspectPoly =
    aroundWith(restorable.flatMap(_.save(Trace.empty))(Trace.empty))(restore => restore)

  /**
   * An aspect that restores the [[zio.test.TestClock TestClock]]'s state to its
   * starting state after the test is run. Note that this is only useful when
   * repeating tests.
   */
  def restoreTestClock: TestAspectPoly =
    restore(testClock(Trace.empty))

  /**
   * An aspect that restores the [[zio.test.TestConsole TestConsole]]'s state to
   * its starting state after the test is run. Note that this is only useful
   * when repeating tests.
   */
  def restoreTestConsole: TestAspectPoly =
    restore(testConsole(Trace.empty))

  /**
   * An aspect that restores the [[zio.test.TestRandom TestRandom]]'s state to
   * its starting state after the test is run. Note that this is only useful
   * when repeating tests.
   */
  def restoreTestRandom: TestAspectPoly =
    restore(testRandom(Trace.empty))

  /**
   * An aspect that restores the [[zio.test.TestSystem TestSystem]]'s state to
   * its starting state after the test is run. Note that this is only useful
   * when repeating tests.
   */
  def restoreTestSystem: TestAspectPoly =
    restore(testSystem(Trace.empty))

  /**
   * An aspect that restores all state in the standard provided test
   * environments ([[zio.test.TestClock TestClock]],
   * [[zio.test.TestConsole TestConsole]], [[zio.test.TestRandom TestRandom]],
   * and [[zio.test.TestSystem TestSystem]]) to their starting state after the
   * test is run. Note that this is only useful when repeating tests.
   */
  def restoreTestEnvironment: TestAspectPoly =
    restoreTestClock >>> restoreTestConsole >>> restoreTestRandom >>> restoreTestSystem

  /**
   * An aspect that runs each test with the number of times to retry flaky tests
   * set to the specified value.
   */
  def retries(n: Int): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        testConfigWith { old =>
          val testConfig = TestConfig.TestV2(
            repeats = old.repeats,
            retries = n,
            samples = old.samples,
            shrinks = old.shrinks,
            checkAspect = old.checkAspect
          )
          withTestConfig(testConfig)(test)
        }
    }

  /**
   * An aspect that retries failed tests according to a schedule.
   */
  def retry[R0, E0](
    schedule: Schedule[R0, TestFailure[E0], Any]
  ): TestAspect[Nothing, R0, Nothing, E0] = {
    val retry = new TestAspect.PerTest[Nothing, R0, Nothing, E0] {
      def perTest[R <: R0, E <: E0](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] = {
        val retrySchedule: Schedule[R0, TestFailure[E0], Any] =
          schedule.tapOutput(_ => Annotations.annotate(TestAnnotation.retried, 1))
        Live.withLive(test)(_.retry(retrySchedule))
      }
    }
    restoreTestEnvironment >>> retry
  }

  /**
   * An aspect that runs each test with the number of sufficient samples to
   * check for a random variable set to the specified value.
   */
  def samples(n: Int): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        testConfigWith { old =>
          val testConfig = TestConfig.TestV2(
            repeats = old.repeats,
            retries = old.retries,
            samples = n,
            shrinks = old.shrinks,
            checkAspect = old.checkAspect
          )
          withTestConfig(testConfig)(test)
        }
    }

  /**
   * An aspect that executes the members of a suite sequentially.
   */
  val sequential: TestAspectPoly =
    executionStrategy(ExecutionStrategy.Sequential)

  /**
   * An aspect that applies the specified aspect on Scala 2.
   */
  def scala2[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala2) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 2.12.
   */
  def scala212[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala212) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 2.13.
   */
  def scala213[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala213) that else identity

  /**
   * An aspect that applies the specified aspect on Scala 3.
   */
  def scala3[LowerR, UpperR, LowerE, UpperE](
    that: TestAspect[LowerR, UpperR, LowerE, UpperE]
  ): TestAspect[LowerR, UpperR, LowerE, UpperE] =
    if (TestVersion.isScala3) that else identity

  /**
   * An aspect that only runs tests on Scala 2.
   */
  val scala2Only: TestAspectPoly =
    if (TestVersion.isScala2) identity else ignore

  /**
   * An aspect that only runs tests on Scala 2.12.
   */
  val scala212Only: TestAspectPoly =
    if (TestVersion.isScala212) identity else ignore

  /**
   * An aspect that only runs tests on Scala 2.13.
   */
  val scala213Only: TestAspectPoly =
    if (TestVersion.isScala213) identity else ignore

  /**
   * An aspect that only runs tests on Scala 3.
   */
  val scala3Only: TestAspectPoly =
    if (TestVersion.isScala3) identity else ignore

  /**
   * Sets the seed of the `TestRandom` instance in the environment to the
   * specified value before each test.
   */
  def setSeed(seed: => Long): TestAspectPoly =
    before(TestRandom.setSeed(seed)(Trace.empty))

  /**
   * An aspect that runs each test with the maximum number of shrinkings to
   * minimize large failures set to the specified value.
   */
  def shrinks(n: Int): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        testConfigWith { old =>
          val testConfig = TestConfig.TestV2(
            repeats = old.repeats,
            retries = old.retries,
            samples = old.samples,
            shrinks = n,
            checkAspect = old.checkAspect
          )
          withTestConfig(testConfig)(test)
        }
    }

  /**
   * An aspect that runs each test with the [[zio.test.TestConsole TestConsole]]
   * instance in the environment set to silent mode so that console output is
   * only written to the output buffer and not rendered to standard output.
   */
  val silent: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        TestConsole.silent(test)
    }

  /**
   * As aspect that runs each test with the default console logger removed so
   * that logs are only written to the output buffer and not rendered to
   * standard output.
   */
  val silentLogging: TestAspectPoly =
    TestAspect.fromLayer(Runtime.removeDefaultLoggers)

  /**
   * An aspect that runs each test with the size set to the specified value.
   */
  def size(n: Int): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        Sized.withSize(n)(test)
    }

  /**
   * An aspect that runs each test with the size set to the specified value.
   */
  @deprecated("use size", "2.0.2")
  def sized(n: Int): TestAspectPoly =
    size(n)

  /**
   * An aspect that converts ignored tests into test failures.
   */
  val success: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        test.flatMap {
          case TestSuccess.Ignored(_) =>
            ZIO.fail(TestFailure.Runtime(Cause.die(new RuntimeException("Test was ignored."))))
          case x => ZIO.succeed(x)
        }
    }

  /**
   * Annotates tests with string tags.
   */
  def tag(tag: String, tags: String*): TestAspectPoly =
    annotate(TestAnnotation.tagged, Set(tag) union tags.toSet)

  /**
   * Annotates tests with their execution times.
   */
  val timed: TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] =
        Live.withLive(test)(_.either.summarized(Clock.instant)(TestDuration.fromInterval)).flatMap {
          case (duration, result) =>
            ZIO.fromEither(result).ensuring(Annotations.annotate(TestAnnotation.timing, duration))
        }
    }

  /**
   * An aspect that times out tests using the specified duration.
   * @param duration
   *   maximum test duration
   */
  def timeout(
    duration: Duration
  ): TestAspectPoly =
    new PerTest.Poly {
      def perTest[R, E](
        test: ZIO[R, TestFailure[E], TestSuccess]
      )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess] = {
        def timeoutFailure =
          TestTimeoutException(s"Timeout of ${duration.render} exceeded.")
        Live
          .withLive(test)(_.either.disconnect.timeout(duration).flatMap {
            case None         => ZIO.fail(TestFailure.Runtime(Cause.die(timeoutFailure)))
            case Some(result) => ZIO.fromEither(result)
          })
      }
    }

  /**
   * Verifies the specified post-condition after each test is run.
   */
  def verify[R0, E0](condition: => ZIO[R0, E0, TestResult]): TestAspect[Nothing, R0, E0, Any] =
    new TestAspect.PerTest[Nothing, R0, E0, Any] {
      def perTest[R <: R0, E >: E0](test: ZIO[R, TestFailure[E], TestSuccess])(implicit
        trace: Trace
      ): ZIO[R, TestFailure[E], TestSuccess] =
        test <* ZTest("verify", condition)
    }

  /**
   * Runs only on Unix / Linux operating systems.
   */
  val unix: TestAspectPoly = os(_.isUnix)

  /**
   * Runs only on Windows operating systems.
   */
  val windows: TestAspectPoly = os(_.isWindows)

  /**
   * An aspect that runs tests with the specified config provider.
   */
  def withConfigProvider(configProvider: ConfigProvider): TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] =
        spec.provideSomeLayer[R](Runtime.setConfigProvider(configProvider))
    }

  /**
   * An aspect that runs tests with the live clock service.
   */
  lazy val withLiveClock: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] = {
        val layer = ZLayer.scoped {
          for {
            clock <- live(ZIO.clock)
            _     <- ZIO.withClockScoped(clock)
          } yield ()
        }
        spec.provideSomeLayer[R](layer)
      }
    }

  /**
   * An aspect that runs tests with the live console service.
   */
  lazy val withLiveConsole: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] = {
        val layer = ZLayer.scoped {
          for {
            console <- live(ZIO.console)
            _       <- ZIO.withConsoleScoped(console)
          } yield ()
        }
        spec.provideSomeLayer[R](layer)
      }
    }

  /**
   * An aspect that runs tests with the live default ZIO services.
   */
  lazy val withLiveEnvironment: TestAspectPoly =
    withLiveClock >>>
      withLiveConsole >>>
      withLiveRandom >>>
      withLiveSystem

  /**
   * An aspect that runs tests with the live random service.
   */
  lazy val withLiveRandom: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] = {
        val layer = ZLayer.scoped {
          for {
            random <- live(ZIO.random)
            _      <- ZIO.withRandomScoped(random)
          } yield ()
        }
        spec.provideSomeLayer[R](layer)
      }
    }

  /**
   * An aspect that runs tests with the live system service.
   */
  lazy val withLiveSystem: TestAspectPoly =
    new TestAspectPoly {
      def some[R, E](spec: Spec[R, E])(implicit trace: Trace): Spec[R, E] = {
        val layer = ZLayer.scoped {
          for {
            system <- live(ZIO.system)
            _      <- ZIO.withSystemScoped(system)
          } yield ()
        }
        spec.provideSomeLayer[R](layer)
      }
    }

  abstract class PerTest[+LowerR, -UpperR, +LowerE, -UpperE] extends TestAspect[LowerR, UpperR, LowerE, UpperE] {

    def perTest[R >: LowerR <: UpperR, E >: LowerE <: UpperE](
      test: ZIO[R, TestFailure[E], TestSuccess]
    )(implicit trace: Trace): ZIO[R, TestFailure[E], TestSuccess]

    final def some[R >: LowerR <: UpperR, E >: LowerE <: UpperE](
      spec: Spec[R, E]
    )(implicit trace: Trace): Spec[R, E] =
      spec.transform[R, E] {
        case Spec.TestCase(test, annotations) => Spec.TestCase(perTest(test), annotations)
        case c                                => c
      }
  }
  object PerTest {

    /**
     * A `PerTest.AtLeast[R]` is a `TestAspect.PerTest` that that requires at
     * least an `R` in its environment
     */
    type AtLeastR[R] = TestAspect.PerTest[Nothing, R, Nothing, Any]

    /**
     * A `PerTest.Poly` is a `TestAspect.PerTest` that is completely
     * polymorphic, having no requirements ZRTestEnv on error or environment.
     */
    type Poly = TestAspect.PerTest[Nothing, Any, Nothing, Any]
  }
}
