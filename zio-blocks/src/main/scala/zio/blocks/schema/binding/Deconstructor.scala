package zio.blocks.schema.binding

import RegisterOffset.RegisterOffset

/**
 * A {{Deconstructor}} is a typeclass that can deconstruct a value of type `A`
 * into a set of registers.
 */
abstract class Deconstructor[-A] { self =>
  def size: RegisterOffset

  /**
   * Deconstructs a value of type `A` into the registers.
   */
  def deconstruct(out: Registers, baseOffset: RegisterOffset, in: A): Unit

  /**
   * Transforms this deconstructor into one that operates on a different type --
   * albeit one that can be converted to this type.
   */
  final def contramap[B](f: B => A): Deconstructor[B] =
    new Deconstructor[B] {
      def size: RegisterOffset = self.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: B): Unit =
        self.deconstruct(out, baseOffset, f(in))
    }

  /**
   * Combines two deconstructors into a single deconstructor that deconstructs a
   * tuple of the two values.
   */
  final def zip[B](that: Deconstructor[B]): Deconstructor[(A, B)] = Deconstructor.tuple2(self, that)
}
object Deconstructor {
  private val _of = new Deconstructor[AnyRef] {
    def size: RegisterOffset = RegisterOffset(objects = 1)

    def deconstruct(out: Registers, baseOffset: RegisterOffset, in: AnyRef): Unit = out.setObject(baseOffset, 0, in)
  }

  def of[A]: Deconstructor[A] = _of.asInstanceOf[Deconstructor[A]]

  val unit: Deconstructor[Unit] =
    new Deconstructor[Unit] {
      def size: RegisterOffset = RegisterOffset.Zero

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Unit): Unit = ()
    }

  val string: Deconstructor[String] =
    new Deconstructor[String] {
      def size = RegisterOffset(objects = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: String): Unit = out.setObject(baseOffset, 0, in)
    }

  val byte: Deconstructor[Byte] =
    new Deconstructor[Byte] {
      def size = RegisterOffset(bytes = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Byte): Unit = out.setByte(baseOffset, 0, in)
    }

  val boolean: Deconstructor[Boolean] =
    new Deconstructor[Boolean] {
      def size = RegisterOffset(booleans = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Boolean): Unit = out.setBoolean(baseOffset, 0, in)
    }

  val short: Deconstructor[Short] =
    new Deconstructor[Short] {
      def size = RegisterOffset(shorts = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Short): Unit = out.setShort(baseOffset, 0, in)
    }

  val int: Deconstructor[Int] =
    new Deconstructor[Int] {
      def size = RegisterOffset(ints = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Int): Unit = out.setInt(baseOffset, 0, in)
    }

  val long: Deconstructor[Long] =
    new Deconstructor[Long] {
      def size = RegisterOffset(longs = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Long): Unit = out.setLong(baseOffset, 0, in)
    }

  val float: Deconstructor[Float] =
    new Deconstructor[Float] {
      def size = RegisterOffset(floats = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Float): Unit = out.setFloat(baseOffset, 0, in)
    }

  val double: Deconstructor[Double] =
    new Deconstructor[Double] {
      def size = RegisterOffset(doubles = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Double): Unit = out.setDouble(baseOffset, 0, in)
    }

  val char: Deconstructor[Char] =
    new Deconstructor[Char] {
      def size = RegisterOffset(chars = 1)

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: Char): Unit = out.setChar(baseOffset, 0, in)
    }

  val none: Deconstructor[None.type] =
    new Deconstructor[None.type] {
      def size = RegisterOffset.Zero

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: None.type): Unit = ()
    }

  def some[A](d: Deconstructor[A]): Deconstructor[Some[A]] = d.contramap(_.get)

  def left[A, B](d: Deconstructor[A]): Deconstructor[Left[A, B]] = d.contramap(_.value)

  def right[A, B](d: Deconstructor[B]): Deconstructor[Right[A, B]] = d.contramap(_.value)

  def tuple2[A, B](_1: Deconstructor[A], _2: Deconstructor[B]): Deconstructor[(A, B)] =
    new Deconstructor[(A, B)] {
      def size: RegisterOffset = _1.size + _2.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: (A, B)): Unit = {
        _1.deconstruct(out, baseOffset, in._1)
        _2.deconstruct(out, baseOffset + _1.size, in._2)
      }
    }

  def tuple3[A, B, C](_1: Deconstructor[A], _2: Deconstructor[B], _3: Deconstructor[C]): Deconstructor[(A, B, C)] =
    new Deconstructor[(A, B, C)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: (A, B, C)): Unit = {
        _1.deconstruct(out, baseOffset, in._1)
        _2.deconstruct(out, baseOffset + _1.size, in._2)
        _3.deconstruct(out, baseOffset + _1.size + _2.size, in._3)
      }
    }

  def tuple4[A, B, C, D](
    _1: Deconstructor[A],
    _2: Deconstructor[B],
    _3: Deconstructor[C],
    _4: Deconstructor[D]
  ): Deconstructor[(A, B, C, D)] =
    new Deconstructor[(A, B, C, D)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: (A, B, C, D)): Unit = {
        _1.deconstruct(out, baseOffset, in._1)
        _2.deconstruct(out, baseOffset + _1.size, in._2)
        _3.deconstruct(out, baseOffset + _1.size + _2.size, in._3)
        _4.deconstruct(out, baseOffset + _1.size + _2.size + _3.size, in._4)
      }
    }

  def tuple5[A, B, C, D, E](
    _1: Deconstructor[A],
    _2: Deconstructor[B],
    _3: Deconstructor[C],
    _4: Deconstructor[D],
    _5: Deconstructor[E]
  ): Deconstructor[(A, B, C, D, E)] =
    new Deconstructor[(A, B, C, D, E)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size + _5.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: (A, B, C, D, E)): Unit = {
        _1.deconstruct(out, baseOffset, in._1)
        _2.deconstruct(out, baseOffset + _1.size, in._2)
        _3.deconstruct(out, baseOffset + _1.size + _2.size, in._3)
        _4.deconstruct(out, baseOffset + _1.size + _2.size + _3.size, in._4)
        _5.deconstruct(out, baseOffset + _1.size + _2.size + _3.size + _4.size, in._5)
      }
    }

  def tuple6[A, B, C, D, E, F](
    _1: Deconstructor[A],
    _2: Deconstructor[B],
    _3: Deconstructor[C],
    _4: Deconstructor[D],
    _5: Deconstructor[E],
    _6: Deconstructor[F]
  ): Deconstructor[(A, B, C, D, E, F)] =
    new Deconstructor[(A, B, C, D, E, F)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size + _5.size + _6.size

      def deconstruct(out: Registers, baseOffset: RegisterOffset, in: (A, B, C, D, E, F)): Unit = {
        _1.deconstruct(out, baseOffset, in._1)
        _2.deconstruct(out, baseOffset + _1.size, in._2)
        _3.deconstruct(out, baseOffset + _1.size + _2.size, in._3)
        _4.deconstruct(out, baseOffset + _1.size + _2.size + _3.size, in._4)
        _5.deconstruct(out, baseOffset + _1.size + _2.size + _3.size + _4.size, in._5)
        _6.deconstruct(out, baseOffset + _1.size + _2.size + _3.size + _4.size + _5.size, in._6)
      }
    }
}
