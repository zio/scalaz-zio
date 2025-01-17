// File: streams/shared/src/test/scala/zio/stream/ZStreamBroadcastSpec.scala

package zio.stream

import zio._
import zio.test._
import zio.test.Assertion._
import zio.test.TestAspect._

object ZStreamBroadcastSpec extends ZIOSpecDefault {
  def spec = suite("ZStream.broadcastDynamic")(
    test("should broadcast elements to multiple consumers") {
      for {
        result <- ZStream.range(1, 5)
                        .broadcastDynamic(2)
                        .use { stream =>
                          for {
                            f1 <- stream.take(2).runCollect.fork
                            f2 <- stream.take(3).runCollect.fork
                            r1 <- f1.join
                            r2 <- f2.join
                          } yield (r1, r2)
                        }
      } yield assertTrue(
        result._1 == Chunk(1, 2),
        result._2 == Chunk(1, 2, 3)
      )
    },
    
    test("should handle early consumer termination") {
      for {
        result <- ZStream.range(1, 10)
                        .broadcastDynamic(2)
                        .use { stream =>
                          for {
                            f1 <- stream.take(2).runCollect.fork
                            f2 <- stream.runCollect.fork
                            r1 <- f1.join
                            r2 <- f2.join
                          } yield (r1, r2)
                        }
      } yield assertTrue(
        result._1 == Chunk(1, 2),
        result._2.size == 9
      )
    },

    test("should cleanup resources when all consumers complete") {
      for {
        ref <- Ref.make(false)
        _   <- ZStream.range(1, 5)
                     .ensuring(ref.set(true))
                     .broadcastDynamic(2)
                     .use { stream =>
                       for {
                         f1 <- stream.take(2).runCollect.fork
                         f2 <- stream.take(3).runCollect.fork
                         _  <- f1.join
                         _  <- f2.join
                       } yield ()
                     }
        cleaned <- ref.get
      } yield assertTrue(cleaned)
    },

    test("should handle errors correctly") {
      for {
        result <- ZStream(1)
                   .concat(ZStream.fail("boom"))
                   .broadcastDynamic(2)
                   .use { stream =>
                     for {
                       f1 <- stream.runCollect.either.fork
                       f2 <- stream.runCollect.either.fork
                       r1 <- f1.join
                       r2 <- f2.join
                     } yield (r1, r2)
                   }
      } yield assertTrue(
        result._1 == Left("boom"),
        result._2 == Left("boom")
      )
    }
  ) @@ timeout(5.seconds)
}