package zio.blocks.schema

// Must be pure data
sealed trait Modifier extends scala.annotation.StaticAnnotation
object Modifier {
  sealed trait Term[A] extends Modifier
  sealed trait Record  extends Modifier
  sealed trait Variant extends Modifier
  sealed trait Dynamic extends Modifier
}
