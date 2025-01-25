package zio.blocks.schema

sealed trait Doc {
  def +(that: Doc): Doc = Doc.Concat(this, that)
}
object Doc {
  case object Empty                              extends Doc
  final case class Text(value: String)           extends Doc
  final case class Concat(left: Doc, right: Doc) extends Doc

  def apply(value: String): Doc = Text(value)
}
