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
import zio.internal.Platform
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.render.{ConsoleRenderer, TestRenderer}
import zio.test.{ExecutionEventPrinter, Summary, TestArgs, TestOutput, ZIOSpecAbstract, ZTestEventHandler}
import zio.{Fiber, Runtime, Scope, System, Trace, Unsafe, ZIO, ZIOAppArgs, ZLayer}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

final class ZTestRunnerJVM(val args: Array[String], val remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends Runner {

  @volatile
  var renderer: TestRenderer =
    ConsoleRenderer

  @volatile
  var shutdownHook: Option[() => Unit] =
    None

  val summaries: AtomicReference[Vector[Summary]] = new AtomicReference(Vector.empty)

  def sendSummary(implicit trace: Trace): SendSummary = SendSummary.fromSendZIO(summary =>
    ZIO.succeed {
      summaries.updateAndGet(_ :+ summary)
      ()
    }
  )

  def done(): String = {
    val allSummaries = summaries.get

    val total  = allSummaries.map(_.total).sum
    val ignore = allSummaries.map(_.ignore).sum

    val compositeSummary =
      allSummaries.foldLeft(Summary.empty)(_.add(_))

    val renderedSummary = renderer.renderSummary(compositeSummary)

    val renderedResults =
      if (allSummaries.nonEmpty && total != ignore)
        colored(renderedSummary)
      else if (ignore > 0)
        s"${Console.YELLOW}All eligible tests are currently ignored ${Console.RESET}"
      else
        s"${Console.YELLOW}No tests were executed${Console.RESET}"

    // We eagerly print out the info here, rather than returning it
    // from this function as a workaround for this bug when running
    // tests in a forked JVM:
    //    https://github.com/sbt/sbt/issues/3510
    if (allSummaries.nonEmpty)
      println(renderedResults)
    else ()

    // If tests are forked, this will only be relevant in the forked
    // JVM, and will not be set in the original JVM.
    shutdownHook.foreach(_.apply())

    // Does not try to return a real summary, because we have already
    // printed this info directly to the console.
    "Completed tests"
  }

  def tasks(defs: Array[TaskDef]): Array[Task] =
    tasksZ(defs, zio.Console.ConsoleLive)(Trace.empty).toArray

  private[sbt] def tasksZ(
    defs: Array[TaskDef],
    console: zio.Console
  )(implicit trace: Trace): Array[ZTestTask[TestOutput]] = {
    val testArgs = TestArgs.parse(args)

    renderer = testArgs.testRenderer // Ensures summary is pretty in same style as rest of the test output
    val sharedTestOutputLayer = ExecutionEventPrinter.live(console, testArgs.testEventRenderer) >>> TestOutput.live

    val specTasks: Array[ZIOSpecAbstract] = defs.map(disectTask(_, testClassLoader))
    val sharedLayerFromSpecs: ZLayer[Any, Any, Any] =
      (Scope.default ++ ZIOAppArgs.empty) >>> specTasks
        .map(_.bootstrap)
        .foldLeft(ZLayer.empty: ZLayer[ZIOAppArgs, Any, Any])(_ +!+ _)

    val sharedLayer: ZLayer[Any, Any, TestOutput] =
      sharedLayerFromSpecs +!+ sharedTestOutputLayer

    val runtime: Runtime.Scoped[TestOutput] =
      zio.Runtime.unsafe.fromLayer(sharedLayer)(Trace.empty, Unsafe.unsafe)

    shutdownHook = Some(() => runtime.unsafe.shutdown()(Unsafe.unsafe))

    defs.map(ZTestTask(_, testClassLoader, sendSummary, testArgs, runtime, console))
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

final class ZTestTask[T](
  taskDef: TaskDef,
  testClassLoader: ClassLoader,
  sendSummary: SendSummary,
  testArgs: TestArgs,
  spec: ZIOSpecAbstract,
  runtime: zio.Runtime[T],
  console: zio.Console
) extends BaseTestTask(taskDef, testClassLoader, sendSummary, testArgs, spec, runtime, console) {

  private def installSignalHandlers()(implicit trace: Trace): Unit =
    try {
      implicit val unsafe: Unsafe = Unsafe
      if (!ZTestTask.installedSignals.getAndSet(true)) {
        val dumpFibers =
          () => Runtime.default.unsafe.run(Fiber.dumpAll)(trace, Unsafe.unsafe).getOrThrowFiberFailure()(Unsafe)

        if (System.os.isWindows) {
          Platform.addSignalHandler("INT", dumpFibers)
        } else {
          Platform.addSignalHandler("INFO", dumpFibers)
          Platform.addSignalHandler("USR1", dumpFibers)
        }
      }
    }

  override private[zio] def run(eventHandlerZ: ZTestEventHandler)(implicit trace: Trace) = {
    installSignalHandlers()
    super.run(eventHandlerZ)
  }
}

object ZTestTask {
  private val installedSignals = new AtomicBoolean(false)

  def apply[T](
    taskDef: TaskDef,
    testClassLoader: ClassLoader,
    sendSummary: SendSummary,
    args: TestArgs,
    runtime: zio.Runtime[T],
    console: zio.Console
  ): ZTestTask[T] = {
    val zioSpec = disectTask(taskDef, testClassLoader)
    new ZTestTask(taskDef, testClassLoader, sendSummary, args, zioSpec, runtime, console)
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
