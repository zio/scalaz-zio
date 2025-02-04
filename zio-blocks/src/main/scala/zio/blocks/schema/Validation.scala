package zio.blocks.schema

sealed trait Validation[+A]
object Validation {
  case object None extends Validation[Nothing]

  sealed trait Numeric[+A] extends Validation[A]
  object Numeric {
    case object Positive                                      extends Numeric[Nothing]
    case object Negative                                      extends Numeric[Nothing]
    case object NonPositive                                   extends Numeric[Nothing]
    case object NonNegative                                   extends Numeric[Nothing]
    final case class Range[A](min: Option[A], max: Option[A]) extends Numeric[A]
    final case class Set[A](values: Predef.Set[A])            extends Numeric[A]
  }

  sealed trait String extends Validation[scala.Predef.String]
  object String {
    case object NonEmpty                                                    extends String
    case object Empty                                                       extends String
    case object Blank                                                       extends String
    case object NonBlank                                                    extends String
    final case class Length(min: Option[scala.Int], max: Option[scala.Int]) extends String
    final case class Pattern(regex: scala.Predef.String)                    extends String
  }
}
