package zio.blocks.schema.codec

trait Codec[I, A] {
  def encode(a: A): I

  def decode(i: I): Either[CodecError, A] =
    try Right(unsafeDecode(i))
    catch {
      case e: CodecError => Left(e)
    }

  def unsafeDecode(i: I): A
}
