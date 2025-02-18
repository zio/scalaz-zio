package zio.blocks.schema.codec

import java.nio.ByteBuffer

abstract class BinaryCodec[A] extends Codec[ByteBuffer, ByteBuffer, A]
