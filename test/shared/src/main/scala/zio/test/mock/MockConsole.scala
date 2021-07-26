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

package zio.test.mock

import zio.{Console, Has, IO, URLayer, ZIO}

import java.io.IOException

object MockConsole extends Mock[Has[Console]] {

  object Print          extends Effect[Any, IOException, Unit]
  object PrintError     extends Effect[Any, IOException, Unit]
  object PrintLine      extends Effect[Any, IOException, Unit]
  object PrintLineError extends Effect[Any, IOException, Unit]
  object ReadLine       extends Effect[Unit, IOException, String]

  val compose: URLayer[Has[Proxy], Has[Console]] =
    ZIO
      .service[Proxy]
      .map(proxy =>
        new Console {
          def print(line: Any): IO[IOException, Unit]          = proxy(Print, line)
          def printError(line: Any): IO[IOException, Unit]     = proxy(PrintError, line)
          def printLine(line: Any): IO[IOException, Unit]      = proxy(PrintLine, line)
          def printLineError(line: Any): IO[IOException, Unit] = proxy(PrintLineError, line)
          val readLine: IO[IOException, String]                = proxy(ReadLine)
        }
      )
      .toLayer
}
