package zio.internal

import zio.Duration

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

private final class WeakConcurrentBagGc[A <: AnyRef] private (
  bag: WeakConcurrentBag[A],
  sleepFor: Duration
) extends Thread {
  override def run(): Unit = {
    val sleepForNanos = sleepFor.toNanos
    while (!isInterrupted) {
      LockSupport.parkNanos(sleepForNanos)
      bag.gc(false)
    }
  }
}

private object WeakConcurrentBagGc {
  private val i = new AtomicInteger(0)

  def start[A <: AnyRef](bag: WeakConcurrentBag[A], every: Duration): Unit = {
    assert(every.toMillis >= 1000, "Auto-gc interval must be >= 1 second")

    val thread = new WeakConcurrentBagGc(bag, every)
    thread.setName(s"zio.internal.WeakConcurrentBag.GcThread-${i.getAndIncrement()}")
    thread.setPriority(4)
    thread.setDaemon(true)
    thread.start()
  }

}
