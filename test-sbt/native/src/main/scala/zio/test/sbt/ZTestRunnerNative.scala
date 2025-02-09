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
import zio.test.{FilteredSpec, Summary, TestArgs, ZIOSpecAbstract}
import zio.{CancelableFuture, Runtime, Scope, Trace, Unsafe, ZIO}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.duration.{Duration => SDuration}

sealed abstract class ZTestRunnerNative(
  val args: Array[String],
  remoteArgs0: Array[String],
  testClassLoader: ClassLoader,
  runnerType: String
) extends Runner {

  def remoteArgs(): Array[String] = remoteArgs0

  def sendSummary: SendSummary

  val summaries: ConcurrentLinkedQueue[Summary] = new ConcurrentLinkedQueue

  def done(): String = {
    val log     = new StringBuilder
    var summary = summaries.poll()
    var total   = 0
    var ignore  = 0
    val isEmpty = summary eq null

    while (summary ne null) {
      total += summary.total
      ignore += summary.ignore
      val details = summary.failureDetails
      if (!details.isBlank) {
        log append colored(details)
        log append '\n'
      }
      summary = summaries.poll()
    }

    if (isEmpty || total == ignore)
      s"${Console.YELLOW}No tests were executed${Console.RESET}"
    else
      log.append("Done").result()
  }

  def tasks(defs: Array[TaskDef]): Array[Task] =
    defs.map(ZTestTask(_, testClassLoader, runnerType, sendSummary, TestArgs.parse(args)))

  override def receiveMessage(summary: String): Option[String] = {
    SummaryProtocol.deserialize(summary).foreach(summaries.offer)

    None
  }

  override def serializeTask(task: Task, serializer: TaskDef => String): String =
    serializer(task.taskDef())

  override def deserializeTask(task: String, deserializer: String => TaskDef): Task =
    ZTestTask(deserializer(task), testClassLoader, runnerType, sendSummary, TestArgs.parse(args))
}

final class ZMasterTestRunner(args: Array[String], remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends ZTestRunnerNative(args, remoteArgs, testClassLoader, "master") {

  // This implementation seems to be used when there's only single spec to run
  override val sendSummary: SendSummary = SendSummary.fromSend { summary =>
    summaries.offer(summary)
    ()
  }

}

final class ZSlaveTestRunner(
  args: Array[String],
  remoteArgs: Array[String],
  testClassLoader: ClassLoader,
  val sendSummary: SendSummary
) extends ZTestRunnerNative(args, remoteArgs, testClassLoader, "slave") {}

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
      zio.Runtime.default,
      zio.Console.ConsoleLive
    ) {

  override def execute(eventHandler: EventHandler, loggers: Array[Logger]): Array[sbt.testing.Task] = {
    var resOutter: CancelableFuture[Unit] = null
    try {
      resOutter = Runtime.default.unsafe.runToFuture {
        ZIO.consoleWith { console =>
          (for {
            summary <- spec.runSpecAsApp(FilteredSpec(spec.spec, args), args, console)
            _       <- sendSummary.provideSomeEnvironment[Any](_.add(summary))
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
        }.mapError {
          case t: Throwable => t
          case other        => new RuntimeException(s"Unknown error during tests: $other")
        }
      }(Trace.empty, Unsafe.unsafe)

      Await.result(resOutter, SDuration.Inf)
      Array()
    } catch {
      case t: Throwable =>
        if (resOutter != null) resOutter.cancel()
        throw t
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
  ): ZTestTask = {
    val zioSpec = disectTask(taskDef, testClassLoader)
    new ZTestTask(taskDef, testClassLoader, runnerType, sendSummary, args, zioSpec)
  }

  private def disectTask(taskDef: TaskDef, testClassLoader: ClassLoader): ZIOSpecAbstract = {
    import org.portablescala.reflect._
    val fqn = taskDef.fullyQualifiedName().stripSuffix("$") + "$"
    // Creating the class from magic ether
    Reflect
      .lookupLoadableModuleClass(fqn, testClassLoader)
      .getOrElse(throw new ClassNotFoundException("failed to load object: " + fqn))
      .loadModule()
      .asInstanceOf[ZIOSpecAbstract]
  }
}
