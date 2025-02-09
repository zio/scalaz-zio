package zio.test.sbt

import sbt.testing.{SuiteSelector, TaskDef}
import zio.{Duration, ZIO}
import zio.test.{
  ExecutionEventSink,
  Summary,
  TestAspect,
  TestConsole,
  ZIOSpecAbstract,
  ZIOSpecDefault,
  assertCompletes,
  assertTrue,
  testConsole
}
import zio.test.render.ConsoleRenderer
import zio.test.sbt.FrameworkSpecInstances._
import zio.test.sbt.TestingSupport._

object ZTestFrameworkZioSpec extends ZIOSpecDefault {

  override def spec = suite("test framework in a more ZIO-centric way")(
    test("basic happy path")(
      for {
        _      <- loadAndExecute(SimpleSpec)
        output <- testOutput
      } yield assertTrue(
        output ==
          Vector(
            s"${green("+")} simple suite\n",
            s"  ${green("+")} spec 1 suite 1 test 1\n"
          )
      )
    ),
    test("renders suite names 1 time in plus-combined specs")(
      for {
        _      <- loadAndExecute(CombinedWithPlusSpec)
        output <- testOutput
      } yield assertTrue(output.length == 3) && (
        // Look for more generic way of asserting on these lines that can be shuffled
        assertTrue(
          output ==
            Vector(
              s"${green("+")} spec A\n",
              s"  ${green("+")} successful test\n",
              s"  ${yellow("-")} ${yellow("failing test")} - ignored: 1\n"
            )
        ) || assertTrue(
          output ==
            Vector(
              s"${green("+")} spec A\n",
              s"  ${yellow("-")} ${yellow("failing test")} - ignored: 1\n",
              s"  ${green("+")} successful test\n"
            )
        )
      )
    ),
    test("renders suite names 1 time in commas-combined specs")(
      for {
        _      <- loadAndExecute(CombinedWithCommasSpec)
        output <- testOutput
      } yield assertTrue(output.length == 3) && (
        // Look for more generic way of asserting on these lines that can be shuffled
        assertTrue(
          output ==
            Vector(
              s"${green("+")} spec A\n",
              s"  ${green("+")} successful test\n",
              s"  ${yellow("-")} ${yellow("failing test")} - ignored: 1\n"
            )
        ) || assertTrue(
          output ==
            Vector(
              s"${green("+")} spec A\n",
              s"  ${yellow("-")} ${yellow("failing test")} - ignored: 1\n",
              s"  ${green("+")} successful test\n"
            )
        )
      )
    ),
    test("displays timeouts")(
      for {
        _      <- loadAndExecute(TimeOutSpec)
        output <- testOutput
      } yield assertTrue(output.mkString("").contains("Timeout of 1 s exceeded.")) && assertTrue(output.length == 2)
    ),
    test("displays runtime exceptions helpfully")(
      for {
        _      <- loadAndExecute(RuntimeExceptionSpec)
        output <- testOutput
      } yield assertTrue(
        output.mkString("").contains("Good luck ;)")
      ) && assertTrue(output.length == 2)
    ),
    test("displays runtime exceptions during spec layer construction")(
      for {
        returnError <-
          loadAndExecuteAllZ(Seq(SimpleSpec, RuntimeExceptionDuringLayerConstructionSpec)).flip
      } yield assertTrue(returnError.exists(_.toString.contains("Other Kafka container already grabbed your port")))
    ) @@ TestAspect.nonFlaky,
    test("ensure shared layers are not re-initialized")(
      for {
        _ <- loadAndExecuteAllZ(
               Seq(FrameworkSpecInstances.Spec1UsingSharedLayer, FrameworkSpecInstances.Spec2UsingSharedLayer)
             )
      } yield assertTrue(FrameworkSpecInstances.counter.get == 1)
    ),
    test("displays multi-colored lines")(
      for {
        _ <- loadAndExecuteAllZ(Seq(FrameworkSpecInstances.MultiLineSharedSpec))
        output <-
          testOutput
        expected =
          List(
            s"  ${red("- multi-line test")}",
            s"    ${Console.BLUE}Hello,"
            //  TODO Figure out what non-printing garbage is breaking the next line
            // s"${blue("World!")} did not satisfy ${cyan("equalTo(Hello, World!)")}",
            // s"    ${assertSourceLocation()}",
            // s"""${ConsoleRenderer.render(Summary(0, 1, 0, ""))}"""
          ).mkString("\n")
      } yield assertTrue(output.mkString("").contains(expected))
    ) @@ TestAspect.ignore,
    test("only executes selected test") {
      for {
        _      <- loadAndExecute(FrameworkSpecInstances.SimpleFailingSharedSpec, testArgs = Array("-t", "passing test"))
        output <- testOutput
        expected =
          List(
            s"${green("+")} some suite\n",
            s"  ${green("+")} passing test\n"
          )

      } yield assertTrue(output.equals(expected))
    },
    test("only execute test with specified tag") {
      for {
        _      <- loadAndExecute(FrameworkSpecInstances.TagsSpec, testArgs = Array("-tags", "IntegrationTest"))
        output <- testOutput
        expected =
          List(
            s"${green("+")} tag suite\n",
            s"""  ${green("+")} integration test - tagged: "IntegrationTest"\n"""
          )
      } yield assertTrue(output.equals(expected))
    } @@ TestAspect.flaky,
    test("do not execute test with ignored tag") {
      for {
        _      <- loadAndExecute(FrameworkSpecInstances.TagsSpec, testArgs = Array("-ignore-tags", "IntegrationTest"))
        output <- testOutput
        expected =
          List(
            s"${green("+")} tag suite\n",
            s"""  ${green("+")} unit test - tagged: "UnitTest"\n"""
          )
      } yield assertTrue(output.equals(expected))
    }
  )

  private val durationPattern = "Executed in (\\d+) (.*)".r
  private def extractTestRunDuration(output: Vector[String]): zio.Duration = {
    val (testTimeNumber, testTimeUnit) = output.mkString
      .split("\n")
      .collect { case durationPattern(duration, unit) =>
        (duration, unit)
      }
      .head

    testTimeUnit match {
      case "ms"  => Duration.fromMillis(testTimeNumber.toInt.toLong)
      case "ns"  => Duration.fromNanos(testTimeNumber.toInt.toLong)
      case other => throw new IllegalStateException("Unexpected duration unit: " + other)
    }
  }

  private val testOutput =
    for {
      console <- testConsole
      output  <- console.output
    } yield output

  private def loadAndExecute[T <: ZIOSpecAbstract](
    spec: T,
    testArgs: Array[String] = Array.empty
  ): ZIO[Any, Throwable, Unit] =
    loadAndExecuteAllZ(Seq(spec), testArgs).mapError(_.head)

  private def loadAndExecuteAllZ[T <: ZIOSpecAbstract](
    specs: Seq[T],
    testArgs: Array[String] = Array.empty
  ): ZIO[Any, ::[Throwable], Unit] = {
    val tasks =
      specs
        .map(_.getClass.getName)
        .map(fqn => new TaskDef(fqn, ZioSpecFingerprint, false, Array(new SuiteSelector)))
        .toArray

    for {
      testC <- testConsole
      tasksZ <-
        ZIO
          .attemptBlocking(
            new ZTestFramework()
              .runner(testArgs, Array(), getClass.getClassLoader)
              .tasksZ(tasks, testC)
          )
          .mapError(error => ::(error, Nil))
      _ <- ZIO.validate(tasksZ.toList)(t => t.run(FrameworkSpecInstances.dummyHandler))
    } yield ()

  }
}
