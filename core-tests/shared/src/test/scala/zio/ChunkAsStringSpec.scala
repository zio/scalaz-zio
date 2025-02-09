package zio

import zio.test.Assertion._
import zio.test._

import java.nio.charset.{Charset, StandardCharsets}

object ChunkAsStringSpec extends ZIOBaseSpec {

  def spec = suite("ChunkAsStringSpec")(
    test("bytes asString with charset") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromArray(str.getBytes(StandardCharsets.UTF_8))
        assert(chunk.asString(StandardCharsets.UTF_8))(equalTo(str))
      }
    },
    test("bytes asString without charset") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromArray(str.getBytes(Charset.defaultCharset()))
        assert(chunk.asString)(equalTo(str))
      }
    },
    test("chars asString") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromArray(str.toCharArray)
        assert(chunk.asString)(equalTo(str))
      }
    },
    test("strings asString") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromIterable(List.fill(5)(str))
        assert(chunk.asString)(equalTo(str * 5))
      }
    },
    test("bytes asBase64String") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromArray(str.getBytes(StandardCharsets.UTF_8))
        assert(chunk.asBase64String)(
          equalTo(java.util.Base64.getEncoder.encodeToString(str.getBytes(StandardCharsets.UTF_8)))
        )
      }
    },
    test("chars asBase64String") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromArray(str.toCharArray)
        assert(chunk.asBase64String)(
          equalTo(java.util.Base64.getEncoder.encodeToString(str.getBytes(StandardCharsets.UTF_8)))
        )
      }
    },
    test("strings asBase64String") {
      check(Gen.alphaNumericString) { str =>
        val chunk = Chunk.fromIterable(List.fill(5)(str))
        assert(chunk.asBase64String)(
          equalTo(java.util.Base64.getEncoder.encodeToString((str * 5).getBytes(StandardCharsets.UTF_8)))
        )
      }
    }
  )

}
