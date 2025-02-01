package zio.blocks.schema

import zio.blocks.schema.binding.{BindingType, HasBinding}

final case class Derivation[TC[_], T, A](derivation: TC[A], summoned: Option[TC[A]]) {
  def preferSummoned: Derivation[TC, T, A] = copy(derivation = summoned.getOrElse(derivation))
}
object Derivation {}

trait HasDerivation[F[_, _], TC[_]] {
  def derivation[T, A](fa: F[T, A]): Derivation[TC, T, A]
}

trait Deriver[TC[_]] { self =>
  def derivePrimitive[F[_, _], A](prim: Reflect.Primitive[F, A]): TC[A]

  def deriveRecord[F[_, _], A](
    fields: List[Term[F, A, ?]],
    typeName: TypeName[A],
    doc: Doc,
    modifiers: List[Modifier.Record]
  )(implicit F: HasBinding[F]): TC[A]

  def deriveVariant[F[_, _], A](
    cases: List[Term[F, A, ?]],
    typeName: TypeName[A],
    doc: Doc,
    modifiers: List[Modifier.Variant]
  )(implicit F: HasBinding[F]): TC[A]

  def deriveSeq[F[_, _], C[_], A](
    element: Reflect[F, A],
    typeName: TypeName[C[A]],
    doc: Doc,
    modifiers: List[Modifier.Seq]
  )(implicit F: HasBinding[F]): TC[C[A]]

  def deriveMap[F[_, _], M[_, _], K, V](
    key: Reflect[F, K],
    value: Reflect[F, V],
    typeName: TypeName[M[K, V]],
    doc: Doc,
    modifiers: List[Modifier.Map]
  )(implicit F: HasBinding[F]): TC[M[K, V]]

  def preferSummoned: Deriver[TC] = ???
}
