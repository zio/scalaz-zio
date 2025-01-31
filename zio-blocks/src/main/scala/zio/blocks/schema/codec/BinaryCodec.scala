package zio.blocks.schema.codec

import java.nio.ByteBuffer

trait BinaryCodec[A] extends Codec[ByteBuffer, A] {
  def unsafeDecode(buffer: ByteBuffer): A
}
