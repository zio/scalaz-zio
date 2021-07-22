package zio

import zio.LatchOps._
import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

object FiberSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec: ZSpec[Environment, Failure] =
    suite("FiberSpec")(
      suite("Create a new Fiber and")(test("lift it into Managed") {
        for {
          ref   <- Ref.make(false)
          fiber <- withLatch(release => (release *> IO.unit).acquireRelease(ref.set(true))(IO.never).fork)
          _     <- fiber.toManaged.use(_ => IO.unit)
          _     <- fiber.await
          value <- ref.get
        } yield assert(value)(isTrue)
      }),
      suite("`inheritLocals` works for Fiber created using:")(
        test("`map`") {
          for {
            fiberRef <- FiberRef.make(initial)
            child    <- withLatch(release => (fiberRef.set(update) *> release).fork)
            _        <- child.map(_ => ()).inheritRefs
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
            _        <- child1.orElse(child2).inheritRefs
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
            _        <- child1.zip(child2).inheritRefs
            value    <- fiberRef.get
          } yield assert(value)(equalTo("child1"))
        }
      ),
      suite("`Fiber.join` on interrupted Fiber")(
        test("is inner interruption") {
          val fiberId = Fiber.Id(0L, 123L)

          for {
            exit <- Fiber.interruptAs(fiberId).join.exit
          } yield assert(exit)(equalTo(Exit.interrupt(fiberId)))
        }
      ) @@ zioTag(interruption),
      suite("if one composed fiber fails then all must fail")(
        test("`await`") {
          for {
            exit <- Fiber.fail("fail").zip(Fiber.never).await
          } yield assert(exit)(fails(equalTo("fail")))
        },
        test("`join`") {
          for {
            exit <- Fiber.fail("fail").zip(Fiber.never).join.exit
          } yield assert(exit)(fails(equalTo("fail")))
        },
        test("`awaitAll`") {
          for {
            exit <- Fiber.awaitAll(Fiber.fail("fail") :: List.fill(100)(Fiber.never)).exit
          } yield assert(exit)(succeeds(isUnit))
        },
        test("`joinAll`") {
          for {
            exit <- Fiber.awaitAll(Fiber.fail("fail") :: List.fill(100)(Fiber.never)).exit
          } yield assert(exit)(succeeds(isUnit))
        },
        test("shard example") {
          def shard[R, E, A](queue: Queue[A], n: Int, worker: A => ZIO[R, E, Unit]): ZIO[R, E, Nothing] = {
            val worker1: ZIO[R, E, Unit] = queue.take.flatMap(a => worker(a).uninterruptible).forever
            ZIO.forkAll(List.fill(n)(worker1)).flatMap(_.join) *> ZIO.never
          }
          for {
            queue <- Queue.unbounded[Int]
            _     <- queue.offerAll(1 to 100)
            worker = (n: Int) => if (n == 100) ZIO.fail("fail") else queue.offer(n).unit
            exit  <- shard(queue, 4, worker).exit
            _     <- queue.shutdown
          } yield assert(exit)(fails(equalTo("fail")))
        }
      ) @@ zioTag(errors),
      test("grandparent interruption is propagated to grandchild despite parent termination") {
        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          c       = ZIO.never.interruptible.onInterrupt(latch2.succeed(()))
          a       = (latch1.succeed(()) *> c.fork.fork).uninterruptible *> ZIO.never
          fiber  <- a.fork
          _      <- latch1.await
          _      <- fiber.interrupt
          _      <- latch2.await
        } yield assertCompletes
      } @@ zioTag(interruption) @@ nonFlaky,
      suite("stack safety")(
        test("awaitAll") {
          assertM(Fiber.awaitAll(fibers))(anything)
        },
        test("joinAll") {
          assertM(Fiber.joinAll(fibers))(anything)
        },
        test("collectAll") {
          assertM(Fiber.collectAll(fibers).join)(anything)
        }
      ) @@ sequential,
      suite("track blockingOn")(
        test("in await") {
          for {
            f1 <- ZIO.never.fork
            f2 <- f1.await.fork
            blockingOn <- f2.status
                            .collect(()) { case Fiber.Status.Suspended(_, _, _, blockingOn, _) =>
                              blockingOn
                            }
                            .eventually
          } yield assert(blockingOn)(equalTo(List(f1.id)))
        },
        test("in race") {
          for {
            f <- ZIO.never.race(ZIO.never).fork
            blockingOn <- f.status
                            .collect(()) { case Fiber.Status.Suspended(_, _, _, blockingOn, _) =>
                              blockingOn
                            }
                            .eventually
          } yield assert(blockingOn)(hasSize(equalTo(2)))
        }
      )
    )

  val (initial, update)                            = ("initial", "update")
  val fibers: List[Fiber.Synthetic[Nothing, Unit]] = List.fill(100000)(Fiber.unit)
}
