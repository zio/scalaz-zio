package zio.test

import zio.test.Assertion.equalTo
import zio.test.ReportingTestUtils._
import zio.test.render.IntelliJRenderer
import zio.{Chunk, ExecutionStrategy, Scope, Trace, ZIO}

object IntellijRendererSpec extends ZIOBaseSpec {
  import IntelliJRenderUtils._

  def spec =
    suite("IntelliJ Renderer")(
      test("correctly reports a successful test") {
        assertZIO(runLog(test1))(equalTo(test1Expected.mkString))
      },
      test("correctly reports a failed test") {
        runLog(test3).map(res => test3Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports successful test suite") {
        assertZIO(runLog(suite1))(equalTo(suite1Expected.mkString))
      },
      test("correctly reports failed test suite") {
        runLog(suite2).map(res => suite2Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports multiple test suites") {
        runLog(suite3).map(res => suite3Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports empty test suite") {
        runLog(suite4).map(res => suite4Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports failure of simple assertion") {
        runLog(test5).map(res => test5Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports labeled failures") {
        runLog(test7).map(res => test7Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      },
      test("correctly reports negated failures") {
        runLog(test8).map(res => test8Expected.map(expected => containsUnstyled(res, expected)).reduce(_ && _))
      }
    ) @@ TestAspect.scala2Only

  def test1Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Addition works fine"),
    testFinished("Addition works fine")
  )

  def test2Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Subtraction works fine"),
    testFinished("Subtraction works fine")
  )

  def test3Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Value falls within range"),
    testFailed(
      "Value falls within range",
      Vector(
        withOffset(2)("✗ 52 was not equal to 42\n"),
        withOffset(2)("52 did not satisfy equalTo(42) || (isGreaterThan(5) && isLessThan(10))\n"),
        withOffset(2)(assertSourceLocation() + "\n"),
        withOffset(2)("✗ 52 was not less than 10\n"),
        withOffset(2)("52 did not satisfy equalTo(42) || (isGreaterThan(5) && isLessThan(10))\n"),
        withOffset(2)(assertSourceLocation() + "\n")
      )
    )
  )

  def suite1Expected(implicit trace: Trace): Vector[String] = Vector(
    suiteStarted("Suite1")
  ) ++ test1Expected ++ test2Expected ++
    Vector(
      suiteFinished("Suite1")
    )

  def suite2Expected(implicit trace: Trace): Vector[String] = Vector(
    suiteStarted("Suite2")
  ) ++ test1Expected ++ test2Expected ++ test3Expected ++
    Vector(
      suiteFinished("Suite2")
    )

  def suite3Expected(implicit trace: Trace): Vector[String] = Vector(
    suiteStarted("Suite3")
  ) ++ suite1Expected ++ suite2Expected ++ test3Expected ++ Vector(
    suiteFinished("Suite3")
  )

  def suite4Expected(implicit trace: Trace): Vector[String] = Vector(
    suiteStarted("Suite4")
  ) ++ suite1Expected ++ Vector(suiteStarted("Empty"), suiteFinished("Empty")) ++
    test3Expected ++ Vector(suiteFinished("Suite4"))

  def test5Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Addition works fine"),
    testFailed(
      "Addition works fine",
      Vector(
        withOffset(2)("✗ 2 was not equal to 3\n"),
        withOffset(2)("1 + 1 did not satisfy equalTo(3)\n"),
        withOffset(2)("1 + 1 = 2\n"),
        withOffset(2)(assertSourceLocation()),
        "\n"
      )
    )
  )

  def test6Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Multiple nested failures"),
    testFailed(
      "Multiple nested failures",
      Vector(
        withOffset(2)("✗ 3 was not greater than 4\n"),
        withOffset(2)("Right(Some(3)) did not satisfy isRight(isSome(isGreaterThan(4)))\n"),
        withOffset(2)("isSome = 3\n"),
        withOffset(2)("isRight = Some(3)\n"),
        withOffset(2)("Right(Some(3)) = Right(value = Some(3))\n"),
        withOffset(2)(assertSourceLocation()),
        "\n"
      )
    )
  )

  def test7Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("labeled failures"),
    testFailed(
      "labeled failures",
      Vector(
        withOffset(2)("✗ 0 was not equal to 1\n"),
        withOffset(2)("third\n"),
        withOffset(2)("c did not satisfy isSome(equalTo(1)).label(\"third\")\n"),
        withOffset(2)("isSome = 0\n"),
        withOffset(2)("c = Some(0)\n"),
        withOffset(2)(assertSourceLocation()),
        "\n"
      )
    )
  )

  def test8Expected(implicit trace: Trace): Vector[String] = Vector(
    testStarted("Not combinator"),
    testFailed(
      "Not combinator",
      Vector(
        withOffset(2)("✗ 100 was equal to 100\n"),
        withOffset(2)("100 did not satisfy not(equalTo(100))\n"),
        withOffset(2)(assertSourceLocation()),
        "\n"
      )
    )
  )
}
object IntelliJRenderUtils {
  import IntelliJRenderer.escape

  def suiteStarted(name: String): String =
    s"##teamcity[testSuiteStarted name='$name']" + "\n"

  def suiteFinished(name: String): String =
    s"##teamcity[testSuiteFinished name='$name']" + "\n"

  def testStarted(name: String)(implicit trace: Trace): String = {
    val location = Option(trace).collect { case Trace(_, file, line) =>
      (file, line)
    }

    val loc = location.fold("") { case (file, line) => s"file://$file:$line" }
    s"##teamcity[testStarted name='$name' locationHint='$loc' captureStandardOutput='true']" + "\n"
  }

  def testFinished(name: String): String =
    s"##teamcity[testFinished name='$name' duration='0']" + "\n"

  def testFailed(name: String, error: Vector[String]): String =
    s"##teamcity[testFailed name='$name' message='Assertion failed:' details='${escape(error.mkString)}']" + "\n"

  def containsUnstyled(string: String, substring: String)(implicit trace: Trace): TestResult =
    assertTrue(unstyled(string).contains(unstyled(substring)))

  def unstyled(str: String): String =
    str
      .replaceAll("\u001B\\|\\[\\d+m", "")
      .replaceAll("\\|n", "\n")

  object TestRenderer extends ReporterEventRenderer {
    override def render(executionEvent: ExecutionEvent)(implicit trace: Trace): Chunk[String] = {
      val event = executionEvent match {
        case t @ ExecutionEvent.Test(_, _, _, _, _, _) => t.copy(duration = 0L)
        case other                                     => other
      }
      Chunk.fromIterable(
        IntelliJRenderer
          .render(event, includeCause = false)
      )
    }
  }

  def runLog(
    spec: Spec[TestEnvironment, String]
  )(implicit trace: Trace): ZIO[TestEnvironment with Scope, Nothing, String] =
    for {
      console <- ZIO.console
      _ <- TestTestRunner(testEnvironment, sinkLayer(console, TestRenderer))
             .run(spec, ExecutionStrategy.Sequential) // to ensure deterministic output
      output <- TestConsole.output
    } yield output.mkString
}
