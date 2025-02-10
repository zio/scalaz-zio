package zio

private[zio] trait TagVersionSpecific {
  implicit def materialize[A]: Tag[A] =
    macro zio.internal.macros.InternalMacros.materializeTag[A]
}
