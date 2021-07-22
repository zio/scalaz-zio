package zio

import zio.test.Assertion._
import zio.test._

object ChunkBuilderSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] = suite("ChunkBuilderSpec")(
    suite("Boolean")(
      test("addOne")(
        check(Gen.chunkOf(Gen.boolean)) { as =>
          val builder = new ChunkBuilder.Boolean
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.boolean)) { as =>
          val builder = new ChunkBuilder.Boolean
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Boolean
        assert(builder.toString)(equalTo("ChunkBuilder.Boolean"))
      }
    ),
    suite("Byte")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyByte)) { as =>
          val builder = new ChunkBuilder.Byte
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyByte)) { as =>
          val builder = new ChunkBuilder.Byte
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Byte
        assert(builder.toString)(equalTo("ChunkBuilder.Byte"))
      }
    ),
    suite("Char")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyChar)) { as =>
          val builder = new ChunkBuilder.Char
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyChar)) { as =>
          val builder = new ChunkBuilder.Char
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Char
        assert(builder.toString)(equalTo("ChunkBuilder.Char"))
      }
    ),
    suite("Double")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyDouble)) { as =>
          val builder = new ChunkBuilder.Double
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyDouble)) { as =>
          val builder = new ChunkBuilder.Double
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Double
        assert(builder.toString)(equalTo("ChunkBuilder.Double"))
      }
    ),
    suite("Float")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyFloat)) { as =>
          val builder = new ChunkBuilder.Float
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyFloat)) { as =>
          val builder = new ChunkBuilder.Float
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Float
        assert(builder.toString)(equalTo("ChunkBuilder.Float"))
      }
    ),
    suite("Int")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyInt)) { as =>
          val builder = new ChunkBuilder.Int
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyInt)) { as =>
          val builder = new ChunkBuilder.Int
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Int
        assert(builder.toString)(equalTo("ChunkBuilder.Int"))
      }
    ),
    suite("Long")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyLong)) { as =>
          val builder = new ChunkBuilder.Long
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyLong)) { as =>
          val builder = new ChunkBuilder.Long
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Long
        assert(builder.toString)(equalTo("ChunkBuilder.Long"))
      }
    ),
    suite("Short")(
      test("addOne")(
        check(Gen.chunkOf(Gen.anyShort)) { as =>
          val builder = new ChunkBuilder.Short
          as.foreach(builder += _)
          assert(builder.result())(equalTo(as))
        }
      ),
      test("addAll") {
        check(Gen.chunkOf(Gen.anyShort)) { as =>
          val builder = new ChunkBuilder.Short
          builder ++= as
          assert(builder.result())(equalTo(as))
        }
      },
      test("toString") {
        val builder = new ChunkBuilder.Short
        assert(builder.toString)(equalTo("ChunkBuilder.Short"))
      }
    )
  )
}
