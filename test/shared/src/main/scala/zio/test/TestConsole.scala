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

import zio.{Chunk, Console, FiberRef, IO, Ref, Trace, UIO, URIO, Unsafe, ZIO, ZLayer}
import zio.internal.stacktracer.Tracer
import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.{EOFException, IOException}

/**
 * `TestConsole` provides a testable interface for programs interacting with the
 * console by modeling input and output as reading from and writing to input and
 * output buffers maintained by `TestConsole` and backed by a `Ref`.
 *
 * All calls to `print` and `printLine` using the `TestConsole` will write the
 * string to the output buffer and all calls to `readLine` will take a string
 * from the input buffer. To facilitate debugging, by default output will also
 * be rendered to standard output. You can enable or disable this for a scope
 * using `debug`, `silent`, or the corresponding test aspects.
 *
 * `TestConsole` has several methods to access and manipulate the content of
 * these buffers including `feedLines` to feed strings to the input buffer that
 * will then be returned by calls to `readLine`, `output` to get the content of
 * the output buffer from calls to `print` and `printLine`, and `clearInput` and
 * `clearOutput` to clear the respective buffers.
 *
 * Together, these functions make it easy to test programs interacting with the
 * console.
 *
 * {{{
 * import zio.Console._
 * import zio.test.TestConsole
 * import zio.ZIO
 *
 * val sayHello = for {
 *   name <- readLine
 *   _    <- printLine("Hello, " + name + "!")
 * } yield ()
 *
 * for {
 *   _ <- TestConsole.feedLines("John", "Jane", "Sally")
 *   _ <- ZIO.collectAll(List.fill(3)(sayHello))
 *   result <- TestConsole.output
 * } yield result == Vector("Hello, John!\n", "Hello, Jane!\n", "Hello, Sally!\n")
 * }}}
 */
trait TestConsole extends Console with Restorable {
  def clearInput(implicit trace: Trace): UIO[Unit]
  def clearOutput(implicit trace: Trace): UIO[Unit]
  def clearOutputErr(implicit trace: Trace): UIO[Unit]
  def debug[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
  def feedLines(lines: String*)(implicit trace: Trace): UIO[Unit]
  def output(implicit trace: Trace): UIO[Vector[String]]
  def outputErr(implicit trace: Trace): UIO[Vector[String]]
  def silent[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A]
}

object TestConsole extends Serializable {

