package zio.test

import zio.ZLayer
import zio.internal.macros.LayerMacros

private[test] trait SpecVersionSpecific[-R, +E] { self: Spec[R, E] =>

  type ZSpec[-R, +E, +T] = Spec[R, E]

  /**
   * Automatically assembles a layer for the spec, translating it up a level.
   */
  def provide[E1 >: E](layer: ZLayer[_, E1, _]*): ZSpec[Any, E1, TestSuccess] =
    macro LayerMacros.provideImpl[ZSpec, R, E1, TestSuccess]

  /**
   * Automatically constructs the part of the environment that is not part of
   * the `TestEnvironment`, leaving an effect that only depends on the
   * `TestEnvironment`. This will also satisfy transitive `TestEnvironment`
   * requirements with `TestEnvironment.any`, allowing them to be provided
   * later.
   *
   * {{{
   * val spec: ZIO[UserRepo with Console, Nothing, Unit] = ???
   * val userRepoLayer: ZLayer[Database, Nothing, UserRepo = ???
   * val databaseLayer: ZLayer[Clock, Nothing, Database] = ???
   *
   * // The TestEnvironment you use later will provide Clock to
   * // `databaseLayer` and Console to `spec`
   * val spec2 : ZIO[TestEnvironment, Nothing, Unit] =
   *   spec.provideCustom(userRepoLayer, databaseLayer)
   * }}}
   */
  @deprecated("use provide", "2.0.2")
  def provideCustom[E1 >: E](layer: ZLayer[_, E1, _]*): ZSpec[TestEnvironment, E1, TestSuccess] =
    macro LayerMacros.provideCustomImpl[ZSpec, TestEnvironment, R, E1, TestSuccess]

  /**
   * Splits the environment into two parts, providing each test with one part
   * using the specified layer and leaving the remainder `R0`.
   *
   * {{{
   * val spec: ZSpec[Clock with Random, Nothing] = ???
   * val clockLayer: ZLayer[Any, Nothing, Clock] = ???
   *
   * val spec2: ZSpec[Random, Nothing] = spec.provideSome[Random](clockLayer)
   * }}}
   */
  def provideSome[R0]: ProvideSomePartiallyApplied[R0, R, E] =
    new ProvideSomePartiallyApplied[R0, R, E](self)

  /**
   * Automatically assembles a layer for the spec, sharing services between all
   * tests.
   */
  def provideShared[E1 >: E](layer: ZLayer[_, E1, _]*): Spec[Any, E1] =
    macro SpecLayerMacros.provideSharedImpl[R, E1]

  /**
   * Automatically constructs the part of the environment that is not part of
   * the `TestEnvironment`, leaving an effect that only depends on the
   * `TestEnvironment`, sharing services between all tests.
   *
   * This will also satisfy transitive `TestEnvironment` requirements with
   * `TestEnvironment.any`, allowing them to be provided later.
   *
   * {{{
   * val spec: ZIO[UserRepo with Console, Nothing, Unit] = ???
   * val userRepoLayer: ZLayer[Database, Nothing, UserRepo = ???
   * val databaseLayer: ZLayer[Clock, Nothing, Database] = ???
   *
   * // The TestEnvironment you use later will provide Clock to
   * // `databaseLayer` and Console to `spec`
   * val spec2 : ZIO[TestEnvironment, Nothing, Unit] =
   *   spec.provideCustomShared(userRepoLayer, databaseLayer)
   * }}}
   */
  @deprecated("use provideShared", "2.0.2")
  def provideCustomShared[E1 >: E](layer: ZLayer[_, E1, _]*): Spec[TestEnvironment, E1] =
    macro SpecLayerMacros.provideCustomSharedImpl[R, E1]

  /**
   * Splits the environment into two parts, providing all tests with a shared
   * version of one part using the specified layer and leaving the remainder
   * `R0`.
   *
   * {{{
   * val spec: ZSpec[Int with Random, Nothing] = ???
   * val intLayer: ZLayer[Any, Nothing, Int] = ???
   *
   * val spec2 = spec.provideSomeShared[Random](intLayer)
   * }}}
   */
  final def provideSomeShared[R0]: ProvideSomeSharedPartiallyApplied[R0, R, E] =
    new ProvideSomeSharedPartiallyApplied[R0, R, E](self)
}

final class ProvideSomePartiallyApplied[R0, -R, +E](val self: Spec[R, E]) extends AnyVal {

  type ZSpec[-R, +E, +T] = Spec[R, E]

  def provideLayer[E1 >: E](
    layer: ZLayer[R0, E1, R]
  ): Spec[R0, E1] =
    self.provideLayer(layer)

  def apply[E1 >: E](layer: ZLayer[_, E1, _]*): ZSpec[R0, E1, TestSuccess] =
    macro LayerMacros.provideSomeImpl[ZSpec, R0, R, E1, TestSuccess]
}

final class ProvideSomeSharedPartiallyApplied[R0, -R, +E](val self: Spec[R, E]) extends AnyVal {
  def provideSomeLayerShared: Spec.ProvideSomeLayerShared[R0, R, E] =
    new Spec.ProvideSomeLayerShared[R0, R, E](self)

  def apply[E1 >: E](layer: ZLayer[_, E1, _]*): Spec[R0, E1] =
    macro SpecLayerMacros.provideSomeSharedImpl[R0, R, E1]
}
