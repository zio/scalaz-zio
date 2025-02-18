package zio.blocks.schema.codec

sealed trait Format {
  def mimeType: String
}

/**
 * A tag that can be used to uniquely identify a binary format, such as Avro.
 * Each format should have its own nominal type, together with a singleton value
 * of that type.
 *
 * e.g.:
 * {{{
 * sealed abstract class Avro extends BinaryFormat("application/avro")
 * case object Avro extends Avro
 * }}}
 */
abstract class BinaryFormat(val mimeType: String) extends Format

/**
 * A tag that can be used to uniquely identify a text format, such as ZioJson.
 * Each format should have its own nominal type, together with a singleton value
 * of that type.
 *
 * e.g.:
 * {{{
 * sealed abstract class ZioJson extends TextFormat
 * case object ZioJson extends Json
 * }}}
 */
abstract class TextFormat(val mimeType: String) extends Format
