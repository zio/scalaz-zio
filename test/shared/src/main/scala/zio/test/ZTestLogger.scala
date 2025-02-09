package zio.test

import zio._

import scala.annotation.tailrec

/**
 * A `ZTestLogger` is an implementation of a `ZLogger` that writes all log
 * messages to an internal data structure. The contents of this data structure
 * can be accessed using the `logOutput` operator. This makes it easy to write
 * tests to verify that expected messages are being logged.
 *
 * {{{
 * test("logging works") {
 *   for {
 *     _      <- ZIO.logDebug("It's alive!")
 *     output <- ZTestLogger.logOutput
 *   } yield assertTrue(output.length == 1) &&
 *     assertTrue(output(0).message() == "It's alive!") &&
 *     assertTrue(output(0).logLevel == LogLevel.Debug)
 * }
 * }}}
 */
sealed trait ZTestLogger[-Message, +Output] extends ZLogger[Message, Output] {

  /**
   * Returns the contents of the log.
   */
  def logOutput: UIO[Chunk[ZTestLogger.LogEntry]]
}

object ZTestLogger {

  /**
   * A layer which constructs a new `ZTestLogger` and runs the effect it is
   * provided to with the `Runtime` updated to add the `ZTestLogger`.
   */
  val default: ZLayer[Any, Nothing, Unit] =
    ZLayer.scoped {
      for {
        testLogger <- ZTestLogger.make
        acquire    <- FiberRef.currentLoggers.locallyScopedWith(_ + testLogger)
      } yield ()
    }

  /**
   * Accesses the contents of the current test logger.
   */
  val logOutput: UIO[Chunk[ZTestLogger.LogEntry]] =
    ZIO.loggersWith { loggers =>
      loggers.collectFirst { case testLogger: ZTestLogger[_, _] =>
        testLogger.logOutput
      }
        .getOrElse(ZIO.dieMessage("Defect: ZTestLogger is missing"))
    }

  /**
   * A log entry captures all of the contents of a log message as a data
   * structure.
   */
  final case class LogEntry(
    trace: Trace,
    fiberId: FiberId,
    logLevel: LogLevel,
    message: () => String,
    cause: Cause[Any],
    context: FiberRefs,
    spans: List[LogSpan],
    annotations: Map[String, String]
  ) {
    def call[A](zlogger: ZLogger[String, A]): A =
      zlogger(trace, fiberId, logLevel, message, cause, context, spans, annotations)
  }

  /**
   * Constructs a `ZTestLogger`.
   */
  private def make: UIO[ZLogger[String, Unit]] =
    ZIO.succeed {

      val _logOutput = new java.util.concurrent.atomic.AtomicReference[Chunk[LogEntry]](Chunk.empty)

      new ZTestLogger[String, Unit] {
        @tailrec
        def apply(
          trace: Trace,
          fiberId: FiberId,
          logLevel: LogLevel,
          message: () => String,
          cause: Cause[Any],
          context: FiberRefs,
          spans: List[LogSpan],
          annotations: Map[String, String]
        ): Unit = {
          val newEntry = LogEntry(trace, fiberId, logLevel, message, cause, context, spans, annotations)

          val oldState = _logOutput.get

          if (!_logOutput.compareAndSet(oldState, oldState :+ newEntry))
            apply(trace, fiberId, logLevel, message, cause, context, spans, annotations)
          else ()
        }
        val logOutput: UIO[Chunk[LogEntry]] =
          ZIO.succeed(_logOutput.get)
      }
    }
}
