package zio.blocks.schema.codec

import java.nio.{ByteBuffer, CharBuffer}
import zio.blocks.schema.Deriver

/**
 * A format is a type that represents a specific serialization format, such as
 * Avro or JSON. Each format should have a unique MIME type that identifies it,
 * which is used both for debugging purposes, and potentially in transport,
 * depending on the protocol.
 *
 * A format is associated with a specific type class, such as {{BinaryCodec}}
 * for binary formats, or {{TextCodec}} for text formats, and a deriver that can
 * be used to derive the codec for the format.
 */
sealed trait Format {
  type DecodeInput
  type EncodeOutput
  type TypeClass[A] <: Codec[DecodeInput, EncodeOutput, A]

  def mimeType: String

  def deriver: Deriver[TypeClass]
}

/**
 * A tag that can be used to uniquely identify a binary format, such as Avro.
 * Each format should have its own nominal type, together with a singleton value
 * of that type.
 *
 * e.g.:
 * {{{
 * sealed abstract class Avro extends BinaryFormat[AvroCodec]("application/avro", AvroDeriver)
 * case object Avro extends Avro
 * }}}
 */
abstract class BinaryFormat[TC[A] <: BinaryCodec[A]](val mimeType: String, val deriver: Deriver[TC]) extends Format {
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
 * sealed abstract class ZioJson extends TextFormat[ZioJsonCodec]("application/zio-json", ZioJsonDeriver)
 * case object ZioJson extends Json
 * }}}
 */
abstract class TextFormat[TC[A] <: TextCodec[A]](val mimeType: String, val deriver: Deriver[TC]) extends Format {
  final type TypeClass[A] = TC[A]

  type DecodeInput  = CharBuffer
  type EncodeOutput = CharBuffer
}
