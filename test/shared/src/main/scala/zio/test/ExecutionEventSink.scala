package zio.test

import zio.{Console, Ref, UIO, ULayer, Unsafe, ZIO, ZLayer}

trait ExecutionEventSink {
  def getSummary: UIO[Summary]
  def process(event: ExecutionEvent): ZIO[Any, Nothing, Unit]
}

object ExecutionEventSink {
  def getSummary: ZIO[ExecutionEventSink, Nothing, Summary] =
    ZIO.serviceWithZIO[ExecutionEventSink](_.getSummary)

  def process(event: ExecutionEvent): ZIO[ExecutionEventSink, Nothing, Unit] =
    ZIO.serviceWithZIO[ExecutionEventSink](_.process(event))

  private def ExecutionEventSinkLive(testOutput: TestOutput): ZIO[Any, Nothing, ExecutionEventSink] =
    ZIO.succeed {
      new ExecutionEventSink {
        private val summary = Ref.unsafe.make(Summary.empty)(Unsafe)

        override def process(event: ExecutionEvent): ZIO[Any, Nothing, Unit] =
          summary.update(_.add(event)) *> testOutput.print(event)

        override def getSummary: UIO[Summary] =
          summary.get
      }
    }

  def live(console: Console, eventRenderer: ReporterEventRenderer): ZLayer[Any, Nothing, ExecutionEventSink] =
    ZLayer.make[ExecutionEventSink](
      ExecutionEventPrinter.live(console, eventRenderer),
      TestOutput.live,
      ZLayer(ZIO.serviceWithZIO[TestOutput](ExecutionEventSinkLive(_)))
    )

  val live: ZLayer[TestOutput, Nothing, ExecutionEventSink] =
    ZLayer(ZIO.serviceWithZIO[TestOutput](ExecutionEventSinkLive(_)))

  val silent: ULayer[ExecutionEventSink] =
    ZLayer.succeed(
      new ExecutionEventSink {
        override def getSummary: UIO[Summary]                                = ZIO.succeed(Summary.empty)
        override def process(event: ExecutionEvent): ZIO[Any, Nothing, Unit] = ZIO.unit
      }
    )
}
