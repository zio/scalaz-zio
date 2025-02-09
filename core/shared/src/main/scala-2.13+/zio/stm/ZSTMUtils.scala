package zio.stm

import scala.collection.{mutable, SortedSet, immutable}

private object ZSTMUtils {

  def newMutableMap[K, V](expectedNumElements: Int): mutable.HashMap[K, V] = {
    val size = if (expectedNumElements <= 3) 4 else Math.ceil(expectedNumElements / 0.75d).toInt
    new mutable.HashMap[K, V](size, 0.75d)
  }

  @inline def newImmutableTreeSet[A](set: SortedSet[A])(implicit ord: Ordering[A]): immutable.TreeSet[A] =
    immutable.TreeSet.from(set)

}
