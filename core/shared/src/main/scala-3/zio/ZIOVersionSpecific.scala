package zio

import zio.internal.macros.LayerMacros

private[zio] transparent trait ZIOVersionSpecific[-R, +E, +A] { self: ZIO[R, E, A] =>

  /**
   * Splits the environment into two parts, assembling one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val zio: ZIO[Clock with Random, Nothing, Unit] = ???
   *
   * val zio2 = zio.provideSome[Random](clockLayer)
   * }}}
   */
  def provideSome[R0] =
    new ProvideSomePartiallyApplied[R0, R, E, A](self)

  /**
   * Automatically assembles a layer for the ZIO effect, which translates it to
   * another level.
   */
  inline def provide[E1 >: E](inline layer: ZLayer[_, E1, _]*): ZIO[Any, E1, A] =
    ${ LayerMacros.provideImpl[Any, R, E1, A]('self, 'layer) }

}

final class ProvideSomePartiallyApplied[R0, -R, +E, +A](val self: ZIO[R, E, A]) extends AnyVal {
  inline def apply[E1 >: E](inline layer: ZLayer[_, E1, _]*): ZIO[R0, E1, A] =
    ${ LayerMacros.provideImpl[R0, R, E1, A]('self, 'layer) }
}
