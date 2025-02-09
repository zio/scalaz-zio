package zio.test.sbt

import sbt.testing.{EventHandler, Logger, Task, TaskDef, TestSelector}
import zio.{CancelableFuture, Console, Scope, Trace, Unsafe, ZEnvironment, ZIO, ZIOAppArgs, ZLayer}
import zio.test._

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import zio.stacktracer.TracingImplicits.disableAutoTrace

abstract class BaseTestTask[T](
  taskDef0: TaskDef,
  val testClassLoader: ClassLoader,
  val sendSummary: SendSummary,
  val args: TestArgs,
  val spec: ZIOSpecAbstract,
  val runtime: zio.Runtime[T],
  val console: Console
) extends Task {

  final override def taskDef(): TaskDef = taskDef0

  protected def sharedFilledTestLayer(implicit
    trace: Trace
  ): ZLayer[Any, Nothing, TestEnvironment with ZIOAppArgs with Scope] =
    ZIOAppArgs.empty +!+ testEnvironment +!+ Scope.default

  private[zio] def run(
    eventHandlerZ: ZTestEventHandler
  )(implicit trace: Trace): ZIO[Any, Throwable, Unit] = {
    val fullArgs = searchTermsFromSelectors(taskDef(), spec.spec) match {
      case Nil   => args
      case terms => args.copy(testSearchTerms = args.testSearchTerms ++ terms)
    }
    (for {
      summary <-
        spec.runSpecWithSharedRuntimeLayer(
          taskDef0.fullyQualifiedName(),
          spec.spec,
          fullArgs,
          runtime,
          eventHandlerZ,
          console
        )
      _ <- sendSummary.provideEnvironment(ZEnvironment(summary))
    } yield ())
      .provideLayer(sharedFilledTestLayer)
  }

  /**
   * If this task def contains only `TestSelector`s, returns the search terms to
   * add to the test arguments, so that only the test cases specified in the
   * `TestSelector`s are executed. If this task def contains any other type of
   * selector, no search terms are returned and the entire suite will be
   * executed.
   *
   * @param td
   *   The task def whose selectors to inspect.
   * @param spec
   *   The spec to filter.
   * @return
   *   The search term corresponding to the tests that are selected.
   */
  private def searchTermsFromSelectors(td: TaskDef, spec: Spec[_, _]): List[String] =
    spec.caseValue match {
      // Test events' names are prefixed with the top level label and a dash. We need to remove that prefix
      // in order to create the appropriate search term.
      case Spec.LabeledCase(label, _) if td.selectors().forall(_.isInstanceOf[TestSelector]) =>
        val prefix = s"$label - "
        val terms  = td.selectors().toList.collect { case ts: TestSelector => ts.testName().stripPrefix(prefix) }
        terms
      case _ => Nil
    }

  override def execute(eventHandler: EventHandler, loggers: Array[Logger]): Array[Task] = {
    implicit val trace                    = Trace.empty
    val zTestHandler                      = new ZTestEventHandlerSbt(eventHandler, taskDef(), args.testRenderer)
    var resOutter: CancelableFuture[Unit] = null
    try {
      val res: CancelableFuture[Unit] =
        runtime.unsafe.runToFuture(run(zTestHandler))(trace, Unsafe.unsafe)

      resOutter = res
      Await.result(res, Duration.Inf)
      Array()
    } catch {
      case t: Throwable =>
        if (resOutter != null) resOutter.cancel()
        throw t
    }
  }

  override def tags(): Array[String] = Array.empty
}
