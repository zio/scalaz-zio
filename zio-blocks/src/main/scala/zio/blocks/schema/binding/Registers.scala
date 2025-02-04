package zio.blocks.schema.binding

/**
 * Temporary storage to be used during encoding and decoding for schema-based
 * data structures. These are mutable and should be cached with thread locals,
 * fiber locals, or pools, to ensure zero-allocation during encoding / decoding.
 */
class Registers private {
  import RegisterOffset.RegisterOffset

  private var booleans: Array[Boolean] = new Array[Boolean](128)
  private var bytes: Array[Byte]       = new Array[Byte](8)
  private var shorts: Array[Short]     = new Array[Short](8)
  private var ints: Array[Int]         = new Array[Int](8)
  private var longs: Array[Long]       = new Array[Long](8)
  private var floats: Array[Float]     = new Array[Float](8)
  private var doubles: Array[Double]   = new Array[Double](8)
  private var chars: Array[Char]       = new Array[Char](8)
  private var objects: Array[AnyRef]   = new Array[AnyRef](8)

  private def ensureBooleanSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= booleans.length) {
      val newBooleans = new Array[Boolean](booleans.length * 2)
      System.arraycopy(booleans, 0, newBooleans, 0, booleans.length)
      booleans = newBooleans
    }

  private def ensureByteSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= bytes.length) {
      val newBytes = new Array[Byte](bytes.length * 2)
      System.arraycopy(bytes, 0, newBytes, 0, bytes.length)
      bytes = newBytes
    }

  private def ensureShortSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= shorts.length) {
      val newShorts = new Array[Short](shorts.length * 2)
      System.arraycopy(shorts, 0, newShorts, 0, shorts.length)
      shorts = newShorts
    }

  private def ensureIntSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= ints.length) {
      val newInts = new Array[Int](ints.length * 2)
      System.arraycopy(ints, 0, newInts, 0, ints.length)
      ints = newInts
    }

  private def ensureLongSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= longs.length) {
      val newLongs = new Array[Long](longs.length * 2)
      System.arraycopy(longs, 0, newLongs, 0, longs.length)
      longs = newLongs
    }

  private def ensureFloatSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= floats.length) {
      val newFloats = new Array[Float](floats.length * 2)
      System.arraycopy(floats, 0, newFloats, 0, floats.length)
      floats = newFloats
    }

  private def ensureDoubleSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= doubles.length) {
      val newDoubles = new Array[Double](doubles.length * 2)
      System.arraycopy(doubles, 0, newDoubles, 0, doubles.length)
      doubles = newDoubles
    }

  private def ensureCharSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= chars.length) {
      val newChars = new Array[Char](chars.length * 2)
      System.arraycopy(chars, 0, newChars, 0, chars.length)
      chars = newChars
    }

  private def ensureObjectSize(absoluteIndex: Int): Unit =
    if (absoluteIndex >= objects.length) {
      val newObjects = new Array[AnyRef](objects.length * 2)
      System.arraycopy(objects, 0, newObjects, 0, objects.length)
      objects = newObjects
    }

  def getBoolean(baseOffset: RegisterOffset, relativeIndex: Int): Boolean = {
    val offset = RegisterOffset.getBooleans(baseOffset) + relativeIndex
    booleans(offset)
  }

  def getByte(baseOffset: RegisterOffset, relativeIndex: Int): Byte = {
    val offset = RegisterOffset.getBytes(baseOffset) + relativeIndex
    bytes(offset)
  }

  def getShort(baseOffset: RegisterOffset, relativeIndex: Int): Short = {
    val offset = RegisterOffset.getShorts(baseOffset) + relativeIndex
    shorts(offset)
  }

  def getInt(baseOffset: RegisterOffset, relativeIndex: Int): Int = {
    val offset = RegisterOffset.getInts(baseOffset) + relativeIndex
    ints(offset)
  }

  def getLong(baseOffset: RegisterOffset, relativeIndex: Int): Long = {
    val offset = RegisterOffset.getLongs(baseOffset) + relativeIndex
    longs(offset)
  }

  def getFloat(baseOffset: RegisterOffset, relativeIndex: Int): Float = {
    val offset = RegisterOffset.getFloats(baseOffset) + relativeIndex
    floats(offset)
  }

  def getDouble(baseOffset: RegisterOffset, relativeIndex: Int): Double = {
    val offset = RegisterOffset.getDoubles(baseOffset) + relativeIndex
    doubles(offset)
  }

  def getChar(baseOffset: RegisterOffset, relativeIndex: Int): Char = {
    val offset = RegisterOffset.getChars(baseOffset) + relativeIndex
    chars(offset)
  }

  def getObject(baseOffset: RegisterOffset, relativeIndex: Int): AnyRef = {
    val offset = RegisterOffset.getObjects(baseOffset) + relativeIndex
    objects(offset)
  }

  def setBoolean(baseOffset: RegisterOffset, relativeIndex: Int, value: Boolean): Unit = {
    val offset = RegisterOffset.getBooleans(baseOffset) + relativeIndex
    ensureBooleanSize(offset)
    booleans(offset) = value
  }

  def setByte(baseOffset: RegisterOffset, relativeIndex: Int, value: Byte): Unit = {
    val offset = RegisterOffset.getBytes(baseOffset) + relativeIndex
    ensureByteSize(offset)
    bytes(offset) = value
  }

  def setShort(baseOffset: RegisterOffset, relativeIndex: Int, value: Short): Unit = {
    val offset = RegisterOffset.getShorts(baseOffset) + relativeIndex
    ensureShortSize(offset)
    shorts(offset) = value
  }

  def setInt(baseOffset: RegisterOffset, relativeIndex: Int, value: Int): Unit = {
    val offset = RegisterOffset.getInts(baseOffset) + relativeIndex
    ensureIntSize(offset)
    ints(offset) = value
  }

  def setLong(baseOffset: RegisterOffset, relativeIndex: Int, value: Long): Unit = {
    val offset = RegisterOffset.getLongs(baseOffset) + relativeIndex
    ensureLongSize(offset)
    longs(offset) = value
  }

  def setFloat(baseOffset: RegisterOffset, relativeIndex: Int, value: Float): Unit = {
    val offset = RegisterOffset.getFloats(baseOffset) + relativeIndex
    ensureFloatSize(offset)
    floats(offset) = value
  }

  def setDouble(baseOffset: RegisterOffset, relativeIndex: Int, value: Double): Unit = {
    val offset = RegisterOffset.getDoubles(baseOffset) + relativeIndex
    ensureDoubleSize(offset)
    doubles(offset) = value
  }

  def setChar(baseOffset: RegisterOffset, relativeIndex: Int, value: Char): Unit = {
    val offset = RegisterOffset.getChars(baseOffset) + relativeIndex
    ensureCharSize(offset)
    chars(offset) = value
  }

  def setObject(baseOffset: RegisterOffset, relativeIndex: Int, value: AnyRef): Unit = {
    val offset = RegisterOffset.getObjects(baseOffset) + relativeIndex
    ensureObjectSize(offset)
    objects(offset) = value
  }
}
object Registers {
  def apply(): Registers = new Registers()
}
