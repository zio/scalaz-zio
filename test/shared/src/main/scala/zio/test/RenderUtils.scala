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

import scala.{Console => SConsole}

private[test] object ConsoleUtils {
  def underlined(s: String): String =
    SConsole.UNDERLINED + s + SConsole.RESET

  def green(s: String): String =
    SConsole.GREEN + s + SConsole.RESET

  def yellow(s: String): String =
    SConsole.YELLOW + s + SConsole.RESET

  def red(s: String): String =
    SConsole.RED + s + SConsole.RESET

  def blue(s: String): String =
    SConsole.BLUE + s + SConsole.RESET

  def magenta(s: String): String =
    SConsole.MAGENTA + s + SConsole.RESET

  def cyan(s: String): String =
    SConsole.CYAN + s + SConsole.RESET

  def dim(s: String): String =
    "\u001b[2m" + s + SConsole.RESET

  def bold(s: String): String =
    SConsole.BOLD + s + SConsole.RESET

  def ansi(ansiColor: String, s: String): String =
    ansiColor + s + SConsole.RESET
}
