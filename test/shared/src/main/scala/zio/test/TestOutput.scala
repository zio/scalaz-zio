package zio.test

import zio.{Chunk, Ref, ZIO, ZLayer}

import scala.io.Source

private[test] trait TestOutput {

  /**
   * Does not necessarily print immediately. Might queue for later, sensible
   * output.
   */
  def print(executionEvent: ExecutionEvent): ZIO[Any, Nothing, Unit]
}

private[test] object TestOutput {
  val live: ZLayer[ExecutionEventPrinter, Nothing, TestOutput] =
    ZLayer.fromZIO(
      for {
        executionEventPrinter <- ZIO.service[ExecutionEventPrinter]
        // If you need to enable the debug output to diagnose flakiness, set this to true
        outputLive <- TestOutputLive.make(executionEventPrinter, debug = false)
      } yield outputLive
    )

  /**
   * Guarantees:
   *   - Everything at or below a specific suite level will be printed
   *     contiguously
   *   - Everything will be printed, as long as required SectionEnd events have
   *     been passed in
   *
   * Not guaranteed:
   *   - Ordering within a suite
   */
  def print(executionEvent: ExecutionEvent): ZIO[TestOutput, Nothing, Unit] =
    ZIO.serviceWithZIO[TestOutput](_.print(executionEvent))

  case class TestOutputLive(
    output: Ref[Map[SuiteId, Chunk[ExecutionEvent]]],
    reporters: TestReporters,
    executionEventPrinter: ExecutionEventPrinter,
    lock: TestDebugFileLock,
    debug: Boolean
  ) extends TestOutput {

    private def getAndRemoveSectionOutput(id: SuiteId) =
      output
        .getAndUpdate(initial => updatedWith(initial, id)(_ => None))
        .map(_.getOrElse(id, Chunk.empty))

    def print(executionEvent: ExecutionEvent): ZIO[Any, Nothing, Unit] =
      executionEvent match {
        case end: ExecutionEvent.SectionEnd      => printOrFlush(end)
        case flush: ExecutionEvent.TopLevelFlush => flushGlobalOutputIfPossible(flush)
        case other                               => printOrQueue(other)
      }

    private def printOrFlush(end: ExecutionEvent.SectionEnd): ZIO[Any, Nothing, Unit] =
      for {
        suiteIsPrinting <- reporters.attemptToGetPrintingControl(end.id, end.ancestors)
        sectionOutput   <- getAndRemoveSectionOutput(end.id).map(_ :+ end)
        _ <-
          if (suiteIsPrinting) print(sectionOutput)
          else {
            end.ancestors match {
              case parentId :: _ =>
                appendToSectionContents(parentId, sectionOutput)
              case Nil =>
                // TODO If we can't find cause of failure in CI, unsafely print to console instead of failing
                ZIO.dieMessage("Suite tried to send its output to a nonexistent parent. ExecutionEvent: " + end)
            }
          }
        _ <- reporters.relinquishPrintingControl(end.id)
      } yield ()

    private def flushGlobalOutputIfPossible(end: ExecutionEvent.TopLevelFlush): ZIO[Any, Nothing, Unit] =
      for {
        sectionOutput   <- getAndRemoveSectionOutput(end.id)
        _               <- appendToSectionContents(SuiteId.global, sectionOutput)
        suiteIsPrinting <- reporters.attemptToGetPrintingControl(SuiteId.global, List.empty)
        _               <- ZIO.whenDiscard(suiteIsPrinting)(getAndRemoveSectionOutput(SuiteId.global).flatMap(print))

      } yield ()

    private def printOrQueue(
      reporterEvent: ExecutionEvent
    ): ZIO[Any, Nothing, Unit] =
      for {
        _               <- ZIO.whenDiscard(debug)(TestDebug.print(reporterEvent, lock))
        _               <- appendToSectionContents(reporterEvent.id, Chunk(reporterEvent))
        suiteIsPrinting <- reporters.attemptToGetPrintingControl(reporterEvent.id, reporterEvent.ancestors)
        _               <- ZIO.whenDiscard(suiteIsPrinting)(getAndRemoveSectionOutput(reporterEvent.id).flatMap(print))
      } yield ()

    private def print(events: Chunk[ExecutionEvent]) =
      ZIO.foreachDiscard(events)(executionEventPrinter.print)

    private def appendToSectionContents(id: SuiteId, content: Chunk[ExecutionEvent]) =
      output.update { outputNow =>
        updatedWith(outputNow, id)(previousSectionOutput =>
          Some(previousSectionOutput.map(old => old ++ content).getOrElse(content))
        )
      }

    // We need this helper to run on Scala 2.12
    private def updatedWith(initial: Map[SuiteId, Chunk[ExecutionEvent]], key: SuiteId)(
      remappingFunction: Option[Chunk[ExecutionEvent]] => Option[Chunk[ExecutionEvent]]
    ): Map[SuiteId, Chunk[ExecutionEvent]] = {
      val previousValue = initial.get(key)
      val nextValue     = remappingFunction(previousValue)
      (previousValue, nextValue) match {
        case (None, None)    => initial
        case (Some(_), None) => initial - key
        case (_, Some(v))    => initial.updated(key, v)
      }
    }

  }

  object TestOutputLive {

    def make(executionEventPrinter: ExecutionEventPrinter, debug: Boolean): ZIO[Any, Nothing, TestOutput] =
      for {
        talkers <- TestReporters.make
        lock    <- TestDebugFileLock.make
        output  <- Ref.make[Map[SuiteId, Chunk[ExecutionEvent]]](Map.empty)
      } yield TestOutputLive(output, talkers, executionEventPrinter, lock, debug)

  }
}
