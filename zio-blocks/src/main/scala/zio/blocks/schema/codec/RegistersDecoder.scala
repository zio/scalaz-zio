package zio.blocks.schema.codec

import zio.blocks.schema.binding._

// TODO: A dumb version that doesn't allow "parallel" decoding
final class RegistersDecoder(depth: Int) {
  private var _registers: Array[Registers]        = Array.fill(depth)(Registers())
  private var constructors: Array[Constructor[_]] = Array.fill(depth)(null)
  private var size: Int                           = 0

  final def enter[A](constructor: Constructor[A]): Registers = {
    ensureCapacity(size + 1)
    val r = _registers(size)
    constructors(size) = constructor
    size += 1
    r
  }

  def unsafeExit[A](): A = {
    val c = constructors(size)

    val result = c.construct(_registers(size), RegisterOffset.Zero)

    constructors(size) = null

    size -= 1

    result.asInstanceOf[A]
  }

  private def ensureCapacity(capacity: Int): Unit =
    if (_registers.length < capacity) {
      val newRegisters    = new Array[Registers](capacity)
      val newConstructors = new Array[Constructor[_]](capacity)

      var i = _registers.length
      while (i < capacity) {
        newRegisters(i) = Registers()
        i += 1
      }

      Array.copy(_registers, 0, newRegisters, 0, _registers.length)
      Array.copy(constructors, 0, newConstructors, 0, constructors.length)

      _registers = newRegisters
      constructors = newConstructors
    }
}

object RegistersDecoder {}
