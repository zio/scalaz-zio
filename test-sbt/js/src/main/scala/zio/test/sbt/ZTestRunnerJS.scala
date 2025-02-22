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

package zio.test.sbt

import sbt.testing._
import zio.test.{FilteredSpec, Summary, TestArgs, TestEnvironment, TestLogger, ZIOSpecAbstract}
import zio.{Exit, Layer, Runtime, Scope, Trace, Unsafe, ZEnvironment, ZIO, ZIOAppArgs, ZLayer}

import scala.collection.mutable

sealed abstract class ZTestRunnerJS(
  val args: Array[String],
  val remoteArgs: Array[String],
  testClassLoader: ClassLoader,
  runnerType: String
) extends Runner {
  def sendSummary: SendSummary

  val summaries: mutable.Buffer[Summary] = mutable.Buffer.empty

  def done(): String = {
    val total  = summaries.map(_.total).sum
    val ignore = summaries.map(_.ignore).sum

    if (summaries.isEmpty || total == ignore)
      s"${Console.YELLOW}No tests were executed${Console.RESET}"
    else
      summaries
        .map(_.failureDetails)
        .filter(_.nonEmpty)
        .flatMap(s => colored(s) :: "\n" :: Nil)
        .mkString("", "", "Done")
  }

  def tasks(defs: Array[TaskDef]): Array[Task] =
    defs.map(ZTestTask(_, testClassLoader, runnerType, sendSummary, TestArgs.parse(args)))

  override def receiveMessage(summary: String): Option[String] = {
    SummaryProtocol.deserialize(summary).foreach(s => summaries += s)

    None
  }

  override def serializeTask(task: Task, serializer: TaskDef => String): String =
    serializer(task.taskDef())

  // This is what prevents us from utilizing merged Specs.
  // When we try to round trip, we only deserialize the first task, so all the others
  // that were merged in are lost.
  override def deserializeTask(task: String, deserializer: String => TaskDef): Task =
    ZTestTask(deserializer(task), testClassLoader, runnerType, sendSummary, TestArgs.parse(args))
}

final class ZMasterTestRunnerJS(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends ZTestRunnerJS(args, remoteArgs, testClassLoader, "master") {

  // This implementation seems to be used when there's only single spec to run
  override val sendSummary: SendSummary = SendSummary.fromSend { summary =>
    summaries += summary
    ()
  }

}

final class ZSlaveTestRunnerJS(
  args: Array[String],
  remoteArgs: Array[String],
  testClassLoader: ClassLoader,
  val sendSummary: SendSummary
) extends ZTestRunnerJS(args, remoteArgs, testClassLoader, "slave") {}

sealed class ZTestTask(
  taskDef: TaskDef,
  testClassLoader: ClassLoader,
  runnerType: String,
  sendSummary: SendSummary,
  testArgs: TestArgs,
  spec: ZIOSpecAbstract
) extends BaseTestTask(
      taskDef,
      testClassLoader,
      sendSummary,
      testArgs,
      spec,
      Runtime.default,
      zio.Console.ConsoleLive
    ) {

  def execute(eventHandler: EventHandler, loggers: Array[Logger], continuation: Array[Task] => Unit): Unit = {
    val fiber = Runtime.default.unsafe.fork {
      val logic =
        ZIO.consoleWith { console =>
          (for {
            summary <- spec
                         .runSpecAsApp(FilteredSpec(spec.spec, args), args, console)
            _ <- sendSummary.provide(ZLayer.succeed(summary))
            // TODO Confirm if/how these events needs to be handled in #6481
            //    Check XML behavior
            _ <- ZIO.when(summary.status == Summary.Failure) {
                   ZIO.attempt(
                     eventHandler.handle(
                       ZTestEvent(
                         taskDef.fullyQualifiedName(),
                         // taskDef.selectors() is "one to many" so we can expect nonEmpty here
                         taskDef.selectors().head,
                         Status.Failure,
                         None,
                         0L,
                         ZioSpecFingerprint
                       )
                     )
                   )
                 }
          } yield ())
            .provideLayer(
              sharedFilledTestLayer +!+ (Scope.default >>> spec.bootstrap)
            )
        }
      logic
    }(Trace.empty, Unsafe.unsafe)
    fiber.unsafe.addObserver { exit =>
      exit match {
        case Exit.Failure(cause) => Console.err.println(s"$runnerType failed. $cause")
        case _                   =>
      }
      continuation(Array())
    }(Unsafe.unsafe)
  }
}
object ZTestTask {
  def apply(
    taskDef: TaskDef,
    testClassLoader: ClassLoader,
    runnerType: String,
    sendSummary: SendSummary,
    args: TestArgs
  ): ZTestTask = {
    val zioSpec = disectTask(taskDef, testClassLoader)
    new ZTestTask(taskDef, testClassLoader, runnerType, sendSummary, args, zioSpec)
  }

  private def disectTask(taskDef: TaskDef, testClassLoader: ClassLoader): ZIOSpecAbstract = {
    import org.portablescala.reflect._
    val fqn = taskDef.fullyQualifiedName().stripSuffix("$") + "$"
    Reflect
      .lookupLoadableModuleClass(fqn, testClassLoader)
      .getOrElse(throw new ClassNotFoundException("failed to load object: " + fqn))
      .loadModule()
      .asInstanceOf[ZIOSpecAbstract]
  }
}
