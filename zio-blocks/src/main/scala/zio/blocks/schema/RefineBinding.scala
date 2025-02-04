package zio.blocks.schema

import zio.blocks.schema.binding._

trait RefineBinding[-F[_, _], +G[_, _]] {
  def apply[K, A](f: F[K, A]): G[K, A]
}
object RefineBinding {
  private type Any2[A, B] = Any

  private val _noBinding: RefineBinding[Any2, NoBinding] = new RefineBinding[Any2, NoBinding] {
    def apply[K, A](f: Any): NoBinding[K, A] = NoBinding[K, A]()
  }

  def noBinding[F[_, _]](): RefineBinding[F, NoBinding] = _noBinding.asInstanceOf[RefineBinding[F, NoBinding]]
}
