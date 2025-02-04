package zio.blocks.schema.binding

trait MapConstructor[M[_, _]] {
  type ObjectBuilder[K, V]

  def newObjectBuilder[K, V](sizeHint: Int = -1): ObjectBuilder[K, V]

  def addObject[K, V](builder: ObjectBuilder[K, V], k: K, v: V): Unit

  def resultObject[K, V](builder: ObjectBuilder[K, V]): M[K, V]
}
object MapConstructor {
  def apply[M[_, _]](implicit mc: MapConstructor[M]): MapConstructor[M] = mc

  val map: MapConstructor[Map] = new MapConstructor[Map] {
    type ObjectBuilder[K, V] = scala.collection.mutable.Builder[(K, V), Map[K, V]]

    def newObjectBuilder[K, V](sizeHint: Int): ObjectBuilder[K, V] = Map.newBuilder[K, V]

    def addObject[K, V](builder: ObjectBuilder[K, V], k: K, v: V): Unit = builder += ((k, v))

    def resultObject[K, V](builder: ObjectBuilder[K, V]): Map[K, V] = builder.result()
  }
}
