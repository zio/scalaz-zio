package zio

import zio.test._
import zio.test.TestAspect._
import java.io.{ByteArrayOutputStream, PrintStream}

object FiberFailureSpec extends ZIOBaseSpec {

  val expectedStackTraceElements = Seq(
    "FiberFailure",
    "apply",
    "getOrThrowFiberFailure"
  )

  def spec = suite("FiberFailureSpec")(
    test("FiberFailure getStackTrace includes relevant ZIO stack traces") {
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail("boom")).getOrThrowFiberFailure()
        }

      val stackTrace = ZIO
        .attempt(subcall())
        .catchAll {
          case fiberFailure: FiberFailure =>
            val stackTraceStr = fiberFailure.getStackTrace.map(_.toString).mkString("\n")
            ZIO.succeed(stackTraceStr)
          case other =>
            ZIO.succeed(s"Unexpected failure: ${other.getMessage}")
        }
        .asInstanceOf[ZIO[Any, Nothing, String]]

      stackTrace.flatMap { trace =>
        ZIO.succeed {
          assertTrue(expectedStackTraceElements.forall(element => trace.contains(element)))
        }
      }
    },
    test("FiberFailure toString should include cause and stack trace") {
      val cause        = Cause.fail(new Exception("Test Exception"))
      val fiberFailure = FiberFailure(cause)

      val toStringOutput = fiberFailure.toString

      assertTrue(
        toStringOutput.contains("Test Exception"),
        // General check for stack trace
        toStringOutput.contains("at")
      )
    },
    test("FiberFailure printStackTrace should correctly output the stack trace") {
      val cause        = Cause.fail(new Exception("Test Exception"))
      val fiberFailure = FiberFailure(cause)

      val outputStream = new ByteArrayOutputStream()
      val printStream  = new PrintStream(outputStream)

      fiberFailure.printStackTrace(printStream)

      val stackTraceOutput = new String(outputStream.toByteArray)

      assertTrue(
        stackTraceOutput.contains("FiberFailure"),
        stackTraceOutput.contains("Test Exception")
      )
    },
    test("FiberFailure captures the stack trace for ZIO.fail with String") {
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail("boom")).getOrThrowFiberFailure()
        }
      def call1(): Unit = subcall()

      val fiberFailureTest = ZIO
        .attempt(call1())
        .catchAll {
          case fiberFailure: FiberFailure =>
            val stackTrace = fiberFailure.getStackTrace.mkString("\n")
            ZIO.log(s"Captured Stack Trace:\n$stackTrace") *>
              ZIO.succeed(stackTrace)
          case other =>
            ZIO.succeed(s"Unexpected failure: ${other.getMessage}")
        }
        .asInstanceOf[ZIO[Any, Nothing, String]]

      fiberFailureTest.flatMap { stackTrace =>
        ZIO.succeed {
          assertTrue(
            stackTrace.contains("call1") &&
              stackTrace.contains("subcall") &&
              expectedStackTraceElements.forall(element => stackTrace.contains(element))
          )
        }
      }
    },
    test("FiberFailure captures the stack trace for ZIO.fail with Throwable") {
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail(new Exception("boom"))).getOrThrowFiberFailure()
        }
      def call1(): Unit = subcall()

      val fiberFailureTest = ZIO
        .attempt(call1())
        .catchAll {
          case fiberFailure: FiberFailure =>
            val stackTrace = fiberFailure.getStackTrace.mkString("\n")
            ZIO.succeed(stackTrace)
          case other =>
            ZIO.succeed(s"Unexpected failure: ${other.getMessage}")
        }
        .asInstanceOf[ZIO[Any, Nothing, String]]

      fiberFailureTest.flatMap { stackTrace =>
        ZIO.succeed {
          assertTrue(
            stackTrace.contains("call1") &&
              stackTrace.contains("subcall") &&
              expectedStackTraceElements.forall(element => stackTrace.contains(element))
          )
        }
      }
    },
    test("FiberFailure captures the stack trace for ZIO.die") {
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.die(new RuntimeException("boom"))).getOrThrowFiberFailure()
        }
      def call1(): Unit = subcall()

      val fiberFailureTest = ZIO
        .attempt(call1())
        .catchAll {
          case fiberFailure: FiberFailure =>
            val stackTrace = fiberFailure.getStackTrace.mkString("\n")
            ZIO.succeed(stackTrace)
          case other =>
            ZIO.succeed(s"Unexpected failure: ${other.getMessage}")
        }
        .asInstanceOf[ZIO[Any, Nothing, String]]

      fiberFailureTest.flatMap { stackTrace =>
        ZIO.succeed {
          assertTrue(
            stackTrace.contains("call1") &&
              stackTrace.contains("subcall") &&
              expectedStackTraceElements.forall(element => stackTrace.contains(element))
          )
        }
      }
    },
    test("getStackTrace, toString, and printStackTrace should produce identical stack traces") {

      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail("boom")).getOrThrowFiberFailure()
        }

      val result = ZIO
        .attempt(subcall())
        .catchAll {
          case fiberFailure: FiberFailure =>
            val stackTraceFromGetStackTrace = fiberFailure.getStackTrace.mkString("\n")
            val stackTraceFromToString      = fiberFailure.toString
            val stackTraceFromPrint = {
              val baos = new ByteArrayOutputStream()
              try {
                fiberFailure.printStackTrace(new PrintStream(baos))
                baos.toString
              } finally {
                baos.close()
              }
            }

            // Logging for review
            ZIO.log(s"Captured Stack Trace from getStackTrace:\n$stackTraceFromGetStackTrace") *>
              ZIO.log(s"Captured toString Output:\n$stackTraceFromToString") *>
              ZIO.log(s"Captured Stack Trace from printStackTrace:\n$stackTraceFromPrint") *>
              ZIO.succeed((stackTraceFromGetStackTrace, stackTraceFromToString, stackTraceFromPrint))
          case other =>
            ZIO.fail(new RuntimeException(s"Unexpected failure: ${other.getMessage}"))
        }
        .asInstanceOf[ZIO[Any, Nothing, (String, String, String)]]

      result.flatMap { case (stackTraceFromGetStackTrace, stackTraceFromToString, stackTraceFromPrint) =>
        // Expected stack trace format (before normalisation)
        // val expectedStackTrace =
        //   """Exception in thread "zio-fiber" java.lang.String: boom
        //     |	at zio.FiberFailureSpec.spec.subcall(FiberFailureSpec.scala:152)
        //     |Stack trace:
        //     |	at zio.FiberFailureSpec.spec.subcall(FiberFailureSpec.scala:152)
        //     |	at zio.Exit.$anonfun$getOrThrowFiberFailure$1(ZIO.scala:6469)
        //     |	at zio.Exit.getOrElse(ZIO.scala:6462)
        //     |	at zio.Exit.getOrElse$(ZIO.scala:6460)
        //     |	at zio.Exit$Failure.getOrElse(ZIO.scala:6665)
        //     |	at zio.Exit.getOrThrowFiberFailure(ZIO.scala:6469)
        //     |	at zio.Exit.getOrThrowFiberFailure$(ZIO.scala:6468)
        //     |	at zio.Exit$Failure.getOrThrowFiberFailure(ZIO.scala:6665)
        //     |	at zio.FiberFailureSpec$.$anonfun$spec$64(FiberFailureSpec.scala:152)
        //     |	at zio.Unsafe$.unsafe(Unsafe.scala:37)
        //     |	at zio.FiberFailureSpec$.subcall$5(FiberFailureSpec.scala:151)
        //     |	at zio.FiberFailureSpec$.$anonfun$spec$66(FiberFailureSpec.scala:156)
        //     |	at scala.runtime.java8.JFunction0$mcV$sp.apply(JFunction0$mcV$sp.scala:18)
        //     |	at zio.ZIOCompanionVersionSpecific.$anonfun$attempt$1(ZIOCompanionVersionSpecific.scala:100)""".stripMargin
        val normalizedGetStackTrace   = normalizeStackTrace(stackTraceFromGetStackTrace)
        val normalizedToString        = normalizeStackTraceWithCauseFilter(stackTraceFromToString)
        val normalizedPrintStackTrace = normalizeStackTraceWithCauseFilter(stackTraceFromPrint)

        // Logging the normalized stack traces for review
        ZIO.log(s"Normalized Stack Trace from getStackTrace:\n$normalizedGetStackTrace") *>
          ZIO.log(s"Normalized toString Output:\n$normalizedToString") *>
          ZIO.log(s"Normalized Stack Trace from printStackTrace:\n$normalizedPrintStackTrace") *>
          ZIO.succeed {
            assertTrue(
              normalizedGetStackTrace == normalizedToString &&
                normalizedToString == normalizedPrintStackTrace
            )
          }
      }
    },
    test("stack trace methods produce consistent output") {
      def call(): Unit = subcall()
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail("boom")).getOrThrowFiberFailure()
        }

      val result = ZIO.attempt(call()).catchAll {
        case ff: FiberFailure =>
          val getStackTrace = ff.getStackTrace.mkString("\n")
          val toString      = ff.toString
          val printStackTrace = {
            val sw = new java.io.StringWriter()
            ff.printStackTrace(new java.io.PrintWriter(sw))
            sw.toString
          }
          ZIO.succeed((getStackTrace, toString, printStackTrace))
        case other =>
          ZIO.fail(new RuntimeException(s"Unexpected: $other"))
      }

      result.map { case (getStackTrace, toString, printStackTrace) =>
        assertTrue(
          // Verify methods produce consistent output
          normalizeTrace(toString) == normalizeTrace(printStackTrace),
          normalizeTrace(getStackTrace) == normalizeTrace(toString),

          // Verify user methods are included
          toString.contains("call"),
          toString.contains("subcall"),

          // Verify internal methods are excluded
          !toString.contains("runLoop"),
          !toString.contains("zio.internal")
        )
      }
    },
    test("stack trace properly separates Java and ZIO traces") {
      def deepCall(): Unit = subcall()
      def midCall(): Unit  = deepCall()
      def call(): Unit     = midCall()
      def subcall(): Unit =
        Unsafe.unsafe { implicit unsafe =>
          Runtime.default.unsafe.run(ZIO.fail("boom")).getOrThrowFiberFailure()
        }

      val result = ZIO.attempt(call()).catchAll {
        case ff: FiberFailure =>
          val trace = ff.toString
          ZIO.succeed(trace)
        case other =>
          ZIO.fail(new RuntimeException(s"Unexpected: $other"))
      }

      result.map { trace =>
        assertTrue(
          // Verify full call chain is present
          trace.contains("deepCall") &&
            trace.contains("midCall") &&
            trace.contains("call"),

          // Verify proper ordering - Java traces should come before ZIO traces
          trace.indexOf("call") < trace.indexOf("zio."),

          // Verify internal ZIO methods are excluded
          !trace.contains("runLoop"),
          !trace.contains("zio.internal")
        )
      }
    }
  ) @@ exceptJS

  /**
   * Normalizes a stack trace string by cleaning up individual lines.
   *   - Removes file names, line numbers, and thread information.
   *   - Adds the "at " prefix where missing.
   *   - Filters out empty and duplicate lines.
   *
   * @param stackTrace
   *   The raw stack trace as a string.
   * @return
   *   A normalized stack trace string.
   */
  private def normalizeStackTrace(stackTrace: String): String =
    stackTrace
      .split("\n") // Split the stack trace into individual lines
      .map { line =>
        val trimmedLine = line.trim
          .replaceAll("""\([^)]*\)""", "")                       // Remove file names and line numbers
          .replaceAll("""^\s*Exception in thread \".*\" """, "") // Remove thread info

        // Only add "at " prefix if the line is not empty and doesn't already have it
        if (trimmedLine.nonEmpty && !trimmedLine.startsWith("at ")) s"at $trimmedLine"
        else trimmedLine
      }
      .filterNot(_.trim.isEmpty) // Filter out empty or incomplete lines
      .distinct                  // Remove duplicate lines to avoid redundancy
      .mkString("\n")            // Join the cleaned lines back into a single string

  // Helper method to filter out the cause and normalize the remaining stack trace
  private def normalizeStackTraceWithCauseFilter(trace: String): String = {
    val filteredTrace = trace
      .split("\n")
      .dropWhile(line => line.contains("boom") || line.contains("Exception in thread"))

    normalizeStackTrace(filteredTrace.mkString("\n"))
  }
}
