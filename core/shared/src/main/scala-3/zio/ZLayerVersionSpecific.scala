package zio

import zio.internal.macros.LayerMacros

private[zio] transparent trait ZLayerVersionSpecific[-R, +E, +A] { self: ZLayer[R, E, A] =>
  inline def runWith[E1 >: E](
    inline layer: ZLayer[_, E1, _]*
  )(using ev: A IsSubtypeOfOutput Unit): ZIO[Any, E1, Unit] =
    ${ LayerMacros.runWithImpl[R, E1]('{ self.asInstanceOf[ZLayer[R, E, Unit]] }, 'layer) }
}
