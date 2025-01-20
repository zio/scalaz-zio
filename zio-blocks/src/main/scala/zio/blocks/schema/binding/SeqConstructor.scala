package zio.blocks.schema.binding

trait SeqConstructor[C[_]] {
  type ObjectBuilder[A]
  type ByteBuilder
  type ShortBuilder
  type IntBuilder
  type LongBuilder
  type FloatBuilder
  type DoubleBuilder
  type CharBuilder

  def newObjectBuilder[A](sizeHint: Int = -1): ObjectBuilder[A]

  def newByteBuilder(sizeHint: Int = -1): ByteBuilder

  def newShortBuilder(sizeHint: Int = -1): ShortBuilder

  def newIntBuilder(sizeHint: Int = -1): IntBuilder

  def newLongBuilder(sizeHint: Int = -1): LongBuilder

  def newFloatBuilder(sizeHint: Int = -1): FloatBuilder

  def newDoubleBuilder(sizeHint: Int = -1): DoubleBuilder

  def newCharBuilder(sizeHint: Int = -1): CharBuilder

  def addObject[A](builder: ObjectBuilder[A], a: A): Unit

  def addByte(builder: ByteBuilder, a: Byte): Unit

  def addShort(builder: ShortBuilder, a: Short): Unit

  def addInt(builder: IntBuilder, a: Int): Unit

  def addLong(builder: LongBuilder, a: Long): Unit

  def addFloat(builder: FloatBuilder, a: Float): Unit

  def addDouble(builder: DoubleBuilder, a: Double): Unit

  def addChar(builder: CharBuilder, a: Char): Unit

  def resultObject[A](builder: ObjectBuilder[A]): C[A]

  def resultByte(builder: ByteBuilder): C[Byte]

  def resultShort(builder: ShortBuilder): C[Short]

  def resultInt(builder: IntBuilder): C[Int]

  def resultLong(builder: LongBuilder): C[Long]

  def resultFloat(builder: FloatBuilder): C[Float]

  def resultDouble(builder: DoubleBuilder): C[Double]

  def resultChar(builder: CharBuilder): C[Char]
}
object SeqConstructor {
  abstract class Boxed[C[_]] extends SeqConstructor[C] {
    override type ByteBuilder   = ObjectBuilder[Byte]
    override type ShortBuilder  = ObjectBuilder[Short]
    override type IntBuilder    = ObjectBuilder[Int]
    override type LongBuilder   = ObjectBuilder[Long]
    override type FloatBuilder  = ObjectBuilder[Float]
    override type DoubleBuilder = ObjectBuilder[Double]
    override type CharBuilder   = ObjectBuilder[Char]

    def newByteBuilder(sizeHint: Int): ByteBuilder = newObjectBuilder(sizeHint)

    def newShortBuilder(sizeHint: Int): ShortBuilder = newObjectBuilder(sizeHint)

    def newIntBuilder(sizeHint: Int): IntBuilder = newObjectBuilder(sizeHint)

    def newLongBuilder(sizeHint: Int): LongBuilder = newObjectBuilder(sizeHint)

    def newFloatBuilder(sizeHint: Int): FloatBuilder = newObjectBuilder(sizeHint)

    def newDoubleBuilder(sizeHint: Int): DoubleBuilder = newObjectBuilder(sizeHint)

    def newCharBuilder(sizeHint: Int): CharBuilder = newObjectBuilder(sizeHint)

    def addByte(builder: ByteBuilder, a: Byte): Unit = addObject(builder, a)

    def addShort(builder: ShortBuilder, a: Short): Unit = addObject(builder, a)

    def addInt(builder: IntBuilder, a: Int): Unit = addObject(builder, a)

    def addLong(builder: LongBuilder, a: Long): Unit = addObject(builder, a)

    def addFloat(builder: FloatBuilder, a: Float): Unit = addObject(builder, a)

    def addDouble(builder: DoubleBuilder, a: Double): Unit = addObject(builder, a)

    def addChar(builder: CharBuilder, a: Char): Unit = addObject(builder, a)

    def resultByte(builder: ByteBuilder): C[Byte] = resultObject(builder)

    def resultShort(builder: ShortBuilder): C[Short] = resultObject(builder)

    def resultInt(builder: IntBuilder): C[Int] = resultObject(builder)

    def resultLong(builder: LongBuilder): C[Long] = resultObject(builder)

    def resultFloat(builder: FloatBuilder): C[Float] = resultObject(builder)

    def resultDouble(builder: DoubleBuilder): C[Double] = resultObject(builder)

    def resultChar(builder: CharBuilder): C[Char] = resultObject(builder)
  }

  def apply[C[_]](implicit sc: SeqConstructor[C]): SeqConstructor[C] = sc

  implicit val setConstructor: SeqConstructor[Set] = new Boxed[Set] {
    type ObjectBuilder[A] = scala.collection.mutable.Builder[A, Set[A]]

    def newObjectBuilder[A](sizeHint: Int): ObjectBuilder[A] = Set.newBuilder[A]

    def addObject[A](builder: ObjectBuilder[A], a: A): Unit = builder += a

    def resultObject[A](builder: ObjectBuilder[A]): Set[A] = builder.result()
  }

