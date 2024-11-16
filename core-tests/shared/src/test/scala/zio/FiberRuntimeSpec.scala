package zio

import zio.internal.FiberScope
import zio.test._

import java.util.concurrent.atomic.AtomicInteger

object FiberRuntimeSpec extends ZIOBaseSpec {
  private implicit val unsafe: Unsafe = Unsafe.unsafe

  def spec = suite("FiberRuntimeSpec")(
    suite("whileLoop")(
      test("auto-yields every 10280 operations when no other yielding is performed") {
        ZIO.suspendSucceed {
          val nIters       = 50000
          val nOpsPerYield = 1024 * 10
          val nOps         = new AtomicInteger(0)
          val latch        = Promise.unsafe.make[Nothing, Unit](FiberId.None)
          val supervisor   = new YieldTrackingSupervisor(latch, nOps)
          val f            = ZIO.whileLoop(nOps.getAndIncrement() < nIters)(Exit.unit)(_ => ())
          ZIO
            .withFiberRuntime[Any, Nothing, Unit] { (parentFib, status) =>
              val fiber = ZIO.unsafe.makeChildFiber(Trace.empty, f, parentFib, status.runtimeFlags, FiberScope.global)
              fiber.setFiberRef(FiberRef.currentSupervisor, supervisor)
              fiber.startConcurrently(f)
              latch.await
            }
            .as {
              val yieldedAt = supervisor.yieldedAt
              assertTrue(
                yieldedAt == List(
                  nIters + 1,
                  nOpsPerYield * 4 - 3,
                  nOpsPerYield * 3 - 2,
                  nOpsPerYield * 2 - 1,
                  nOpsPerYield
                )
              )
            }
        }
      },
      test("doesn't auto-yield when effect itself yields") {
        ZIO.suspendSucceed {
          val nIters     = 50000
          val nOps       = new AtomicInteger(0)
          val latch      = Promise.unsafe.make[Nothing, Unit](FiberId.None)
          val supervisor = new YieldTrackingSupervisor(latch, nOps)
          val f =
            ZIO.whileLoop(nOps.getAndIncrement() < nIters)(ZIO.when(nOps.get() % 10000 == 0)(ZIO.yieldNow))(_ => ())
          ZIO
            .withFiberRuntime[Any, Nothing, Unit] { (parentFib, status) =>
              val fiber = ZIO.unsafe.makeChildFiber(Trace.empty, f, parentFib, status.runtimeFlags, FiberScope.global)
              fiber.setFiberRef(FiberRef.currentSupervisor, supervisor)
              fiber.startConcurrently(f)
              latch.await
            }
            .as {
              val yieldedAt = supervisor.yieldedAt
              assertTrue(
                yieldedAt == List(nIters + 1, 50000, 40000, 30000, 20000, 10000)
              )
            }
        }
      }
    )
  )

  private final class YieldTrackingSupervisor(
    latch: Promise[Nothing, Unit],
    nOps: AtomicInteger
  ) extends Supervisor[Unit] {
    @volatile var yieldedAt           = List.empty[Int]
    @volatile private var onEndCalled = false

    def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

    def onStart[R, E, A](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A]
    )(implicit unsafe: Unsafe): Unit = ()

    override def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      onEndCalled = true
      ()
    }

    override def onSuspend[E, A](fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      yieldedAt ::= nOps.get()
      if (onEndCalled) latch.unsafe.done(Exit.unit) // onEnd gets called before onSuspend
      ()
    }
  }

}
