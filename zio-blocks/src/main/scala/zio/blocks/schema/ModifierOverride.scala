package zio.blocks.schema

final case class ModifierOverride[S, A](optic: Optic.Bound[S, A], modifier: Modifier)
