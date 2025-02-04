package zio.blocks.schema.binding

import RegisterOffset.RegisterOffset

sealed trait Register[A] {
  final type Boxed = A

  def registerType: RegisterType[A]

  def get(registers: Registers, base: RegisterOffset): Boxed

  def set(registers: Registers, base: RegisterOffset, boxed: Boxed): Unit

  def size: RegisterOffset
}
object Register {
  case object None extends Register[scala.Unit] {
    def registerType: RegisterType[scala.Unit] = RegisterType.Unit

    def get(registers: Registers, base: RegisterOffset): scala.Unit              = ()
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Unit): Unit = ()

    def size: RegisterOffset = RegisterOffset.Zero
  }
  final case class Boolean(relativeIndex: scala.Int) extends Register[scala.Boolean] {
    def registerType: RegisterType[scala.Boolean] = RegisterType.Boolean

    def get(registers: Registers, base: RegisterOffset): scala.Boolean = registers.getBoolean(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Boolean): Unit =
      registers.setBoolean(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementBooleans(RegisterOffset.Zero)
  }
  final case class Byte(relativeIndex: scala.Int) extends Register[scala.Byte] {
    def registerType: RegisterType[scala.Byte] = RegisterType.Byte

    def get(registers: Registers, base: RegisterOffset): scala.Byte = registers.getByte(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Byte): Unit =
      registers.setByte(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementBytes(RegisterOffset.Zero)
  }
  final case class Short(relativeIndex: scala.Int) extends Register[scala.Short] {
    def registerType: RegisterType[scala.Short] = RegisterType.Short

    def get(registers: Registers, base: RegisterOffset): scala.Short = registers.getShort(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Short): Unit =
      registers.setShort(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementShorts(RegisterOffset.Zero)
  }
  final case class Int(relativeIndex: scala.Int) extends Register[scala.Int] {
    def registerType: RegisterType[scala.Int] = RegisterType.Int

    def get(registers: Registers, base: RegisterOffset): scala.Int = registers.getInt(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Int): Unit =
      registers.setInt(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementInts(RegisterOffset.Zero)
  }
  final case class Long(relativeIndex: scala.Int) extends Register[scala.Long] {
    def registerType: RegisterType[scala.Long] = RegisterType.Long

    def get(registers: Registers, base: RegisterOffset): scala.Long = registers.getLong(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Long): Unit =
      registers.setLong(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementLongs(RegisterOffset.Zero)
  }
  final case class Float(relativeIndex: scala.Int) extends Register[scala.Float] {
    def registerType: RegisterType[scala.Float] = RegisterType.Float

    def get(registers: Registers, base: RegisterOffset): scala.Float = registers.getFloat(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Float): Unit =
      registers.setFloat(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementFloats(RegisterOffset.Zero)
  }
  final case class Double(relativeIndex: scala.Int) extends Register[scala.Double] {
    def registerType: RegisterType[scala.Double] = RegisterType.Double

    def get(registers: Registers, base: RegisterOffset): scala.Double = registers.getDouble(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Double): Unit =
      registers.setDouble(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementDoubles(RegisterOffset.Zero)
  }
  final case class Char(relativeIndex: scala.Int) extends Register[scala.Char] {
    def registerType: RegisterType[scala.Char] = RegisterType.Char

    def get(registers: Registers, base: RegisterOffset): scala.Char = registers.getChar(base, relativeIndex)
    def set(registers: Registers, base: RegisterOffset, boxed: scala.Char): Unit =
      registers.setChar(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementChars(RegisterOffset.Zero)
  }
  final case class Object[Boxed <: AnyRef](relativeIndex: scala.Int) extends Register[Boxed] {
    def registerType: RegisterType[Boxed] = RegisterType.Object[Boxed]()

    def get(registers: Registers, base: RegisterOffset): Boxed =
      registers.getObject(base, relativeIndex).asInstanceOf[Boxed]
    def set(registers: Registers, base: RegisterOffset, boxed: Boxed): Unit =
      registers.setObject(base, relativeIndex, boxed)

    def size: RegisterOffset = RegisterOffset.incrementObjects(RegisterOffset.Zero)
  }
}
