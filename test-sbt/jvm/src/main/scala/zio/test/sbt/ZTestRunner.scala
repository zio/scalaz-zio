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
import zio.ZIO
import zio.test.{Summary, TestArgs}

import java.util.concurrent.atomic.AtomicReference

final class ZTestRunner(val args: Array[String], val remoteArgs: Array[String], testClassLoader: ClassLoader)
    extends Runner {
  val summaries: AtomicReference[Vector[Summary]] = new AtomicReference(Vector.empty)

  val sendSummary: SendSummary = SendSummary.fromSendM(summary =>
    ZIO.succeed {
      summaries.updateAndGet(_ :+ summary)
      ()
    }
  )

  def done(): String = {
    val allSummaries = summaries.get

    val total  = allSummaries.map(_.total).sum
    val ignore = allSummaries.map(_.ignore).sum

    if (allSummaries.isEmpty || total == ignore)
      s"${Console.YELLOW}No tests were executed${Console.RESET}"
    else
      allSummaries
        .map(_.summary)
        .filter(_.nonEmpty)
        .flatMap(summary => colored(summary) :: "\n" :: Nil)
        .mkString("", "", "Done")
  }

  def tasks(defs: Array[TaskDef]): Array[Task] = {
    val testArgs        = TestArgs.parse(args)
    val tasks           = defs.map(new ZTestTask(_, testClassLoader, sendSummary, testArgs))
    val entrypointClass = testArgs.testTaskPolicy.getOrElse(classOf[ZTestTaskPolicyDefaultImpl].getName)
    val taskPolicy = getClass.getClassLoader
      .loadClass(entrypointClass)
      .getConstructor()
      .newInstance()
      .asInstanceOf[ZTestTaskPolicy]
    taskPolicy.merge(tasks)
  }
}

final class ZTestTask(taskDef: TaskDef, testClassLoader: ClassLoader, sendSummary: SendSummary, testArgs: TestArgs)
    extends BaseTestTask(taskDef, testClassLoader, sendSummary, testArgs)

abstract class ZTestTaskPolicy {
  def merge(zioTasks: Array[ZTestTask]): Array[Task]
}

class ZTestTaskPolicyDefaultImpl extends ZTestTaskPolicy {
  override def merge(zioTasks: Array[ZTestTask]): Array[Task] = zioTasks.toArray
}
