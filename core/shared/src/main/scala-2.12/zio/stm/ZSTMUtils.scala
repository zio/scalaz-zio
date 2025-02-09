package zio.stm

import scala.collection.{mutable, SortedSet, immutable}

private object ZSTMUtils {

  def newMutableMap[K, V](expectedNumElements: Int): mutable.HashMap[K, V] = {
    val map = new mutable.HashMap[K, V]
    if (expectedNumElements > 0) map.sizeHint(expectedNumElements)
    map
  }

  def newImmutableTreeSet[A](set: SortedSet[A])(implicit ord: Ordering[A]): immutable.TreeSet[A] =
    immutable.TreeSet.empty[A] ++ set

}
