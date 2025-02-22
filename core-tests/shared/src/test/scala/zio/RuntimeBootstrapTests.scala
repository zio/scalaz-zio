package zio

object RuntimeBootstrapTests {
  import LatchOps._

  val singleThreadedExecutor =
    zio.Executor.fromJavaExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())

  implicit class RunSyntax[A](
    task: Task[A]
  ) {
    def run(): A = Unsafe.unsafe(implicit unsafe => Runtime.default.unsafe.run(task).getOrThrowFiberFailure())
  }

  def test(name: String)(task: => Task[Any]): Unit = {
    print(s" - $name...")
    try {
      task.run()

      println("...OK")
    } catch {
      case e: java.lang.AssertionError =>
        println("...FAILED")
        e.printStackTrace()
      case t: Throwable =>
        println("...CATASTROPHIC")
        println(t.getMessage())
        println(t.toString())
        t.printStackTrace()
    }
  }

  def testN(n: Int)(name: String)(task: Task[Any]): Unit =
    (1 to n).foreach(_ => test(name)(task))

  def helloWorld() =
    test("Hello World") {
      for {
        _ <- Console.print("Hello World!")
      } yield assert(true)
    }

  def fibAcc(n: Int): Task[Int] =
    if (n <= 1)
      ZIO.succeed(n)
    else
      for {
        a <- fibAcc(n - 1)
        b <- fibAcc(n - 2)
      } yield a + b

  def fib() =
    test("fib") {
      for {
        result <- fibAcc(10)
      } yield assert(result == 55)
    }

  def runtimeFlags() =
    test("runtimeFlags") {
      ZIO.succeed {
        import RuntimeFlag._

        val flags =
          RuntimeFlags(Interruption, CurrentFiber)

        assert(RuntimeFlags.isEnabled(flags)(Interruption))
        assert(RuntimeFlags.isEnabled(flags)(CurrentFiber))
        assert(RuntimeFlags.isDisabled(flags)(FiberRoots))
        assert(RuntimeFlags.isDisabled(flags)(OpLog))
        assert(RuntimeFlags.isDisabled(flags)(OpSupervision))
        assert(RuntimeFlags.isDisabled(flags)(RuntimeMetrics))

        assert(
          RuntimeFlags.interruption(RuntimeFlags.Patch.patch(RuntimeFlags.enable(Interruption))(RuntimeFlags.none))
        )
      }
    }

  def race() =
    testN(100)("race") {
      ZIO.unit.race(ZIO.unit)
    }

  def iteration() =
    test("iteration") {
      ZIO.iterate(0)(_ < 100)(index => ZIO.succeed(index + 1)).map(value => assert(value == 100))
    }

  def asyncInterruption() =
    testN(100)("async interruption") {
      for {
        fiber <- ZIO.never.forkDaemon
        _     <- fiber.interrupt
      } yield assert(true)
    }

  def syncInterruption() =
    testN(100)("sync interruption") {
      for {
        fiber <- fibAcc(100).forkDaemon
        _     <- fiber.interrupt
      } yield assert(true)
    }

  def autoInterruption() =
    test("auto interruption with finalization") {
      for {
        ref    <- Ref.make(0)
        latch  <- Promise.make[Nothing, Unit]
        child   = (latch.succeed(()) *> ZIO.infinity).ensuring(ref.update(_ + 1))
        parent <- (child.fork *> latch.await).fork
        _      <- parent.await
        count  <- ref.get
      } yield assert(count == 1)
    }

  def autoInterruption2() =
    test("auto interruption with finalization 2") {
      def plus1(start: Promise[Nothing, Unit], end: Promise[Nothing, Unit], finalizer: UIO[Any]) =
        (start.succeed(()) *> ZIO.sleep(1.hour)).onInterrupt(finalizer *> end.succeed(()))

      for {
        interruptionRef <- Ref.make(0)
        latch1Start     <- Promise.make[Nothing, Unit]
        latch2Start     <- Promise.make[Nothing, Unit]
        latch1End       <- Promise.make[Nothing, Unit]
        latch2End       <- Promise.make[Nothing, Unit]
        inc              = interruptionRef.update(_ + 1)
        left             = plus1(latch1Start, latch1End, inc)
        right            = plus1(latch2Start, latch2End, inc)
        fiber           <- left.disconnect.race(right.disconnect).fork
        _               <- latch1Start.await *> latch2Start.await *> fiber.interrupt *> latch1End.await *> latch2End.await
        interrupted     <- interruptionRef.get
      } yield assert(interrupted == 2)
    }

  def asyncInterruptionOfNever() =
    test("async interruption of never") {
      for {
        finalized <- Ref.make(false)
        fork <- ((ZIO
                  .asyncMaybe[Any, Nothing, Unit] { _ =>
                    Some(ZIO.unit)
                  }
                  .flatMap { _ =>
                    ZIO.never
                  })
                  .ensuring(finalized.set(true)))
                  .uninterruptible
                  .forkDaemon
        _      <- fork.interrupt.timeout(5.seconds)
        result <- finalized.get
      } yield assert(result == false)
    }

  def interruptRacedForks() =
    test("race of two forks does not interrupt winner") {
      def forkWaiter(interrupted: Ref[Int], latch: Promise[Nothing, Unit], done: Promise[Nothing, Unit]) =
        ZIO.uninterruptibleMask { restore =>
          restore(latch.await)
            .onInterrupt(interrupted.update(_ + 1) *> done.succeed(()))
            .fork
        }

      for {
        interrupted <- Ref.make(0)
        fibers      <- Ref.make(Set.empty[Fiber[Any, Any]])
        latch1      <- Promise.make[Nothing, Unit]
        latch2      <- Promise.make[Nothing, Unit]
        done1       <- Promise.make[Nothing, Unit]
        done2       <- Promise.make[Nothing, Unit]
        forkWaiter1  = forkWaiter(interrupted, latch1, done1)
        forkWaiter2  = forkWaiter(interrupted, latch2, done2)
        awaitAll     = fibers.get.flatMap(Fiber.awaitAll(_))
        _           <- forkWaiter1.race(forkWaiter2)
        count       <- latch1.succeed(()) *> done1.await *> done2.await *> interrupted.get
      } yield assert(count == 2)
    }

  def useInheritance() =
    test("acquireRelease use inherits interrupt status") {
      for {
        ref <- Ref.make(false)
        fiber1 <-
          withLatch { (release2, await2) =>
            withLatch { release1 =>
              ZIO
                .acquireReleaseWith(release1)(_ => ZIO.unit)(_ => await2 *> Clock.sleep(10.millis) *> ref.set(true))
                .uninterruptible
                .fork
            } <* release2
          }
        _     <- fiber1.interrupt
        value <- ref.get
      } yield assert(value == true)
    }

  def useInheritance2() =
    test("acquireRelease use inherits interrupt status 2") {
      for {
        latch1 <- Promise.make[Nothing, Unit]
        latch2 <- Promise.make[Nothing, Unit]
        ref    <- Ref.make(false)
        fiber1 <-
          ZIO
            .acquireReleaseExitWith(latch1.succeed(()))((_: Boolean, _: Exit[Any, Any]) => ZIO.unit)((_: Boolean) =>
              latch2.await *> Clock.sleep(10.millis) *> ref.set(true).unit
            )
            .uninterruptible
            .fork
        _     <- latch1.await
        _     <- latch2.succeed(())
        _     <- fiber1.interrupt
        value <- ref.get
      } yield assert(value == true)
    }

  def asyncUninterruptible() =
    test("async can be uninterruptible") {
      for {
        ref <- Ref.make(false)
        fiber <- withLatch { release =>
                   (release *> Clock.sleep(10.millis) *> ref.set(true).unit).uninterruptible.fork
                 }
        _     <- fiber.interrupt
        value <- ref.get
      } yield assert(value == true)
    }

  def uninterruptibleClosingScope() =
    test("closing scope is uninterruptible") {
      for {
        ref     <- Ref.make(false)
        promise <- Promise.make[Nothing, Unit]
        child    = promise.succeed(()) *> ZIO.sleep(10.milliseconds) *> ref.set(true)
        parent   = child.uninterruptible.fork *> promise.await
        fiber   <- parent.fork
        _       <- promise.await
        _       <- fiber.interrupt
        value   <- ref.get
      } yield assert(value == true)
    }

  def syncInterruption2() =
    test("sync forever is interruptible") {
      for {
        latch <- Promise.make[Nothing, Unit]
        f     <- (latch.succeed(()) *> ZIO.succeed[Int](1).forever).fork
        _     <- latch.await
        _     <- f.interrupt
      } yield assert(true)
    }

  def acquireReleaseDisconnect() =
    test("acquireReleaseWith disconnect release called on interrupt in separate fiber") {
      for {
        useLatch     <- Promise.make[Nothing, Unit]
        releaseLatch <- Promise.make[Nothing, Unit]
        fiber <- ZIO
                   .acquireReleaseWith(ZIO.unit)(_ => releaseLatch.succeed(()) *> ZIO.unit)(_ =>
                     useLatch.succeed(()) *> ZIO.never
                   )
                   .disconnect
                   .fork
        _      <- useLatch.await
        _      <- fiber.interrupt
        result <- releaseLatch.await.timeoutTo(false)(_ => true)(5.seconds)
      } yield assert(result == true)
    }

  def disconnectedInterruption() =
    test("disconnected effect that is then interrupted eventually performs interruption") {
      for {
        finalized      <- Ref.make(false)
        startLatch     <- Promise.make[Nothing, Unit]
        finalizedLatch <- Promise.make[Nothing, Unit]
        fiber <- (startLatch.succeed(()) *> ZIO.never)
                   .ensuring(finalized.set(true) *> Clock.sleep(10.millis) *> finalizedLatch.succeed(()))
                   .disconnect
                   .fork
        _    <- startLatch.await
        _    <- fiber.interrupt
        _    <- finalizedLatch.await
        test <- finalized.get
      } yield assert(test == true)
    }

  def interruptibleAfterRace() =
    test("interruptible after race") {
      for {
        status1 <- ZIO.checkInterruptible(status => ZIO.succeed(status))
        _       <- ZIO.unit.race(ZIO.unit)
        status2 <- ZIO.checkInterruptible(status => ZIO.succeed(status))
      } yield assert(status1 == InterruptStatus.Interruptible && status2 == InterruptStatus.Interruptible)
    }

  def uninterruptibleRace() =
    test("race in uninterruptible region") {
      for {
        _ <- ZIO.unit.race(ZIO.infinity).uninterruptible
      } yield assert(true)
    }

  def interruptionDetection() =
    test("interruption detection") {
      for {
        startLatch <- Promise.make[Nothing, Unit]
        endLatch   <- Promise.make[Nothing, Unit]
        finalized  <- Ref.make(false)
        fiber      <- (startLatch.succeed(()) *> ZIO.infinity).onInterrupt(finalized.set(true) *> endLatch.succeed(())).fork
        _          <- startLatch.await
        _          <- fiber.interrupt
        _          <- endLatch.await
        value      <- finalized.get
      } yield assert(value == true)
    }

  def interruptionRecovery() =
    test("interruption recovery") {
      for {
        startLatch          <- Promise.make[Nothing, Unit]
        endLatch            <- Promise.make[Nothing, Unit]
        exitRef             <- Ref.make[Exit[Any, Any]](Exit.succeed(()))
        pastInterruptionRef <- Ref.make(false)
        fiber <- (ZIO.uninterruptibleMask { restore =>
                   restore(startLatch.succeed(()) *> ZIO.infinity).exit.flatMap(exitRef.set(_))
                 } *> pastInterruptionRef.set(true)).ensuring(endLatch.succeed(())).fork
        _    <- startLatch.await
        _    <- fiber.interrupt
        _    <- endLatch.await
        exit <- exitRef.get
        past <- pastInterruptionRef.get
      } yield assert(exit.causeOption.get.isInterrupted && past == false)
    }

  def cooperativeYielding() =
    test("cooperative yielding") {
      import zio._

      def infiniteProcess(ref: Ref[Int]): UIO[Nothing] =
        ref.update(_ + 1) *> infiniteProcess(ref)

      for {
        ref1   <- Ref.make(0)
        ref2   <- Ref.make(0)
        ref3   <- Ref.make(0)
        fiber1 <- infiniteProcess(ref1).onExecutor(singleThreadedExecutor).fork
        fiber2 <- infiniteProcess(ref2).onExecutor(singleThreadedExecutor).fork
        fiber3 <- infiniteProcess(ref3).onExecutor(singleThreadedExecutor).fork
        _      <- ZIO.sleep(Duration.fromSeconds(1))
        _      <- fiber1.interruptFork *> fiber2.interruptFork *> fiber3.interruptFork
        _      <- fiber1.await *> fiber2.await *> fiber3.await
        v1     <- ref1.get
        v2     <- ref2.get
        v3     <- ref3.get
      } yield assert(v1 > 0 && v2 > 0 && v3 > 0)
    }

  def interruptionOfForkedRace() =
    testN(100)("interruption of forked raced") {
      def make(ref: Ref[Int], start: Promise[Nothing, Unit], done: Promise[Nothing, Unit]) =
        (start.succeed(()) *> ZIO.infinity).onInterrupt(ref.update(_ + 1) *> done.succeed(()))

      for {
        ref   <- Ref.make(0)
        cont1 <- Promise.make[Nothing, Unit]
        cont2 <- Promise.make[Nothing, Unit]
        done1 <- Promise.make[Nothing, Unit]
        done2 <- Promise.make[Nothing, Unit]
        raced <- (make(ref, cont1, done1).race(make(ref, cont2, done2))).fork
        _     <- cont1.await *> cont2.await
        _     <- raced.interrupt
        _     <- done1.await *> done2.await
        count <- ref.get
      } yield assert(count == 2)
    }

  def secondLevelCallStack =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- ZIO.stackTrace
    } yield t

  def firstLevelCallStack =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- secondLevelCallStack
    } yield t

  def stackTraceTest1 =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- firstLevelCallStack
    } yield t

  def secondLevelCallStackFail =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- ZIO.fail("Uh oh!")
    } yield t

  def firstLevelCallStackFail =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- secondLevelCallStackFail
    } yield t

  def stackTraceTest2 =
    for {
      _ <- ZIO.succeed(10)
      _ <- ZIO.succeed(20)
      _ <- ZIO.succeed(30)
      t <- firstLevelCallStackFail
    } yield t

  def stackTrace1() =
    test("stack trace 1") {
      for {
        trace <- stackTraceTest1
        t      = trace.stackTrace.map(_.toString())
      } yield assert(
        t.length == 5 &&
          t.exists(_.contains("stackTrace")) &&
          t.exists(_.contains("stackTraceTest1")) &&
          t.exists(_.contains("firstLevelCallStack")) &&
          t.exists(_.contains("secondLevelCallStack"))
      )
    }

  def stackTrace2() =
    test("stack trace 2") {
      for {
        cause <- stackTraceTest2.sandbox.flip
        t      = cause.trace.stackTrace.map(_.toString())
      } yield assert(
        t.length == 5 &&
          t.exists(_.contains("stackTrace")) &&
          t.exists(_.contains("stackTraceTest2")) &&
          t.exists(_.contains("firstLevelCallStackFail")) &&
          t.exists(_.contains("secondLevelCallStackFail"))
      )
    }

  def interruptibleHole() =
    test("child becoming interruptible is interrupted due to auto-supervision of uninterruptible parent") {
      for {
        latch <- Promise.make[Nothing, Unit]
        child  = ZIO.infinity.interruptible.onInterrupt(latch.succeed(())).fork
        _     <- child.fork.uninterruptible
        _     <- latch.await
      } yield assert(true)
    }

  def queueOfferInterruption() = {
    def waitForValue[T](ref: UIO[T], value: T): UIO[T] =
      (ref <* Clock.sleep(10.millis)).repeatUntil(_ == value)

    def waitForSize[A](queue: Queue[A], size: Int): UIO[Int] =
      waitForValue(queue.size, size)

    testN(100)("offers are suspended by back pressure") {
      for {
        queue        <- Queue.bounded[Int](10)
        _            <- queue.offer(1).repeatN(9)
        refSuspended <- Ref.make[Boolean](true)
        f            <- (queue.offer(2) *> refSuspended.set(false)).fork
        _            <- waitForSize(queue, 11)
        isSuspended  <- refSuspended.get
        _            <- f.interrupt
      } yield assert(isSuspended)
    }
  }

  def localSupervision() = {
    def aThreadLocal() =
      new ThreadLocal[Option[String]] {
        override def initialValue() = None
      }

    def tracking[R, E, A](
      initialValue: String
    )(effect: (FiberRef[String], UIO[Option[String]]) => ZIO[R, E, A]): ZIO[R & Scope & ThreadLocalBridge, E, A] = {
      val threadLocal    = aThreadLocal()
      val threadLocalGet = ZIO.succeed(threadLocal.get)
      ThreadLocalBridge
        .makeFiberRef[String](initialValue)(a => threadLocal.set(Some(a)))
        .flatMap(effect(_, threadLocalGet))
    }

    test("track initial value") {
      val tag          = "tiv"
      val initialValue = s"initial-value-$tag"
      tracking(initialValue) { (_, threadLocalGet) =>
        (for {
          _     <- FiberRef.currentSupervisor.get
          ab    <- threadLocalGet zipPar threadLocalGet
          (a, b) = ab
        } yield {
          assert(
            a.contains(initialValue),
            b.contains(initialValue)
          )
        })
      }.provide(Scope.default, ThreadLocalBridge.live)
    }
  }

  def bigSyncInterruption() =
    testN(100)("big sync interruption") {
      import zio.internal.Stack

      def makeWriter(stack: Stack[String]) = ZIO.succeed {
        var goUp   = 90
        var goDown = 80
        stack.push("1")
        while (!stack.isEmpty) {
          (0 to goUp).foreach(_ => stack.push("1"))
          (0 to goDown).foreach(_ => if (!stack.isEmpty) stack.pop())
          goUp = goUp - 1
          goDown = goDown + 1
        }
      }

      for {
        stack   <- ZIO.succeed(Stack[String]())
        fiber   <- makeWriter(stack).forever.fork
        readers <- ZIO.forkAll(List.fill(100)(ZIO.succeed(stack.toList.forall(_ != null))))
        noNulls <- readers.join.map(_.forall(a => a))
        _       <- fiber.interrupt
      } yield assert(noNulls)
    }

  def yieldForked() =
    test("yield forked") {
      ZIOAppDefault.fromZIO(ZIO.yieldNow).invoke(Chunk.empty).asInstanceOf[Task[Any]]
    }

  def invisibleInterruption() =
    test("recovery in uninterruptible region does not reveal interruption in cause") {
      for {
        startLatch <- Promise.make[Nothing, Unit]
        failLatch  <- Promise.make[Nothing, Unit]
        fiber0     <- (startLatch.succeed(()) *> failLatch.await *> ZIO.fail("foo")).sandbox.flip.fork.uninterruptible
        fiber       = fiber0: Fiber[Nothing, Cause[String]]
        _          <- startLatch.await *> fiber.interruptFork *> failLatch.succeed(())
        _          <- ZIO.debug("\n******* About to join!")
        cause      <- fiber.join.debug("cause2")
        _          <- ZIO.debug("\n+++++++ Ending here!")
      } yield assert(cause == Cause.fail("foo"))
    }

  def invisibleinterruptedCause() =
    test("child interrupted cause cause cannot be seen from parent") {
      for {
        parentId     <- ZIO.fiberId
        parentBefore <- FiberRef.interruptedCause.get
        child        <- FiberRef.interruptedCause.set(Cause.interrupt(parentId)).fork
        _            <- child.join
        parentAfter  <- FiberRef.interruptedCause.get
      } yield assert(parentBefore == parentAfter)
    }

  def accretiveInterruptions() =
    test("interrupters are accretive") {
      for {
        breakpoint1 <- Promise.make[Nothing, Unit]
        breakpoint2 <- Promise.make[Nothing, Unit]
        started     <- Promise.make[Nothing, Unit]
        effect =
          for {
            _      <- started.succeed(())
            cause1 <- ZIO.interruptible(ZIO.never).catchAllCause(ZIO.succeed(_)) <* breakpoint1.succeed(())
            cause2 <- breakpoint2.await *> ZIO.interruptible(ZIO.never).catchAllCause(ZIO.succeed(_))
          } yield (cause1, cause2)
        fiber           <- effect.fork.uninterruptible
        _               <- (breakpoint1.await *> fiber.interruptFork *> breakpoint2.succeed(())).fork
        _               <- started.await
        tuple           <- fiber.interruptFork *> fiber.join
        (cause1, cause2) = tuple
      } yield assert(cause1.size == 1 && cause2.size == 2)
    }

  def stackRegression1() =
    test("return a `CompletableFuture` that fails if `IO` fails") {
      val error = new Exception("IOs also can fail")
      val failed = ZIO.fail(error).catchAll { e =>
        println("about to throw: " + e)
        ZIO.attempt(throw e)
      }

      for {
        exit <- failed.exit
      } yield assert(exit == Exit.fail(error))
    }

  def zipParInterruption() =
    test("zipPar is interruptible") {
      for {
        promise1 <- Promise.make[Nothing, Unit]
        promise2 <- Promise.make[Nothing, Unit]
        left      = promise1.succeed(()) *> ZIO.never
        right     = promise2.succeed(()) *> ZIO.never
        fiber    <- left.zipPar(right).fork
        _        <- promise1.await
        _        <- promise2.await
        _        <- fiber.interrupt
      } yield ()
    }

  def main(args: Array[String]): Unit = {
    val _ = ()
    runtimeFlags()
    helloWorld()
    fib()
    iteration()
    asyncInterruption()
    syncInterruption()
    race()
    autoInterruption()
    autoInterruption2()
    asyncInterruptionOfNever()
    interruptRacedForks()
    useInheritance()
    useInheritance2()
    asyncUninterruptible()
    uninterruptibleClosingScope()
    syncInterruption2()
    acquireReleaseDisconnect()
    disconnectedInterruption()
    interruptibleAfterRace()
    // uninterruptibleRace()
    interruptionDetection()
    interruptionRecovery()
    // cooperativeYielding()
    // interruptionOfForkedRace()
    stackTrace1()
    stackTrace2()
    // interruptibleHole()
    // queueOfferInterruption()
    // localSupervision()
    // bigSyncInterruption()
    // yieldForked()
    // invisibleInterruption()
    // invisibleinterruptedCause()
    // accretiveInterruptions()
    stackRegression1()
    // zipParInterruption()
  }

}
