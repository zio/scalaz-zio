package zio.internal.stacktracer

import zio.ZIOBaseSpec
import zio.test._

object TracerUtilsSpec extends ZIOBaseSpec {

  def spec =
    suite("TracerUtils")(
      suite("#parse")(
        test("returns location, file and line for correct trace") {
          check(Gen.int(1, Int.MaxValue)) { n =>
            assertTrue(TracerUtils.parse(s"location(file:$n)") == ParsedTrace("location", "file", n))
          }
        },
        test("returns null for an empty or invalid trace") {
          assertTrue(
            TracerUtils.parse("") == null,
            TracerUtils.parse("1") == null,
            TracerUtils.parse("1)") == null,
            TracerUtils.parse(":1)") == null,
            TracerUtils.parse(": )") == null,
            TracerUtils.parse(":a)") == null,
            TracerUtils.parse("location(file:0)") == null
          )
        },
        test("returns null for too big line number") {
          check(Gen.long(Int.MaxValue.toLong + 1, Long.MaxValue)) { n =>
            assertTrue(TracerUtils.parse(s"location(file:$n)") == null)
          }
        }
      )
    )

}
