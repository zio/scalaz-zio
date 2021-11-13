/*
 * Copyright 2019-2021 John A. De Goes and the ZIO Contributors
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
import zio.test.{AbstractRunnableSpec, Summary, TestArgs, ZIOSpecAbstract, sbt}
import zio.{Chunk, Exit, Runtime, UIO, ZServiceBuilder, ZIO, ZIOAppArgs}

import scala.collection.mutable

sealed abstract class ZTestRunner(
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
      summaries.map(_.summary).filter(_.nonEmpty).flatMap(s => colored(s) :: "\n" :: Nil).mkString("", "", "Done")
  }

  def tasks(defs: Array[TaskDef]): Array[Task] =
    defs.map(ZTestTask(_, testClassLoader, runnerType, sendSummary, TestArgs.parse(args)))

  override def receiveMessage(summary: String): Option[String] = {
    SummaryProtocol.deserialize(summary).foreach(s => summaries += s)

    None
  }

  override def serializeTask(task: Task, serializer: TaskDef => String): String =
    serializer(task.taskDef())

  override def deserializeTask(task: String, deserializer: String => TaskDef): Task =
    ZTestTask(deserializer(task), testClassLoader, runnerType, sendSummary, TestArgs.parse(args))
}

final class ZMasterTestRunner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends ZTestRunner(args, remoteArgs, testClassLoader, "master") {

  //This implementation seems to be used when there's only single spec to run
  override val sendSummary: SendSummary = SendSummary.fromSend { summary =>
    summaries += summary
    ()
  }

}

final class ZSlaveTestRunner(
  args: Array[String],
  remoteArgs: Array[String],
  testClassLoader: ClassLoader,
  val sendSummary: SendSummary
) extends ZTestRunner(args, remoteArgs, testClassLoader, "slave") {}

sealed class ZTestTask(
  taskDef: TaskDef,
  testClassLoader: ClassLoader,
  runnerType: String,
  sendSummary: SendSummary,
  testArgs: TestArgs,
  spec: NewOrLegacySpec
) extends BaseTestTask(taskDef, testClassLoader, sendSummary, testArgs, spec) {

  def execute(eventHandler: EventHandler, loggers: Array[Logger], continuation: Array[Task] => Unit): Unit =
    spec match {
      case NewSpecWrapper(zioSpec) =>
        Runtime((), zioSpec.runtime.runtimeConfig).unsafeRunAsyncWith {
          zioSpec.run
            .provideServices(ZServiceBuilder.succeed(ZIOAppArgs(Chunk.empty)) ++ zio.ZEnv.live)
            .onError(e => UIO(println(e.prettyPrint)))
        } { exit =>
          exit match {
            case Exit.Failure(cause) => Console.err.println(s"$runnerType failed: " + cause.prettyPrint)
            case _                   =>
          }
          continuation(Array())
        }
      case LegacySpecWrapper(abstractRunnableSpec) =>
        Runtime((), abstractRunnableSpec.runtimeConfig).unsafeRunAsyncWith {
          run(eventHandler, abstractRunnableSpec).toManaged
            .provideServices(sbtTestServiceBuilder(loggers))
            .useDiscard(ZIO.unit)
        } { exit =>
          exit match {
            case Exit.Failure(cause) => Console.err.println(s"$runnerType failed: " + cause.prettyPrint)
            case _                   =>
          }
          continuation(Array())
        }
    }
}
object ZTestTask {
  def apply(
    taskDef: TaskDef,
    testClassLoader: ClassLoader,
    runnerType: String,
    sendSummary: SendSummary,
    args: TestArgs
  ): ZTestTask =
    disectTask(taskDef, testClassLoader) match {
      case NewSpecWrapper(zioSpec) =>
        new ZTestTaskNew(taskDef, testClassLoader, runnerType, sendSummary, args, zioSpec)
      case LegacySpecWrapper(abstractRunnableSpec) =>
        new ZTestTaskLegacy(taskDef, testClassLoader, runnerType, sendSummary, args, abstractRunnableSpec)
    }

  def disectTask(taskDef: TaskDef, testClassLoader: ClassLoader): NewOrLegacySpec = {
    import org.portablescala.reflect._
    val fqn = taskDef.fullyQualifiedName().stripSuffix("$") + "$"
    val module =
      Reflect
        .lookupLoadableModuleClass(fqn, testClassLoader)
        .getOrElse(throw new ClassNotFoundException("failed to load object: " + fqn))
        .loadModule()
    module match {
      case specAbstract: ZIOSpecAbstract => sbt.NewSpecWrapper(specAbstract)
      case _ =>
        sbt.LegacySpecWrapper(
          module
            .asInstanceOf[AbstractRunnableSpec]
        )
    }

  }
}

final class ZTestTaskLegacy(
  taskDef: TaskDef,
  testClassLoader: ClassLoader,
  runnerType: String,
  sendSummary: SendSummary,
  testArgs: TestArgs,
  spec: AbstractRunnableSpec
) extends ZTestTask(taskDef, testClassLoader, runnerType, sendSummary, testArgs, sbt.LegacySpecWrapper(spec))

final class ZTestTaskNew(
  taskDef: TaskDef,
  testClassLoader: ClassLoader,
  runnerType: String,
  sendSummary: SendSummary,
  testArgs: TestArgs,
  val newSpec: ZIOSpecAbstract
) extends ZTestTask(taskDef, testClassLoader, runnerType, sendSummary, testArgs, sbt.NewSpecWrapper(newSpec))
