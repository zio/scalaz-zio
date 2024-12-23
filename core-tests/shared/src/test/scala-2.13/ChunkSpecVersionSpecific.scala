package zio

import zio.test._

import scala.collection.Factory

object ChunkSpecVersionSpecific extends ZIOBaseSpec {

  def spec = suite("ChunkSpecVersionSpecific")(
    test("to") {
      val list  = List(1, 2, 3)
      val chunk = Chunk(1, 2, 3)
      assertTrue(list.to(Chunk) == chunk)
    },
    test("factory") {
      val list    = List(1, 2, 3)
      val chunk   = Chunk(1, 2, 3)
      val factory = implicitly[Factory[Int, Chunk[Int]]]
      assertTrue(factory.fromSpecific(list) == chunk)
    }
  )
}
