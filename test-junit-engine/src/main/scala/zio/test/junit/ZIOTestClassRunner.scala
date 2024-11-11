/*
 * Copyright 2024-2024 Vincent Raman and the ZIO Contributors
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

package zio.test.junit

import org.junit.platform.engine.{EngineExecutionListener, TestDescriptor, TestExecutionResult}
import zio.test.render.ConsoleRenderer
import zio.test.render.ExecutionResult.ResultType.Test
import zio.test.render.ExecutionResult.Status.Failed
import zio.test.render.LogLine.Message
import zio.test._
import zio._

import scala.jdk.OptionConverters._

/**
 * The `ZIOTestClassRunner` is responsible for running ZIO tests within a test
 * class and reporting their results using the given JUnit 5
 * `EngineExecutionListener`.
 *
 * @param descriptor
 *   The ZIO test class JUnit 5 descriptor
 */
class ZIOTestClassRunner(descriptor: ZIOTestClassDescriptor) {
  private val spec = descriptor.spec

  def run(notifier: EngineExecutionListener): IO[Any, Summary] = {
    def instrumentedSpec[R, E](zspec: Spec[R, E]) = {
      def loop(
        spec: Spec[R, E],
        description: TestDescriptor,
        path: Vector[String] = Vector.empty
      ): Spec.SpecCase[R, E, Spec[R, E]] =
        spec.caseValue match {
          case Spec.ExecCase(exec, spec: Spec[R, E]) => Spec.ExecCase(exec, Spec(loop(spec, description, path)))
          case Spec.LabeledCase(label, spec) =>
            Spec.LabeledCase(label, Spec(loop(spec, description, path :+ label)))
          case Spec.ScopedCase(scoped) =>
            Spec.ScopedCase[R, E, Spec[R, E]](scoped.map(spec => Spec(loop(spec, description, path))))
          case Spec.MultipleCase(specs) =>
            val uniqueId =
              description.getUniqueId.append(ZIOSuiteTestDescriptor.segmentType, path.lastOption.getOrElse(""))
            descriptor
              .findByUniqueId(uniqueId)
              .toScala
              .map(suiteDescription => Spec.MultipleCase(specs.map(spec => Spec(loop(spec, suiteDescription, path)))))
              .getOrElse(
                // filtered out
                Spec.MultipleCase(Chunk.empty)
              )
          case Spec.TestCase(test, annotations) =>
            val uniqueId = description.getUniqueId.append(ZIOTestDescriptor.segmentType, path.lastOption.getOrElse(""))
            descriptor
              .findByUniqueId(uniqueId)
              .toScala
              .map(_ => Spec.TestCase(test, annotations))
              .getOrElse(
                // filtered out
                Spec.TestCase(ZIO.succeed(TestSuccess.Ignored()), annotations)
              )
        }

      Spec(loop(zspec, descriptor))
    }

    def testDescriptorFromReversedLabel(labelsReversed: List[String]): Option[TestDescriptor] = {
      val uniqueId = labelsReversed.reverse.zipWithIndex.foldLeft(descriptor.getUniqueId) {
        case (uid, (label, labelIdx)) if labelIdx == labelsReversed.length - 1 =>
          uid.append(ZIOTestDescriptor.segmentType, label)
        case (uid, (label, _)) => uid.append(ZIOSuiteTestDescriptor.segmentType, label)
      }
      descriptor.findByUniqueId(uniqueId).toScala
    }

    def notifyTestFailure(testDescriptor: TestDescriptor, failure: TestFailure[_]): Unit = failure match {
      case TestFailure.Assertion(result, _) =>
        notifier.executionFinished(
          testDescriptor,
          TestExecutionResult.failed(
            new TestFailed(renderToString(renderFailureDetails(testDescriptor, result)))
          )
        )
      case TestFailure.Runtime(cause, _) =>
        notifier.executionFinished(
          testDescriptor,
          TestExecutionResult.failed(cause.squashWith {
            case t: Throwable => t
            case _            => new TestFailed(renderToString(ConsoleRenderer.renderCause(cause, 0)))
          })
        )
    }

    val instrumented: Spec[spec.Environment with TestEnvironment with Scope, Any] = instrumentedSpec(spec.spec)

    val eventHandler: ZTestEventHandler = {
      case ExecutionEvent.TestStarted(labelsReversed, _, _, _, _) =>
        ZIO.succeed(testDescriptorFromReversedLabel(labelsReversed).foreach(notifier.executionStarted))
      case ExecutionEvent.Test(labelsReversed, test, _, _, _, _, _) =>
        ZIO.succeed(testDescriptorFromReversedLabel(labelsReversed).foreach { testDescriptor =>
          test match {
            case Left(failure: TestFailure[_]) =>
              notifyTestFailure(testDescriptor, failure)
            case Right(TestSuccess.Succeeded(_)) =>
              notifier.executionFinished(testDescriptor, TestExecutionResult.successful())
            case Right(TestSuccess.Ignored(_)) =>
              notifier.executionSkipped(testDescriptor, "Test skipped")
          }
        })
      case ExecutionEvent.RuntimeFailure(_, labelsReversed, failure, _) =>
        ZIO.succeed(testDescriptorFromReversedLabel(labelsReversed).foreach(notifyTestFailure(_, failure)))
      case _ => ZIO.unit // unhandled events linked to the suite level
    }

    spec
      .runSpecAsApp(instrumented, TestArgs.empty, Console.ConsoleLive, eventHandler)
      .provide(
        Scope.default >>> (liveEnvironment >>> TestEnvironment.live ++ ZLayer.environment[Scope]),
        spec.bootstrap
      )
  }

  private def renderFailureDetails(descriptor: TestDescriptor, result: TestResult): Message =
    Message(
      ConsoleRenderer
        .rendered(
          Test,
          descriptor.getDisplayName,
          Failed,
          0,
          ConsoleRenderer.renderAssertionResult(result.result, 0).lines: _*
        )
        .streamingLines
    )

  private def renderToString(message: Message) =
    message.lines
      .map(_.fragments.map(_.text).fold("")(_ + _))
      .mkString("\n")
}
