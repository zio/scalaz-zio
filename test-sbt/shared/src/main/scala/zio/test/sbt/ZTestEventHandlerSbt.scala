package zio.test.sbt

import sbt.testing.{EventHandler, Status, TaskDef}
import zio.test.render.TestRenderer
import zio.{Exit, Semaphore, UIO, Unsafe, ZIO}
import zio.test.{ExecutionEvent, TestAnnotation, TestFailure, ZTestEventHandler}

/**
 * Reports test results to SBT, ensuring that the `test` task fails if any ZIO
 * test instances fail
 *
 * @param eventHandler
 *   The underlying handler provided by SBT
 * @param taskDef
 *   The test task that we are reporting for
 */
class ZTestEventHandlerSbt(eventHandler: EventHandler, taskDef: TaskDef, renderer: TestRenderer)
    extends ZTestEventHandler {
  val semaphore = Semaphore.unsafe.make(1L)(Unsafe.unsafe)

  def handle(event: ExecutionEvent): UIO[Unit] =
    event match {
      // TODO Is there a non-sbt version of this I need to add similar handling to?
      case _: ExecutionEvent.TestStarted => Exit.unit
      case evt: ExecutionEvent.Test[?] =>
        val zTestEvent = ZTestEvent.convertEvent(evt, taskDef, renderer)
        semaphore.withPermit(ZIO.succeed(eventHandler.handle(zTestEvent)))
      case _: ExecutionEvent.SectionStart  => Exit.unit
      case _: ExecutionEvent.SectionEnd    => Exit.unit
      case _: ExecutionEvent.TopLevelFlush => Exit.unit
      case f: ExecutionEvent.RuntimeFailure[Any] =>
        f.failure match {
          case _: TestFailure.Assertion => Exit.unit // Assertion failures all come through Execution.Test path above
          case rt: TestFailure.Runtime[?] =>
            val zTestEvent = ZTestEvent(
              taskDef.fullyQualifiedName(),
              taskDef.selectors().head,
              Status.Failure,
              rt.cause.dieOption,
              rt.annotations.get(TestAnnotation.timing).toMillis,
              ZioSpecFingerprint
            )
            semaphore.withPermit(ZIO.succeed(eventHandler.handle(zTestEvent)))
        }

    }
}
