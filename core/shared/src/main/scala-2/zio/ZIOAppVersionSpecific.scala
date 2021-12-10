package zio

trait ZIOAppVersionSpecific { self: ZIOApp =>

  /**
   * This implicit conversion macro will ensure that the provided ZIO effect
   * does not require more than the provided environment.
   *
   * If it is missing requirements, it will report a descriptive error message.
   * Otherwise, the effect will be returned unmodified.
   */
  implicit def validateEnv[R, E, A](zio: ZIO[R, E, A]): ZIO[Environment with ZEnv with ZIOAppArgs, E, A] =
    macro internal.macros.LayerMacros.validate[Environment with ZEnv with ZIOAppArgs, R]

}
