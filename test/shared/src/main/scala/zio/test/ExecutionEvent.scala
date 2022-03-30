package zio.test

object ExecutionEvent {

  final case class Test[+E](
    labelsReversed: List[String],
    test: Either[TestFailure[E], TestSuccess],
    annotations: TestAnnotationMap,
    ancestors: List[SuiteId],
    duration: Long = 0L,
    id: SuiteId
  ) extends ExecutionEvent {
    def labels: List[String] = labelsReversed.reverse
  }

  final case class SectionStart(
    labelsReversed: List[String],
    id: SuiteId,
    ancestors: List[SuiteId]
  ) extends ExecutionEvent {
    def labels: List[String] = labelsReversed.reverse
  }

  final case class SectionEnd(
    labelsReversed: List[String],
    id: SuiteId,
    ancestors: List[SuiteId]
  ) extends ExecutionEvent {
    def labels: List[String] = labelsReversed.reverse
  }

  final case class RuntimeFailure[+E](
    id: SuiteId,
    labelsReversed: List[String],
    failure: TestFailure[E],
    ancestors: List[SuiteId]
  ) extends ExecutionEvent {
    def labels: List[String] = labelsReversed.reverse
  }

}

sealed trait ExecutionEvent {
  val id: SuiteId
  val ancestors: List[SuiteId]
}
