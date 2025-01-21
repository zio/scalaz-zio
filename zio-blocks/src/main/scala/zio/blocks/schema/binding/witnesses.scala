package zio.blocks.schema.binding

trait HasConstructor[-F[_, _]] {
  def constructor[A](fa: F[BindingType.Record, A]): Constructor[A]
}

trait HasDeconstructor[-F[_, _]] {
  def deconstructor[A](fa: F[BindingType.Record, A]): Deconstructor[A]
}

trait HasDiscriminator[-F[_, _]] {
  def discriminator[A](fa: F[BindingType.Variant, A]): Discriminator[A]
}

trait HasMatchers[-F[_, _]] {
  def matchers[A](fa: F[BindingType.Variant, A]): Matchers[A]
}

trait HasSeqConstructor[-F[_, _]] {
  def constructor[C[_], A](fa: F[BindingType.Seq[C], A]): SeqConstructor[C]
}
trait HasSeqDeconstructor[-F[_, _]] {
  def deconstructor[C[_], A](fa: F[BindingType.Seq[C], A]): SeqDeconstructor[C]
}

trait HasMapConstructor[-F[_, _]] {
  def constructor[M[_, _], A](fa: F[BindingType.Map[M], A]): MapConstructor[M]
}

trait HasMapDeconstructor[-F[_, _]] {
  def deconstructor[M[_, _], A](fa: F[BindingType.Map[M], A]): MapDeconstructor[M]
}

trait IsBinding[F[_, _]] {
  def apply[T, A](fa: F[T, A]): Binding[T, A]

  def unapply[T, A](fa: Binding[T, A]): F[T, A]
}