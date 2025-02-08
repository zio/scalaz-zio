package zio.blocks.schema

import zio.blocks.schema.binding.Binding

/**
 * A {{Schema}} is a data type that contains reified information on the
 * structure of a Scala data type, together with the ability to tear down and
 * build up values of that type.
 */
final case class Schema[A](reflect: Reflect.Bound[A]) {
  def toDynamicValue(value: A): DynamicValue = ??? // TODO

  def fromDynamicValue(value: DynamicValue): Either[codec.CodecError, A] = ??? // TODO

  def encode[I](value: A, input: I)(implicit codec: zio.blocks.schema.codec.Codec[I, A]): I = ??? // TODO

  def defaultValue[B](optic: Optic.Bound[A, B], value: => B): Schema[A] = ??? // TODO

  def defaultValue(value: => A): Schema[A] = ??? // TODO

  def derive[TC[_]](implicit deriver: Deriver[TC]): TC[A] = ??? // TODO

  def examples: List[A] = ??? // TODO

  def examples(value: A, values: A*): Schema[A] = ??? // TODO

  def examples[B](optic: Optic.Bound[A, B]): List[B] = ??? // TODO

  def examples[B](optic: Optic.Bound[A, B])(value: B, values: B*): Schema[A] = ??? // TODO

  def doc: Doc = ??? // TODO

  def doc(value: String): Schema[A] = ??? // TODO

  def doc[B](optic: Optic.Bound[A, B]): Doc = ???

  def doc[B](optic: Optic.Bound[A, B])(value: String): Schema[A] = ??? // TODO
}
object Schema {
  import Reflect._

  def apply[A](implicit schema: Schema[A]): Schema[A] = schema

  implicit val byte: Schema[Byte] = Schema(Reflect.byte[Binding])

  implicit val short: Schema[Short] = Schema(Reflect.short[Binding])

  implicit val int: Schema[Int] = Schema(Reflect.int[Binding])

  implicit val long: Schema[Long] = Schema(Reflect.long[Binding])

  implicit val float: Schema[Float] = Schema(Reflect.float[Binding])

  implicit val double: Schema[Double] = Schema(Reflect.double[Binding])

  implicit val char: Schema[Char] = Schema(Reflect.char[Binding])

  implicit val string: Schema[String] = Schema(Reflect.string[Binding])

  implicit val unit: Schema[Unit] = Schema(Reflect.unit[Binding])

  implicit def set[A](implicit element: Schema[A]): Schema[Set[A]] = Schema(Reflect.set(element.reflect))

  implicit def list[A](implicit element: Schema[A]): Schema[List[A]] = Schema(Reflect.list(element.reflect))

  implicit def vector[A](implicit element: Schema[A]): Schema[Vector[A]] = Schema(Reflect.vector(element.reflect))

  implicit def array[A](implicit element: Schema[A]): Schema[Array[A]] = Schema(Reflect.array(element.reflect))

  implicit def some[A](implicit element: Schema[A]): Schema[Some[A]] = Schema(Reflect.some(element.reflect))

  implicit def left[A, B](implicit element: Schema[A]): Schema[Left[A, B]] = Schema(
    Reflect.left[Binding, A, B](element.reflect)
  )

  implicit def right[A, B](implicit element: Schema[B]): Schema[Right[A, B]] = Schema(
    Reflect.right[Binding, A, B](element.reflect)
  )

  implicit val none: Schema[None.type] = Schema(Reflect.none[Binding])

  implicit def option[A](implicit element: Schema[A]): Schema[Option[A]] = Schema(Reflect.option(element.reflect))

  implicit def either[L, R](implicit l: Schema[L], r: Schema[R]): Reflect.Bound[Either[L, R]] =
    Reflect.either(l.reflect, r.reflect)

  implicit def tuple2[A, B](implicit a: Schema[A], b: Schema[B]): Reflect.Bound[(A, B)] =
    Reflect.tuple2(a.reflect, b.reflect)

  implicit def tuple3[A, B, C](implicit a: Schema[A], b: Schema[B], c: Schema[C]): Reflect.Bound[(A, B, C)] =
    Reflect.tuple3(a.reflect, b.reflect, c.reflect)

  implicit def tuple4[A, B, C, D](implicit
    a: Schema[A],
    b: Schema[B],
    c: Schema[C],
    d: Schema[D]
  ): Reflect.Bound[(A, B, C, D)] = Reflect.tuple4(a.reflect, b.reflect, c.reflect, d.reflect)

  implicit def tuple5[A, B, C, D, E](implicit
    a: Schema[A],
    b: Schema[B],
    c: Schema[C],
    d: Schema[D],
    e: Schema[E]
  ): Reflect.Bound[(A, B, C, D, E)] = Reflect.tuple5(a.reflect, b.reflect, c.reflect, d.reflect, e.reflect)
}
