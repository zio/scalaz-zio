package zio.blocks.schema

import zio.blocks.schema.binding._

/**
 * A schema metadata structure can store arbitrary metadata attached to the
 * structure of a type.
 */
final case class SchemaMetadata[F[_, _], S, G[_]](private val map: Map[Optic[F, S, ?], Array[G[?]]]) {
  def add[A](optic: Optic[F, S, A], value: G[A]): SchemaMetadata[F, S, G] =
    SchemaMetadata[F, S, G](map.updated(optic, map.getOrElse(optic, Array()) :+ value))

  def fold[Z](z: Z)(fold: SchemaMetadata.Folder[F, S, G, Z]): Z =
    map.foldLeft(z) { case (z, (optic, values)) =>
      values.foldLeft(z) { case (z, value) =>
        fold.fold(z, optic.asInstanceOf[Optic[F, S, Any]], value.asInstanceOf[G[Any]])
      }
    }

  def get[A](optic: Optic[F, S, A]): Option[G[A]] = getAll(optic).headOption

  def getAll[A](optic: Optic[F, S, A]): IndexedSeq[G[A]] = map.getOrElse(optic, Array()).asInstanceOf[Array[G[A]]]

  def removeAll[A](optic: Optic[F, S, A]): SchemaMetadata[F, S, G] = SchemaMetadata[F, S, G](map - optic)

  def size: Int = map.size
}
object SchemaMetadata {
  trait Folder[F[_, _], S, G[_], Z] {
    def initial: Z

    def fold[A](z: Z, optic: Optic[F, S, A], value: G[A]): Z
  }
  object Folder {
    type Bound[S, G[_], Z] = Folder[Binding, S, G, Z]

    type Simple[S, Z] = Bound[S, Id, Z]

    def simple[S, Z](initial0: Z)(f: (Z, Optic[Binding, S, ?]) => Z): Simple[S, Z] = new Simple[S, Z] {
      val initial = initial0

      def fold[A](z: Z, optic: Optic[Binding, S, A], value: Id[A]): Z = f(z, optic)
    }
  }
  type Id[A] = A

  type Bound[S, G[_]] = SchemaMetadata[Binding, S, G]

  type Simple[S] = Bound[S, Id]

  def bound[S, G[_]]: SchemaMetadata[Binding, S, G] = empty[Binding, S, G]

  def empty[F[_, _], S, G[_]]: SchemaMetadata[F, S, G] = SchemaMetadata[F, S, G](Map())

  def simple[S]: SchemaMetadata.Simple[S] = empty[Binding, S, Id]
}
