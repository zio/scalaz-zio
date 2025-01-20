package zio.blocks.schema.binding

final case class Matchers[+A](matchers: IndexedSeq[Matcher[? <: A]]) {
  def apply(i: Int): Matcher[? <: A] = matchers(i)
}
object Matchers {
  def apply[A](matchers: Matcher[? <: A]*): Matchers[A] = new Matchers(matchers.toArray.toIndexedSeq)
}
