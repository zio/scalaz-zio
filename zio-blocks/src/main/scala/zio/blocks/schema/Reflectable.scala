package zio.blocks.schema

trait Reflectable[A] {
  def anns: List[Modifier]
  def doc: Doc
}
