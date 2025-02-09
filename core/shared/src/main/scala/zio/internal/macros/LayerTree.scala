package zio.internal.macros

import zio.internal.macros.LayerTree.{ComposeH, ComposeV, Empty, Value}
import scala.collection.mutable

sealed abstract class LayerTree[+A] extends Product with Serializable { self =>

  def >>>[A1 >: A](that: LayerTree[A1]): LayerTree[A1] =
    if (self eq Empty) that else if (that eq Empty) self else ComposeV(self, that)

  def ++[A1 >: A](that: LayerTree[A1]): LayerTree[A1] =
    if (self eq Empty) that else if (that eq Empty) self else ComposeH(self, that)

  def fold[B](z: B, value: A => B, composeH: (B, B) => B, composeV: (B, B) => B): B = self match {
    case Empty         => z
    case Value(value0) => value(value0)
    case ComposeH(left, right) =>
      composeH(left.fold(z, value, composeH, composeV), right.fold(z, value, composeH, composeV))
    case ComposeV(left, right) =>
      composeV(left.fold(z, value, composeH, composeV), right.fold(z, value, composeH, composeV))
  }

  def map[B](f: A => B): LayerTree[B] =
    fold[LayerTree[B]](Empty, a => Value(f(a)), ComposeH(_, _), ComposeV(_, _))

  def toSet[A1 >: A]: Set[A1] = fold[Set[A1]](Set.empty[A1], Set(_), _ ++ _, _ ++ _)

  def toList: List[A] =
    fold[mutable.LinkedHashSet[A]](mutable.LinkedHashSet.empty[A], mutable.LinkedHashSet(_), _ ++ _, _ ++ _).toList
}

object LayerTree {
  def succeed[A](value: A): LayerTree[A] = Value(value)
  def empty: LayerTree[Nothing]          = Empty

  case object Empty                                                      extends LayerTree[Nothing]
  final case class Value[+A](value: A)                                   extends LayerTree[A]
  final case class ComposeH[+A](left: LayerTree[A], right: LayerTree[A]) extends LayerTree[A]
  final case class ComposeV[+A](left: LayerTree[A], right: LayerTree[A]) extends LayerTree[A]

  implicit final class LayerComposeIterableOps[A](private val self: Iterable[LayerTree[A]]) extends AnyVal {
    def combineHorizontally: LayerTree[A] = self.foldLeft[LayerTree[A]](Empty)(_ ++ _)
  }
}
