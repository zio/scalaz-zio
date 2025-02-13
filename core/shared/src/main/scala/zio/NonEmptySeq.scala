package zio

trait NonEmptySeq[+A, CC[+_], EC[+_]] extends NonEmptyOps[A, CC, EC] {
  def appended[B >: A](elem: B): CC[B]
  def collectFirst[B](pf: PartialFunction[A, B]): Option[B]
  def distinct: CC[A]
  def prepended[B >: A](elem: B): CC[B]
  def reverse: CC[A]
  def sortBy[B](f: A => B)(implicit ord: Ordering[B]): CC[A]
  def sorted[B >: A](implicit ord: Ordering[B]): CC[B]
}
