package zio

private[zio] trait QueuePlatformSpecific {
  type ConcurrentDeque[A] = java.util.concurrent.ConcurrentLinkedDeque[A]
}
