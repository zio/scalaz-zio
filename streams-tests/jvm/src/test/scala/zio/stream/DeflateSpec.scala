package zio.stream

import zio._
import zio.stream.compression.TestData._
import zio.test.Assertion._
import zio.test._

import java.util.zip.Deflater

object DeflateSpec extends ZIOBaseSpec {
  override def spec =
    suite("DeflateSpec")(
      test("JDK inflates what was deflated")(
        check(Gen.listOfBounded(0, size)(Gen.byte), Gen.int(1, size), Gen.int(1, size)) { (input, n, bufferSize) =>
          for {
            tuple <-
              (ZStream.fromIterable(input).rechunk(n).channel >>> Deflate.makeDeflater(bufferSize)).runCollect
            (deflated, _) = tuple
            inflated     <- jdkInflate(deflated.flatten, noWrap = false)
          } yield assert(inflated)(equalTo(input))
        }
      ),
      test("deflate empty bytes, small buffer")(
        assertZIO(
          (ZStream.fromIterable(List.empty).rechunk(1).channel >>> Deflate
            .makeDeflater(100, noWrap = false)).runCollect
            .map(_._1.flatten.toList)
        )(equalTo(jdkDeflate(Array.empty, new Deflater(-1, false)).toList))
      ),
      test("deflates same as JDK")(
        assertZIO(
          (ZStream.fromIterable(longText).rechunk(128).channel >>> Deflate.makeDeflater(256, noWrap = false)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, false))))
        )
      ),
      test("deflates same as JDK, nowrap")(
        assertZIO(
          (ZStream.fromIterable(longText).rechunk(128).channel >>> Deflate.makeDeflater(256, noWrap = true)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, true))))
        )
      ),
      test("deflates same as JDK, small buffer")(
        assertZIO(
          (ZStream.fromIterable(longText).rechunk(64).channel >>> Deflate.makeDeflater(1, noWrap = false)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, false))))
        )
      ),
      test("deflates same as JDK, nowrap, small buffer ")(
        assertZIO(
          (ZStream.fromIterable(longText).rechunk(64).channel >>> Deflate.makeDeflater(1, noWrap = true)).runCollect
            .map(_._1.flatten)
        )(
          equalTo(Chunk.fromArray(jdkDeflate(longText, new Deflater(-1, true))))
        )
      )
    )

}
