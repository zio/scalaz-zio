package zio.stream

import zio.ZLayer
import zio.internal.macros.LayerMacros

private[stream] trait ZStreamVersionSpecific[-R, +E, +O] { self: ZStream[R, E, O] =>

  /**
   * Automatically assembles a layer for the ZStream effect, which translates it
   * to another level.
   */
  inline def provide[E1 >: E](inline layer: ZLayer[_, E1, _]*): ZStream[Any, E1, O] =
    ${ ZStreamProvideMacro.provideImpl[Any, R, E1, O]('self, 'layer) }

}

private[stream] object ZStreamProvideMacro {
  import scala.quoted._

  def provideImpl[R0: Type, R: Type, E: Type, A: Type](
    zstream: Expr[ZStream[R, E, A]],
    layer: Expr[Seq[ZLayer[_, E, _]]]
  )(using Quotes): Expr[ZStream[R0, E, A]] = {
    val layerExpr = LayerMacros.constructLayer[R0, R, E](layer)
    '{ $zstream.provideLayer($layerExpr.asInstanceOf[ZLayer[R0, E, R]]) }
  }
}
