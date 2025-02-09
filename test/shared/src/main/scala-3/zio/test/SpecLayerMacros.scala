package zio.test

import zio.internal.macros.LayerMacroUtils._
import scala.quoted._
import zio.internal.macros._
import zio._

object SpecLayerMacros {
  def provideImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[ZLayer[_, E, _]]])(using
    Quotes
  ): Expr[Spec[R0, E]] = {
    val expr = LayerMacros.constructLayer[R0, R, E](layer)
    '{ $spec.provideLayer($expr) }
  }

  def provideSharedImpl[R0: Type, R: Type, E: Type](spec: Expr[Spec[R, E]], layer: Expr[Seq[ZLayer[_, E, _]]])(using
    Quotes
  ): Expr[Spec[R0, E]] = {
    val expr = LayerMacros.constructLayer[R0, R, E](layer)
    '{ $spec.provideLayerShared($expr) }
  }
}
