package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

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
    ),
    suite("getOrThrowFiberFailure")(
      test("fills in the external stack trace") {
        def call(): Unit = subcall()
        def subcall2()   = ZIO.fail("boom")
        def subcall(): Unit = Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(subcall2())
            .getOrThrowFiberFailure()
        }

        val failure = ZIO.attempt(call()) *> ZIO.never
        for (trace <- matchPrettyPrintCause(failure))
          yield assertHasMessage(trace)("zio.FiberFailure: boom") &&
            assertHasStacktraceFor(trace)("spec.subcall2") &&
            assertHasStacktraceFor(trace)("spec.subcall") &&
            assertHasStacktraceFor(trace)("subcall") &&
            assertHasStacktraceFor(trace)("call")
      }
    ) @@ jvmOnly,
    suite("getOrThrow")(
      test("fills in the external stack trace (as suppressed)") {
        def call(): Unit = subcall()
        def subcall2()   = ZIO.die(new RuntimeException("boom"))
        def subcall(): Unit = Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe
            .run(subcall2())
            .getOrThrow()
        }

        val failure = ZIO.attempt(call()) *> ZIO.never
        for {
          trace      <- matchPrettyPrintCause(failure)
          suppressed <- matchPrettyPrintSuppressed(failure)
        } yield assertHasMessage(trace)("java.lang.RuntimeException: boom") &&
          assertHasStacktraceFor(trace)("spec.failure") &&
          assertHasMessage(suppressed)("zio.Cause$FiberTrace: java.lang.RuntimeException: boom") &&
          assertHasStacktraceFor(suppressed)("spec.subcall2") &&
          assertHasStacktraceFor(suppressed)("spec.subcall") &&
          assertHasStacktraceFor(suppressed)("subcall") &&
          assertHasStacktraceFor(suppressed)("call")
      }
    ) @@ jvmOnly
  ) @@ sequential

  // set to true to print traces
  private val debug = false

  private def show(trace: => Cause[Any]): Unit =
    if (debug) {
      println("*****")
      println(trace.prettyPrint)
    }

  private def assertHasMessage(trace: String): String => TestResult =
    errorMessage => assert(trace)(startsWithString(errorMessage))

  private def assertHasStacktraceFor(trace: String): String => TestResult = subject =>
    assert(trace)(matchesRegex(s"""(?s).*at zio\\.StackTracesSpec.?\\.$subject.*\\(.*:\\d*\\).*"""))

  private def numberOfOccurrences(text: String): String => Int = stack =>
    (stack.length - stack.replace(text, "").length) / text.length

  private val matchPrettyPrintCause: ZIO[Any, Any, Nothing] => ZIO[Any, Throwable, String] =
    _.catchAllCause(cause => ZIO.succeed(show(cause)) *> ZIO.attempt(cause.prettyPrint))

  private val matchPrettyPrintSuppressed: ZIO[Any, Throwable, Nothing] => ZIO[Any, Throwable, String] =
    failure => matchPrettyPrintCause(failure.mapErrorCause(cause => Cause.fail(cause.squash.getSuppressed.head)))
}
