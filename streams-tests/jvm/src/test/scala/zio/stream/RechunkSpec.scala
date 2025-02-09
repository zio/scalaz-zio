package zio.stream

import zio._
import zio.stream.ZStream.fromChunk
import zio.test.Assertion._
import zio.test._

object RechunkSpec extends ZIOBaseSpec {
  override def spec =
    suite("RechunkSpec")(
      test("rechunk small with rest")(
        assertZIO(ZStream(1, 2, 3, 4, 5).rechunk(2).chunks.runCollect)(
          equalTo(Chunk(Chunk(1, 2), Chunk(3, 4), Chunk(5)))
        )
      ),
      test("rechunk small with no rest")(
        assertZIO(ZStream(1, 2, 3, 4).rechunk(2).chunks.runCollect)(
          equalTo(Chunk(Chunk(1, 2), Chunk(3, 4)))
        )
      ),
      test("rechunk large") {
        val elems = (1 to 51).toList
        for {
          result <- ZStream.from(elems).rechunk(2).chunks.runCollect
        } yield {
          assertTrue(result.size == 26) &&
          assertTrue(result.dropRight(1).forall(_.size == 2)) &&
          assertTrue(result.flatten.toList == elems)
        }
      },
      test("rechunk mixed large/small sizes")(
        check(Gen.chunkOfN(10)(Gen.chunkOfBounded(0, 50)(Gen.int(1, 100))), Gen.int(10, 60)) { (c, n) =>
          val in = ZStream.fromChunk(c).flatMap(fromChunk(_))
          for {
            result  <- in.rechunk(n).chunks.runCollect
            expected = c.flatten.grouped(n).toList
          } yield assertTrue(result.toList == expected)
        }
      )
    )
}
