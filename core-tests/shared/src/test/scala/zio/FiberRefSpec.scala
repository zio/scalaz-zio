package zio

import zio.FiberRefSpecUtil._
import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._

object FiberRefSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("FiberRefSpec")(
    suite("Classic FiberRef.make")(
      test("fork can modify ref value") {
        for {
          ref     <- FiberRef.make[Int](0, _ + 1, (parent, _) => parent)
          initial <- ref.get
          fiber   <- ref.get.fork
          child   <- fiber.join
        } yield assertTrue(initial == 0 && child == 1)
      },
      test("parent can be retained") {
        for {
          ref            <- FiberRef.make[Int](0, _ + 1, (parent, _) => parent)
          initial1       <- ref.get
          fiber          <- (ref.update(_ + 42) *> ref.get).fork
          initial2       <- ref.get
          tuple          <- fiber.join <*> ref.get
          (child, joined) = tuple
        } yield assertTrue(initial1 == 0 && initial2 == 0 && child == 43 && joined == 0)
      }
    ),
    suite("Create a new FiberRef with a specified value and check if:")(
      test("`delete` restores the original value") {
        for {
          fiberRef <- FiberRef.make(initial)
          _        <- fiberRef.set(update)
          _        <- fiberRef.delete
          v        <- fiberRef.get
        } yield assert(v)(equalTo(initial))
      },
      test("`get` returns the current value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      },
      test("`get` returns the current value for a child") {
        for {
          fiberRef <- FiberRef.make(initial)
          child    <- fiberRef.get.fork
          value    <- child.join
        } yield assert(value)(equalTo(initial))
      },
      test("`getAndUpdate` changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1   <- fiberRef.getAndUpdate(_ => update)
          value2   <- fiberRef.get
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(update))
      },
      test("`getAndUpdateSome` changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1 <- fiberRef.getAndUpdateSome { case _ =>
                      update
                    }
          value2 <- fiberRef.get
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(update))
      },
      test("`getAndUpdateSome` not changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1 <- fiberRef.getAndUpdateSome {
                      case _ if false => update
                    }
          value2 <- fiberRef.get
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(initial))
      },
      test("`locally` restores original value") {
        for {
          fiberRef <- FiberRef.make(initial)
          local    <- fiberRef.locally(update)(fiberRef.get)
          value    <- fiberRef.get
        } yield assert(local)(equalTo(update)) && assert(value)(equalTo(initial))
      },
      test("`locally` restores parent's value") {
        for {
          fiberRef <- FiberRef.make(initial)
          child    <- fiberRef.locally(update)(fiberRef.get).fork
          local    <- child.join
          value    <- fiberRef.get
        } yield assert(local)(equalTo(update)) && assert(value)(equalTo(initial))
      },
      test("`locally` restores undefined value") {
        for {
          child <- FiberRef.make(initial).fork
          // Don't use join as it inherits values from child.
          fiberRef   <- child.await.unexit
          localValue <- fiberRef.locally(update)(fiberRef.get)
          value      <- fiberRef.get
        } yield assert(localValue)(equalTo(update)) && assert(value)(equalTo(initial))
      },
      test("`modify` changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1   <- fiberRef.modify(_ => (1, update))
          value2   <- fiberRef.get
        } yield assert(value1)(equalTo(1)) && assert(value2)(equalTo(update))
      },
      test("`modifySome` not changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1 <- fiberRef.modifySome(2) {
                      case _ if false => (1, update)
                    }
          value2 <- fiberRef.get
        } yield assert(value1)(equalTo(2)) && assert(value2)(equalTo(initial))
      },
      test("`set` updates the current value") {
        for {
          fiberRef <- FiberRef.make(initial)
          _        <- fiberRef.set(update)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update))
      },
      test("`set` by a child doesn't update parent's value") {
        for {
          fiberRef <- FiberRef.make(initial)
          promise  <- Promise.make[Nothing, Unit]
          _        <- (fiberRef.set(update) *> promise.succeed(())).fork
          _        <- promise.await
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      },
      test("`updateAndGet` changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1   <- fiberRef.updateAndGet(_ => update)
          value2   <- fiberRef.get
        } yield assert(value1)(equalTo(update)) && assert(value2)(equalTo(update))
      },
      test("`updateSomeAndGet` changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1 <- fiberRef.updateSomeAndGet { case _ =>
                      update
                    }
          value2 <- fiberRef.get
        } yield assert(value1)(equalTo(update)) && assert(value2)(equalTo(update))
      },
      test("`updateSomeAndGet` not changes value") {
        for {
          fiberRef <- FiberRef.make(initial)
          value1 <- fiberRef.updateSomeAndGet {
                      case _ if false => update
                    }
          value2 <- fiberRef.get
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(initial))
      },
      test("its value is inherited on join") {
        for {
          fiberRef <- FiberRef.make(initial)
          child    <- fiberRef.set(update).fork
          _        <- child.join
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update))
      },
      test("initial value is always available") {
        for {
          child    <- FiberRef.make(initial).fork
          fiberRef <- child.await.unexit
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      },
      test("its value is inherited after simple race") {
        for {
          fiberRef <- FiberRef.make(initial)
          _        <- fiberRef.set(update1).race(fiberRef.set(update2))
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update1)) || assert(value)(equalTo(update2))
      },
      test("its value is inherited after a race with a bad winner") {
        for {
          fiberRef <- FiberRef.make(initial)
          latch    <- Promise.make[Nothing, Unit]
          badWinner = fiberRef.set(update1) *> ZIO.fail("ups").ensuring(latch.succeed(()))
          goodLoser = fiberRef.set(update2) *> latch.await *> Live.live(ZIO.sleep(1.second))
          _        <- badWinner.race(goodLoser)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update2))
      } @@ TestAspect.flaky,
      test("its value is not inherited after a race of losers") {
        for {
          fiberRef <- FiberRef.make(initial)
          loser1    = fiberRef.set(update1) *> ZIO.fail("ups1")
          loser2    = fiberRef.set(update2) *> ZIO.fail("ups2")
          _        <- loser1.race(loser2).ignore
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      } @@ zioTag(errors),
      test("the value of the loser is inherited in zipPar") {
        for {
          fiberRef <- FiberRef.make(initial)
          latch    <- Promise.make[Nothing, Unit]
          winner    = fiberRef.set(update1) *> latch.succeed(())
          loser     = latch.await *> Live.live(ZIO.sleep(1.second)) *> fiberRef.set(update2)
          _        <- winner.zipPar(loser)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update2))
      } @@ TestAspect.flaky @@ zioTag(errors),
      test("nothing gets inherited with a failure in zipPar") {
        for {
          fiberRef <- FiberRef.make(initial)
          success   = fiberRef.set(update)
          failure1  = fiberRef.set(update1) *> ZIO.fail(":-(")
          failure2  = fiberRef.set(update2) *> ZIO.fail(":-O")
          _        <- success.zipPar(failure1.zipPar(failure2)).orElse(ZIO.unit)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      } @@ zioTag(errors),
      test("fork function is applied on fork - 1") {
        def increment(x: Int): Int = x + 1
        for {
          fiberRef <- FiberRef.make(initial = 0, fork = increment)
          child    <- ZIO.unit.fork
          _        <- child.join
          value    <- fiberRef.get
        } yield assert(value)(equalTo(1))
      },
      test("fork function is applied on fork - 2") {
        def increment(x: Int): Int = x + 1
        for {
          fiberRef <- FiberRef.make(initial = 0, fork = increment)
          child    <- ZIO.unit.fork.flatMap(_.join).fork
          _        <- child.join
          value    <- fiberRef.get
        } yield assert(value)(equalTo(2))
      },
      test("join function is applied on join - 1") {
        for {
          fiberRef <- FiberRef.make(initial = 0, join = math.max)
          child    <- fiberRef.update(_ + 1).fork
          _        <- child.join
          value    <- fiberRef.get
        } yield assert(value)(equalTo(1))
      },
      test("join function is applied on join - 2") {
        for {
          fiberRef <- FiberRef.make(initial = 0, join = math.max)
          child    <- fiberRef.update(_ + 1).fork
          _        <- fiberRef.update(_ + 2)
          _        <- child.join
          value    <- fiberRef.get
        } yield assert(value)(equalTo(2))
      },
      test("its value is inherited in a trivial race") {
        for {
          fiberRef <- FiberRef.make(initial)
          _        <- fiberRef.set(update).raceAll(Iterable.empty)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(update))
      },
      test("the value of the winner is inherited when racing two ZIOs with raceAll") {
        for {
          fiberRef <- FiberRef.make(initial)

          latch  <- Promise.make[Nothing, Unit]
          winner1 = fiberRef.set(update1) *> latch.succeed(())
          loser1  = latch.await *> fiberRef.set(update2) *> ZIO.never
          _      <- loser1.raceAll(List(winner1))
          value1 <- fiberRef.get <* fiberRef.set(initial)

          winner2 = fiberRef.set(update1)
          loser2  = fiberRef.set(update2) *> ZIO.fail(":-O")
          _      <- loser2.raceAll(List(winner2))
          value2 <- fiberRef.get <* fiberRef.set(initial)
        } yield assert((value1, value2))(equalTo((update1, update1)))
      },
      test("the value of the winner is inherited when racing many ZIOs with raceAll") {
        for {
          fiberRef <- FiberRef.make(initial)
          n         = 63

          latch  <- Promise.make[Nothing, Unit]
          winner1 = fiberRef.set(update1) *> latch.succeed(())
          loser1  = latch.await *> fiberRef.set(update2) *> ZIO.never
          losers1 = Iterable.fill(n)(loser1)
          _      <- winner1.raceAll(losers1)
          value1 <- fiberRef.get <* fiberRef.set(initial)

          winner2 = fiberRef.set(update1)
          loser2  = fiberRef.set(update2) *> ZIO.fail("Nooooo")
          losers2 = Iterable.fill(n)(loser2)
          _      <- winner2.raceAll(losers2)
          value2 <- fiberRef.get <* fiberRef.set(initial)
        } yield assert((value1, value2))(equalTo((update1, update1)))
      },
      test("nothing gets inherited when racing failures with raceAll") {
        for {
          fiberRef <- FiberRef.make(initial)
          loser     = fiberRef.set(update) *> ZIO.fail("darn")
          _        <- loser.raceAll(Iterable.fill(63)(loser)).orElse(ZIO.unit)
          value    <- fiberRef.get
        } yield assert(value)(equalTo(initial))
      } @@ zioTag(errors),
      test("the value of all fibers in inherited when running many ZIOs with collectAllPar") {
        for {
          fiberRef <- FiberRef.make[Int](0, _ => 0, _ + _)
          _        <- ZIO.collectAllPar(List.fill(100000)(fiberRef.update(_ + 1)))
          value    <- fiberRef.get
        } yield assert(value)(equalTo(100000))
      } @@ TestAspect.exceptNative,
      test("an unsafe handle is initialized and updated properly") {
        for {
          fiberRef <- FiberRef.make(initial)
          handle   <- Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
          value1   <- ZIO.succeed(handle.get())
          _        <- fiberRef.set(update1)
          value2   <- ZIO.succeed(handle.get())
          _        <- ZIO.succeed(handle.set(update2))
          value3   <- fiberRef.get
        } yield assertTrue(value1 == initial && value2 == update1 && value3 == update2)
      },
      test("unsafe handles work properly when initialized in a race") {
        for {
          fiberRef  <- FiberRef.make(initial)
          initHandle = Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
          handle    <- ZIO.raceAll(initHandle, Iterable.fill(64)(initHandle))
          value1    <- ZIO.succeed(handle.get())
          doUpdate   = fiberRef.set(update)
          _         <- ZIO.raceAll(doUpdate, Iterable.fill(64)(doUpdate))
          value2    <- ZIO.succeed(handle.get())
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(update))
      },
      test("unsafe handles work properly when accessed concurrently") {
        for {
          fiberRef <- FiberRef.make(0)
          setAndGet =
            (value: Int) =>
              setRefOrHandle(fiberRef, value) *> Unsafe.unsafe { implicit unsafe =>
                fiberRef.asThreadLocal.flatMap(h => ZIO.succeed(h.get()))
              }
          n       = 64
          fiber  <- ZIO.forkAll(1.to(n).map(setAndGet))
          values <- fiber.join
        } yield assert(values)(equalTo(1.to(n)))
      },
      test("unsafe handles don't see updates from other fibers") {
        for {
          fiberRef <- FiberRef.make(initial)
          handle   <- Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
          value1   <- ZIO.succeed(handle.get())
          n         = 64
          fiber    <- ZIO.forkAll(Iterable.fill(n)(fiberRef.set(update).race(ZIO.succeed(handle.set(update)))))
          _        <- fiber.await
          value2   <- ZIO.succeed(handle.get())
        } yield assert(value1)(equalTo(initial)) && assert(value2)(equalTo(initial))
      },
      test("unsafe handles keep their values if there are async boundaries") {
        for {
          fiberRef <- FiberRef.make(0)

          test = (i: Int) =>
                   for {
                     handle <- Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
                     _      <- setRefOrHandle(fiberRef, handle, i)
                     _      <- ZIO.yieldNow
                     value  <- ZIO.succeed(handle.get())
                   } yield assert(value)(equalTo(i))

          n        = 64
          results <- ZIO.reduceAllPar(test(1), 2.to(n).map(test))(_ && _)
        } yield results
      },
      test("calling remove on unsafe handles restores their initial values") {
        for {
          fiberRef <- FiberRef.make(initial)
          _        <- fiberRef.set(update)
          handle   <- Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal)
          _        <- ZIO.succeed(handle.remove())
          value1   <- fiberRef.get
          value2   <- ZIO.succeed(handle.get())
        } yield assert((value1, value2))(equalTo((initial, initial)))
      }
    ),
    test("makeEnvironment") {
      for {
        testClock   <- testClock
        testConsole <- testConsole
        testRandom  <- testRandom
        testSystem  <- testSystem
        fiberRef    <- FiberRef.makeEnvironment(DefaultServices.live)
        _           <- fiberRef.update(_.add(testClock))
        left        <- fiberRef.update(_.add(testConsole)).fork
        right       <- fiberRef.update(_.add(testRandom)).fork
        _           <- left.join
        _           <- right.join
        _           <- fiberRef.update(_.add(testSystem))
        environment <- fiberRef.get
      } yield assertTrue(environment.get[Clock] == testClock) &&
        assertTrue(environment.get[Console] == testConsole) &&
        assertTrue(environment.get[Random] == testRandom) &&
        assertTrue(environment.get[System] == testSystem)
    } @@ exceptJS(nonFlaky),
    test("zipPar") {
      for {
        _ <- ZIO.unit.timeout(1.second) <& TestClock.adjust(1.second)
        _ <- testClock
      } yield assertCompletes
    } @@ exceptJS(nonFlaky),
    test("runtime") {
      for {
        expected <- ZIO.clock
        runtime  <- ZIO.runtime[Any]
        actual   <- ZIO.succeedBlockingUnsafe(implicit unsafe => runtime.unsafe.run(ZIO.clock).getOrThrowFiberFailure())
      } yield assertTrue(actual == expected)
    },
    test("fork patch is applied when a fiber is unsafely run") {
      for {
        fiberRef <- FiberRef.make[Boolean](true, _ => true)
        runtime  <- fiberRef.locally(false)(ZIO.runtime[Any])
        promise  <- Promise.make[Nothing, Boolean]
        _ <- ZIO.succeed {
               Unsafe.unsafe { implicit unsafe =>
                 runtime.unsafe.run(fiberRef.get.intoPromise(promise))
               }
             }
        value <- promise.await
      } yield assertTrue(value)
    },
    test("fork patch is applied when a fiber is unsafely forked") {
      for {
        fiberRef <- FiberRef.make[Boolean](true, _ => true)
        runtime  <- fiberRef.locally(false)(ZIO.runtime[Any])
        promise  <- Promise.make[Nothing, Boolean]
        _ <- ZIO.succeed {
               Unsafe.unsafe { implicit unsafe =>
                 runtime.unsafe.fork(fiberRef.get.intoPromise(promise))
               }
             }
        value <- promise.await
      } yield assertTrue(value)
    },
    test("child fiber inherits subsequent changes made by parent when joining sibling") {
      for {
        fiberRef <- FiberRef.make(false)
        promise  <- Promise.make[Nothing, Fiber.Runtime[Any, Any]]
        child1   <- (promise.await.flatMap(_.join) *> fiberRef.get).fork
        _        <- fiberRef.set(true)
        child2   <- ZIO.unit.fork
        _        <- promise.succeed(child2)
        value    <- child1.join
      } yield assertTrue(value)
    },
    suite("hasIdentityFork & hasSecondFnJoin")(
      test("ZIO-provided refs") {
        val shouldBeTrue = List(
          FiberRef.currentLogLevel,
          FiberRef.currentLogSpan,
          FiberRef.currentLogAnnotations,
          FiberRef.currentTags,
          FiberRef.overrideExecutor,
          FiberRef.currentEnvironment,
          FiberRef.currentBlockingExecutor,
          FiberRef.currentFatal,
          FiberRef.currentFiberIdGenerator,
          FiberRef.currentLoggers,
          FiberRef.currentReportFatal,
          FiberRef.currentRuntimeFlags,
          FiberRef.currentSupervisor,
          FiberRef.unhandledErrorLogLevel
        )
        val shouldBeFalse = List(
          FiberRef.forkScopeOverride,
          FiberRef.interruptedCause
        )
        assertTrue(
          shouldBeTrue.forall(_.hasIdentityFork),
          shouldBeTrue.forall(_.hasSecondFnJoin),
          !shouldBeFalse.exists(_.hasIdentityFork),
          !shouldBeFalse.exists(_.hasSecondFnJoin)
        )
      },
      test("user-defined ref") {
        implicit val unsafe: Unsafe = Unsafe

        val ref1 = FiberRef.unsafe.make[String]("foo")
        val ref2 = FiberRef.unsafe.make[String]("foo", fork = _ => "foo")
        val ref3 = FiberRef.unsafe.make[String]("foo", join = (l, r) => l + r)
        val ref4 = FiberRef.unsafe.make[String]("foo", fork = _ => "foo", join = (l, r) => l + r)

        assertTrue(
          ref1.hasIdentityFork,
          ref1.hasSecondFnJoin,
          !ref2.hasIdentityFork,
          ref2.hasSecondFnJoin,
          ref3.hasIdentityFork,
          !ref3.hasSecondFnJoin,
          !ref4.hasIdentityFork,
          !ref4.hasSecondFnJoin
        )
      }
    )
  ) @@ TestAspect.fromLayer(Runtime.enableCurrentFiber) @@ TestAspect.sequential
}

object FiberRefSpecUtil {
  val (initial, update, update1, update2) = ("initial", "update", "update1", "update2")

  def setRefOrHandle(fiberRef: FiberRef[Int], value: Int): UIO[Unit] =
    if (value % 2 == 0) fiberRef.set(value)
    else Unsafe.unsafe(implicit unsafe => fiberRef.asThreadLocal.flatMap(h => ZIO.succeed(h.set(value))))

  def setRefOrHandle(fiberRef: FiberRef[Int], handle: ThreadLocal[Int], value: Int): UIO[Unit] =
    if (value % 2 == 0) fiberRef.set(value)
    else ZIO.succeed(handle.set(value))
}
