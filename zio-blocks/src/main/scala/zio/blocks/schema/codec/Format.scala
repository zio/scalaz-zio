package zio.blocks.schema.codec

import java.nio.ByteBuffer
import java.nio.CharBuffer

/**
 * A format is a type that represents a specific serialization format, such as
 * Avro or JSON. Each format should have a unique MIME type that identifies it,
 * which is used both for debugging purposes, and potentially in transport,
 * depending on the protocol.
 *
 * A format is associated with a specific type class, such as {{BinaryCodec}}
 * for binary formats, or {{TextCodec}} for text formats.
 */
sealed trait Format {
  def mimeType: String

  type TypeClass[_]

  type DecodeInput
  type EncodeOutput
}

/**
 * A tag that can be used to uniquely identify a binary format, such as Avro.
 * Each format should have its own nominal type, together with a singleton value
 * of that type.
 *
 * e.g.:
 * {{{
 * sealed abstract class Avro extends BinaryFormat[AvroCodec]("application/avro")
 * case object Avro extends Avro
 * }}}
 */
abstract class BinaryFormat[TC[_] <: BinaryCodec[_]](val mimeType: String) extends Format {
  final type TypeClass[A] = TC[A]

  type DecodeInput  = ByteBuffer
  type EncodeOutput = ByteBuffer
}

/**
 * A tag that can be used to uniquely identify a text format, such as ZioJson.
 * Each format should have its own nominal type, together with a singleton value
 * of that type.
 *
 * e.g.:
 * {{{
 * sealed abstract class ZioJson extends TextFormat[ZioJsonCodec]("application/zio-json")
 * case object ZioJson extends Json
 * }}}
 */
abstract class TextFormat[TC[_] <: TextCodec](val mimeType: String) extends Format {
  final type TypeClass[A] = TC[A]

  type DecodeInput  = CharBuffer
  type EncodeOutput = CharBuffer
}
