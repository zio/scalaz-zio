package zio.stream

import zio._
import zio.stream.compression.TestData.{size, jdkGzip, longText, otherShortText, shortText}
import zio.test.Assertion._
import zio.test._
import zio.{Chunk, ZIO}

import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

object GunzipSpec extends ZIOBaseSpec {

  override def spec =
    suite("Gunzip")(
      test("short stream")(
        assertZIO(
          (jdkGzippedStream(shortText).channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(shortText))
        )
      ),
      test("stream of two gzipped inputs as a single chunk")(
        assertZIO(
          ((jdkGzippedStream(shortText) ++ jdkGzippedStream(otherShortText))
            .rechunk(500)
            .channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText) ++ Chunk.fromArray(otherShortText)))
      ),
      test("long input")(
        assertZIO(
          (jdkGzippedStream(longText).channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, no SYNC_FLUSH")(
        assertZIO(
          (jdkGzippedStream(longText, syncFlush = false).channel >>> Gunzip.makeGunzipper(64)).runCollect
            .map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, buffer smaller than chunks")(
        assertZIO(
          (jdkGzippedStream(longText).rechunk(500).channel >>> Gunzip.makeGunzipper(1)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("long input, chunks smaller then buffer")(
        assertZIO(
          (jdkGzippedStream(longText).rechunk(1).channel >>> Gunzip.makeGunzipper(500)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(longText)))
      ),
      test("fail early if header is corrupted")(
        assertZIO(
          (ZStream.fromIterable(1 to 10).map(_.toByte).channel >>> Gunzip.makeGunzipper()).runCollect.exit
        )(fails(anything))
      ),
      test("fail if input stream finished unexpected")(
        assertZIO(
          (jdkGzippedStream(longText).take(80).channel >>> Gunzip.makeGunzipper()).runCollect.exit
        )(fails(anything))
      ),
      test("no output on very incomplete stream is not OK")(
        assertZIO(
          (ZStream.fromIterable(1 to 5).map(_.toByte).channel >>> Gunzip.makeGunzipper()).runCollect.exit
        )(fails(anything))
      ),
      test("gunzip what JDK gzipped, nowrap")(
        check(Gen.listOfBounded(0, size)(Gen.byte).zip(Gen.int(1, size)).zip(Gen.int(1, size))) {
          case (chunk, n, bufferSize) =>
            assertZIO(for {
              deflated <- ZIO.succeed(jdkGzippedStream(chunk.toArray))
              out      <- (deflated.rechunk(n).channel >>> Gunzip.makeGunzipper(bufferSize)).runCollect.map(_._1.flatten)
            } yield out.toList)(equalTo(chunk))
        }
      ),
      test("parses header with FEXTRA")(
        assertZIO(
          (headerWithExtra.channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      ),
      test("parses header with FCOMMENT")(
        assertZIO(
          (headerWithComment.channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      ),
      test("parses header with FNAME")(
        assertZIO(
          (headerWithFileName.channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      ),
      test("parses header with CRC16")(
        assertZIO(
          (headerWithCrc.rechunk(1).channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      ),
      test("parses header with CRC16, FNAME, FCOMMENT, FEXTRA")(
        assertZIO(
          (headerWithAll.channel >>> Gunzip.makeGunzipper(64)).runCollect.map(_._1.flatten)
        )(equalTo(Chunk.fromArray(shortText)))
      )
    )

  private def jdkGzippedStream(bytes: Array[Byte], syncFlush: Boolean = true): ZStream[Any, Nothing, Byte] =
    ZStream.fromIterable(jdkGzip(bytes, syncFlush))

  private def makeStreamWithCustomHeader(flag: Int, headerTail: Array[Byte]): ZStream[Any, Nothing, Byte] = {
    val headerHead = Array(31, 139, 8, flag, 0, 0, 0, 0, 0, 0).map(_.toByte)
    ZStream.fromIterable(headerHead ++ headerTail ++ jdkGzip(shortText).drop(10))
  }

  private val headerWithExtra: ZStream[Any, Nothing, Byte] =
    makeStreamWithCustomHeader(4, (Seq(13.toByte, 0.toByte) ++ Seq.fill(13)(42.toByte)).toArray)

  private val headerWithComment: ZStream[Any, Nothing, Byte] =
    makeStreamWithCustomHeader(16, "ZIO rocks!".getBytes(StandardCharsets.ISO_8859_1) ++ Array(0.toByte))

  private val headerWithFileName: ZStream[Any, Nothing, Byte] =
    makeStreamWithCustomHeader(8, "some-file-name.md".getBytes(StandardCharsets.ISO_8859_1) ++ Array(0.toByte))

  private val headerWithCrc: ZStream[Any, Nothing, Byte] = {
    val crcFlag     = 2
    val headerBytes = Array(31, 139, 8, crcFlag, 0, 0, 0, 0, 0, 0).map(_.toByte)
    val crc32       = new CRC32
    crc32.update(headerBytes)
    val crc16      = (crc32.getValue() & 0xffffL).toInt
    val crc16Byte1 = (crc16 & 0xff).toByte
    val crc16Byte2 = (crc16 >> 8).toByte
    val header     = headerBytes ++ Array(crc16Byte1, crc16Byte2)
    ZStream.fromIterable(header ++ jdkGzip(shortText).drop(10))
  }

  private val headerWithAll: ZStream[Any, Nothing, Byte] = {
    val flags         = 2 + 4 + 8 + 16
    val fixedHeader   = Array(31, 139, 8, flags, 0, 0, 0, 0, 0, 0).map(_.toByte)
    val extra         = (Seq(7.toByte, 0.toByte) ++ Seq.fill(7)(99.toByte)).toArray
    val fileName      = "win32.ini".getBytes(StandardCharsets.ISO_8859_1) ++ Array(0.toByte)
    val comment       = "the last test".getBytes(StandardCharsets.ISO_8859_1) ++ Array(0.toByte)
    val headerUpToCrc = fixedHeader ++ extra ++ fileName ++ comment
    val crc32         = new CRC32
    crc32.update(headerUpToCrc)
    val crc16      = (crc32.getValue() & 0xffffL).toInt
    val crc16Byte1 = (crc16 & 0xff).toByte
    val crc16Byte2 = (crc16 >> 8).toByte
    val header     = headerUpToCrc ++ Array(crc16Byte1, crc16Byte2)
    ZStream.fromIterable(header ++ jdkGzip(shortText).drop(10))
  }

}
