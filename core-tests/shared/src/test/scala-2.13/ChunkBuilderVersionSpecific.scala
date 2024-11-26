import zio.ChunkBuilder
import zio.ZIOBaseSpec
import zio.test._

object ChunkBuilderVersionSpecific extends ZIOBaseSpec {

  def spec = suite("ChunkBuilderVersionSpecific")(
    suite("Boolean")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.boolean)) { as =>
          val builder = new ChunkBuilder.Boolean
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Byte")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.byte)) { as =>
          val builder = new ChunkBuilder.Byte
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Char")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.char)) { as =>
          val builder = new ChunkBuilder.Char
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Double")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.double)) { as =>
          val builder = new ChunkBuilder.Double
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Float")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.float)) { as =>
          val builder = new ChunkBuilder.Float
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Int")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.int)) { as =>
          val builder = new ChunkBuilder.Int
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Long")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.long)) { as =>
          val builder = new ChunkBuilder.Long
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    ),
    suite("Short")(
      test("knownSize")(
        check(Gen.chunkOf(Gen.short)) { as =>
          val builder = new ChunkBuilder.Short
          as.foreach(builder += _)
          assertTrue(builder.knownSize == as.size)
        }
      )
    )
  )
}
