package zio.test.sbt

import sbt.testing._
import zio.duration._
import zio.test.environment.Live
import zio.test.sbt.TestingSupport._
import zio.test.{
  Annotations,
  Assertion,
  DefaultRunnableSpec,
  Spec,
  Summary,
  TestArgs,
  TestAspect,
  TestFailure,
  TestSuccess,
  ZSpec
}
import zio.{UIO, ZIO}

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object ZTestFrameworkSpec {

  def main(args: Array[String]): Unit =
    run(tests: _*)

  def tests: Seq[Try[Unit]] = Seq(
    test("should return correct fingerprints")(testFingerprints()),
    test("should report events")(testReportEvents()),
    test("should report durations")(testReportDurations()),
    test("should log messages")(testLogMessages()),
    test("should correctly display colorized output for multi-line strings")(testColored()),
    test("should test only selected test")(testTestSelection()),
    test("should return summary when done")(testSummary()),
    test("should warn when no tests are executed")(testNoTestsExecutedWarning())
  )

  def testFingerprints(): Unit = {
    val fingerprints = new ZTestFramework().fingerprints.toSeq
    assertEquals("fingerprints", fingerprints, Seq(RunnableSpecFingerprint))
  }

  def testReportEvents(): Unit = {
    val reported = ArrayBuffer[Event]()
    loadAndExecute(failingSpecFQN, reported.append(_))

    val expected = Set(
      sbtEvent(failingSpecFQN, "failing test", Status.Failure),
      sbtEvent(failingSpecFQN, "passing test", Status.Success),
      sbtEvent(failingSpecFQN, "ignored test", Status.Ignored)
    )

    assertEquals("reported events", reported.toSet, expected)
  }

  private def sbtEvent(fqn: String, label: String, status: Status) =
    ZTestEvent(fqn, new TestSelector(label), status, None, 0, RunnableSpecFingerprint)

  def testReportDurations(): Unit = {
    val reported = ArrayBuffer[Event]()
    loadAndExecute(timedSpecFQN, reported.append(_))

    assert(reported.forall(_.duration() > 0), s"reported events should have positive durations: $reported")
  }

  def testLogMessages(): Unit = {
    val loggers = Seq.fill(3)(new MockLogger)

    loadAndExecute(failingSpecFQN, loggers = loggers)

    loggers.map(_.messages) foreach (messages =>
      assertEquals(
        "logged messages",
        messages.mkString.split("\n").dropRight(1).mkString("\n").withNoLineNumbers,
        List(
          s"${reset("info:")} ${red("- some suite")} - ignored: 1",
          s"${reset("info:")}   ${red("- failing test")}",
          s"${reset("info:")}     ${blue("1")} did not satisfy ${cyan("equalTo(2)")}",
          s"${reset("info:")}     ${cyan(assertLocation)}",
          s"${reset("info:")}   ${green("+")} passing test",
          s"${reset("info:")}   ${yellow("-")} ${yellow("ignored test")} - ignored: 1"
        ).mkString("\n")
      )
    )
  }

  def testColored(): Unit = {
    val loggers = Seq.fill(3)(new MockLogger)

    loadAndExecute(multiLineSpecFQN, loggers = loggers)
    loggers.map(_.messages) foreach (messages =>
      assertEquals(
        "logged messages",
        messages.mkString.split("\n").dropRight(1).mkString("\n").withNoLineNumbers,
        List(
          s"${red("- multi-line test")}",
          s"  ${Console.BLUE}Hello,",
          s"${blue("World!")} did not satisfy ${cyan("equalTo(Hello, World!)")}",
          s"  ${cyan(assertLocation)}"
        ).mkString("\n")
          .split('\n')
          .map(s"${reset("info:")} " + _)
          .mkString("\n")
      )
    )
  }

  def testTestSelection(): Unit = {
    val loggers = Seq(new MockLogger)

    loadAndExecute(failingSpecFQN, loggers = loggers, testArgs = Array("-t", "passing test"))

    loggers.map(_.messages) foreach (messages =>
      assertEquals(
        "logged messages",
        messages.mkString.split("\n").dropRight(1).mkString("\n"),
        List(
          s"${reset("info:")} ${green("+")} some suite",
          s"${reset("info:")}   ${green("+")} passing test"
        ).mkString("\n")
      )
    )
  }

  def testSummary(): Unit = {
    val taskDef = new TaskDef(failingSpecFQN, RunnableSpecFingerprint, false, Array())
    val runner  = new ZTestFramework().runner(Array(), Array(), getClass.getClassLoader)
    val task = runner
      .tasks(Array(taskDef))
      .map(task => task.asInstanceOf[ZTestTask])
      .map { zTestTask =>
        new ZTestTask(
          zTestTask.taskDef,
          zTestTask.testClassLoader,
          UIO.succeed(Summary(1, 0, 0, "foo")) >>> zTestTask.sendSummary,
          TestArgs.empty
        )
      }
      .head

    task.execute(_ => (), Array.empty)

    assertEquals("done contains summary", runner.done(), "foo\nDone")
  }

  def testNoTestsExecutedWarning(): Unit = {
    val taskDef = new TaskDef(failingSpecFQN, RunnableSpecFingerprint, false, Array())
    val runner  = new ZTestFramework().runner(Array(), Array(), getClass.getClassLoader)
    val task = runner
      .tasks(Array(taskDef))
      .map(task => task.asInstanceOf[ZTestTask])
      .map { zTestTask =>
        new ZTestTask(
          zTestTask.taskDef,
          zTestTask.testClassLoader,
          UIO.succeed(Summary(0, 0, 0, "foo")) >>> zTestTask.sendSummary,
          TestArgs.empty
        )
      }
      .head

    task.execute(_ => (), Array.empty)

    assertEquals("warning is displayed", runner.done(), s"${Console.YELLOW}No tests were executed${Console.RESET}")
  }

  private def loadAndExecute(
    fqn: String,
    eventHandler: EventHandler = _ => (),
    loggers: Seq[Logger] = Nil,
    testArgs: Array[String] = Array.empty
  ) = {
    val taskDef = new TaskDef(fqn, RunnableSpecFingerprint, false, Array(new SuiteSelector))
    val task = new ZTestFramework()
      .runner(testArgs, Array(), getClass.getClassLoader)
      .tasks(Array(taskDef))
      .head

    @scala.annotation.tailrec
    def doRun(tasks: Iterable[Task]): Unit = {
      val more = tasks.flatMap(_.execute(eventHandler, loggers.toArray))
      if (more.nonEmpty) {
        doRun(more)
      }
    }
    doRun(Iterable(task))
  }

  lazy val failingSpecFQN = SimpleFailingSpec.getClass.getName
  object SimpleFailingSpec extends DefaultRunnableSpec {
    def spec: Spec[Annotations, TestFailure[Any], TestSuccess] = zio.test.suite("some suite")(
      test("failing test") {
        zio.test.assert(1)(Assertion.equalTo(2))
      },
      test("passing test") {
        zio.test.assert(1)(Assertion.equalTo(1))
      },
      test("ignored test") {
        zio.test.assert(1)(Assertion.equalTo(2))
      } @@ TestAspect.ignore
    )
  }

  lazy val timedSpecFQN = TimedSpec.getClass.getName
  object TimedSpec extends DefaultRunnableSpec {
    override def spec: ZSpec[Environment, Failure] = test("timed passing test") {
      zio.test.assertCompletes
    } @@ TestAspect.before(Live.live(ZIO.sleep(5.millis))) @@ TestAspect.timed
  }

  lazy val multiLineSpecFQN = MultiLineSpec.getClass.getName
  object MultiLineSpec extends DefaultRunnableSpec {
    def spec: ZSpec[Environment, Failure] = test("multi-line test") {
      zio.test.assert("Hello,\nWorld!")(Assertion.equalTo("Hello, World!"))
    }
  }

  lazy val sourceFilePath: String = zio.test.sourcePath
  lazy val assertLocation: String = s"at $sourceFilePath:XXX"
  implicit class TestOutputOps(output: String) {
    def withNoLineNumbers: String =
      output.replaceAll(Pattern.quote(sourceFilePath + ":") + "\\d+", sourceFilePath + ":XXX")
  }
}
