package zio.test

import zio._

object ExecutionEventSinkSpec extends ZIOSpecDefault {
  val uuid = SuiteId(0)

  override def spec = suite("ExecutionEventSinkSpec")(
    test("process single test") {
      val event = ExecutionEvent.Test(
        List("add", "ConcurrentSetSpec"),
        Right(TestSuccess.Succeeded(BoolAlgebra.Value(()))),
        TestAnnotationMap.empty,
        List.empty,
        0,
        uuid
      )
      for {
        _       <- ExecutionEventSink.process(event)
        summary <- ExecutionEventSink.getSummary
      } yield assertTrue(summary.success == 1)
    },
    test("process single test failure") {
      val event = ExecutionEvent.Test(
        List("add", "ConcurrentSetSpec"),
        Left(TestFailure.fail("You lose! Good day, sir!")),
        TestAnnotationMap.empty,
        List.empty,
        0,
        uuid
      )
      for {
        _       <- ExecutionEventSink.process(event)
        summary <- ExecutionEventSink.getSummary
      } yield assertTrue(summary.fail == 1)
    },
    test("process with ancestor") {
      val event = ExecutionEvent.Test(
        List("add", "ConcurrentSetSpec"),
        Right(TestSuccess.Succeeded(BoolAlgebra.Value(()))),
        TestAnnotationMap.empty,
        List(SuiteId(1)),
        0L,
        uuid
      )
      for {
        _       <- ExecutionEventSink.process(event)
        summary <- ExecutionEventSink.getSummary
      } yield assertTrue(summary.success == 1)
    }
  ).provide(
    Console.live,
    TestLogger.fromConsole,
    ExecutionEventSink.live,
    TestOutput.live
  )

}
