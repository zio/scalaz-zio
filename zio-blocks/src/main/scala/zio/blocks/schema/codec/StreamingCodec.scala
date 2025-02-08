package zio.blocks.schema.codec

import scala.util.control.NoStackTrace

// TODO: Keep this???
trait StreamingCodec[I, A] {
  import StreamingCodec._

  type State

  def decode(input: I): Result[State, A] =
    try Done(unsafeDecode(input))
    catch {
      case e: CodecError        => Error(e)
      case c: MoreSignal[state] => More(c.state.asInstanceOf[State])
    }

  def unsafeDecode(input: I): A

  def encode(a: A): I

  def continueDecode(state: State, input: I): Result[State, A] =
    try Done(unsafeContinueDecode(state, input))
    catch {
      case e: CodecError        => Error(e)
      case c: MoreSignal[state] => More(c.state.asInstanceOf[State])
    }

  def unsafeContinueDecode(state: State, input: I): A

}

object StreamingCodec {
  final case class MoreSignal[State](state: State) extends Exception with NoStackTrace

  sealed trait Result[State, +A]
  final case class Done[State, A](value: A)        extends Result[State, A]
  final case class More[State](state: State)       extends Result[State, Nothing]
  final case class Error[State](error: CodecError) extends Result[State, Nothing]
}
