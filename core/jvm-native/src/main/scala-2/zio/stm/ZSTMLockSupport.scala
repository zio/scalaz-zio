package zio.stm

import java.util.concurrent.locks.ReentrantLock
import scala.collection.SortedSet

private object ZSTMLockSupport {

  type Lock = ReentrantLock
  object Lock {
    def apply(fair: Boolean = false): Lock = new ReentrantLock(fair)
  }

  @inline def lock[A](refs: SortedSet[TRef[?]])(f: => A): Unit =
    refs.size match {
      case 0 => f
      case 1 =>
        val lock = refs.head.lock
        lock1(lock)(f)
      case _ => lockN(refs)(f)
    }

  @inline private def lock1[A](lock: Lock)(f: => A): Unit = {
    lock.lock()
    try f
    finally lock.unlock()
  }

  @inline private def lockN[A](refs: SortedSet[TRef[?]])(f: => A): Unit = {
    refs.foreach(_.lock.lock())
    try f
    finally refs.foreach(_.lock.unlock())
  }

  @inline def tryLock[A](refs: SortedSet[TRef[?]])(f: => A): Boolean =
    refs.size match {
      case 0 => f; true
      case 1 =>
        val lock = refs.head.lock
        tryLock(lock)(f)
      case n => tryLockN(refs, n)(f)
    }

  @inline def tryLock[A](lock: Lock)(f: => A): Boolean =
    if (lock.tryLock()) {
      try { f; true }
      finally lock.unlock()
    } else false

  @inline private def tryLockN[A](refs: SortedSet[TRef[?]], size: Int)(f: => A): Boolean = {
    val acquired = Array.ofDim[ReentrantLock](size)
    var locked   = true
    val it       = refs.iterator
    var i        = 0
    while (i < size && locked) {
      val lock = it.next().lock
      if (lock.tryLock()) {
        acquired(i) = lock
        i += 1
      } else locked = false
    }
    try {
      if (locked) { f; true }
      else false
    } finally unlock(acquired, i)
  }

  private def unlock(locks: Array[ReentrantLock], upTo: Int): Unit = {
    var i = 0
    while (i < upTo) {
      locks(i).unlock()
      i += 1
    }
  }
}
