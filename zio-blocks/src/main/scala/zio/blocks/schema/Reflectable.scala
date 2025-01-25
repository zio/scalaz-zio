package zio.blocks.schema

trait Reflectable[A] {
  def modifiers: List[Modifier]
  def doc: Doc
}
