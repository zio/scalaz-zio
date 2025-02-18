package zio.blocks.schema.codec

import java.nio.CharBuffer

abstract class TextCodec[A] extends Codec[CharBuffer, CharBuffer, A]
