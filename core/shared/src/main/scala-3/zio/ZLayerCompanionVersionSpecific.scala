package zio

import zio.internal.macros._
import scala.deriving._

final class WirePartiallyApplied[R](val dummy: Boolean = true) extends AnyVal {
  inline def apply[E](inline layer: ZLayer[_, E, _]*): ZLayer[Any, E, R] =
    ${ LayerMacros.constructLayer[Any, R, E]('layer) }
}

final class WireSomePartiallyApplied[R0, R](val dummy: Boolean = true) extends AnyVal {
  inline def apply[E](inline layer: ZLayer[_, E, _]*): ZLayer[R0, E, R] =
    ${ LayerMacros.constructLayer[R0, R, E]('layer) }
}

private[zio] transparent trait ZLayerCompanionVersionSpecific {

  /**
   * Automatically assembles a layer for the provided type.
   *
   * {{{
   * val layer = ZLayer.make[Car](carLayer, wheelsLayer, engineLayer)
   * }}}
   */
  inline def make[R]: WirePartiallyApplied[R] =
    new WirePartiallyApplied[R]()

  /**
   * Automatically assembles a layer for the provided type `R`, leaving a
   * remainder `R0`.
   *
   * {{{
   * val carLayer: ZLayer[Engine with Wheels, Nothing, Car] = ???
   * val wheelsLayer: ZLayer[Any, Nothing, Wheels] = ???
   *
   * val layer = ZLayer.makeSome[Engine, Car](carLayer, wheelsLayer)
   * }}}
   */
  def makeSome[R0, R] =
    new WireSomePartiallyApplied[R0, R]

  /**
   * Automatically derives a simple layer for the provided type.
   *
   * {{{
   * class Car(wheels: Wheels, engine: Engine) { /* ... */ }
   *
   * val carLayer: URLayer[Wheels & Engine, Car] = ZLayer.derive[Car]
   * }}}
   */
  transparent inline def derive[A]: ZLayer[Nothing, Any, A] =
    ZLayerDerivationMacros.deriveLayer[A]
}
