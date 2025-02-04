package zio.blocks.schema

sealed trait Doc {
  def +(that: Doc): Doc = Doc.Concat(this, that)

  def flatten: List[Doc.Leaf]

  override lazy val hashCode: Int = flatten.hashCode

  override def equals(that: Any): Boolean = that match {
    case that: Doc => this.flatten == that.flatten
    case _         => false
  }
}
object Doc {
  sealed trait Leaf extends Doc

  case object Empty extends Leaf {
    def flatten: List[Leaf] = Nil

    override def equals(that: Any): Boolean = that match {
      case that: Empty.type => true
      case _                => false
    }
  }
  final case class Text(value: String) extends Leaf {
    def flatten: List[Leaf] = List(this)

    override def equals(that: Any): Boolean = that match {
      case that: Text => this.value == that.value
      case _          => false
    }
  }
  final case class Concat(left: Doc, right: Doc) extends Doc {
    lazy val flatten: List[Leaf] = left.flatten ++ right.flatten
  }

  def apply(value: String): Doc = Text(value)
}
