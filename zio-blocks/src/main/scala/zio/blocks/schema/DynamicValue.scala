package zio.blocks.schema

import zio.blocks.schema.binding._

sealed trait DynamicValue
object DynamicValue {
  def constructorFor[F[_, _], A](reflect: Reflect[F, A]): Constructor[DynamicValue] = ???

  def deconstructorFor[F[_, _], A](reflect: Reflect[F, A]): Deconstructor[DynamicValue] = ???

  final case class Record(fields: IndexedSeq[(String, DynamicValue)])     extends DynamicValue
  final case class Variant(caseName: String, value: DynamicValue)         extends DynamicValue
  final case class Sequence(elements: IndexedSeq[DynamicValue])           extends DynamicValue
  final case class Map(entries: IndexedSeq[(DynamicValue, DynamicValue)]) extends DynamicValue
  final case class Primitive(value: PrimitiveValue)                       extends DynamicValue
}
