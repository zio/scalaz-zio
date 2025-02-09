package zio

import zio.test.Assertion._
import zio.test._

object ChunkBuilderSpec extends ZIOBaseSpec {

  def spec = suite("ChunkBuilderSpec")(
    suite("Boolean")(
      test("addOne")(
        check(Gen.chunkOf(Gen.boolean)) { as =>
          val builder = new ChunkBuilder.Boolean
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.boolean)) { as =>
          val builder = new ChunkBuilder.Boolean
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Boolean
        assert(builder.toString)(equalTo("ChunkBuilder.Boolean"))
      }
    ),
    suite("Byte")(
      test("addOne")(
        check(Gen.chunkOf(Gen.byte)) { as =>
          val builder = new ChunkBuilder.Byte
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.byte)) { as =>
          val builder = new ChunkBuilder.Byte
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Byte
        assert(builder.toString)(equalTo("ChunkBuilder.Byte"))
      }
    ),
    suite("Char")(
      test("addOne")(
        check(Gen.chunkOf(Gen.char)) { as =>
          val builder = new ChunkBuilder.Char
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.char)) { as =>
          val builder = new ChunkBuilder.Char
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Char
        assert(builder.toString)(equalTo("ChunkBuilder.Char"))
      }
    ),
    suite("Double")(
      test("addOne")(
        check(Gen.chunkOf(Gen.double)) { as =>
          val builder = new ChunkBuilder.Double
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.double)) { as =>
          val builder = new ChunkBuilder.Double
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Double
        assert(builder.toString)(equalTo("ChunkBuilder.Double"))
      }
    ),
    suite("Float")(
      test("addOne")(
        check(Gen.chunkOf(Gen.float)) { as =>
          val builder = new ChunkBuilder.Float
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.float)) { as =>
          val builder = new ChunkBuilder.Float
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Float
        assert(builder.toString)(equalTo("ChunkBuilder.Float"))
      }
    ),
    suite("Int")(
      test("addOne")(
        check(Gen.chunkOf(Gen.int)) { as =>
          val builder = new ChunkBuilder.Int
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.int)) { as =>
          val builder = new ChunkBuilder.Int
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Int
        assert(builder.toString)(equalTo("ChunkBuilder.Int"))
      }
    ),
    suite("Long")(
      test("addOne")(
        check(Gen.chunkOf(Gen.long)) { as =>
          val builder = new ChunkBuilder.Long
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.long)) { as =>
          val builder = new ChunkBuilder.Long
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Long
        assert(builder.toString)(equalTo("ChunkBuilder.Long"))
      }
    ),
    suite("Short")(
      test("addOne")(
        check(Gen.chunkOf(Gen.short)) { as =>
          val builder = new ChunkBuilder.Short
          as.foreach(builder += _)
          assertTrue(builder.result() == as)
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.short)) { as =>
          val builder = new ChunkBuilder.Short
          builder ++= as
          assertTrue(builder.result() == as)
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Short
        assert(builder.toString)(equalTo("ChunkBuilder.Short"))
      }
    )
  )
}
