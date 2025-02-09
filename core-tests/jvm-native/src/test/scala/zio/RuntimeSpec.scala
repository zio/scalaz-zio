package zio

import zio.test.{TestAspect, assertCompletes}

import java.util.concurrent.atomic.AtomicBoolean

object RuntimeSpec extends ZIOBaseSpec {
  private implicit val unsafe: Unsafe = Unsafe.unsafe

  private val rt = Runtime.default.unsafe

  def spec = suite("RuntimeSpec")(
    test("unsafe.fork on synchronous effects") {
      ZIO.succeed {
        val (f1, f2) = mkEffects()
        rt.fork(f1)
        rt.fork(f2)
        assertCompletes
      }
    },
    test("unsafe.runToFuture on synchronous effects") {
      ZIO.suspendSucceed {
        val (f1, f2) = mkEffects()
        val fut1     = rt.runToFuture(f1)
        val fut2     = rt.runToFuture(f2)
        ZIO.fromFuture(_ => fut1) *> ZIO.fromFuture(_ => fut2) *> assertCompletes
      }
    }
  ) @@ TestAspect.timeout(5.seconds)

  private def mkEffects() = {
    val loop = new AtomicBoolean(true)

    val f1 = ZIO.succeed {
      while (loop.get()) { Thread.sleep(1) }
    }
    val f2 = ZIO.succeed {
      loop.set(false)
    }

    (f1, f2)
  }

}
