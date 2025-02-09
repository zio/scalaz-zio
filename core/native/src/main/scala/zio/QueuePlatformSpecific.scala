package zio

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable

private[zio] trait QueuePlatformSpecific {

  // java.util.concurrent.ConcurrentLinkedDeque is not available in Scala Native, so we need to createa custom `addFirst` method
  private[zio] final class ConcurrentDeque[A <: AnyRef] extends ConcurrentLinkedQueue[A] {

    def addFirst(a: A): Unit = {
      var popped = poll()
      if (popped eq null) {
        offer(a)
      } else {
        val buf = new mutable.ArrayBuffer[A]
        while (popped ne null) {
          buf += popped
          popped = poll()
        }
        offer(a)
        buf.foreach(offer)
      }
    }

  }
}
