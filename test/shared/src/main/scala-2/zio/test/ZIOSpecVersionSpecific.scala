package zio.test

private[test] trait ZIOSpecVersionSpecific[R] {
  // SCALA 2

  def suiteAll(name: String)(spec: Any): Spec[Nothing, Any] =
    macro SmartSpecMacros.suiteImpl
}
