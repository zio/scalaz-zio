package zio.blocks.schema.binding

import RegisterOffset.RegisterOffset

/**
 * A {{Constructor}} is a typeclass that can construct a value of type `A` from
 * a set of registers.
 */
abstract class Constructor[+A] { self =>

  /**
   * The size of the registers required to construct a value of type `A`.
   */
  def size: RegisterOffset

  /**
   * Constructs a value of type `A` from the registers.
   */
  def construct(in: Registers, baseOffset: RegisterOffset): A

  /**
   * Maps a function over the constructed value.
   */
  final def map[B](f: A => B): Constructor[B] =
    new Constructor[B] {
      def size: RegisterOffset = self.size

      def construct(in: Registers, baseOffset: RegisterOffset): B = f(self.construct(in, baseOffset))
    }

  /**
   * Combines two constructors into a single constructor that constructs a tuple
   * of the two values.
   */
  final def zip[B](that: Constructor[B]): Constructor[(A, B)] = Constructor.tuple2(self, that)
}
object Constructor {
  private val _of = new Constructor[AnyRef] {
    def size: RegisterOffset = RegisterOffset(objects = 1)

    def construct(in: Registers, baseOffset: RegisterOffset): AnyRef = in.getObject(baseOffset, 0)
  }

  def of[A]: Constructor[A] = _of.asInstanceOf[Constructor[A]]

  val unit: Constructor[Unit] =
    new Constructor[Unit] {
      def size: RegisterOffset = RegisterOffset.Zero

      def construct(in: Registers, baseOffset: RegisterOffset): Unit = ()
    }

  val string: Constructor[String] =
    new Constructor[String] {
      def size: RegisterOffset = RegisterOffset(objects = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): String =
        in.getObject(baseOffset, 0).asInstanceOf[String]
    }

  val byte: Constructor[Byte] =
    new Constructor[Byte] {
      def size: RegisterOffset = RegisterOffset(bytes = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Byte = in.getByte(baseOffset, 0)
    }

  val boolean: Constructor[Boolean] =
    new Constructor[Boolean] {
      def size: RegisterOffset = RegisterOffset(booleans = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Boolean = in.getBoolean(baseOffset, 0)
    }

  val short: Constructor[Short] =
    new Constructor[Short] {
      def size: RegisterOffset = RegisterOffset(shorts = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Short = in.getShort(baseOffset, 0)
    }

  val int: Constructor[Int] =
    new Constructor[Int] {
      def size: RegisterOffset = RegisterOffset(ints = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Int = in.getInt(baseOffset, 0)
    }

  val long: Constructor[Long] =
    new Constructor[Long] {
      def size: RegisterOffset = RegisterOffset(longs = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Long = in.getLong(baseOffset, 0)
    }

  val float: Constructor[Float] =
    new Constructor[Float] {
      def size: RegisterOffset = RegisterOffset(floats = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Float = in.getFloat(baseOffset, 0)
    }

  val double: Constructor[Double] =
    new Constructor[Double] {
      def size: RegisterOffset = RegisterOffset(doubles = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Double = in.getDouble(baseOffset, 0)
    }

  val char: Constructor[Char] =
    new Constructor[Char] {
      def size: RegisterOffset = RegisterOffset(chars = 1)

      def construct(in: Registers, baseOffset: RegisterOffset): Char = in.getChar(baseOffset, 0)
    }

  def some[A](c: Constructor[A]): Constructor[Some[A]] = c.map(Some(_))

  val none: Constructor[None.type] = Constructor.unit.map(_ => None)

  def left[A](c: Constructor[A]): Constructor[Left[A, Nothing]] = c.map(Left(_))

  def right[B](c: Constructor[B]): Constructor[Right[Nothing, B]] = c.map(Right(_))

  def tuple2[A, B](_1: Constructor[A], _2: Constructor[B]): Constructor[(A, B)] =
    new Constructor[(A, B)] {
      def size: RegisterOffset = _1.size + _2.size

      def construct(in: Registers, baseOffset: RegisterOffset): (A, B) = {

        val a: A = _1.construct(in, baseOffset)
        val b: B = _2.construct(in, baseOffset + _1.size)

        (a, b)
      }
    }

  def tuple3[A, B, C](_1: Constructor[A], _2: Constructor[B], _3: Constructor[C]): Constructor[(A, B, C)] =
    new Constructor[(A, B, C)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size

      def construct(in: Registers, baseOffset: RegisterOffset): (A, B, C) = {
        val a: A = _1.construct(in, baseOffset)
        val b: B = _2.construct(in, baseOffset + _1.size)
        val c: C = _3.construct(in, baseOffset + _1.size + _2.size)

        (a, b, c)
      }
    }

  def tuple4[A, B, C, D](
    _1: Constructor[A],
    _2: Constructor[B],
    _3: Constructor[C],
    _4: Constructor[D]
  ): Constructor[(A, B, C, D)] =
    new Constructor[(A, B, C, D)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size

      def construct(in: Registers, baseOffset: RegisterOffset): (A, B, C, D) = {
        val a: A = _1.construct(in, baseOffset)
        val b: B = _2.construct(in, baseOffset + _1.size)
        val c: C = _3.construct(in, baseOffset + _1.size + _2.size)
        val d: D = _4.construct(in, baseOffset + _1.size + _2.size + _3.size)

        (a, b, c, d)
      }
    }

  def tuple5[A, B, C, D, E](
    _1: Constructor[A],
    _2: Constructor[B],
    _3: Constructor[C],
    _4: Constructor[D],
    _5: Constructor[E]
  ): Constructor[(A, B, C, D, E)] =
    new Constructor[(A, B, C, D, E)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size + _5.size

      def construct(in: Registers, baseOffset: RegisterOffset): (A, B, C, D, E) = {
        val a: A = _1.construct(in, baseOffset)
        val b: B = _2.construct(in, baseOffset + _1.size)
        val c: C = _3.construct(in, baseOffset + _1.size + _2.size)
        val d: D = _4.construct(in, baseOffset + _1.size + _2.size + _3.size)
        val e: E = _5.construct(in, baseOffset + _1.size + _2.size + _3.size + _4.size)

        (a, b, c, d, e)
      }
    }

  def tuple6[A, B, C, D, E, F](
    _1: Constructor[A],
    _2: Constructor[B],
    _3: Constructor[C],
    _4: Constructor[D],
    _5: Constructor[E],
    _6: Constructor[F]
  ): Constructor[(A, B, C, D, E, F)] =
    new Constructor[(A, B, C, D, E, F)] {
      def size: RegisterOffset = _1.size + _2.size + _3.size + _4.size + _5.size + _6.size

      def construct(in: Registers, baseOffset: RegisterOffset): (A, B, C, D, E, F) = {
        val a: A = _1.construct(in, baseOffset)
        val b: B = _2.construct(in, baseOffset + _1.size)
        val c: C = _3.construct(in, baseOffset + _1.size + _2.size)
        val d: D = _4.construct(in, baseOffset + _1.size + _2.size + _3.size)
        val e: E = _5.construct(in, baseOffset + _1.size + _2.size + _3.size + _4.size)
        val f: F = _6.construct(in, baseOffset + _1.size + _2.size + _3.size + _4.size + _5.size)

        (a, b, c, d, e, f)
      }
    }
}
