package zio.blocks.schema

final case class DeriveOverride[TC[_], S, A](optic: Optic.Bound[S, A], tc: TC[A])
