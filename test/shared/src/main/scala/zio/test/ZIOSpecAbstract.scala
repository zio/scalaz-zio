/*
 * Copyright 2021 John A. De Goes and the ZIO Contributors
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
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render._

@EnableReflectiveInstantiation
abstract class ZIOSpecAbstract extends ZIOApp { self =>

  def spec: ZSpec[Environment with TestEnvironment with ZIOAppArgs, Any]

  def aspects: Chunk[TestAspect.WithOut[
    Nothing,
    Environment with TestEnvironment with ZIOAppArgs,
    Nothing,
    Any,
    ({ type OutEnv[Env] = Env })#OutEnv,
    ({ type OutErr[Err] = Err })#OutErr
  ]] =
    Chunk.empty

  final def run: ZIO[ZEnv with ZIOAppArgs, Any, Any] = {
    implicit val trace = Tracer.newTrace
    runSpec.provideSomeServices[ZEnv with ZIOAppArgs](TestEnvironment.live ++ serviceBuilder)
  }

  final def <>(that: ZIOSpecAbstract)(implicit trace: ZTraceElement): ZIOSpecAbstract =
    new ZIOSpecAbstract {
      type Environment = self.Environment with that.Environment
      def serviceBuilder: ZServiceBuilder[ZIOAppArgs, Any, Environment] =
        self.serviceBuilder +!+ that.serviceBuilder
      override def runSpec: ZIO[Environment with TestEnvironment with ZIOAppArgs, Any, Any] =
        self.runSpec.zipPar(that.runSpec)
      def spec: ZSpec[Environment with TestEnvironment with ZIOAppArgs, Any] =
        self.spec + that.spec
      def tag: Tag[Environment] = {
        implicit val selfTag: Tag[self.Environment] = self.tag
        implicit val thatTag: Tag[that.Environment] = that.tag
        val _                                       = (selfTag, thatTag)
        Tag[Environment]
      }
    }

  protected def runSpec: ZIO[Environment with TestEnvironment with ZIOAppArgs, Any, Any] = {
    implicit val trace = Tracer.newTrace
    for {
      args         <- ZIO.service[ZIOAppArgs]
      testArgs      = TestArgs.parse(args.getArgs.toArray)
      executedSpec <- runSpec(spec, testArgs)
      hasFailures = executedSpec.exists {
                      case ExecutedSpec.TestCase(test, _) => test.isLeft
                      case _                              => false
                    }
      exitCode = if (hasFailures) 1 else 0
      _       <- doExit(exitCode)
    } yield ()
  }

  private def createTestReporter(rendererName: String)(implicit trace: ZTraceElement): TestReporter[Any] = {
    val renderer = rendererName match {
      case "intellij" => IntelliJRenderer
      case _          => TestRenderer.default
    }
    DefaultTestReporter(renderer, TestAnnotationRenderer.default)
  }

  private def doExit(exitCode: Int)(implicit trace: ZTraceElement): UIO[Unit] =
    ZIO.succeed(
      try if (!isAmmonite) sys.exit(exitCode)
      catch { case _: SecurityException => }
    )

  private def isAmmonite: Boolean =
    sys.env.exists { case (k, v) =>
      k.contains("JAVA_MAIN_CLASS") && v == "ammonite.Main"
    }

  private[zio] def runSpec(
    spec: ZSpec[Environment with TestEnvironment with ZIOAppArgs, Any],
    testArgs: TestArgs
  )(implicit trace: ZTraceElement): URIO[Environment with TestEnvironment with ZIOAppArgs, ExecutedSpec[Any]] = {
    val filteredSpec = FilteredSpec(spec, testArgs)

    for {
      env <- ZIO.environment[Environment with TestEnvironment with ZIOAppArgs]
      runner =
        TestRunner(
          TestExecutor.default[Environment with TestEnvironment with ZIOAppArgs, Any](
            ZServiceBuilder.succeedMany(env)
          )
        )
      testReporter = testArgs.testRenderer.fold(runner.reporter)(createTestReporter)
      results <-
        runner.withReporter(testReporter).run(aspects.foldLeft(filteredSpec)(_ @@ _)).provideServices(runner.bootstrap)
      _ <- TestLogger
             .logLine(SummaryBuilder.buildSummary(results).summary)
             .when(testArgs.printSummary)
             .provideServices(runner.bootstrap)
    } yield results
  }
}
