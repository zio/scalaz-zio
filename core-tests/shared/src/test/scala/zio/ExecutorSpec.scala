package zio

import zio.test.Assertion._
import zio.test._

import java.util.concurrent.RejectedExecutionException
import scala.concurrent.ExecutionContext

final class TestExecutor(val submitResult: Boolean) extends Executor {
  val here: Boolean                                                = true
  def shutdown(): Unit                                             = ()
  def submit(runnable: Runnable)(implicit unsafe: Unsafe): Boolean = submitResult
  def yieldOpCount: Int                                            = 1
  def metrics(implicit unsafe: Unsafe): None.type                  = None
}

final class CheckPrintThrowable extends Throwable {
  var printed = false

  override def printStackTrace(): Unit = printed = true
}

object TestExecutor {
  val failing = new TestExecutor(submitResult = false)
  val y       = new TestExecutor(submitResult = true)
  val u       = new TestExecutor(submitResult = true)

  val badEC: ExecutionContext = new ExecutionContext {
    override def execute(r: Runnable): Unit            = throw new RejectedExecutionException("Rejected: " + r.toString)
    override def reportFailure(cause: Throwable): Unit = ()
  }

  val ec: ExecutionContext = new ExecutionContext {
    override def execute(r: Runnable): Unit            = ()
    override def reportFailure(cause: Throwable): Unit = ()
  }

  val runnable: Runnable = new Runnable {
    override def run(): Unit = ()
  }
}

object ExecutorSpec extends ZIOBaseSpec {

  def spec = suite("ExecutorSpec")(
    suite("Create the default unyielding executor and check that:")(
      test("When converted to an EC, it reports Throwables to stdout") {
        val t = new CheckPrintThrowable
        TestExecutor.failing.asExecutionContext.reportFailure(t)
        assert(t.printed)(isTrue)
      }
    ),
    suite("Create an executor that cannot have tasks submitted to and check that:")(
      test("It throws an exception upon submission") {
        assert(Unsafe.unsafe(implicit unsafe => TestExecutor.failing.submitOrThrow(TestExecutor.runnable)))(
          throwsA[RejectedExecutionException]
        )
      },
      test("When converted to Java, it throws an exception upon calling execute") {
        assert(TestExecutor.failing.asJava.execute(TestExecutor.runnable))(throwsA[RejectedExecutionException])
      }
    ),
    suite("Create a yielding executor and check that:")(
      test("Runnables can be submitted ") {
        assert(Unsafe.unsafe(implicit unsafe => TestExecutor.y.submitOrThrow(TestExecutor.runnable)))(
          not(throwsA[RejectedExecutionException])
        )
      },
      test("When converted to an ExecutionContext, it accepts Runnables") {
        assert(TestExecutor.y.asExecutionContext.execute(TestExecutor.runnable))(
          not(throwsA[RejectedExecutionException])
        )
      },
      test("When created from an EC, must not throw when fed an effect ") {
        assert(Unsafe.unsafe { implicit unsafe =>
          Executor.fromExecutionContext(TestExecutor.ec).submit(TestExecutor.runnable)
        })(
          not(throwsA[RejectedExecutionException])
        )
      },
      test("When converted to Java, it accepts Runnables") {
        assert(TestExecutor.y.asJava.execute(TestExecutor.runnable))(not(throwsA[RejectedExecutionException]))
      }
    ),
    suite("Create an unyielding executor and check that:")(
      test("Runnables can be submitted") {
        assert(Unsafe.unsafe(implicit unsafe => TestExecutor.u.submitOrThrow(TestExecutor.runnable)))(
          not(throwsA[RejectedExecutionException])
        )
      },
      test("When converted to an ExecutionContext, it accepts Runnables") {
        assert(TestExecutor.u.asExecutionContext.execute(TestExecutor.runnable))(
          not(throwsA[RejectedExecutionException])
        )
      },
      test("When converted to Java, it accepts Runnables") {
        assert(TestExecutor.u.asJava.execute(TestExecutor.runnable))(not(throwsA[RejectedExecutionException]))
      }
    )
  )
}
