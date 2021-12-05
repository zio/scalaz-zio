package zio.test

import zio.internal.macros.LayerMacroUtils._
import scala.quoted._
import zio.internal.macros._
import zio._

object SpecLayerMacros {
  def injectImpl[R0: Type, R: Type, E: Type, T: Type]
  (spec: Expr[Spec[R, E, T]], layer: Expr[Seq[ZLayer[_,E,_]]])(using Quotes): Expr[Spec[R0, E, T]] = {
    val expr = LayerMacros.fromAutoImpl[R0, R, E](layer).asInstanceOf[Expr[ZLayer[R0, E, R]]]
    '{$spec.provide($expr)}
  }

  def injectSharedImpl[R0: Type, R: Type, E: Type, T: Type]
  (spec: Expr[Spec[R,E,T]], layer: Expr[Seq[ZLayer[_,E,_]]])(using Quotes): Expr[Spec[R0,E,T]] = {
    val expr = LayerMacros.fromAutoImpl[R0, R, E](layer)
    '{$spec.provideShared($expr)}
  }
}
