package zio.stm

private object ZSTMLockSupport {

  final class Lock
  object Lock {
    def apply(fair: Boolean = false): Lock = new Lock
  }

  @inline def lock[A](refs: collection.SortedSet[TRef[?]])(f: => A): Boolean = {
    val _ = f
    true
  }

  @inline def tryLock[A](refs: collection.SortedSet[TRef[?]])(f: => A): Boolean = {
    val _ = f
    true
  }

  @inline def tryLock[A](lock: Lock)(f: => A): Boolean = {
    val _ = f
    true
  }
}
