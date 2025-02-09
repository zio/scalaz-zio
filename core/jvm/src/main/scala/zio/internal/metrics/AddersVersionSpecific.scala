package zio.internal.metrics

private[zio] trait AddersVersionSpecific {
  type DoubleAdder = java.util.concurrent.atomic.DoubleAdder
}
