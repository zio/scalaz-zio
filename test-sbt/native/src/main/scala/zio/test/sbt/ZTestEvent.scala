package zio.test.sbt

import sbt.testing._
import zio.test.render.ConsoleRenderer
import zio.test.render.LogLine.{Line, Message}
import zio.test.{ExecutionEvent, TestAnnotation, TestSuccess}

final case class ZTestEvent(
  fullyQualifiedName0: String,
  selector0: Selector,
  status0: Status,
  maybeThrowable: Option[Throwable],
  duration0: Long,
  fingerprint0: Fingerprint
) extends Event {
  override def fullyQualifiedName(): String = fullyQualifiedName0
  override def selector(): Selector         = selector0
  override def status(): Status             = status0
  override def duration(): Long             = duration0
  override def fingerprint(): Fingerprint   = fingerprint0
  def throwable(): OptionalThrowable        = maybeThrowable.fold(new OptionalThrowable())(new OptionalThrowable(_))

}

object ZTestEvent {
  def convertEvent(test: ExecutionEvent.Test[_], taskDef: TaskDef): Event = {
    val status = statusFrom(test)
    val maybeThrowable = status match {
      case Status.Failure =>
        // Includes ansii colors
        val failureMsg =
          ConsoleRenderer
            .renderToStringLines(Message(ConsoleRenderer.render(test, true).map(Line.fromString(_))))
            .mkString("\n")
        Some(new Exception(failureMsg))
      case _ => None
    }

    ZTestEvent(
      fullyQualifiedName0 = taskDef.fullyQualifiedName(),
      selector0 = new TestSelector(test.labels.mkString(" - ")),
      status0 = status,
      maybeThrowable = maybeThrowable,
      duration0 = test.annotations.get(TestAnnotation.timing).toMillis,
      fingerprint0 = ZioSpecFingerprint
    )
  }

  private def statusFrom(test: ExecutionEvent.Test[_]): Status =
    test.test match {
      case Left(_) => Status.Failure
      case Right(value) =>
        value match {
          case TestSuccess.Succeeded(_) => Status.Success
          case TestSuccess.Ignored(_)   => Status.Ignored
        }
    }
}
