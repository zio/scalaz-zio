package zio

import zio.test.Assertion._
import zio.test._

object BitChunkSpec extends ZIOBaseSpec {

  val genByteChunk: Gen[Has[Random] with Has[Sized], Chunk[Byte]] =
    for {
      bytes <- Gen.listOf(Gen.anyByte)
    } yield Chunk.fromIterable(bytes)

  val genInt: Gen[Has[Random] with Has[Sized], Int] =
    Gen.small(Gen.const(_))

  def toBinaryString(byte: Byte): String =
    String.format("%8s", (byte.toInt & 0xff).toBinaryString).replace(' ', '0')

  def spec: ZSpec[Environment, Failure] = suite("BitChunkSpec")(
    test("drop") {
      check(genByteChunk, genInt) { (bytes, n) =>
        val actual   = bytes.asBits.drop(n).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.drop(n)
        assert(actual)(equalTo(expected))
      }
    },
    test("drop and then drop") {
      check(genByteChunk, genInt, genInt) { (bytes, n, m) =>
        val actual   = bytes.asBits.drop(n).drop(m).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.drop(n).drop(m)
        assert(actual)(equalTo(expected))
      }
    },
    test("drop and then take") {
      check(genByteChunk, genInt, genInt) { (bytes, n, m) =>
        val actual   = bytes.asBits.drop(n).take(m).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.drop(n).take(m)
        assert(actual)(equalTo(expected))
      }
    },
    test("take") {
      check(genByteChunk, genInt) { (bytes, n) =>
        val actual   = bytes.asBits.take(n).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.take(n)
        assert(actual)(equalTo(expected))
      }
    },
    test("take and then drop") {
      check(genByteChunk, genInt, genInt) { (bytes, n, m) =>
        val actual   = bytes.asBits.take(n).drop(m).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.take(n).drop(m)
        assert(actual)(equalTo(expected))
      }
    },
    test("take and then take") {
      check(genByteChunk, genInt, genInt) { (bytes, n, m) =>
        val actual   = bytes.asBits.take(n).take(m).toBinaryString
        val expected = bytes.map(toBinaryString).mkString.take(n).take(m)
        assert(actual)(equalTo(expected))
      }
    },
    test("toBinaryString") {
      check(genByteChunk) { bytes =>
        val actual   = bytes.asBits.toBinaryString
        val expected = bytes.map(toBinaryString).mkString
        assert(actual)(equalTo(expected))
      }
    }
  )
}
