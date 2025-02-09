package zio.stream

import zio._
import zio.stream.compression.TestData._
import zio.test.Assertion._
import zio.test._

import java.util.zip.Deflater

import Inflate._

object InflateSpec extends ZIOBaseSpec {
  override def spec =
    suite("CompressionSpec")(
      test("short stream")(
        assertZIO(
          (deflatedStream(shortText).channel >>> makeInflater(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      ),
      test("stream of two deflated inputs")(
        assertZIO(
          ((deflatedStream(shortText) ++ deflatedStream(otherShortText)).channel >>> makeInflater(64)).runCollect
            .map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText) ++ Chunk.fromArray(otherShortText)))
      ),
      test("stream of two deflated inputs as a single chunk")(
        assertZIO(
          ((deflatedStream(shortText) ++ deflatedStream(otherShortText)).rechunk(500).channel >>> makeInflater(
            64
          )).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText) ++ Chunk.fromArray(otherShortText)))
      ),
      test("long input")(
        assertZIO(
          (deflatedStream(longText).channel >>> makeInflater(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, buffer smaller than chunks")(
        assertZIO(
          (deflatedStream(longText).rechunk(500).channel >>> makeInflater(1)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, chunks smaller then buffer")(
        assertZIO(
          (deflatedStream(longText).rechunk(1).channel >>> makeInflater(500)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, not wrapped in ZLIB header and trailer")(
        assertZIO(
          (noWrapDeflatedStream(longText).channel >>> makeInflater(1024, noWrap = true)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("fail early if header is corrupted")(
        assertZIO(
          (ZStream.fromIterable(Seq(1, 2, 3, 4, 5).map(_.toByte)).channel >>> makeInflater()).runCollect.exit
        )(fails(anything))
      ),
      test("inflate what JDK deflated")(
        check(Gen.listOfBounded(0, size)(Gen.byte).zip(Gen.int(1, size)).zip(Gen.int(1, size))) {
          case (chunk, n, bufferSize) =>
            assertZIO(for {
              deflated <- ZIO.succeed(deflatedStream(chunk.toArray))
              out      <- (deflated.rechunk(n).channel >>> makeInflater(bufferSize)).runCollect.map(_._1.flatten)
            } yield out.toList)(equalTo(chunk))
        }
      ),
      test("inflate what JDK deflated, nowrap")(
        check(Gen.listOfBounded(0, size)(Gen.byte).zip(Gen.int(1, size)).zip(Gen.int(1, size))) {
          case (chunk, n, bufferSize) =>
            assertZIO(for {
              deflated <- ZIO.succeed(noWrapDeflatedStream(chunk.toArray))
              out <-
                (deflated.rechunk(n).channel >>> makeInflater(bufferSize, noWrap = true)).runCollect.map(_._1.flatten)
            } yield out.toList)(equalTo(chunk))
        }
      ),
      test("inflate nowrap: remaining = 0 but not all was pulled")(
        // This case shown error when not all data was pulled out of inflater
        assertZIO(for {
          input    <- ZIO.succeed(inflateRandomExampleThatFailed)
          deflated <- ZIO.succeed(noWrapDeflatedStream(input))
          out      <- (deflated.rechunk(40).channel >>> makeInflater(11, noWrap = true)).runCollect.map(_._1.flatten)
        } yield out.toList)(equalTo(inflateRandomExampleThatFailed.toList))
      ),
      test("fail if input stream finished unexpected")(
        assertZIO(
          (ZStream
            .fromIterable(jdkGzip(longText, syncFlush = true))
            .take(800)
            .channel >>> makeInflater()).runCollect.exit
        )(fails(anything))
      )
    )

  def deflatedStream(data: Array[Byte]): ZStream[Any, Nothing, Byte] =
    ZStream.fromIterable(jdkDeflate(data, new Deflater()))

  def noWrapDeflatedStream(data: Array[Byte]): ZStream[Any, Nothing, Byte] =
    ZStream.fromIterable(jdkDeflate(data, new Deflater(9, true)))

}
