package zio.blocks.schema

final case class Namespace(packages: List[String], values: List[String]) {
  val elements: List[String] = packages ++ values
}
