package zio.http

final case class Body(content: Option[String] = None) {
  def isEmpty: Boolean = content.isEmpty
}

object Body {
  val empty: Body = Body(None)
  def fromString(s: String): Body = Body(Some(s))
} 