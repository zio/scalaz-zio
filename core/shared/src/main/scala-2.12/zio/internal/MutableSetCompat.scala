package zio.internal

import scala.collection.mutable

trait MutableSetCompat[V] extends mutable.Set[V] {

  override def +=(element: V): this.type = {
    add(element)
    this
  }

  override def -=(element: V): this.type = {
    remove(element)
    this
  }

}
