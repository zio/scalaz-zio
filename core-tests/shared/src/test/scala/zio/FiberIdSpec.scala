package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object FiberIdSpec extends ZIOBaseSpec {

  def spec = suite("FiberIdSpec")(
    suite("stack safety")(
      test("ids") {
        assert(fiberId.ids.size)(equalTo(20000))
      },
      test("isNone") {
        assert(fiberId.isNone)(equalTo(false))
      },
      test("toSet") {
        assert(fiberId.toSet.size)(equalTo(20000))
      }
    ) @@ sequential @@ exceptJS
  )

  val fiberId: FiberId = {
    def runtimeFiber(i: Int): FiberId = FiberId.Runtime(i, i.toLong, Trace.empty)

    val left  = (2 to 10000).foldLeft(runtimeFiber(1))((id, i) => id <> runtimeFiber(i))
    val right = (10002 to 20000).foldLeft(runtimeFiber(10001))((id, i) => id <> runtimeFiber(i))
    left <> right
  }
}
