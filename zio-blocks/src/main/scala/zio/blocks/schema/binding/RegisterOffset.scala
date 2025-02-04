package zio.blocks.schema.binding

object RegisterOffset {
  type RegisterOffset = Long

  val Zero: RegisterOffset = 0

  val BooleansShift = 0
  val BooleansMask  = 0x7f

  val BytesShift = 7
  val BytesMask  = 0x7f

  val ShortsShift = 14
  val ShortsMask  = 0x7f

  val IntsShift = 21
  val IntsMask  = 0x7f

  val LongsShift = 28
  val LongsMask  = 0x7f

  val FloatsShift = 35
  val FloatsMask  = 0x7f

  val DoublesShift = 42
  val DoublesMask  = 0x7f

  val CharsShift = 49
  val CharsMask  = 0x7f

  val ObjectsShift = 56
  val ObjectsMask  = 0x7f

  def getBooleans(offset: RegisterOffset): Int = ((offset >> BooleansShift) & BooleansMask).toInt
  def getBytes(offset: RegisterOffset): Int    = ((offset >> BytesShift) & BytesMask).toInt
  def getShorts(offset: RegisterOffset): Int   = ((offset >> ShortsShift) & ShortsMask).toInt
  def getInts(offset: RegisterOffset): Int     = ((offset >> IntsShift) & IntsMask).toInt
  def getLongs(offset: RegisterOffset): Int    = ((offset >> LongsShift) & LongsMask).toInt
  def getFloats(offset: RegisterOffset): Int   = ((offset >> FloatsShift) & FloatsMask).toInt
  def getDoubles(offset: RegisterOffset): Int  = ((offset >> DoublesShift) & DoublesMask).toInt
  def getChars(offset: RegisterOffset): Int    = ((offset >> CharsShift) & CharsMask).toInt
  def getObjects(offset: RegisterOffset): Int  = ((offset >> ObjectsShift) & ObjectsMask).toInt

  def apply(
    booleans: Int = 0,
    bytes: Int = 0,
    shorts: Int = 0,
    ints: Int = 0,
    longs: Int = 0,
    floats: Int = 0,
    doubles: Int = 0,
    chars: Int = 0,
    objects: Int = 0
  ): RegisterOffset = {
    val booleansShifted = (booleans.toLong << BooleansShift)
    val bytesShifted    = (bytes.toLong << BytesShift)
    val shortsShifted   = (shorts.toLong << ShortsShift)
    val intsShifted     = (ints.toLong << IntsShift)
    val longsShifted    = (longs.toLong << LongsShift)
    val floatsShifted   = (floats.toLong << FloatsShift)
    val doublesShifted  = (doubles.toLong << DoublesShift)
    val charsShifted    = (chars.toLong << CharsShift)
    val objectsShifted  = (objects.toLong << ObjectsShift)

    booleansShifted | bytesShifted | shortsShifted | intsShifted | longsShifted | floatsShifted | doublesShifted | charsShifted | objectsShifted
  }

  def add(left: RegisterOffset, right: RegisterOffset): RegisterOffset =
    // TODO: Assert no overflow, which would happen if in any channel, the sum of the two values is greater than 127
    left + right

  def incrementBooleans(offset: RegisterOffset): RegisterOffset = offset + (1L << BooleansShift)

  def incrementBytes(offset: RegisterOffset): RegisterOffset = offset + (1L << BytesShift)

  def incrementShorts(offset: RegisterOffset): RegisterOffset = offset + (1L << ShortsShift)

  def incrementInts(offset: RegisterOffset): RegisterOffset = offset + (1L << IntsShift)

  def incrementLongs(offset: RegisterOffset): RegisterOffset = offset + (1L << LongsShift)

  def incrementFloats(offset: RegisterOffset): RegisterOffset = offset + (1L << FloatsShift)

  def incrementDoubles(offset: RegisterOffset): RegisterOffset = offset + (1L << DoublesShift)

  def incrementChars(offset: RegisterOffset): RegisterOffset = offset + (1L << CharsShift)

  def incrementObjects(offset: RegisterOffset): RegisterOffset = offset + (1L << ObjectsShift)
}
