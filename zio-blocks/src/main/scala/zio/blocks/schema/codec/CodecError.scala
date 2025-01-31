package zio.blocks.schema.codec

import scala.util.control.NoStackTrace

import zio.blocks.schema._

sealed trait CodecError extends Exception with NoStackTrace {
  def message: String

  override def getMessage(): String = message
}
object CodecError {
  // FIXME: Flesh these out
  final case class ValidationError[A](
    validation: Validation[A],
    value: A,
    primitiveType: PrimitiveType[A],
    message: String
  ) extends CodecError
  final case class MissingField(message: String) extends CodecError
  final case class UnknownField(message: String) extends CodecError
  final case class InvalidType(message: String)  extends CodecError
  final case class InvalidCase(message: String)  extends CodecError
  final case class MultipleErrors(errors: ::[CodecError]) extends CodecError {
    def message: String = errors.map(_.message).mkString("\n")
  }
}
