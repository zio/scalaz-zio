package zio.blocks.schema

// FIXME: Add all primitive types, including date/time, currency, etc.
sealed trait PrimitiveType[A] {
  def validation: Validation[A]

  def defaultValue: Option[() => A]

  def examples: List[A]
}
object PrimitiveType {
  sealed trait Val[A <: AnyVal] extends PrimitiveType[A]
  sealed trait Ref[A <: AnyRef] extends PrimitiveType[A]

  case object Unit extends Val[scala.Unit] {
    def validation: Validation[scala.Unit] = Validation.None

    def defaultValue: Option[() => scala.Unit] = thunk

    def examples: List[scala.Unit] = List(())

    private val thunk = Some(() => ())
  }
  final case class Byte(defaultValue: Option[() => scala.Byte] = None, examples: List[scala.Byte] = Nil)
      extends Val[scala.Byte] {
    def validation: Validation[scala.Byte] = Validation.None
  }
  final case class Boolean(defaultValue: Option[() => scala.Boolean] = None) extends Val[scala.Boolean] {
    def validation: Validation[scala.Boolean] = Validation.None

    def examples: List[scala.Boolean] = List(true, false)
  }
  final case class Short(
    validation: Validation[scala.Short],
    defaultValue: Option[() => scala.Short] = None,
    examples: List[scala.Short] = Nil
  ) extends Val[scala.Short]
  final case class Int(
    validation: Validation[scala.Int],
    defaultValue: Option[() => scala.Int] = None,
    examples: List[scala.Int] = Nil
  ) extends Val[scala.Int]
  final case class Long(
    validation: Validation[scala.Long],
    defaultValue: Option[() => scala.Long] = None,
    examples: List[scala.Long] = Nil
  ) extends Val[scala.Long]
  final case class Float(
    validation: Validation[scala.Float],
    defaultValue: Option[() => scala.Float] = None,
    examples: List[scala.Float] = Nil
  ) extends Val[scala.Float]
  final case class Double(
    validation: Validation[scala.Double],
    defaultValue: Option[() => scala.Double] = None,
    examples: List[scala.Double] = Nil
  ) extends Val[scala.Double]
  final case class Char(
    validation: Validation[scala.Char],
    defaultValue: Option[() => scala.Char] = None,
    examples: List[scala.Char] = Nil
  ) extends Val[scala.Char]
  final case class String(
    validation: Validation[Predef.String],
    defaultValue: Option[() => Predef.String] = None,
    examples: List[Predef.String] = Nil
  ) extends Ref[Predef.String]

}
