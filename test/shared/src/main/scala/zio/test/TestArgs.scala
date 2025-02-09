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

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.test.ReporterEventRenderer.{ConsoleEventRenderer, IntelliJEventRenderer}
import zio.test.render.{ConsoleRenderer, IntelliJRenderer, TestRenderer}

private[test] final case class TestArgs(
  testSearchTerms: List[String],
  tagSearchTerms: List[String],
  tagIgnoreTerms: List[String],
  testTaskPolicy: Option[String],
  testRenderer: TestRenderer,
  printSummary: Boolean
) {
  val testEventRenderer: ReporterEventRenderer =
    testRenderer match {
      case _: ConsoleRenderer  => ConsoleEventRenderer
      case _: IntelliJRenderer => IntelliJEventRenderer
    }

  def ignoreFailures: Boolean =
    // When calling from IntelliJ, we want to ignore emitting a non-0 exit code.
    // Test running and termination is entirely handled by IntelliJ.
    testRenderer match {
      case _: IntelliJRenderer => true
      case _                   => false
    }
}

object TestArgs {
  def empty: TestArgs =
    TestArgs(List.empty[String], List.empty[String], List.empty[String], None, ConsoleRenderer, printSummary = true)

  def parse(args: Array[String]): TestArgs = {
    // TODO: Add a proper command-line parser
    val parsedArgs = args
      .sliding(2, 2)
      .collect {
        case Array("-t", term)           => ("testSearchTerm", term)
        case Array("-tags", term)        => ("tagSearchTerm", term)
        case Array("-ignore-tags", term) => ("tagIgnoreTerm", term)
        case Array("-policy", name)      => ("policy", name)
        case Array("-renderer", name)    => ("renderer", name)
        case Array("-summary", flag)     => ("summary", flag)
      }
      .toList
      .groupBy(_._1)
      .map { case (k, v) =>
        (k, v.map(_._2))
      }

    val terms          = parsedArgs.getOrElse("testSearchTerm", Nil)
    val tags           = parsedArgs.getOrElse("tagSearchTerm", Nil)
    val ignoreTags     = parsedArgs.getOrElse("tagIgnoreTerm", Nil)
    val testTaskPolicy = parsedArgs.getOrElse("policy", Nil).headOption
    val testRenderer   = parsedArgs.getOrElse("renderer", Nil).headOption.map(_.toLowerCase)
    val printSummary   = parsedArgs.getOrElse("summary", Nil).headOption.forall(_.toBoolean)
    val typedTestRenderer =
      testRenderer match {
        case Some(value) if value == "intellij" => IntelliJRenderer
        case _                                  => ConsoleRenderer
      }
    TestArgs(terms, tags, ignoreTags, testTaskPolicy, typedTestRenderer, printSummary)
  }
}
