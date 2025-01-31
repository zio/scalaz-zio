package zio.blocks.schema.binding

trait HasBinding[F[_, _]] {
  def binding[T, A](fa: F[T, A]): Binding[T, A]

  def updateBinding[T, A](fa: F[T, A], f: Binding[T, A] => Binding[T, A]): F[T, A]

  def fromBinding[T, A](binding: Binding[T, A]): F[T, A]

  final def record[A](fa: F[BindingType.Record, A]): Binding.Record[A] =
    binding(fa) match {
      case record @ Binding.Record(_, _, _, _) => record
    }

  final def variant[A](fa: F[BindingType.Variant, A]): Binding.Variant[A] =
    binding(fa) match {
      case variant @ Binding.Variant(_, _, _, _) => variant
    }

  final def constructor[A](fa: F[BindingType.Record, A]): Constructor[A] = record(fa).constructor

  final def updateConstructor[A](
    fa: F[BindingType.Record, A],
    f: Constructor[A] => Constructor[A]
  ): F[BindingType.Record, A] =
    updateBinding(
      fa,
      { case record @ Binding.Record(_, _, _, _) =>
        record.copy(constructor = f(record.constructor))
      }
    )

  final def deconstructor[A](fa: F[BindingType.Record, A]): Deconstructor[A] = record(fa).deconstructor

  final def updateDeconstructor[A](
    fa: F[BindingType.Record, A],
    f: Deconstructor[A] => Deconstructor[A]
  ): F[BindingType.Record, A] =
    updateBinding(
      fa,
      { case record @ Binding.Record(_, _, _, _) =>
        record.copy(deconstructor = f(record.deconstructor))
      }
    )

  final def discriminator[A](fa: F[BindingType.Variant, A]): Discriminator[A] = variant(fa).discriminator

  final def updateDiscriminator[A](
    fa: F[BindingType.Variant, A],
    f: Discriminator[A] => Discriminator[A]
  ): F[BindingType.Variant, A] =
    updateBinding(
      fa,
      { case variant @ Binding.Variant(_, _, _, _) =>
        variant.copy(discriminator = f(variant.discriminator))
      }
    )

  final def matchers[A](fa: F[BindingType.Variant, A]): Matchers[A] = variant(fa).matchers

  final def updateMatchers[A](fa: F[BindingType.Variant, A], f: Matchers[A] => Matchers[A]): F[BindingType.Variant, A] =
    updateBinding(
      fa,
      { case variant @ Binding.Variant(_, _, _, _) =>
        variant.copy(matchers = f(variant.matchers))
      }
    )

  final def mapConstructor[M[_, _], K, V](fa: F[BindingType.Map[M], M[K, V]]): MapConstructor[M] =
    map(fa).constructor

  final def mapDeconstructor[M[_, _], K, V](fa: F[BindingType.Map[M], M[K, V]]): MapDeconstructor[M] =
    map(fa).deconstructor

  final def seqConstructor[C[_], A](fa: F[BindingType.Seq[C], C[A]]): SeqConstructor[C] =
    seq(fa).constructor

  final def seqDeconstructor[C[_], A](fa: F[BindingType.Seq[C], C[A]]): SeqDeconstructor[C] =
    seq(fa).deconstructor

  final def map[M[_, _], K, V](fa: F[BindingType.Map[M], M[K, V]]): Binding.Map[M, K, V] =
    binding(fa) match {
      case map @ Binding.Map(_, _, _, _) => map
    }

  final def updateMap[M[_, _], K, V](
    fa: F[BindingType.Map[M], M[K, V]],
    f: Binding.Map[M, K, V] => Binding.Map[M, K, V]
  ): F[BindingType.Map[M], M[K, V]] =
    updateBinding(
      fa,
      { case map @ Binding.Map(_, _, _, _) =>
        f(map)
      }
    )

  final def seq[C[_], A](fa: F[BindingType.Seq[C], C[A]]): Binding.Seq[C, A] =
    binding(fa) match {
      case seq @ Binding.Seq(_, _, _, _) => seq
    }

  final def updateSeq[C[_], A](
    fa: F[BindingType.Seq[C], C[A]],
    f: Binding.Seq[C, A] => Binding.Seq[C, A]
  ): F[BindingType.Seq[C], C[A]] =
    updateBinding(
      fa,
      { case seq @ Binding.Seq(_, _, _, _) =>
        f(seq)
      }
    )
}

trait IsBinding[F[_, _]] {
  def apply[T, A](fa: F[T, A]): Binding[T, A]

  def unapply[T, A](fa: Binding[T, A]): F[T, A]
}
