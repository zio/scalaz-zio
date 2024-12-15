package zio.internal.stacktracer

import zio.ZIOBaseSpec
import zio.test.*

object TracerSpec extends ZIOBaseSpec {

  def spec =
    suite("Tracer")(
      suite("#unapply")(
        test("returns location, file and line for correct trace") {
          val trace  = Tracer.instance.apply("location", "file", 1)
          val result = Tracer.instance.unapply(trace)
          assertTrue(result.contains(("location", "file", 1)))
        },
        test("returns None for an empty trace") {
          val trace  = Tracer.instance.empty
          val result = Tracer.instance.unapply(trace)
          assertTrue(result == None)
        },
      ),
    )

}