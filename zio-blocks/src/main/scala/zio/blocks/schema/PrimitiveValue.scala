package zio.blocks.schema

sealed trait PrimitiveValue {
  type Type

  def primitiveType: PrimitiveType[Type]
}
object PrimitiveValue {
  sealed trait Val extends PrimitiveValue {
    type Type <: AnyVal
  }
  sealed trait Ref extends PrimitiveValue {
    type Type <: AnyRef
  }
  case object Unit extends Val {
    final type Type = scala.Unit

    def primitiveType: PrimitiveType[scala.Unit] = PrimitiveType.Unit
  }
  final case class Boolean(value: scala.Boolean) extends Val {
    final type Type = scala.Boolean

    def primitiveType: PrimitiveType[scala.Boolean] = PrimitiveType.Boolean()
  }
  final case class Byte(value: scala.Byte) extends Val {
    final type Type = scala.Byte

    def primitiveType: PrimitiveType[scala.Byte] = PrimitiveType.Byte()
  }
  final case class Short(value: scala.Short) extends Val {
    final type Type = scala.Short

    def primitiveType: PrimitiveType[scala.Short] = PrimitiveType.Short(Validation.None)
  }
  final case class Int(value: scala.Int) extends Val {
    final type Type = scala.Int

    def primitiveType: PrimitiveType[scala.Int] = PrimitiveType.Int(Validation.None)
  }
  final case class Long(value: scala.Long) extends Val {
    final type Type = scala.Long

    def primitiveType: PrimitiveType[scala.Long] = PrimitiveType.Long(Validation.None)
  }
  final case class Float(value: scala.Float) extends Val {
    final type Type = scala.Float

    def primitiveType: PrimitiveType[scala.Float] = PrimitiveType.Float(Validation.None)
  }
  final case class Double(value: scala.Double) extends Val {
    final type Type = scala.Double

    def primitiveType: PrimitiveType[scala.Double] = PrimitiveType.Double(Validation.None)
  }
  final case class Char(value: scala.Char) extends Val {
    final type Type = scala.Char

    def primitiveType: PrimitiveType[scala.Char] = PrimitiveType.Char(Validation.None)
  }
  final case class String(value: Predef.String) extends Ref {
    final type Type = Predef.String

    def primitiveType: PrimitiveType[Predef.String] = PrimitiveType.String(Validation.None)
  }
}
