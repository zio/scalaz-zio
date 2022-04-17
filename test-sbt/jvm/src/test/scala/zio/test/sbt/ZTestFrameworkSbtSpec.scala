package zio.test.sbt

import sbt.testing._
import zio.test.Assertion.equalTo
import zio.test.ExecutionEvent.{RuntimeFailure, SectionEnd, SectionStart, Test}
import zio.test.render.ConsoleRenderer
import zio.test.sbt.TestingSupport._
import zio.test.{assertCompletes, assert => _, test => _, _}
import zio.{ZEnvironment, ZIO, ZTraceElement, durationInt}

import java.util.regex.Pattern
import scala.collection.mutable.ArrayBuffer
import scala.util.Try

object ZTestFrameworkSbtSpec {

  def main(args: Array[String]): Unit =
    run(tests: _*)

  def tests: Seq[Try[Unit]] = Seq(
    test("should return correct fingerprints")(testFingerprints()),
    // TODO restore once we are calculating durations again. Fix for #6482
    //test("should report durations")(testReportDurations()),
//    test("should log messages")(testLogMessages()),
//    test("should correctly display colorized output for multi-line strings")(testColored()),
//    test("should test only selected test")(testTestSelection()),
//    test("should return summary when done")(testSummary()),
    test("should use a shared layer without re-initializing it")(testSharedLayer())
//    test("should warn when no tests are executed")(testNoTestsExecutedWarning())
  )

  def testFingerprints(): Unit = {
    val fingerprints = new ZTestFramework().fingerprints.toSeq
    assertEquals("fingerprints", fingerprints, Seq(ZioSpecFingerprint))
  }

  val dummyHandler: EventHandler = (_: Event) => ()

  def testReportDurations(): Unit = {
    val loggers  = Seq(new MockLogger)
    val reported = ArrayBuffer[ExecutionEvent]()

    loadAndExecute(TimedSharedSpec, loggers = loggers)

    assert(reported.nonEmpty)
    reported.foreach(println)
    assert(
      reported.forall(event =>
        event match {
          case Test(_, _, _, _, duration, _) => duration > 0
          case RuntimeFailure(_, _, _, _)    => false
          case SectionStart(_, _, _)         => false
          case SectionEnd(_, _, _)           => false
        }
      ),
      s"reported events should have positive durations: $reported"
    )
  }

  def testLogMessages()(implicit trace: ZTraceElement): Unit = {
    val loggers = Seq(new MockLogger)

    loadAndExecute(FrameworkSpecInstances.SimpleFailingSharedSpec, loggers = loggers)

    loggers.map(_.messages.map(_.withNoLineNumbers)) foreach { messages =>
      assertContains(
        "logs success",
        messages,
        Seq(
          s"${reset("info:")}     ${green("+")} passing test"
        )
      )
      assertContains(
        "logs errors",
        messages,
        Seq(
          s"${reset("info:")}     ${red("- failing test")}",
          s"${reset("info:")}       ${blue("1")} did not satisfy ${cyan("equalTo(2)")}",
          s"${reset("info:")}       ${assertSourceLocation()}"
        )
      )
      assertContains(
        "logs ignored message",
        messages,
        Seq(
          s"${reset("info:")}     ${yellow("-")} ${yellow("ignored test")}"
        )
      )
    }
  }

  def testColored(): Unit = {
    val loggers = Seq.fill(3)(new MockLogger)

    loadAndExecute(FrameworkSpecInstances.MultiLineSharedSpec, loggers = loggers)
    loggers.map(_.messages) foreach (messages =>
      assertEquals(
        "logged messages",
        messages.drop(1).mkString("\n").withNoLineNumbers,
        List(
          s"${reset("info: ")}  ${red("- multi-line test")}",
          s"${reset("info: ")}    ${Console.BLUE}Hello,",
          s"${reset("info: ")}${blue("World!")} did not satisfy ${cyan("equalTo(Hello, World!)")}",
          s"${reset("info: ")}    ${assertSourceLocation()}",
          s"""${reset("info: ")}${ConsoleRenderer.render(Summary(0, 1, 0, ""))}"""
        ).mkString("\n")
      )
    )
  }

  def testTestSelection(): Unit = {
    val loggers = Seq(new MockLogger)

    loadAndExecute(
      FrameworkSpecInstances.SimpleFailingSharedSpec,
      loggers = loggers,
      testArgs = Array("-t", "passing test")
    )

    loggers.map(_.messages) foreach { messages =>
      val results = messages.drop(1).mkString("\n")
      assertEquals(
        "logged messages",
        results,
        List(
          s"${reset("info:")} ${green("+")} some suite",
          s"${reset("info:")}     ${green("+")} passing test",
          s"""${reset("info: ")}${ConsoleRenderer.render(Summary(1, 0, 0, ""))}"""
        ).mkString("\n")
      )
    }
  }

