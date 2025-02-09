/*
 * Copyright 2021-2024 John A. De Goes and the ZIO Contributors
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

import org.portablescala.reflect.annotation.EnableReflectiveInstantiation
import zio._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.ConsoleRenderer

@EnableReflectiveInstantiation
abstract class ZIOSpecAbstract extends ZIOApp with ZIOSpecAbstractVersionSpecific {
  self =>

  def spec: Spec[Environment with TestEnvironment with Scope, Any]

  def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
    Chunk(TestAspect.fibers, TestAspect.timeoutWarning(60.seconds))

  def bootstrap: ZLayer[Any, Any, Environment]

  final def run: ZIO[Environment with ZIOAppArgs with Scope, Any, Summary] = {
    implicit val trace = Trace.empty

    runSpec.provideSomeLayer[Environment with ZIOAppArgs with Scope](
      ZLayer.environment[Environment with ZIOAppArgs with Scope] +!+
        (liveEnvironment >>> TestEnvironment.live +!+ TestLogger.fromConsole(Console.ConsoleLive))
    )
  }

  final def <>(that: ZIOSpecAbstract)(implicit trace: Trace): ZIOSpecAbstract =
    new ZIOSpecAbstract {
      type Environment = self.Environment with that.Environment

      def bootstrap: ZLayer[Any, Any, Environment] =
        self.bootstrap +!+ that.bootstrap

      def spec: Spec[Environment with TestEnvironment with Scope, Any] =
        self.aspects.foldLeft(self.spec)(_ @@ _) + that.aspects.foldLeft(that.spec)(_ @@ _)

      def environmentTag: EnvironmentTag[Environment] = {
        implicit val selfTag: EnvironmentTag[self.Environment] = self.environmentTag
        implicit val thatTag: EnvironmentTag[that.Environment] = that.environmentTag
        val _                                                  = (selfTag, thatTag)
        EnvironmentTag[Environment]
      }

      override def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
        Chunk.empty
    }

  protected final def runSpec(implicit trace: Trace): ZIO[
    Environment with TestEnvironment with ZIOAppArgs with Scope,
    Throwable,
    Summary
  ] =
    for {
      args    <- ZIO.service[ZIOAppArgs]
      console <- ZIO.console
      testArgs = TestArgs.parse(args.getArgs.toArray)
      summary <- runSpecAsApp(spec, testArgs, console)
      _ <- ZIO.when(testArgs.printSummary) {
             console.printLine(testArgs.testRenderer.renderSummary(summary)).orDie
           }
      _ <- ZIO
             .when(summary.status == Summary.Failure) {
               ZIO.fail(new RuntimeException("Tests failed."))
             }
             .unless(testArgs.ignoreFailures)
    } yield summary

  /*
   * Regardless of test assertion or runtime failures, this method will always return a summary
   * capturing this information
   */
  private[zio] def runSpecAsApp(
    spec: Spec[Environment with TestEnvironment with Scope, Any],
    testArgs: TestArgs,
    console: Console,
    testEventHandler: ZTestEventHandler = ZTestEventHandler.silent
  )(implicit
    trace: Trace
  ): URIO[
    Environment with TestEnvironment with Scope,
    Summary
  ] = {
    val filteredSpec: Spec[Environment with TestEnvironment with Scope, Any] = FilteredSpec(spec, testArgs)

    for {
      runtime <-
        ZIO.runtime[
          TestEnvironment with Scope
        ]

      scopeEnv: ZEnvironment[Scope] = runtime.environment
      perTestLayer = (ZLayer.succeedEnvironment(scopeEnv) ++ liveEnvironment) >>>
                       (TestEnvironment.live ++ ZLayer.environment[Scope])

      executionEventSinkLayer = ExecutionEventSink.live(console, testArgs.testEventRenderer)
      environment            <- ZIO.environment[Environment]
      runner =
        TestRunner(
          TestExecutor
            .default[Environment, Any](
              ZLayer.succeedEnvironment(environment),
              perTestLayer,
              executionEventSinkLayer,
              testEventHandler
            )
        )
      randomId <- ZIO.withRandom(Random.RandomLive)(Random.nextInt).map("test_case_" + _)
      summary <-
        runner.run(randomId, aspects.foldLeft(filteredSpec)(_ @@ _) @@ TestAspect.fibers)
    } yield summary
  }

  private[zio] def runSpecWithSharedRuntimeLayer(
    fullyQualifiedName: String,
    spec: Spec[Environment with TestEnvironment with Scope, Any],
    testArgs: TestArgs,
    runtime: Runtime[_],
    testEventHandler: ZTestEventHandler,
    console: Console
  )(implicit
    trace: Trace
  ): UIO[Summary] = {
    val filteredSpec = FilteredSpec(spec, testArgs)

    val castedRuntime: Runtime[Environment with TestOutput] =
      runtime.asInstanceOf[Runtime[Environment with TestOutput]]

    TestRunner(
      TestExecutor
        .default[Environment, Any](
          ZLayer.succeedEnvironment(castedRuntime.environment),
          testEnvironment ++ Scope.default,
          ZLayer.succeedEnvironment(castedRuntime.environment) >>> ExecutionEventSink.live,
          testEventHandler
        )
    ).run(fullyQualifiedName, aspects.foldLeft(filteredSpec)(_ @@ _) @@ TestAspect.fibers)
  }
}
