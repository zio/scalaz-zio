package zio.test

import zio.{Ref, Unsafe, ZIO}

object TestReporters {
  val make: ZIO[Any, Nothing, TestReporters] = {
    // This SuiteId should probably be passed in a more obvious way
    ZIO.succeed(TestReporters(Ref.unsafe.make(List(SuiteId.global))(Unsafe)))
  }
}

case class TestReporters(reportersStack: Ref[List[SuiteId]]) {

  def attemptToGetPrintingControl(id: SuiteId, ancestors: List[SuiteId]): ZIO[Any, Nothing, Boolean] =
    reportersStack.updateSomeAndGet {
      case Nil                                                   => List(id)
      case rs if ancestors.nonEmpty && rs.head == ancestors.head => id :: rs
    }.map(_.head == id)

  def relinquishPrintingControl(id: SuiteId): ZIO[Any, Nothing, Unit] =
    reportersStack.updateSome {
      case currentReporter :: reporters if currentReporter == id => reporters
    }

}
