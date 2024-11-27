package zio

import zio.internal.macros.ZLayerMakeMacros

private[zio] trait ZLayerVersionSpecific[-R, +E, +A] { self: ZLayer[R, E, A] =>

  def runWith[E1 >: E](layers: ZLayer[_, E1, _]*)(implicit ev: A IsSubtypeOfOutput Unit): ZIO[Any, E1, Unit] =
    macro ZLayerMakeMacros.runWithImpl[R, E1, A]

}