  implicit val listConstructor: SeqConstructor[List] = new Boxed[List] {
    type ObjectBuilder[A] = scala.collection.mutable.Builder[A, List[A]]

    def newObjectBuilder[A](sizeHint: Int): ObjectBuilder[A] = List.newBuilder[A]

    def addObject[A](builder: ObjectBuilder[A], a: A): Unit = builder += a

    def resultObject[A](builder: ObjectBuilder[A]): List[A] = builder.result()
  }

  implicit val vectorConstructor: SeqConstructor[Vector] = new Boxed[Vector] {
    type ObjectBuilder[A] = scala.collection.mutable.Builder[A, Vector[A]]

    def newObjectBuilder[A](sizeHint: Int): ObjectBuilder[A] = Vector.newBuilder[A]

    def addObject[A](builder: ObjectBuilder[A], a: A): Unit = builder += a

    def resultObject[A](builder: ObjectBuilder[A]): Vector[A] = builder.result()
  }

  implicit val arrayConstructor: SeqConstructor[Array] = new SeqConstructor[Array] {
    case class Builder[A](var buffer: Array[A], var size: Int)

    type ObjectBuilder[A] = Builder[A]
    type ByteBuilder      = Builder[Byte]
    type ShortBuilder     = Builder[Short]
    type IntBuilder       = Builder[Int]
    type LongBuilder      = Builder[Long]
    type FloatBuilder     = Builder[Float]
    type DoubleBuilder    = Builder[Double]
    type CharBuilder      = Builder[Char]

    def newObjectBuilder[A](sizeHint: Int): Builder[A] =
      Builder(new Array[AnyRef](sizeHint.max(8)).asInstanceOf[Array[A]], 0)

    def newByteBuilder(sizeHint: Int): ByteBuilder = Builder(new Array[Byte](sizeHint.max(8)), 0)

    def newShortBuilder(sizeHint: Int): ShortBuilder = Builder(new Array[Short](sizeHint.max(8)), 0)

    def newIntBuilder(sizeHint: Int): IntBuilder = Builder(new Array[Int](sizeHint.max(8)), 0)

    def newLongBuilder(sizeHint: Int): LongBuilder = Builder(new Array[Long](sizeHint.max(8)), 0)

    def newFloatBuilder(sizeHint: Int): FloatBuilder = Builder(new Array[Float](sizeHint.max(8)), 0)

    def newDoubleBuilder(sizeHint: Int): DoubleBuilder = Builder(new Array[Double](sizeHint.max(8)), 0)

    def newCharBuilder(sizeHint: Int): CharBuilder = Builder(new Array[Char](sizeHint.max(8)), 0)

    def addObject[A](builder: ObjectBuilder[A], a: A): Unit = {
      ensureObjectSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addByte(builder: ByteBuilder, a: Byte): Unit = {
      ensureByteSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addShort(builder: ShortBuilder, a: Short): Unit = {
      ensureShortSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addInt(builder: IntBuilder, a: Int): Unit = {
      ensureIntSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addLong(builder: LongBuilder, a: Long): Unit = {
      ensureLongSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addFloat(builder: FloatBuilder, a: Float): Unit = {
      ensureFloatSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addDouble(builder: DoubleBuilder, a: Double): Unit = {
      ensureDoubleSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def addChar(builder: CharBuilder, a: Char): Unit = {
      ensureCharSize(builder, builder.size + 1)
      builder.buffer(builder.size) = a
      builder.size += 1
    }

    def resultObject[A](builder: ObjectBuilder[A]): Array[A] = builder.buffer.take(builder.size)

    def resultByte(builder: ByteBuilder): Array[Byte] = builder.buffer.take(builder.size)

    def resultShort(builder: ShortBuilder): Array[Short] = builder.buffer.take(builder.size)

    def resultInt(builder: IntBuilder): Array[Int] = builder.buffer.take(builder.size)

    def resultLong(builder: LongBuilder): Array[Long] = builder.buffer.take(builder.size)

    def resultFloat(builder: FloatBuilder): Array[Float] = builder.buffer.take(builder.size)

    def resultDouble(builder: DoubleBuilder): Array[Double] = builder.buffer.take(builder.size)

    def resultChar(builder: CharBuilder): Array[Char] = builder.buffer.take(builder.size)

    private def ensureObjectSize[A](builder: Builder[A], size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[AnyRef](builder.buffer.length * 2).asInstanceOf[Array[A]]
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureByteSize(builder: ByteBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Byte](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureShortSize(builder: ShortBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Short](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureIntSize(builder: IntBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Int](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureLongSize(builder: LongBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Long](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureFloatSize(builder: FloatBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Float](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureDoubleSize(builder: DoubleBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Double](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }

    private def ensureCharSize(builder: CharBuilder, size: Int): Unit =
      if (builder.buffer.length < size) {
        val newBuffer = new Array[Char](builder.buffer.length * 2)
        System.arraycopy(builder.buffer, 0, newBuffer, 0, builder.size)
        builder.buffer = newBuffer
      }
  }
}
