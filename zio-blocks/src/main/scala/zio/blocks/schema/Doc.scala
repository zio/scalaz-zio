package zio.blocks.schema

sealed trait Doc
object Doc {
  case object Empty                    extends Doc
  final case class Text(value: String) extends Doc

  def apply(value: String): Doc = Text(value)
}
