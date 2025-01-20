package zio.blocks.schema.binding

final abstract class BindingType
object BindingType {
  type Record <: BindingType
  type Variant <: BindingType
  type Seq[C[_]] <: BindingType
  type Map[M[_, _]] <: BindingType
}