  val randomFailure =
    zio.test.assert(new java.util.Random().nextInt())(equalTo(2))

  def numberedTest(specIdx: Int, suiteIdx: Int, testIdx: Int) =
    zio.test.test(s"spec $specIdx suite $suiteIdx test $testIdx") {
      assertCompletes
    }

  def testSharedLayer(): Unit = {

    val loggers = Seq(new MockLogger)
    loadAndExecuteAll(Seq.fill(3)(FrameworkSpecInstances.Spec1UsingSharedLayer), loggers, Array.empty)

    assert(FrameworkSpecInstances.counter.get() == 1)
  }

  def testSummary(): Unit = {
    val taskDef = new TaskDef(FrameworkSpecInstances.failingSpecFQN, ZioSpecFingerprint, false, Array())
    val runner  = new ZTestFramework().runner(Array(), Array(), getClass.getClassLoader)

    val task = runner
      .tasks(Array(taskDef))
      .map(task => task.asInstanceOf[ZTestTask])
      .map { zTestTask =>
        new ZTestTask(
          zTestTask.taskDef,
          zTestTask.testClassLoader,
          zTestTask.sendSummary.provideEnvironment(ZEnvironment(Summary(1, 0, 0, "foo"))),
          TestArgs.empty,
          zTestTask.spec
        )
      }
      .head

    task.execute(_ => (), Array.empty)

    assertEquals("done contains summary", runner.done(), "foo\nDone")
  }

  def testNoTestsExecutedWarning(): Unit = {
    val taskDef =
      new TaskDef(FrameworkSpecInstances.failingSpecFQN, ZioSpecFingerprint, false, Array(new TestSelector("nope")))
    val runner = new ZTestFramework().runner(Array(), Array(), getClass.getClassLoader)
    val task = runner
      .tasks(Array(taskDef))
      .map(task => task.asInstanceOf[ZTestTask])
      .map { zTestTask =>
        new ZTestTask(
          zTestTask.taskDef,
          zTestTask.testClassLoader,
          zTestTask.sendSummary.provideEnvironment(ZEnvironment(Summary(0, 0, 0, "foo"))),
          TestArgs.empty,
          zTestTask.spec
        )
      }
      .head

    task.execute(_ => (), Array.empty)

    assertEquals("warning is displayed", runner.done(), s"${Console.YELLOW}No tests were executed${Console.RESET}")
  }

  private def loadAndExecute[T <: ZIOSpecAbstract](
    fqn: T,
    loggers: Seq[Logger],
    testArgs: Array[String] = Array.empty
  ) =
    loadAndExecuteAll(Seq(fqn), loggers, testArgs)

  private def loadAndExecuteAll[T <: ZIOSpecAbstract](
    fqns: Seq[T],
    loggers: Seq[Logger],
    testArgs: Array[String]
  ) = {

    val tasks =
      fqns
        .map(_.getClass.getName)
        .map(fqn => new TaskDef(fqn, ZioSpecFingerprint, false, Array(new SuiteSelector)))
        .toArray
    val task = new ZTestFramework()
      .runner(testArgs, Array(), getClass.getClassLoader)
      .tasks(tasks)
      .head

    @scala.annotation.tailrec
    def doRun(tasks: Iterable[Task]): Unit = {
      val more = tasks.flatMap(_.execute(dummyHandler, loggers.toArray))
      if (more.nonEmpty) {
        doRun(more)
      }
    }

    doRun(Iterable(task))
  }

  lazy val timedSpecFQN = TimedSharedSpec.getClass.getName
  object TimedSharedSpec extends ZIOSpecDefault {
    override def spec = test("timed passing test") {
      zio.test.assertCompletes
    } @@ TestAspect.before(Live.live(ZIO.sleep(5.millis))) @@ TestAspect.timed
  }

  def assertSourceLocation()(implicit trace: ZTraceElement): String = {
    val filePath = Option(trace).collect { case ZTraceElement(_, file, _) =>
      file
    }
    filePath.fold("")(path => cyan(s"at $path:XXX"))
  }

  implicit class TestOutputOps(output: String) {
    def withNoLineNumbers(implicit trace: ZTraceElement): String = {
      val filePath = Option(trace).collect { case ZTraceElement(_, file, _) =>
        file
      }
      filePath.fold(output)(path => output.replaceAll(Pattern.quote(path + ":") + "\\d+", path + ":XXX"))
    }
  }
}
