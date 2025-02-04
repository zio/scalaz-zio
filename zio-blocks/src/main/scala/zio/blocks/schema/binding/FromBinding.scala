package zio.blocks.schema.binding

trait FromBinding[F[_, _]] {
  def fromBinding[T, A](binding: Binding[T, A]): F[T, A]
}
