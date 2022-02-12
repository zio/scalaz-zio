package zio

import zio.internal.macros.LayerMacros

trait ZManagedVersionSpecific[-R, +E, +A] { self: ZManaged[R, E, A] =>
  /**
   * Automatically constructs the part of the environment that is not part of the `ZEnv`,
   * leaving an effect that only depends on the `ZEnv`. This will also satisfy transitive
   * `ZEnv` requirements with `ZEnv.any`, allowing them to be provided later.
   *
   * {{{
   * val managed: ZManaged[OldLady with Console, Nothing, Unit] = ???
   * val oldLadyLayer: ZLayer[Fly, Nothing, OldLady] = ???
   * val flyLayer: ZLayer[Blocking, Nothing, Fly] = ???
   *
   * // The ZEnv you use later will provide both Blocking to flyLayer and Console to managed
   * val managed2 : ZManaged[ZEnv, Nothing, Unit] = managed.provideCustom(oldLadyLayer, flyLayer)
   * }}}
   */
  inline def provideCustom[E1 >: E](inline layer: ZLayer[_,E1,_]*): ZManaged[ZEnv, E1, A] =
    ${ZManagedMacros.provideImpl[ZEnv, R, E1, A]('self, 'layer)}


  /**
   * Splits the environment into two parts, assembling one part using the
   * specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val managed: ZIO[Clock with Random, Nothing, Unit] = ???
   *
   * val managed2 = managed.provideSome[Random](clockLayer)
   * }}}
   */
  def provideSome[R0] =
    new provideSomeZManagedPartiallyApplied[R0, R, E, A](self)

  /**
   * Automatically assembles a layer for the ZManaged effect,
   * which translates it to another level.
   */
  inline def provide[E1 >: E](inline layer: ZLayer[_,E1,_]*): ZManaged[Any, E1, A] =
    ${ZManagedMacros.provideImpl[Any, R, E1, A]('self, 'layer)}
}

private final class provideSomeZManagedPartiallyApplied[R0, -R, +E, +A](val self: ZManaged[R, E, A]) extends AnyVal {
  inline def apply[E1 >: E](inline layer: ZLayer[_, E1, _]*): ZManaged[R0, E1, A] =
    ${ZManagedMacros.provideImpl[R0, R, E1, A]('self, 'layer)}
}


object ZManagedMacros {
  import scala.quoted._

  def provideImpl[R0: Type, R: Type, E: Type, A: Type](schedule: Expr[ZManaged[R, E, A]], layer: Expr[Seq[ZLayer[_, E, _]]])(using Quotes):
  Expr[ZManaged[R0, E, A]] = {
    val layerExpr = LayerMacros.constructLayer[R0, R, E](layer)
    '{
      $schedule.provideLayer($layerExpr.asInstanceOf[ZLayer[R0, E, R]])
    }
  }
}