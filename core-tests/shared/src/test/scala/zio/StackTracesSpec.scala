package zio

import zio.test.Assertion.{containsString, matchesRegex}
import zio.test.{TestResult, assert, assertTrue}
import zio.test.TestAspect.sequential

object StackTracesSpec extends ZIOBaseSpec {

  def spec = suite("StackTracesSpec")(
    suite("captureSimpleCause")(
      test("captures a simple failure") {
        for {
          _     <- ZIO.succeed(25)
          value  = ZIO.fail("Oh no!")
          trace <- matchPrettyPrintCause(value)
        } yield {
          assertHasMessage(trace)("java.lang.String: Oh no!") &&
          assertHasStacktraceFor(trace)("matchPrettyPrintCause") &&
          assertTrue(!trace.contains("Suppressed"))
        }
      }
    ),
    suite("captureMultiMethod")(
      test("captures a deep embedded failure") {
        val deepUnderlyingFailure =
          for {
            _ <- ZIO.succeed(5)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("deep failure"))
          } yield f

        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- deepUnderlyingFailure.ensuring(ZIO.dieMessage("other failure"))
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          trace <- matchPrettyPrintCause(value)
        } yield {
          assertHasMessage(trace)("java.lang.String: Oh no!") &&
          assertHasStacktraceFor(trace)("spec.deepUnderlyingFailure") &&
          assertHasStacktraceFor(trace)("spec.underlyingFailure") &&
          assertHasStacktraceFor(trace)("matchPrettyPrintCause") &&
          assert(trace)(containsString("Suppressed: java.lang.RuntimeException: deep failure")) &&
          assert(trace)(containsString("Suppressed: java.lang.RuntimeException: other failure")) &&
          assertTrue(numberOfOccurrences("Suppressed")(trace) == 2)
        }
      },
      test("captures a deep embedded failure without suppressing the underlying cause") {
        val deepUnderlyingFailure =
          for {
            _ <- ZIO.succeed(5)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("deep failure"))
          } yield f

        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- deepUnderlyingFailure
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          trace <- matchPrettyPrintCause(value)
        } yield {
          assertHasMessage(trace)("java.lang.String: Oh no!") &&
          assertHasStacktraceFor(trace)("spec.deepUnderlyingFailure") &&
          assertHasStacktraceFor(trace)("spec.underlyingFailure") &&
          assertHasStacktraceFor(trace)("matchPrettyPrintCause") &&
          assert(trace)(containsString("Suppressed: java.lang.RuntimeException: deep failure")) &&
          assertTrue(numberOfOccurrences("Suppressed")(trace) == 1)
        }
      },
      test("captures the embedded failure") {
        val underlyingFailure =
          for {
            _ <- ZIO.succeed(15)
            f <- ZIO.fail("Oh no!").ensuring(ZIO.dieMessage("other failure"))
          } yield f

        for {
          _     <- ZIO.succeed(25)
          value  = underlyingFailure
          trace <- matchPrettyPrintCause(value)
        } yield {
          assertHasMessage(trace)("java.lang.String: Oh no!") &&
          assertHasStacktraceFor(trace)("spec.underlyingFailure") &&
          assertHasStacktraceFor(trace)("matchPrettyPrintCause") &&
          assert(trace)(containsString("Suppressed: java.lang.RuntimeException: other failure")) &&
          assertTrue(numberOfOccurrences("Suppressed")(trace) == 1)
        }
      },
      test("captures a die failure") {
        val underlyingFailure =
          ZIO
            .succeed("ITEM")
            .map(_ => List.empty.head)

        for {
          trace <- matchPrettyPrintCause(underlyingFailure)
        } yield {
          assertHasMessage(trace)("java.util.NoSuchElementException: head of empty list") &&
          assertHasStacktraceFor(trace)("spec.underlyingFailure") &&
          assertHasStacktraceFor(trace)("matchPrettyPrintCause")
        }
      }
    )
  ) @@ sequential

  // set to true to print traces
  private val debug = false

  private def show(trace: => Cause[Any]): Unit =
    if (debug) {
      println("*****")
      println(trace.prettyPrint)
    }

  private def assertHasMessage(trace: String): String => TestResult =
    errorMessage => assert(trace)(containsString(errorMessage))

  private def assertHasStacktraceFor(trace: String): String => TestResult = subject =>
    assert(trace)(matchesRegex(s"""(?s).*at zio\\.StackTracesSpec.?\\.$subject.*\\(.*:\\d*\\).*"""))

  private def numberOfOccurrences(text: String): String => Int = stack =>
    (stack.length - stack.replace(text, "").length) / text.length

  private val matchPrettyPrintCause: ZIO[Any, String, Nothing] => ZIO[Any, Throwable, String] = {
    case fail: IO[String, Nothing] =>
      fail.catchAllCause { cause =>
        ZIO.succeed(show(cause)) *> ZIO.attempt(cause.prettyPrint)
      }
  }
}
