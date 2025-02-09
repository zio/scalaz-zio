package zio

import scala.quoted._
import zio.internal.TerminalRendering

private[zio] transparent trait ZIOAppVersionSpecific {

  /**
   * This implicit conversion macro will ensure that the provided ZIO effect
   * does not require more than the provided environment.
   *
   * If it is missing requirements, it will report a descriptive error message.
   * Otherwise, the effect will be returned unmodified.
   */
  inline implicit def validateEnv[R1, R, E, A](inline zio: ZIO[R, E, A]): ZIO[R1, E, A] =
    ${ ZIOAppVersionSpecificMacros.validate[R1, R, E, A]('zio) }

}

private[zio] object ZIOAppVersionSpecificMacros {
  def validate[Provided: Type, Required: Type, E: Type, A: Type](zio: Expr[ZIO[Required, E, A]])(using ctx: Quotes) =
    new ZIOAppVersionSpecificMacros(ctx).validate[Provided, Required, E, A](zio)
}

private[zio] class ZIOAppVersionSpecificMacros(val ctx: Quotes) {
  given Quotes = ctx
  import ctx.reflect._

  def validate[Provided: Type, Required: Type, E: Type, A: Type](zio: Expr[ZIO[Required, E, A]]) = {

    val required = flattenAnd(TypeRepr.of[Required])
    val provided = flattenAnd(TypeRepr.of[Provided])

    val missing =
      required.toSet -- provided.toSet

    if (missing.nonEmpty) {
      val message = TerminalRendering.missingLayersForZIOApp(missing.map(_.show))
      report.errorAndAbort(message)
    }

    zio.asInstanceOf[Expr[ZIO[Provided, E, A]]]
  }

  def flattenAnd(typeRepr: TypeRepr): List[TypeRepr] =
    typeRepr.dealias match {
      case AndType(left, right) =>
        flattenAnd(left) ++ flattenAnd(right)
      case _ =>
        List(typeRepr)
    }

}
