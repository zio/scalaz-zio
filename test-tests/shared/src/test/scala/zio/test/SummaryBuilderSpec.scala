package zio.test

import zio.test.Assertion._
import zio.test.ReportingTestUtils._
import zio.test.TestAspect.silent
import zio.internal.macros.StringUtils.StringOps

object SummaryBuilderSpec extends ZIOBaseSpec {

  def summarize(log: Vector[String]): String =
    log.mkString("\n").stripLineEnd + "\n"

  def labelOnly(log: Vector[String]): String =
    log.take(1).mkString.stripLineEnd

  private def containsUnstyled(string: String, substring: String): TestResult =
    substring.unstyled.split("\n").map(line => assertTrue(string.unstyled.contains(line))).reduce(_ && _)

  def spec =
    suite("SummaryBuilderSpec")(
      test("doesn't generate summary for a successful test")(
        assertZIO(runSummary(test1))(equalTo(""))
      ),
      test("includes a failed test")(
        runSummary(test3).map(str => containsUnstyled(str, summarize(test3Expected())))
      ),
      test("doesn't generate summary for a successful test suite")(
        assertZIO(runSummary(suite1))(equalTo(""))
      ),
      test("correctly reports failed test suite")(
        runSummary(suite2).map(res => containsUnstyled(res, summarize(suite2ExpectedSummary)))
      ),
      test("correctly reports multiple test suites")(
        runSummary(suite3).map(res => containsUnstyled(res, summarize(suite3ExpectedSummary)))
      ),
      test("correctly reports failure of simple assertion")(
        runSummary(test5).map(str => containsUnstyled(str, summarize(test5Expected)))
      )
    ) @@ silent
}
