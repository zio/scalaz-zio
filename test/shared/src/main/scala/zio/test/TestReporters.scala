package zio.test

import zio.{Ref, ZIO}

object TestReporters {
  val make: ZIO[Any, Nothing, TestReporters] =
    // This SuiteId should probably be passed in a more obvious way
    Ref.make(List(SuiteId.global)).map(TestReporters(_))
}

case class TestReporters(reportersStack: Ref[List[SuiteId]]) {

  def attemptToGetPrintingControl(id: SuiteId, ancestors: List[SuiteId]): ZIO[Any, Nothing, Boolean] =
    reportersStack.updateSomeAndGet {
      case Nil =>
        List(id)

      case reporters if ancestors.nonEmpty && reporters.head == ancestors.head =>
        id :: reporters
    }.map(_.head == id)

  def relinquishPrintingControl(id: SuiteId): ZIO[Any, Nothing, Unit] =
    reportersStack.updateSome {
      case currentReporter :: reporters if currentReporter == id =>
        reporters
    }

}
