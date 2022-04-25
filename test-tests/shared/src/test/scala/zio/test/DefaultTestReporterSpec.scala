package zio.test

import zio.internal.macros.StringUtils.StringOps
import zio.test.ReportingTestUtils._
import zio.test.TestAspect._
import zio.test.render.ExecutionResult
import zio.test.render.ExecutionResult.ResultType.Test
import zio.test.render.ExecutionResult.Status.Failed
import zio.{ZIO, ZTrace, ZTraceElement}

object DefaultTestReporterSpec extends ZIOBaseSpec {

  def containsUnstyled(string: String, substring: String)(implicit trace: ZTraceElement): TestResult =
    assertTrue(string.unstyled.contains(substring.unstyled))

  def spec =
    suite("DefaultTestReporterSpec")(
      suite("reports")(
        test("a successful test") {
          runLog(test1).map(res => assertTrue(test1Expected == res))
        },
        test("a failed test") {
          runLog(test3).map(r => test3Expected.map(ex => containsUnstyled(r, ex)).reduce(_ && _))
        },
        test("an error in a test") {
          runLog(test4).map(log => assertTrue(log.contains("Test 4 Fail")))
        },
        test("successful test suite") {
          runLog(suite1).map(res => suite1Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("failed test suite") {
          runLog(suite2).map(res => suite2Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("multiple test suites") {
          runLog(suite3).map(res => suite3Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("empty test suite") {
          runLog(suite4).map(res => suite4Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("failure of simple assertion") {
          runLog(test5).map(res => test5Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("multiple nested failures") {
          runLog(test6).map(res => test6Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("labeled failures") {
          runLog(test7).map(res => test7Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        },
        test("labeled failures for assertTrue") {
          for {
            log <- runLog(test9)
          } yield assertTrue(log.contains("third"), log.contains("fourth"))
        },
        test("negated failures") {
          runLog(test8).map(res => test8Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
        }
      ),
      suite("Runtime exception reporting")(
        test("ExecutionEvent.RuntimeFailure  Runtime does not swallow error") {
          val expectedLabel            = "RuntimeFailure label"
          val expectedExceptionMessage = "boom"
          for {
            result <- ZIO.succeed(
                        DefaultTestReporter.render(
                          ExecutionEvent.RuntimeFailure(
                            SuiteId(1),
                            labelsReversed = List(expectedLabel),
                            failure = TestFailure.failCause(
                              zio.Cause.Die(new RuntimeException(expectedExceptionMessage), ZTrace.none)
                            ),
                            ancestors = List.empty
                          ),
                          true
                        )
                      )
            res <- extractSingleExecutionResult(result)
          } yield assertTrue(res.resultType == Test) && assertTrue(res.status == Failed) && assertTrue(
            res.label == expectedLabel
          ) && assertTrue(res.streamingLines.exists(_.fragments.exists(_.text.contains(expectedLabel)))) && assertTrue(
            res.streamingLines.exists(_.fragments.exists(_.text.contains(expectedExceptionMessage)))
          )
        },
        test("ExecutionEvent.RuntimeFailure  Assertion does not swallow error") {
          val expectedLabel = "RuntimeFailure assertion label"
          for {
            result <- ZIO.succeed(
                        DefaultTestReporter.render(
                          ExecutionEvent.RuntimeFailure(
                            SuiteId(1),
                            labelsReversed = List(expectedLabel),
                            failure = TestFailure.assertion(assertTrue(true)),
                            ancestors = List.empty
                          ),
                          true
                        )
                      )
            res <- extractSingleExecutionResult(result)
          } yield assertTrue(res.resultType == Test) && assertTrue(res.status == Failed) && assertTrue(
            res.label == expectedLabel
          ) && assertTrue(res.streamingLines.exists(_.fragments.exists(_.text.contains(expectedLabel))))
        }
      )
    ) @@ silent

  private def extractSingleExecutionResult(results: Seq[ExecutionResult]): ZIO[Any, String, ExecutionResult] =
    results match {
      case res :: others =>
        if (others.isEmpty)
          ZIO.succeed(res)
        else ZIO.fail("More than one ExecutionResult returned")
      case _ => ZIO.fail("No ExecutionResults returned")
    }

}
