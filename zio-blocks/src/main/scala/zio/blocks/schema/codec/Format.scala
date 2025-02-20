package zio.blocks.schema.codec

sealed trait Format {
  def mimeType: String

  type TypeClass[_]
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
}
