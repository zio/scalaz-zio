package zio.blocks.schema.binding

trait MapDeconstructor[M[_, _]] {
  type KeyValue[K, V]

  def deconstruct[K, V](m: M[K, V]): Iterator[KeyValue[K, V]]

  def getKey[K, V](kv: KeyValue[K, V]): K

  def getValue[K, V](kv: KeyValue[K, V]): V
}
object MapDeconstructor {
  def apply[M[_, _]](implicit md: MapDeconstructor[M]): MapDeconstructor[M] = md

  implicit val map: MapDeconstructor[Map] = new MapDeconstructor[Map] {
    type KeyValue[K, V] = (K, V)

    def deconstruct[K, V](m: Map[K, V]): Iterator[(K, V)] = m.iterator

    def getKey[K, V](kv: (K, V)): K = kv._1

    def getValue[K, V](kv: (K, V)): V = kv._2
  }
}
