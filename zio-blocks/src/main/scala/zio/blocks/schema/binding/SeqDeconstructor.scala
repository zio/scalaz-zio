package zio.blocks.schema.binding

trait SeqDeconstructor[C[_]] {
  def deconstruct[A](c: C[A]): Iterator[A]
}
object SeqDeconstructor {
  sealed trait Indexed[C[_]] extends SeqDeconstructor[C] {
    def length[A](c: C[A]): Int

    def objectAt[A](c: C[A], index: Int): A

    def byteAt(c: C[Byte], index: Int): Byte

    def shortAt(c: C[Short], index: Int): Short

    def intAt(c: C[Int], index: Int): Int

    def longAt(c: C[Long], index: Int): Long

    def floatAt(c: C[Float], index: Int): Float

    def doubleAt(c: C[Double], index: Int): Double

    def charAt(c: C[Char], index: Int): Char
  }

  def apply[C[_]](implicit sd: SeqDeconstructor[C]): SeqDeconstructor[C] = sd

  implicit val setDeconstructor: SeqDeconstructor[Set] = new SeqDeconstructor[Set] {
    def deconstruct[A](c: Set[A]): Iterator[A] = c.iterator
  }

  implicit val listDeconstructor: SeqDeconstructor[List] = new SeqDeconstructor[List] {
    def deconstruct[A](c: List[A]): Iterator[A] = c.iterator
  }

  implicit val vectorDeconstructor: SeqDeconstructor[Vector] = new SeqDeconstructor[Vector] {
    def deconstruct[A](c: Vector[A]): Iterator[A] = c.iterator
  }

  implicit val arrayDeconstructor: Indexed[Array] = new Indexed[Array] {
    def deconstruct[A](c: Array[A]): Iterator[A] = c.iterator

    def length[A](c: Array[A]): Int = c.length

    def objectAt[A](c: Array[A], index: Int): A = c(index)

    def byteAt(c: Array[Byte], index: Int): Byte = c(index)

    def shortAt(c: Array[Short], index: Int): Short = c(index)

    def intAt(c: Array[Int], index: Int): Int = c(index)

    def longAt(c: Array[Long], index: Int): Long = c(index)

    def floatAt(c: Array[Float], index: Int): Float = c(index)

    def doubleAt(c: Array[Double], index: Int): Double = c(index)

    def charAt(c: Array[Char], index: Int): Char = c(index)
  }
}
