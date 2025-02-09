package zio

import zio.FiberRefSpecUtil._
import zio.test.Assertion._
import zio.test._

import java.util.concurrent.atomic.AtomicReference

object FiberRefSpecJvm extends ZIOBaseSpec {

  def spec = suite("FiberRefSpecJvm")(
    test("unsafe handles behave properly if fiber specific data cannot be accessed") {
      for {
        fiberRef <- FiberRef.make(initial)
        handle   <- Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
        resRef   <- ZIO.succeed(new AtomicReference(("", "", "")))

        unsafelyGetSetGet = new Runnable {
                              def run(): Unit = {
                                val v1 = handle.get()
                                handle.set(update2)
                                val v2 = handle.get()
                                handle.remove()
                                val v3 = handle.get()
                                resRef.set((v1, v2, v3))
                              }
                            }

        _      <- fiberRef.set(update1)
        thread <- ZIO.succeed(new Thread(unsafelyGetSetGet))
        _      <- ZIO.succeed(thread.start()).ensuring(ZIO.succeed(thread.join()))

        value0                  <- fiberRef.get
        values                  <- ZIO.succeed(resRef.get())
        (value1, value2, value3) = values
      } yield assert((value0, value1, value2, value3))(equalTo((update1, initial, update2, initial)))
    }
  )
}
