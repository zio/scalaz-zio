/*
 * Copyright 2017-2024 John A. De Goes and the ZIO Contributors
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

import java.io.{EOFException, IOException, PrintStream}
import scala.io.StdIn
import scala.{Console => SConsole}

trait Console extends Serializable { self =>
  def print(line: => Any)(implicit trace: Trace): IO[IOException, Unit]

  def printError(line: => Any)(implicit trace: Trace): IO[IOException, Unit]

  def printLine(line: => Any)(implicit trace: Trace): IO[IOException, Unit]

  def printLineError(line: => Any)(implicit trace: Trace): IO[IOException, Unit]

  def readLine(implicit trace: Trace): IO[IOException, String]

  def readLine(prompt: String)(implicit trace: Trace): IO[IOException, String] =
    print(prompt) *> readLine

  trait UnsafeAPI extends Serializable {
    def print(line: Any)(implicit unsafe: Unsafe): Unit
    def printError(line: Any)(implicit unsafe: Unsafe): Unit
    def printLine(line: Any)(implicit unsafe: Unsafe): Unit
    def printLineError(line: Any)(implicit unsafe: Unsafe): Unit
    def readLine()(implicit unsafe: Unsafe): String
  }

  def unsafe: UnsafeAPI =
    new UnsafeAPI {
      def print(line: Any)(implicit unsafe: Unsafe): Unit =
        Runtime.default.unsafe.run(self.print(line)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def printError(line: Any)(implicit unsafe: Unsafe): Unit =
        Runtime.default.unsafe.run(self.printError(line)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def printLine(line: Any)(implicit unsafe: Unsafe): Unit =
        Runtime.default.unsafe.run(self.printLine(line)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def printLineError(line: Any)(implicit unsafe: Unsafe): Unit =
        Runtime.default.unsafe.run(self.printLineError(line)(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()

      def readLine()(implicit unsafe: Unsafe): String =
        Runtime.default.unsafe.run(self.readLine(Trace.empty))(Trace.empty, unsafe).getOrThrowFiberFailure()
    }
}

object Console extends Serializable {

  val tag: Tag[Console] = Tag[Console]

  object ConsoleLive extends Console {

    def print(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.attemptBlockingIO(unsafe.print(line)(Unsafe.unsafe))

    def printError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.attemptBlockingIO(unsafe.printError(line)(Unsafe.unsafe))

    def printLine(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.attemptBlockingIO(unsafe.printLine(line)(Unsafe.unsafe))

    def printLineError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.attemptBlockingIO(unsafe.printLineError(line)(Unsafe.unsafe))

    def readLine(implicit trace: Trace): IO[IOException, String] =
      ZIO.attemptBlockingInterrupt(unsafe.readLine()(Unsafe.unsafe)).refineToOrDie[IOException]

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def print(line: Any)(implicit unsafe: Unsafe): Unit =
          print(SConsole.out)(line)

        override def printError(line: Any)(implicit unsafe: Unsafe): Unit =
          print(SConsole.err)(line)

        override def printLine(line: Any)(implicit unsafe: Unsafe): Unit =
          printLine(SConsole.out)(line)

        override def printLineError(line: Any)(implicit unsafe: Unsafe): Unit =
          printLine(SConsole.err)(line)

        override def readLine()(implicit unsafe: Unsafe): String = {
          val line = StdIn.readLine()

          if (line ne null) line
          else throw new EOFException("There is no more input left to read")
        }

        private def print(stream: => PrintStream)(line: => Any): Unit =
          SConsole.withOut(stream)(SConsole.print(line))

        private def printLine(stream: => PrintStream)(line: => Any): Unit =
          SConsole.withOut(stream)(SConsole.println(line))
      }
  }

  /**
   * Prints text to the console.
   */
  def print(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
    ZIO.consoleWith(_.print(line))

  /**
   * Prints text to the standard error console.
   */
  def printError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
    ZIO.consoleWith(_.printError(line))

  /**
   * Prints a line of text to the console, including a newline character.
   */
  def printLine(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
    ZIO.consoleWith(_.printLine(line))

  /**
   * Prints a line of text to the standard error console, including a newline
   * character.
   */
  def printLineError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
    ZIO.consoleWith(_.printLineError(line))

  /**
   * Retrieves a line of input from the console. Fails with an
   * [[java.io.EOFException]] when the underlying [[java.io.Reader]] returns
   * null.
   */
  def readLine(implicit trace: Trace): IO[IOException, String] =
    ZIO.consoleWith(_.readLine)

  /**
   * Prints the given prompt and then reads a line of input from the console.
   * Fails with an [[java.io.EOFException]] when the underlying
   * [[java.io.Reader]] returns null.
   */
  def readLine(prompt: String)(implicit trace: Trace): IO[IOException, String] =
    ZIO.consoleWith(_.readLine(prompt))
}
