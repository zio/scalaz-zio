package zio.internal

/**
 * JCToolsQueue is defined only under `benchmarks` so `coreJVM` doesn't have
 * extra dependency on the whole JCTools.
 */
class JCToolsQueue[A](desiredCapacity: Int) extends MutableConcurrentQueue[A] {
  private val jctools = new org.jctools.queues.MpmcArrayQueue[A](desiredCapacity)

  override val capacity: Int = jctools.capacity()

  override def size(): Int = jctools.size()

  override def enqueuedCount(): Long = jctools.currentProducerIndex()

  override def dequeuedCount(): Long = jctools.currentConsumerIndex()

  override def offer(a: A): Boolean = jctools.offer(a)

  override def poll(default: A): A = {
    val res = jctools.poll()
    if (res != null) res else default
  }

  override def isEmpty(): Boolean = jctools.isEmpty()

  override def isFull(): Boolean =
    jctools.currentConsumerIndex() + jctools.capacity() - 1 == jctools.currentProducerIndex()
}
