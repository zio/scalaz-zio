package zio.blocks.schema

// Must be pure data
sealed trait Modifier extends scala.annotation.StaticAnnotation
object Modifier {
  sealed trait Term     extends Modifier
  case object transient extends Term

  sealed trait Record extends Modifier

  sealed trait Variant extends Modifier

  sealed trait Dynamic extends Modifier

  /**
   * A configuration key-value pair, which can be attached to any type of
   * reflective value. The convention for keys is `<format>.<property>`. For
   * example, `protobuf.field-id` is a key that specifies the field id for a
   * protobuf format.
   */
  final case class config(key: String, value: String) extends Term with Record with Variant with Dynamic
}
