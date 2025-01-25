package zio.blocks.schema

// FIXME: Add all primitive types, including date/time, currency, etc.
sealed trait PrimitiveType[A] {
  def validation: Validation[A]
}
object PrimitiveType {
  sealed trait Val[A <: AnyVal] extends PrimitiveType[A]
  sealed trait Ref[A <: AnyRef] extends PrimitiveType[A]

  case object Unit extends Val[scala.Unit] {
    def validation: Validation[scala.Unit] = Validation.None
  }
  case object Byte extends Val[scala.Byte] {
    def validation: Validation[scala.Byte] = Validation.None
  }
  case object Boolean extends Val[scala.Boolean] {
    def validation: Validation[scala.Boolean] = Validation.None
  }
  final case class Short(validation: Validation[scala.Short])    extends Val[scala.Short]
  final case class Int(validation: Validation[scala.Int])        extends Val[scala.Int]
  final case class Long(validation: Validation[scala.Long])      extends Val[scala.Long]
  final case class Float(validation: Validation[scala.Float])    extends Val[scala.Float]
  final case class Double(validation: Validation[scala.Double])  extends Val[scala.Double]
  final case class Char(validation: Validation[scala.Char])      extends Val[scala.Char]
  final case class String(validation: Validation[Predef.String]) extends Ref[Predef.String]

}