  case class Test(
    consoleState: Ref.Atomic[TestConsole.Data],
    live: Live,
    annotations: Annotations,
    debugState: FiberRef[Boolean]
  ) extends TestConsole {

    /**
     * Clears the contents of the input buffer.
     */
    def clearInput(implicit trace: Trace): UIO[Unit] =
      consoleState.update(data => data.copy(input = List.empty))

    /**
     * Clears the contents of the output buffer.
     */
    def clearOutput(implicit trace: Trace): UIO[Unit] =
      consoleState.update(data => data.copy(output = Vector.empty))

    /**
     * Clears the contents of the output error buffer.
     */
    def clearOutputErr(implicit trace: Trace): UIO[Unit] =
      consoleState.update(data => data.copy(errOutput = Vector.empty))

    /**
     * Runs the specified effect with the `TestConsole` set to debug mode, so
     * that console output is rendered to standard output in addition to being
     * written to the output buffer.
     */
    def debug[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      debugState.locally(true)(zio)

    /**
     * Writes the specified sequence of strings to the input buffer. The first
     * string in the sequence will be the first to be taken. These strings will
     * be taken before any strings that were previously in the input buffer.
     */
    def feedLines(lines: String*)(implicit trace: Trace): UIO[Unit] =
      consoleState.update(data => data.copy(input = lines.toList ::: data.input))

    /**
     * Takes the first value from the input buffer, if one exists, or else fails
     * with an `EOFException`.
     */
    def readLine(implicit trace: Trace): IO[IOException, String] =
      ZIO.attempt(unsafe.readLine()(Unsafe.unsafe)).refineToOrDie[IOException].tap { line =>
        annotations.annotate(TestAnnotation.output, Chunk(ConsoleIO.Input(line)))
      }

    /**
     * Returns the contents of the output buffer. The first value written to the
     * output buffer will be the first in the sequence.
     */
    def output(implicit trace: Trace): UIO[Vector[String]] =
      consoleState.get.map(_.output)

    /**
     * Returns the contents of the error output buffer. The first value written
     * to the error output buffer will be the first in the sequence.
     */
    def outputErr(implicit trace: Trace): UIO[Vector[String]] =
      consoleState.get.map(_.errOutput)

    /**
     * Writes the specified string to the output buffer.
     */
    override def print(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.succeed(unsafe.print(line)(Unsafe.unsafe)) *>
        live.provide(Console.print(line)).whenZIO(debugState.get).unit

    /**
     * Writes the specified string to the error buffer.
     */
    override def printError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.succeed(unsafe.printError(line)(Unsafe.unsafe)) *>
        live.provide(Console.printError(line)).whenZIO(debugState.get).unit

    /**
     * Writes the specified string to the output buffer followed by a newline
     * character.
     */
    override def printLine(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      annotations.annotate(TestAnnotation.output, Chunk(ConsoleIO.Output(line.toString))) *>
        ZIO.succeed(unsafe.printLine(line)(Unsafe.unsafe)) *>
        live.provide(Console.printLine(line)).whenZIO(debugState.get).unit

    /**
     * Writes the specified string to the error buffer followed by a newline
     * character.
     */
    override def printLineError(line: => Any)(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.succeed(unsafe.printLineError(line)(Unsafe.unsafe)) *>
        live.provide(Console.printLineError(line)).whenZIO(debugState.get).unit

    /**
     * Saves the `TestConsole`'s current state in an effect which, when run,
     * will restore the `TestConsole` state to the saved state.
     */
    def save(implicit trace: Trace): UIO[UIO[Unit]] =
      for {
        consoleData <- consoleState.get
      } yield consoleState.set(consoleData)

    /**
     * Runs the specified effect with the `TestConsole` set to silent mode, so
     * that console output is only written to the output buffer and not rendered
     * to standard output.
     */
    def silent[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
      debugState.locally(false)(zio)

    override val unsafe: UnsafeAPI =
      new UnsafeAPI {
        override def print(line: Any)(implicit unsafe: Unsafe): Unit =
          consoleState.unsafe.update { data =>
            Data(data.input, data.output :+ line.toString, data.errOutput)
          }

        override def printError(line: Any)(implicit unsafe: Unsafe): Unit =
          consoleState.unsafe.update { data =>
            Data(data.input, data.output, data.errOutput :+ line.toString)
          }

        override def printLine(line: Any)(implicit unsafe: Unsafe): Unit =
          consoleState.unsafe.update { data =>
            Data(data.input, data.output :+ s"$line\n", data.errOutput)
          }

        override def printLineError(line: Any)(implicit unsafe: Unsafe): Unit =
          consoleState.unsafe.update { data =>
            Data(data.input, data.output, data.errOutput :+ s"$line\n")
          }

        override def readLine()(implicit unsafe: Unsafe): String =
          consoleState.unsafe.modify { data =>
            data.input match {
              case head :: tail =>
                head -> Data(tail, data.output, data.errOutput)
              case Nil =>
                throw new EOFException("There is no more input left to read")

            }
          }
      }
  }

  /**
   * Constructs a new `Test` object that implements the `TestConsole` interface.
   * This can be useful for mixing in with implementations of other interfaces.
   */
  def make(data: Data, debug: Boolean = true)(implicit
    trace: Trace
  ): ZLayer[Live with Annotations, Nothing, TestConsole] =
    ZLayer.scoped {
      for {
        live        <- ZIO.service[Live]
        annotations <- ZIO.service[Annotations]
        ref         <- ZIO.succeed(Ref.unsafe.make(data)(Unsafe.unsafe))
        debugRef    <- FiberRef.make(debug)
        test         = Test(ref, live, annotations, debugRef)
        _           <- ZIO.withConsoleScoped(test)
      } yield test
    }

  val any: ZLayer[TestConsole, Nothing, TestConsole] =
    ZLayer.environment[TestConsole](Tracer.newTrace)

  val debug: ZLayer[Live with Annotations, Nothing, TestConsole] =
    make(Data(Nil, Vector()), true)(Tracer.newTrace)

  val silent: ZLayer[Live with Annotations, Nothing, TestConsole] =
    make(Data(Nil, Vector()), false)(Tracer.newTrace)

  /**
   * Accesses a `TestConsole` instance in the environment and clears the input
   * buffer.
   */
  def clearInput(implicit trace: Trace): UIO[Unit] =
    testConsoleWith(_.clearInput)

  /**
   * Accesses a `TestConsole` instance in the environment and clears the output
   * buffer.
   */
  def clearOutput(implicit trace: Trace): UIO[Unit] =
    testConsoleWith(_.clearOutput)

  /**
   * Accesses a `TestConsole` instance in the environment and runs the specified
   * effect with the `TestConsole` set to debug mode, so that console output is
   * rendered to standard output in addition to being written to the output
   * buffer.
   */
  def debug[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    testConsoleWith(_.debug(zio))

  /**
   * Accesses a `TestConsole` instance in the environment and writes the
   * specified sequence of strings to the input buffer.
   */
  def feedLines(lines: String*)(implicit trace: Trace): UIO[Unit] =
    testConsoleWith(_.feedLines(lines: _*))

  /**
   * Accesses a `TestConsole` instance in the environment and returns the
   * contents of the output buffer.
   */
  def output(implicit trace: Trace): UIO[Vector[String]] =
    testConsoleWith(_.output)

  /**
   * Accesses a `TestConsole` instance in the environment and returns the
   * contents of the error buffer.
   */
  def outputErr(implicit trace: Trace): UIO[Vector[String]] =
    testConsoleWith(_.outputErr)

  /**
   * Accesses a `TestConsole` instance in the environment and saves the console
   * state in an effect which, when run, will restore the `TestConsole` to the
   * saved state.
   */
  def save(implicit trace: Trace): UIO[UIO[Unit]] =
    testConsoleWith(_.save)

  /**
   * Accesses a `TestConsole` instance in the environment and runs the specified
   * effect with the `TestConsole` set to silent mode, so that console output is
   * only written to the output buffer and not rendered to standard output.
   */
  def silent[R, E, A](zio: ZIO[R, E, A])(implicit trace: Trace): ZIO[R, E, A] =
    testConsoleWith(_.silent(zio))

  /**
   * The state of the `TestConsole`.
   */
  final case class Data(
    input: List[String] = List.empty,
    output: Vector[String] = Vector.empty,
    errOutput: Vector[String] = Vector.empty
  )
}
