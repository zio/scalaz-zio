package zio.internal

import zio.Duration

private object WeakConcurrentBagGc {
  def start[A <: AnyRef](bag: WeakConcurrentBag[A], every: Duration): Unit = ()
}
