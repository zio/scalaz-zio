package zio.test

import scala.quoted._
import zio.internal.TerminalRendering

private[test] trait ZIOSpecAbstractVersionSpecific {

  /**
   * This implicit conversion macro will ensure that the provided ZIO effect
   * does not require more than the provided environment.
   *
   * If it is missing requirements, it will report a descriptive error message.
   * Otherwise, the effect will be returned unmodified.
   */
  inline implicit def validateEnv[R1, R, E, A](inline spec: Spec[R, E]): Spec[R1, E] =
    ${ ZIOSpecAbstractSpecificMacros.validate[R1, R, E]('spec) }

}

private[test] object ZIOSpecAbstractSpecificMacros {
  def validate[Provided: Type, Required: Type, E: Type](spec: Expr[Spec[Required, E]])(using ctx: Quotes) =
    new ZIOSpecAbstractSpecificMacros(ctx).validate[Provided, Required, E](spec)
}

private[test] class ZIOSpecAbstractSpecificMacros(val ctx: Quotes) {
  given Quotes = ctx
  import ctx.reflect._

  def validate[Provided: Type, Required: Type, E: Type](spec: Expr[Spec[Required, E]]) = {

    val required = flattenAnd(TypeRepr.of[Required])
    val provided = flattenAnd(TypeRepr.of[Provided])

    val missing =
      required.toSet -- provided.toSet

    if (missing.nonEmpty) {
      val message = TerminalRendering.missingLayersForZIOSpec(missing.map(_.show))
      report.errorAndAbort(message)
    }

    spec.asInstanceOf[Expr[Spec[Provided, E]]]
  }

  def flattenAnd(typeRepr: TypeRepr): List[TypeRepr] =
    typeRepr.dealias match {
      case AndType(left, right) =>
        flattenAnd(left) ++ flattenAnd(right)
      case _ =>
        List(typeRepr)
    }

}
