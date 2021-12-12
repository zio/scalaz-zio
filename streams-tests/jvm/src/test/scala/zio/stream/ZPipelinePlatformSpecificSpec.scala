package zio.stream

import zio._
import zio.stream.compression.TestData._
import zio.test._
import zio.test.Assertion._

import java.util.zip.Deflater

object ZPipelinePlatformSpecificSpec extends ZIOBaseSpec {
  override def aspects: Chunk[TestAspectAtLeastR[Live]] =
    Chunk(TestAspect.timeout(300.seconds))

  def spec = suite("ZPipeline JVM")(
    suite("Constructors")(
      suite("Deflate")(
        test("JDK inflates what was deflated")(
          check(Gen.listOfBounded(0, `1K`)(Gen.byte).zip(Gen.int(1, `1K`)).zip(Gen.int(1, `1K`))) {
            case (input, n, bufferSize) =>
              assertM(for {
                deflated <-
                  ZStream
                    .fromIterable(input)
                    .rechunk(n)
                    .via(ZPipeline.deflate(bufferSize))
                    .runCollect
                inflated <- jdkInflate(deflated, noWrap = false)
              } yield inflated)(equalTo(input))
          }
        )
      ),
      suite("Inflate")(
        test("inflate what JDK deflated")(
          check(Gen.listOfBounded(0, `1K`)(Gen.byte).zip(Gen.int(1, `1K`)).zip(Gen.int(1, `1K`))) {
            case (chunk, n, bufferSize) =>
              assertM(for {
                out <-
                  ZStream
                    .fromIterable(jdkDeflate(chunk.toArray, new Deflater()))
                    .rechunk(n)
                    .via(ZPipeline.inflate(bufferSize))
                    .runCollect
              } yield out.toList)(equalTo(chunk))
          }
        )
      ),
      suite("Gunzip")(
        test("gunzip what JDK gzipped, nowrap")(
          check(Gen.listOfBounded(0, `1K`)(Gen.byte).zip(Gen.int(1, `1K`)).zip(Gen.int(1, `1K`))) {
            case (chunk, n, bufferSize) =>
              assertM(for {
                out <- ZStream
                         .fromIterable(jdkGzip(chunk.toArray))
                         .rechunk(n)
                         .via(ZPipeline.gunzip(bufferSize))
                         .runCollect
              } yield out.toList)(equalTo(chunk))
          }
        )
      ),
      suite("Gzip")(
        test("JDK gunzips what was gzipped")(
          check(Gen.listOfBounded(0, `1K`)(Gen.byte).zip(Gen.int(1, `1K`)).zip(Gen.int(1, `1K`))) {
            case (input, n, bufferSize) =>
              assertM(for {
                gzipped <- ZStream
                             .fromIterable(input)
                             .rechunk(n)
                             .via(ZPipeline.gzip(bufferSize))
                             .runCollect
                inflated <- jdkGunzip(gzipped)
              } yield inflated)(equalTo(input))
          }
        )
      )
    )
  )
}
