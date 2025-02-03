package zio

import zio.Clock.ClockLive
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
    ),
    suite("supervisor")(
      suite("onStart and onEnd are called exactly once")(
        test("sync effect") {
          for {
            s <- ZIO.succeed(new StartEndTrackingSupervisor)
            f <- ZIO.unit.fork.supervised(s)
            _ <- f.await
            // onEnd might be called after the forked fiber notifies the current fiber
            _ <- ZIO.succeed(s.onEndCalls).repeatUntil(_ > 0)
          } yield assertTrue(s.onStartCalls == 1, s.onEndCalls == 1)
        },
        test("async effect") {
          for {
            s <- ZIO.succeed(new StartEndTrackingSupervisor)
            f <- ClockLive.sleep(100.micros).fork.supervised(s)
            _ <- f.await
            // onEnd might be called after the forked fiber notifies the current fiber
            _ <- ZIO.succeed(s.onEndCalls).repeatUntil(_ > 0)
          } yield assertTrue(s.onStartCalls == 1, s.onEndCalls == 1)
        }
      ) @@ TestAspect.nonFlaky(100)
    )
  ) @@ TestAspect.timeout(10.seconds)

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

  private final class StartEndTrackingSupervisor extends Supervisor[Unit] {
    private val _onStartCalls, _onEndCalls = new AtomicInteger(0)

    def value(implicit trace: Trace): UIO[Unit] = ZIO.unit

    def onStart[R, E, A](
      environment: ZEnvironment[R],
      effect: ZIO[R, E, A],
      parent: Option[Fiber.Runtime[Any, Any]],
      fiber: Fiber.Runtime[E, A]
    )(implicit unsafe: Unsafe): Unit = {
      _onStartCalls.incrementAndGet()
      ()
    }

    def onEnd[R, E, A](value: Exit[E, A], fiber: Fiber.Runtime[E, A])(implicit unsafe: Unsafe): Unit = {
      _onEndCalls.incrementAndGet
      ()
    }

    def onStartCalls = _onStartCalls.get
    def onEndCalls   = _onEndCalls.get
  }

}
