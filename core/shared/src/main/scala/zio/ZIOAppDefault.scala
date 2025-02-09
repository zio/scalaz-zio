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

package zio

import zio.stacktracer.TracingImplicits.disableAutoTrace

/**
 * The entry point for a ZIO application.
 *
 * {{{
 * import zio.ZIOAppDefault
 * import zio.Console._
 *
 * object MyApp extends ZIOAppDefault {
 *
 *   def run =
 *     for {
 *       _ <- printLine("Hello! What is your name?")
 *       n <- readLine
 *       _ <- printLine("Hello, " + n + ", good to meet you!")
 *     } yield ()
 * }
 * }}}
 */
trait ZIOAppDefault extends ZIOApp {

  type Environment = Any

  val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = ZLayer.empty

  val environmentTag: EnvironmentTag[Any] = EnvironmentTag[Any]

}

object ZIOAppDefault {

  /**
   * Creates a [[ZIOAppDefault]] from an effect, which can consume the arguments
   * of the program, as well as a hook into the ZIO runtime configuration.
   */
  def apply(
    run0: ZIO[ZIOAppArgs, Any, Any]
  ): ZIOAppDefault =
    new ZIOAppDefault {
      def run = run0
    }

  /**
   * Creates a [[ZIOAppDefault]] from an effect, using the unmodified default
   * runtime's configuration.
   */
  def fromZIO(run0: ZIO[ZIOAppArgs, Any, Any]): ZIOAppDefault =
    ZIOAppDefault(run0)
}
