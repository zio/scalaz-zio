package zio

import zio.LatchOps._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object FiberSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec =
    suite("FiberSpec")(
      suite("Create a new Fiber and")(test("scope it") {
        for {
          ref <- Ref.make(false)
          fiber <-
            withLatch(release => ZIO.acquireReleaseWith(release *> ZIO.unit)(_ => ref.set(true))(_ => ZIO.never).fork)
          _     <- ZIO.scoped(fiber.scoped)
          _     <- fiber.await
          value <- ref.get
        } yield assert(value)(isTrue)
      }),
      suite("`inheritLocals` works for Fiber created using:")(
        test("`map`") {
          for {
            fiberRef <- FiberRef.make(initial)
            child    <- withLatch(release => (fiberRef.set(update) *> release).fork)
            _        <- child.map(_ => ()).inheritAll
            value    <- fiberRef.get
          } yield assert(value)(equalTo(update))
        },
        test("`orElse`") {
          implicit val canFail = CanFail
          for {
            fiberRef <- FiberRef.make(initial)
            latch1   <- Promise.make[Nothing, Unit]
            latch2   <- Promise.make[Nothing, Unit]
            child1   <- (fiberRef.set("child1") *> latch1.succeed(())).fork
            child2   <- (fiberRef.set("child2") *> latch2.succeed(())).fork
            _        <- latch1.await *> latch2.await
            _        <- child1.orElse(child2).inheritAll
            value    <- fiberRef.get
          } yield assert(value)(equalTo("child1"))
        },
        test("`zip`") {
          for {
            fiberRef <- FiberRef.make(initial)
            latch1   <- Promise.make[Nothing, Unit]
            latch2   <- Promise.make[Nothing, Unit]
            child1   <- (fiberRef.set("child1") *> latch1.succeed(())).fork
            child2   <- (fiberRef.set("child2") *> latch2.succeed(())).fork
            _        <- latch1.await *> latch2.await
            _        <- child1.zip(child2).inheritAll
            value    <- fiberRef.get
          } yield assert(value)(equalTo("child1"))
        }
      ),
      suite("`Fiber.join` on interrupted Fiber")(
        test("is inner interruption") {
          val fiberId = FiberId.Runtime(0, 123, Trace.empty)

          for {
            exit <- Fiber.interruptAs(fiberId).join.exit
          } yield assert(exit)(equalTo(Exit.interrupt(fiberId)))
        }
      ) @@ zioTag(interruption),
      test("shard example") {
        def shard[R, E, A](queue: Queue[A], n: Int, worker: A => ZIO[R, E, Unit]): ZIO[R, E, Nothing] = {
          val worker1: ZIO[R, E, Unit] = queue.take.flatMap(a => worker(a).uninterruptible).forever
          ZIO.forkAll(List.fill(n)(worker1)).flatMap(_.join) *> ZIO.never
        }
        for {
          queue <- Queue.unbounded[Int]
          _     <- queue.offerAll(1 to 100)
          worker = (n: Int) => if (n == 100) queue.shutdown *> ZIO.fail("fail") else queue.offer(n).unit
          exit  <- shard(queue, 4, worker).exit
          _     <- queue.shutdown
        } yield assert(exit)(fails(equalTo("fail")))
      } @@ exceptJS(nonFlaky),
      test("child becoming interruptible is interrupted due to auto-supervision of uninterruptible parent") {
        for {
          latch <- Promise.make[Nothing, Unit]
          child  = ZIO.never.interruptible.onInterrupt(latch.succeed(())).fork
          _     <- child.fork.uninterruptible
          _     <- latch.await
        } yield assertCompletes
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      suite("roots")(
        test("dual roots") {
          def rootContains(f: Fiber.Runtime[_, _]): UIO[Boolean] =
            Fiber.roots.map(_.contains(f))

          for {
            fiber1 <- ZIO.never.forkDaemon
            fiber2 <- ZIO.never.forkDaemon
            _      <- (rootContains(fiber1) && rootContains(fiber2)).repeatUntil(_ == true)
            _      <- fiber1.interrupt *> fiber2.interrupt
          } yield assertCompletes
        }
      ),
      suite("stack safety")(
        test("awaitAll") {
          assertZIO(Fiber.awaitAll(fibers))(anything)
        },
        test("joinAll") {
          assertZIO(Fiber.joinAll(fibers))(anything)
        },
        test("collectAll") {
          assertZIO(Fiber.collectAll(fibers).join)(anything)
        }
      ) @@ sequential,
      suite("track blockingOn")(
        test("in await") {
          for {
            f1 <- ZIO.never.fork
            f2 <- f1.await.fork
            blockingOn <- f2.status
                            .collect(()) { case Fiber.Status.Suspended(_, _, blockingOn) =>
                              blockingOn
                            }
                            .eventually
          } yield assertTrue(blockingOn == f1.id)
        },
        test("in race") {
          for {
            f <- ZIO.infinity.race(ZIO.infinity).fork
            blockingOn <- f.status
                            .collect(()) { case Fiber.Status.Suspended(_, _, blockingOn) =>
                              blockingOn
                            }
                            .eventually
          } yield assertTrue(blockingOn.toSet.size == 2)
        }
      ),
      test("interruptAll interrupts fibers in parallel") {
        for {
          promise1 <- Promise.make[Nothing, Unit]
          promise2 <- Promise.make[Nothing, Unit]
          fiber1   <- (promise1.succeed(()) *> ZIO.never).forkDaemon
          fiber2   <- (ZIO.uninterruptible(promise2.succeed(()) *> fiber1.await)).forkDaemon
          _        <- promise1.await
          _        <- promise2.await
          _        <- Fiber.interruptAll(List(fiber2, fiber1))
          _        <- fiber2.await
        } yield assertCompletes
      } @@ exceptJS(nonFlaky),
      test("await does not return until all fibers have completed execution") {
        for {
          ref   <- Ref.make(0)
          fiber <- ZIO.forkAll(List.fill(100)(ref.set(10)))
          _     <- fiber.interrupt
          _     <- ref.set(-1)
          value <- ref.get
        } yield assertTrue(value == -1)
      },
      test("current fiber") {
        Unsafe.unsafe { implicit unsafe =>
          for {
            runtime      <- ZIO.runtime[Any]
            _            <- ZIO.succeed(runtime.unsafe.run(ZIO.unit))
            currentFiber <- ZIO.succeed(Fiber.currentFiber())
          } yield assertTrue(currentFiber.isDefined)
        }
      } @@ TestAspect.fromLayer(Runtime.enableCurrentFiber)
    )

  val (initial, update)                            = ("initial", "update")
  val fibers: List[Fiber.Synthetic[Nothing, Unit]] = List.fill(100000)(Fiber.unit)
}
