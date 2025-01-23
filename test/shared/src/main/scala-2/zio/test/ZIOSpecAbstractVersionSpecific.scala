package zio.test

private[test] trait ZIOSpecAbstractVersionSpecific {

  /**
   * This implicit conversion macro will ensure that the provided ZIO effect
   * does not require more than the provided environment.
   *
   * If it is missing requirements, it will report a descriptive error message.
   * Otherwise, the effect will be returned unmodified.
   */
  implicit def validateEnv[R1, R, E](spec: Spec[R, E]): Spec[R1, E] =
    macro SpecLayerMacros.validate[R1, R]

}
