package zio

trait NonEmptyOps[+A, CC[+_], EC[+_]] {
  def collect[B](pf: PartialFunction[A, B]): EC[B]
  def exists(p: A => Boolean): Boolean
  def filter(p: A => Boolean): EC[A]
  def filterNot(p: A => Boolean): EC[A]
  def find(p: A => Boolean): Option[A]
  def foldLeft[B](z: B)(op: (B, A) => B): B
  def forall(p: A => Boolean): Boolean
  def grouped(size: Int): Iterator[CC[A]]
  def head: A
  def init: EC[A]
  def iterator: Iterator[A]
  def last: A
  def map[B](f: A => B): CC[B]
  def reduce[B >: A](op: (B, B) => B): B
  def tail: EC[A]
  def zip[B](that: CC[B])(implicit zippable: Zippable[A, B]): CC[zippable.Out]
  def zipWithIndex: CC[(A, Int)]
}
