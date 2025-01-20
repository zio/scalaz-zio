package zio.blocks.schema

import zio.blocks.schema.binding._

final case class Term[+F[_, _], S, A](name: String, value: Reflect[F, A], doc: Doc, anns: List[Modifier.Term[A]])
    extends Reflectable[A] {
  def refineBinding[G[_, _]](f: RefineBinding[F, G]): Term[G, S, A] = Term(name, value.refineBinding(f), doc, anns)
}
object Term {
  type Bound[S, A] = Term[Binding, S, A]
}
