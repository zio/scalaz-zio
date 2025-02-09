package zio.internal

import zio.internal.WeakConcurrentBag.IsAlive
import zio.{Chunk, Duration, Unsafe}

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate
import scala.annotation.tailrec

/**
 * A [[WeakConcurrentBag]] stores a collection of values that will ultimately be
 * wrapped in a `WeakReference`, if they are survive long enough in a 'nursery'.
 * The structure is optimized for addition, and will achieve zero allocations in
 * the happy path. There is no way to remove values from the bag, as they will
 * be automatically removed assuming they become unreachable.
 *
 * The larger the nursery size, the less weak references will be created,
 * because the more values will die before they are forced out of the nursery.
 * However, larger nursery sizes use more memory, and increase the worst
 * possible performance of the `add` method, which has to do maintenance of the
 * nursery and occassional garbage collection.
 */
private[zio] class WeakConcurrentBag[A <: AnyRef](nurserySize: Int, isAlive: IsAlive[A]) { self =>
  private[this] def nCpu              = java.lang.Runtime.getRuntime.availableProcessors()
  private[this] val nursery           = new PartitionedRingBuffer[WeakReference[A]](nCpu * 4, nurserySize, roundToPow2 = true)
  private[this] val nurseryActualSize = nursery.capacity

  private[this] val graduates = Platform.newConcurrentSet[WeakReference[A]](nurseryActualSize * 2)(Unsafe.unsafe)
  private[this] val gcStatus  = new AtomicBoolean(false)
  private[this] val autoGc    = new AtomicBoolean(false)

  private[this] val notAlive = new Predicate[WeakReference[A]] {
    def test(ref: WeakReference[A]): Boolean = {
      val value = ref.get()
      (value eq null) || !isAlive(value)
    }
  }

  /**
   * Schedules a thread (if not already running) which will wake up on the
   * specified interval and remove dead references from long term storage by
   * running `gc(false)`.
   *
   * @note
   *   this method is only supported on the JVM. On Scala JS and Scala Native,
   *   it is a no-op.
   */
  def withAutoGc(every: Duration): WeakConcurrentBag[A] = {
    if (autoGc.compareAndSet(false, true)) {
      WeakConcurrentBagGc.start(self, every)
    }
    self
  }

  /**
   * Adds a new value to the weak concurrent bag, graduating nursery occupants
   * if necessary to make room.
   */
  final def add(a: A): Unit = {
    val flushed = maybeFlushAndOffer(new WeakReference[A](a))

    if (flushed.nonEmpty) {
      addToLongTermStorage(flushed)
      if (graduates.size() > nurseryActualSize) gc(false)
    }
  }

  /**
   * Performs a garbage collection, which consists of traversing long-term
   * storage, identifying dead or GC'd values, and removing them.
   */
  final def gc(): Unit = gc(true)

  final def gc(force: Boolean): Unit = {
    val lockAcquired = gcStatus.compareAndSet(false, true)

    // NOTE: try-finally most probably not needed; just being extra cautious not to accidentally lock GC
    try if (force || lockAcquired) graduates.removeIf(notAlive)
    finally if (lockAcquired) gcStatus.set(false)
  }

  /**
   * Moves all occupants of the nursery into long-term storage in a concurrent
   * set, wrapped in a weak reference to avoid interfering with GC.
   *
   * This method will occassionally perform garbage collection, but only when
   * the size of long-term storage exceeds the nursery size.
   */
  final def graduate(): Unit = {
    flushNurseryToLongTermStorage()
    if (graduates.size() > nurseryActualSize) gc(false)
  }

  private def flushNurseryToLongTermStorage(): Unit = {
    val partitions = nursery.partitionIterator

    while (partitions.hasNext) {
      val partition = partitions.next()
      addToLongTermStorage(partition.pollUpTo(partition.capacity))
    }
  }

  /**
   * Attempts to offer an element to a queue partition. If the partition is
   * full, then we flush half of the partition and add the element to it
   *
   * @note
   *   We only flush half of the partition so that we give a chance to the
   *   WeakReferences to be GC'd without moving them to long-term storage
   *
   * @return
   *   the elements that were flushed from the partition
   */
  private def maybeFlushAndOffer(a: WeakReference[A]): Chunk[WeakReference[A]] = {
    val queue = nursery.randomPartition(ThreadLocalRandom.current())
    if (!queue.offer(a)) {
      val flushed = queue.pollUpTo(queue.capacity >> 1)
      // In the extremely unlikely case that the partition filled up between the poll and the offer
      // return the element as part of the flushed elements
      if (queue.offer(a)) flushed else flushed :+ a
    } else Chunk.empty
  }

  private def addToLongTermStorage(chunk: Chunk[WeakReference[A]]): Unit = {
    var i    = 0
    val iter = chunk.chunkIterator
    while (iter.hasNextAt(i)) {
      val ref   = iter.nextAt(i)
      val value = ref.get()
      if ((value ne null) && isAlive(value)) graduates.add(ref)
      i += 1
    }
  }

  /**
   * Returns a weakly consistent iterator over the bag. This iterator will never
   * throw exceptions even in the presence of concurrent modifications.
   */
  final def iterator: Iterator[A] = {
    // No need to force GC if another thread is already doing it as we'll remove the entry in the iterator below
    flushNurseryToLongTermStorage()

    new Iterator[A] {
      val it    = graduates.iterator()
      var _next = prefetch()

      @tailrec
      def prefetch(): A =
        if (it.hasNext) {
          val next = it.next().get()

          if (next eq null) {
            it.remove() // Remove dead reference since we're iterating over the set
            prefetch()
          } else next
        } else {
          null.asInstanceOf[A]
        }

      def hasNext() = _next ne null

      def next(): A =
        if (_next eq null)
          throw new NoSuchElementException("There is no more element in the weak concurrent bag iterator")
        else {
          val result = _next

          _next = prefetch()

          result
        }
    }
  }

  /**
   * Returns the approximate size of the bag.
   */
  def size = graduates.size() + nursery.size()

  override final def toString(): String = iterator.mkString("WeakConcurrentBag(", ",", ")")
}

private[zio] object WeakConcurrentBag {

  def apply[A <: AnyRef](capacity: Int, isAlive: IsAlive[A] = IsAlive.always): WeakConcurrentBag[A] =
    new WeakConcurrentBag(capacity, isAlive)

  /** Specialized Function1 that doesn't cause boxing of the Boolean */
  trait IsAlive[-A] {
    def apply(value: A): Boolean
  }

  object IsAlive {
    val always: IsAlive[Any] = _ => true
  }

}
