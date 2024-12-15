package zio.internal.stacktracer

import zio.ZIOBaseSpec
import zio.test.*

object TracerSpec extends ZIOBaseSpec {

  def spec = suite("Tracer")(
    test("Tracer#unapply") {
      val trace = Tracer.instance.apply("location", "file", 1)
      val result = Tracer.instance.unapply(trace)
      assertTrue(result.contains(("location", "file", 1)))
    }
  )

}