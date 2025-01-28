package zio.blocks.schema.binding

trait HasConstructor[F[_, _]] {
  def constructor[A](fa: F[BindingType.Record, A]): Constructor[A]

  def updateConstructor[A](fa: F[BindingType.Record, A], f: Constructor[A] => Constructor[A]): F[BindingType.Record, A]
}

trait HasDeconstructor[F[_, _]] {
  def deconstructor[A](fa: F[BindingType.Record, A]): Deconstructor[A]

  def updateDeconstructor[A](
    fa: F[BindingType.Record, A],
    f: Deconstructor[A] => Deconstructor[A]
  ): F[BindingType.Record, A]
}

trait HasDiscriminator[F[_, _]] {
  def discriminator[A](fa: F[BindingType.Variant, A]): Discriminator[A]

  def updateDiscriminator[A](
    fa: F[BindingType.Variant, A],
    f: Discriminator[A] => Discriminator[A]
  ): F[BindingType.Variant, A]
}

trait HasMatchers[F[_, _]] {
  def matchers[A](fa: F[BindingType.Variant, A]): Matchers[A]

  def updateMatchers[A](fa: F[BindingType.Variant, A], f: Matchers[A] => Matchers[A]): F[BindingType.Variant, A]
}

trait HasSeqConstructor[F[_, _]] {
  def constructor[C[_], A](fa: F[BindingType.Seq[C], C[A]]): SeqConstructor[C]

  def updateConstructor[C[_], A](
    fa: F[BindingType.Seq[C], C[A]],
    f: SeqConstructor[C] => SeqConstructor[C]
  ): F[BindingType.Seq[C], C[A]]
}
trait HasSeqDeconstructor[F[_, _]] {
  def deconstructor[C[_], A](fa: F[BindingType.Seq[C], C[A]]): SeqDeconstructor[C]

  def updateDeconstructor[C[_], A](
    fa: F[BindingType.Seq[C], C[A]],
    f: SeqDeconstructor[C] => SeqDeconstructor[C]
  ): F[BindingType.Seq[C], C[A]]
}

trait HasMapConstructor[F[_, _]] {
  def constructor[M[_, _], K, V](fa: F[BindingType.Map[M], M[K, V]]): MapConstructor[M]

  def updateConstructor[M[_, _], K, V](
    fa: F[BindingType.Map[M], M[K, V]],
    f: MapConstructor[M] => MapConstructor[M]
  ): F[BindingType.Map[M], M[K, V]]
}

trait HasMapDeconstructor[F[_, _]] {
  def deconstructor[M[_, _], K, V](fa: F[BindingType.Map[M], M[K, V]]): MapDeconstructor[M]

  def updateDeconstructor[M[_, _], K, V](
    fa: F[BindingType.Map[M], M[K, V]],
    f: MapDeconstructor[M] => MapDeconstructor[M]
  ): F[BindingType.Map[M], M[K, V]]
}

trait IsBinding[F[_, _]] {
  def apply[T, A](fa: F[T, A]): Binding[T, A]

  def unapply[T, A](fa: Binding[T, A]): F[T, A]
}
