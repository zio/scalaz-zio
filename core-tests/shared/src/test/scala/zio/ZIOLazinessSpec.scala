package zio

import zio.test._

object ZIOLazinessSpec extends ZIOBaseSpec {

  def assertLazy(f: (=> Nothing) => Any): UIO[TestResult] =
    ZIO.succeed {
      val _ = f(throw new RuntimeException("not lazy"))
      assertCompletes
    }

  def spec = suite("ZIOLazinessSpec")(
    test("die")(assertLazy(ZIO.die)),
    test("dieMessage")(assertLazy(ZIO.dieMessage)),
    test("fail")(assertLazy(ZIO.fail)),
    test("failCause")(assertLazy(ZIO.failCause)),
    test("fromEither")(assertLazy(ZIO.fromEither)),
    test("fromFiber")(assertLazy(ZIO.fromFiber)),
    test("fromOption")(assertLazy(ZIO.fromOption)),
    test("fromTry")(assertLazy(ZIO.fromTry)),
    test("getOrFailUnit")(assertLazy(ZIO.getOrFailUnit)),
    test("interruptAs")(assertLazy(ZIO.interruptAs)),
    test("left")(assertLazy(ZIO.left)),
    test("onExecutor")(assertLazy(ZIO.onExecutor)),
    test("provideEnvironment")(assertLazy(ZIO.provideEnvironment)),
    test("right")(assertLazy(ZIO.right)),
    test("sleep")(assertLazy(ZIO.sleep)),
    test("some")(assertLazy(ZIO.some)),
    test("succeed")(assertLazy(ZIO.succeed))
  )
}
