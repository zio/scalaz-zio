package zio.test

import zio.internal.macros.StringUtils.StringOps
import zio.internal.stacktracer.SourceLocation
import zio.test.ReportingTestUtils._
import zio.test.TestAspect._
import zio.test.render.ConsoleRenderer
import zio.{StackTrace, ZIO}

object ConsoleTestOutputSpec extends ZIOBaseSpec {

  private def containsUnstyled(result: String, expected: Vector[String])(implicit
    sourceLocation: SourceLocation
  ): TestResult =
    expected.map(ex => assertTrue(result.unstyled.contains(ex.unstyled))).reduce(_ && _)

  def spec =
    suite("ConsoleTestOutputSpec")(
      suite("reports")(
        test("a successful test") {
          runLog(test1).map(res => assertTrue(test1Expected == res))
        },
        test("a failed test") {
          runLog(test3).map(r => containsUnstyled(r, test3Expected()))
        },
        test("a failed test with annotations") {
          runLog(testAnnotations).map(r => containsUnstyled(r, testAnnotationsExpected()))
        },
        test("an error in a test") {
          runLog(test4).map(log => assertTrue(log.contains("Test 4 Fail")))
        },
        test("successful test suite") {
          runLog(suite1).map(res => containsUnstyled(res, suite1Expected))
        },
        test("failed test suite") {
          runLog(suite2).map(res => containsUnstyled(res, suite2Streaming))
        },
        test("multiple test suites") {
          runLog(suite3).map(res => containsUnstyled(res, suite2Streaming))
        },
        test("empty test suite") {
          runLog(suite4).map(res => containsUnstyled(res, suite4Expected))
        },
        test("failure of simple assertion") {
          runLog(test5).map(res => containsUnstyled(res, test5Expected))
        },
        test("multiple nested failures") {
          runLog(test6).map(res => containsUnstyled(res, test6Expected))
        },
        test("labeled failures") {
          runLog(test7).map(res => containsUnstyled(res, test7Expected))
        },
        test("labeled failures for assertTrue") {
          for {
            log <- runLog(test9)
          } yield assertTrue(log.contains("third"), log.contains("fourth"))
        },
        test("negated failures") {
          runLog(test8).map(res => containsUnstyled(res, test8Expected))
        }
      ),
      suite("Runtime exception reporting")(
        test("ExecutionEvent.RuntimeFailure  Runtime does not swallow error") {
          val expectedLabel            = "RuntimeFailure label"
          val expectedExceptionMessage = "boom"
          for {
            result <- ZIO.succeed(
                        ConsoleRenderer.render(
                          ExecutionEvent.RuntimeFailure(
                            SuiteId(1),
                            labelsReversed = List(expectedLabel),
                            failure = TestFailure.failCause(
                              zio.Cause.Die(new RuntimeException(expectedExceptionMessage), StackTrace.none)
                            ),
                            ancestors = List.empty
                          ),
                          includeCause = true
                        )
                      )
            res <- extractSingleExecutionResult(result)
          } yield assertTrue(res.contains(expectedLabel), res.contains(expectedExceptionMessage))
        },
        test("ExecutionEvent.RuntimeFailure  Assertion does not swallow error") {
          val expectedLabel = "RuntimeFailure assertion label"
          for {
            result <- ZIO.succeed(
                        ConsoleRenderer.render(
                          ExecutionEvent.RuntimeFailure(
                            SuiteId(1),
                            labelsReversed = List(expectedLabel),
                            failure = TestFailure.assertion(assertTrue(true)),
                            ancestors = List.empty
                          ),
                          includeCause = true
                        )
                      )
            res <- extractSingleExecutionResult(result)
          } yield assertTrue(res.contains(expectedLabel))
        }
      )
    ) @@ silent

  private def extractSingleExecutionResult(results: Seq[String]): ZIO[Any, String, String] =
    results match {
      case res :: others =>
        if (others.isEmpty)
          ZIO.succeed(res)
        else ZIO.fail("More than one ExecutionResult returned")
      case _ => ZIO.fail("No ExecutionResults returned")
    }

}
