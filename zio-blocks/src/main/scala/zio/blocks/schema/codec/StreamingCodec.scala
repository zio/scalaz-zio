package zio.blocks.schema.codec

import scala.util.control.NoStackTrace

// TODO: Keep this???
trait StreamingCodec[I, A] {
  type State

  def decode(input: I): Either[CodecError, A] =
    try Right(unsafeDecode(input))
    catch {
      case e: CodecError => Left(e)
    }

  def unsafeDecode(input: I): A

  def encode(a: A): I

  def continueDecode(state: State, input: I): Either[CodecError, A] =
    try Right(unsafeContinueDecode(state, input))
    catch {
      case e: CodecError => Left(e)
    }

  def unsafeContinueDecode(state: State, input: I): A

}

object StreamingCodec {
  final case class Continue[State](state: State) extends Exception with NoStackTrace
}
