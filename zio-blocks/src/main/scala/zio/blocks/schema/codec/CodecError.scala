package zio.blocks.schema.codec

import scala.util.control.NoStackTrace

import zio.blocks.schema._

sealed trait CodecError extends Exception with NoStackTrace {
  def message: String

  override def getMessage(): String = message
}
object CodecError {
  // FIXME: Flesh these out
  final case class ValidationError[F[_, _], A](
    structure: Reflect[F, ?],
    focus: Reflect[F, A],
    expected: Validation[A],
    actual: A,
    message: String
  ) extends CodecError
  final case class MissingField[F[_, _], S, A](structure: Reflect.Record[F, S], field: Term[F, S, A], message: String)
      extends CodecError
  final case class UnknownField[F[_, _], S, A](structure: Reflect.Record[F, S], field: Term[F, S, A], message: String)
      extends CodecError
  final case class InvalidType[F[_, _], A](structure: Reflect[F, ?], focus: Reflect[F, A], message: String)
      extends CodecError
  final case class MissingCase[F[_, _], S, A](structure: Reflect.Variant[F, S], case0: Term[F, S, A], message: String)
      extends CodecError
  final case class UnknownCase[F[_, _], S, A](structure: Reflect.Variant[F, S], case0: Term[F, S, A], message: String)
      extends CodecError
  final case class MultipleErrors(errors: ::[CodecError]) extends CodecError {
    def message: String = errors.map(_.message).mkString("\n")
  }
}
