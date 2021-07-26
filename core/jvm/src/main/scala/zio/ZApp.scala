/*
 * Copyright 2017-2021 John A. De Goes and the ZIO Contributors
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

package zio

import zio.internal.FiberContext

/**
 * The entry point for a purely-functional application on the JVM.
 *
 * {{{
 * import zio.ZApp
 * import zio.Console._
 *
 * object MyApp extends ZApp[Has[Console]] {
 *
 *   def environment: Has[Console] = Has(ConsoleLive)
 *
 *   final def run(args: List[String]) =
 *     myAppLogic.exitCode
 *
 *   def myAppLogic =
 *     for {
 *       _ <- printLine("Hello! What is your name?")
 *       n <- readLine
 *       _ <- printLine("Hello, " + n + ", good to meet you!")
 *     } yield ()
 * }
 * }}}
 */
trait ZApp[R] extends ZBootstrapRuntime[R] {

  /**
   * The main function of the application, which will be passed the command-line
   * arguments to the program and has to return an `IO` with the errors fully handled.
   */
  def run(args: List[String]): URIO[R, ExitCode]

  /**
   * The Scala main function, intended to be called only by the Scala runtime.
   */
  // $COVERAGE-OFF$ Bootstrap to `Unit`
  final def main(args0: Array[String]): Unit =
    try sys.exit(
      unsafeRun(
        for {
          fiber <- run(args0.toList).fork
          _ <- IO.succeed(java.lang.Runtime.getRuntime.addShutdownHook(new Thread {
                 override def run() =
                   if (FiberContext.fatal.get) {
                     println(
                       "**** WARNING ***\n" +
                         "Catastrophic JVM error encountered. " +
                         "Application not safely interrupted. " +
                         "Resources may be leaked. " +
                         "Check the logs for more details and consider overriding `Platform.reportFatal` to capture context."
                     )
                   } else {
                     val _ = unsafeRunSync(fiber.interrupt)
                   }
               }))
          result <- fiber.join
          _      <- fiber.interrupt
        } yield result.code
      )
    )
    catch { case _: SecurityException => }
  // $COVERAGE-ON$
}
