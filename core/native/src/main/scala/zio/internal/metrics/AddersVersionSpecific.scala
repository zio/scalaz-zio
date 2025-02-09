package zio.internal.metrics

private[zio] trait AddersVersionSpecific {
  protected final class DoubleAdder {
    private val ref = AtomicDouble.make(0.0d)

    def add(v: Double): Unit = ref.updateAndGet(_ + v)
    def sum(): Double        = ref.get()
  }
}
