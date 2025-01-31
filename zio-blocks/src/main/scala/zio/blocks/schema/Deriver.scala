package zio.blocks.schema

import zio.blocks.schema.binding.BindingType

final case class Derivation[TC[_], T, A](derivation: TC[A], summoned: Option[TC[A]]) {
  def preferSummoned: Derivation[TC, T, A] = copy(derivation = summoned.getOrElse(derivation))
}

trait Deriver[TC[_]] { self =>
  type F[T, A] = Derivation[TC, T, A]

  def derivePrimitive[A](prim: Reflect.Primitive[F, A]): TC[A]

  def deriveRecord[A](
    fields: List[Term[F, A, ?]],
    typeName: TypeName[A],
    doc: Doc,
    modifiers: List[Modifier.Record]
  ): TC[A]

  def deriveVariant[A](
    cases: List[Term[F, A, ?]],
    typeName: TypeName[A],
    doc: Doc,
    modifiers: List[Modifier.Variant]
  ): TC[A]

  def deriveSeq[C[_], A](
    element: Reflect[F, A],
    typeName: TypeName[C[A]],
    doc: Doc,
    modifiers: List[Modifier.Seq]
  ): TC[C[A]]

  def deriveMap[M[_, _], K, V](
    key: Reflect[F, K],
    value: Reflect[F, V],
    typeName: TypeName[M[K, V]],
    doc: Doc,
    modifiers: List[Modifier.Map]
  ): TC[M[K, V]]

  def preferSummoned: Deriver[TC] = ???
}
