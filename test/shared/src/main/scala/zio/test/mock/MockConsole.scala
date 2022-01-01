/*
 * Copyright 2019-2022 John A. De Goes and the ZIO Contributors
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

import zio.console.Console
import zio.{Has, IO, URLayer, ZLayer}

import java.io.IOException

object MockConsole extends Mock[Console] {

  object PutStr      extends Effect[String, IOException, Unit]
  object PutStrErr   extends Effect[String, IOException, Unit]
  object PutStrLn    extends Effect[String, IOException, Unit]
  object PutStrLnErr extends Effect[String, IOException, Unit]
  object GetStrLn    extends Effect[Unit, IOException, String]

  val compose: URLayer[Has[Proxy], Console] =
    ZLayer.fromService(proxy =>
      new Console.Service {
        def putStr(line: String): IO[IOException, Unit]      = proxy(PutStr, line)
        def putStrErr(line: String): IO[IOException, Unit]   = proxy(PutStrErr, line)
        def putStrLn(line: String): IO[IOException, Unit]    = proxy(PutStrLn, line)
        def putStrLnErr(line: String): IO[IOException, Unit] = proxy(PutStrLnErr, line)
        val getStrLn: IO[IOException, String]                = proxy(GetStrLn)
      }
    )
}
