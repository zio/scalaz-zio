package zio.test.sbt

import sbt.testing.{Event, Status, TaskDef, TestSelector}
import zio._
import zio.test.Assertion.anything
import zio.test.TestAspect.ignore
import zio.test.render.ConsoleRenderer
import zio.test._
import zio.test.sbt.TestingSupport._

object ZTestEventSpec extends ZIOSpecDefault {
  def spec =
    suite("exhaustive conversions")(
      test("just zio sleep")(
        ZIO.withClock(Clock.ClockLive)(ZIO.sleep(2.second)).as(assertCompletes)
      ),
      test("succeeded") {
        val test = ExecutionEvent.Test(
          labelsReversed = List("test", "specific", "realm"),
          test = Right(TestSuccess.Succeeded()),
          annotations = TestAnnotationMap.empty,
          ancestors = List.empty,
          duration = 0L,
          id = SuiteId(0),
          fullyQualifiedName = "test.specific.realm"
        )
        val result: Event =
          ZTestEvent.convertEvent(
            test,
            new TaskDef("zio.dev.test", ZioSpecFingerprint, false, Array.empty),
            ConsoleRenderer
          )
        val expected: Event = ZTestEvent(
          "zio.dev.test",
          new TestSelector("realm - specific - test"),
          Status.Success,
          None,
          0L,
          ZioSpecFingerprint
        )
        assertEqualEvents(result, expected)
      },
      test("runtime failure") {
        val test = ExecutionEvent.Test(
          labelsReversed = List("test", "specific", "realm"),
          test = Left(TestFailure.Runtime(Cause.fail("boom"))),
          annotations = TestAnnotationMap.empty,
          ancestors = List.empty,
          duration = 0L,
          id = SuiteId(0),
          fullyQualifiedName = "test.specific.realm"
        )
        val result: Event =
          ZTestEvent.convertEvent(
            test,
            new TaskDef("zio.dev.test", ZioSpecFingerprint, false, Array.empty),
            ConsoleRenderer
          )
        val expected: Event = ZTestEvent(
          "zio.dev.test",
          new TestSelector("realm - specific - test"),
          Status.Failure,
          Some(
            new Exception(
              s"""|    ${ConsoleUtils.bold(red("- test"))}
                  |      java.lang.String: boom""".stripMargin
            )
          ),
          0L,
          ZioSpecFingerprint
        )
        assertEqualEvents(result, expected)
      }
    )
  // Required because
  //  - `Selector` equality isn't working
  //  - Ansi colors make comparisons horrible to work with
  def assertEqualEvents(result: Event, expected: Event): TestResult = {
    println(stripAnsi(result.throwable()))
    println("\n==================\n")
    println(stripAnsi(result.throwable()))
    assertTrue(
      result.fullyQualifiedName() == expected.fullyQualifiedName()
    ) &&
    assertTrue(
      result.selector().toString == expected.selector().toString
    ) &&
    assertTrue(
      result.status() == expected.status()
    ) &&
    assertTrue(
      stripAnsi(result.throwable())
        == stripAnsi(expected.throwable())
    ) &&
    assertTrue(
      result.duration() == expected.duration()
    ) &&
    assertCompletes
  }

  private def stripAnsi(input: Any) =
    input.toString
      .replaceAll("\\e\\[[\\d;]*[^\\d;]", "")
      .replaceAll("\\e\\[[\\d;]*[^\\d;]", "")

}
