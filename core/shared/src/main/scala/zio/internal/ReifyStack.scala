package zio.internal

import zio._
import zio.ZIO.EvaluationStep

import scala.util.control.NoStackTrace

private[zio] sealed abstract class ReifyStack extends Exception with NoStackTrace
object ReifyStack {
  case object AsyncJump extends ReifyStack

  final case class Trampoline(
    effect: ZIO[Any, Any, Any],
    forceYield: Boolean
  ) extends ReifyStack

  case object GenerateTrace extends ReifyStack
}
