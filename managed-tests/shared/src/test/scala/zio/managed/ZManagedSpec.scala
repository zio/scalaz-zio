package zio.managed

import zio._
import zio.managed.ZManaged.ReleaseMap
import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky, scala2Only}
import zio.test._

import scala.concurrent.ExecutionContext

object ZManagedSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("ZManaged")(
    suite("absorbWith")(
      test("on fail") {
        assertZIO(ZManagedExampleError.absorbWith(identity).use[Any, Throwable, Int](ZIO.succeed(_)).exit)(
          fails(equalTo(ExampleError))
        )
      } @@ zioTag(errors),
      test("on die") {
        assertZIO(ZManagedExampleDie.absorbWith(identity).use[Any, Throwable, Int](ZIO.succeed(_)).exit)(
          fails(equalTo(ExampleError))
        )
      } @@ zioTag(errors),
      test("on success") {
        assertZIO(ZIO.succeed(1).absorbWith(_ => ExampleError))(equalTo(1))
      }
    ),
    suite("acquireReleaseWith")(
      test("Invokes cleanups in reverse order of acquisition.") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1) *> res(2) *> res(3)
          values  <- program.useDiscard(ZIO.unit) *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 3, 3, 2, 1)))
      },
      test("Constructs an uninterruptible Managed value") {
        doInterrupt(io => ZManaged.acquireReleaseWith(io)(_ => ZIO.unit), _ => isNone)
      },
      test("Infers the environment type correctly") {
        trait R
        trait R1
        trait R2 extends R1
        trait E
        trait A
        def acquire1: ZIO[R, E, A]               = ZIO.never
        def acquire2: ZIO[R1, E, A]              = ZIO.never
        def acquire3: ZIO[R2, E, A]              = ZIO.never
        def release1: A => URIO[R, Any]          = _ => ZIO.never
        def release2: A => ZIO[R1, Nothing, Any] = _ => ZIO.never
        def release3: A => ZIO[R2, Nothing, Any] = _ => ZIO.never
        def managed1: ZManaged[R with R1, E, A]  = ZManaged.acquireReleaseWith(acquire1)(release2)
        def managed2: ZManaged[R with R1, E, A]  = ZManaged.acquireReleaseWith(acquire2)(release1)
        def managed3: ZManaged[R2, E, A]         = ZManaged.acquireReleaseWith(acquire2)(release3)
        def managed4: ZManaged[R2, E, A]         = ZManaged.acquireReleaseWith(acquire3)(release2)
        lazy val result                          = (managed1, managed2, managed3, managed4)
        ZIO.succeed(assert(result)(anything))
      }
    ),
    suite("acquireReleaseAttemptWith")(
      test("Invokes cleanups in reverse order of acquisition.") {
        var effects               = List[Int]()
        def acquire(x: Int): Int  = { effects = x :: effects; x }
        def release(x: Int): Unit = effects = x :: effects

        val res     = (x: Int) => ZManaged.acquireReleaseAttemptWith(acquire(x))(release)
        val program = res(1) *> res(2) *> res(3)

        for {
          _ <- program.useDiscard(ZIO.unit)
        } yield assert(effects)(equalTo(List(1, 2, 3, 3, 2, 1)))
      }
    ),
    suite("fromReservation")(
      test("Interruption is possible when using this form") {
        doInterrupt(
          io => ZManaged.fromReservation(Reservation(io, _ => ZIO.unit)),
          selfId => isSome(failsCause(containsCause(Cause.interrupt(selfId))))
        )
      }
    ),
    suite("acquireReleaseExitWith")(
      test("Invokes with the failure of the use") {
        val ex = new RuntimeException("Use died")

        def res(exits: Ref[List[Exit[Any, Any]]]) =
          for {
            _ <- ZManaged.acquireReleaseExitWith(ZIO.unit)((_, e) => exits.update(e :: _))
            _ <- ZManaged.acquireReleaseExitWith(ZIO.unit)((_, e) => exits.update(e :: _))
          } yield ()

        for {
          exits  <- Ref.make[List[Exit[Any, Any]]](Nil)
          _      <- res(exits).useDiscard(ZIO.die(ex)).exit
          result <- exits.get
        } yield assert(result)(
          equalTo(
            List[Exit[Any, Any]](
              Exit.Failure(Cause.Die(ex, StackTrace.none)),
              Exit.Failure(Cause.Die(ex, StackTrace.none))
            )
          )
        )
      } @@ zioTag(errors),
      test("Invokes with the failure of the subsequent acquire") {
        val useEx     = new RuntimeException("Use died")
        val acquireEx = new RuntimeException("Acquire died")

        def res(exits: Ref[List[Exit[Any, Any]]]) =
          for {
            _ <- ZManaged.acquireReleaseExitWith(ZIO.unit)((_, e) => exits.update(e :: _))
            _ <- ZManaged.acquireReleaseExitWith(ZIO.die(acquireEx))((_, e) => exits.update(e :: _))
          } yield ()

        for {
          exits  <- Ref.make[List[Exit[Any, Any]]](Nil)
          _      <- res(exits).useDiscard(ZIO.die(useEx)).exit
          result <- exits.get
        } yield assert(result)(equalTo(List[Exit[Any, Any]](Exit.Failure(Cause.Die(acquireEx, StackTrace.none)))))
      }
    ) @@ zioTag(errors),
    suite("zipWithPar")(
      test("Properly performs parallel acquire and release") {
        for {
          log      <- Ref.make[List[String]](Nil)
          a         = ZManaged.acquireReleaseWith(ZIO.succeed("A"))(_ => log.update("A" :: _))
          b         = ZManaged.acquireReleaseWith(ZIO.succeed("B"))(_ => log.update("B" :: _))
          result   <- a.zipWithPar(b)(_ + _).use(ZIO.succeed(_))
          cleanups <- log.get
        } yield assert(result.length)(equalTo(2)) && assert(cleanups)(hasSize(equalTo(2)))
      },
      test("preserves ordering of nested finalizers") {
        val inner = Ref.make(List[Int]()).toManaged.flatMap { ref =>
          ZManaged.finalizer(ref.update(1 :: _)) *>
            ZManaged.finalizer(ref.update(2 :: _)) *>
            ZManaged.finalizer(ref.update(3 :: _)).as(ref)
        }

        (inner <&> inner).useNow.flatMap { case (l, r) =>
          l.get <*> r.get
        }.map { case (l, r) =>
          assert(l)(equalTo(List(1, 2, 3))) &&
          assert(r)(equalTo(List(1, 2, 3)))
        }
      } @@ exceptJS(nonFlaky)
    ),
    suite("fromZIO")(
      test("Performed interruptibly") {
        assertZIO(ZManaged.fromZIO(ZIO.checkInterruptible(ZIO.succeed(_))).use(ZIO.succeed(_)))(
          equalTo(InterruptStatus.interruptible)
        )
      }
    ) @@ zioTag(interruption),
    suite("fromZIOUninterruptible")(
      test("Performed uninterruptibly") {
        assertZIO(ZManaged.fromZIOUninterruptible(ZIO.checkInterruptible(ZIO.succeed(_))).use(ZIO.succeed(_)))(
          equalTo(InterruptStatus.uninterruptible)
        )
      }
    ) @@ zioTag(interruption),
    suite("ensuring")(
      test("Runs on successes") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                 .finalizer(effects.update("First" :: _))
                 .ensuring(effects.update("Second" :: _))
                 .useDiscard(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("Second", "First")))
      },
      test("Runs on failures") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _       <- ZManaged.fromZIO(ZIO.fail(())).ensuring(effects.update("Ensured" :: _)).useDiscard(ZIO.unit).either
          result  <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      } @@ zioTag(errors),
      test("Works when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                 .finalizer(ZIO.dieMessage("Boom"))
                 .ensuring(effects.update("Ensured" :: _))
                 .useDiscard(ZIO.unit)
                 .exit
          result <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      }
    ),
    suite("ensuringFirst")(
      test("Runs on successes") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                 .finalizer(effects.update("First" :: _))
                 .ensuringFirst(effects.update("Second" :: _))
                 .useDiscard(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("First", "Second")))
      },
      test("Runs on failures") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _       <- ZManaged.fromZIO(ZIO.fail(())).ensuringFirst(effects.update("Ensured" :: _)).useDiscard(ZIO.unit).either
          result  <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      } @@ zioTag(errors),
      test("Works when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- ZManaged
                 .finalizer(ZIO.dieMessage("Boom"))
                 .ensuringFirst(effects.update("Ensured" :: _))
                 .useDiscard(ZIO.unit)
                 .exit
          result <- effects.get
        } yield assert(result)(equalTo(List("Ensured")))
      }
    ),
    test("eventually") {
      def acquire(ref: Ref[Int]) =
        for {
          v <- ref.get
          r <- if (v < 10) ref.update(_ + 1) *> ZIO.fail("Ouch")
               else ZIO.succeed(v)
        } yield r

      for {
        ref <- Ref.make(0)
        _   <- ZManaged.acquireReleaseWith(acquire(ref))(_ => ZIO.unit).eventually.use(_ => ZIO.unit)
        r   <- ref.get
      } yield assert(r)(equalTo(10))
    },
    suite("flatMap")(
      test("All finalizers run even when finalizers have defects") {
        for {
          effects <- Ref.make[List[String]](Nil)
          _ <- (for {
                 _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                 _ <- ZManaged.finalizer(effects.update("First" :: _))
                 _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                 _ <- ZManaged.finalizer(effects.update("Second" :: _))
                 _ <- ZManaged.finalizer(ZIO.dieMessage("Boom"))
                 _ <- ZManaged.finalizer(effects.update("Third" :: _))
               } yield ()).useDiscard(ZIO.unit).exit
          result <- effects.get
        } yield assert(result)(equalTo(List("First", "Second", "Third")))
      }
    ),
    suite("foldManaged")(
      test("Runs onFailure on failure") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = ZManaged.fromZIO(ZIO.fail(())).foldManaged(_ => res(1), _ => ZManaged.unit)
          values  <- program.useDiscard(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      } @@ zioTag(errors),
      test("Runs onSuccess on success") {
        implicit val canFail = CanFail
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = ZManaged.succeed(()).foldManaged(_ => ZManaged.unit, _ => res(1))
          values  <- program.useDiscard(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      },
      test("Invokes cleanups") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1).flatMap(_ => ZManaged.fail(())).foldManaged(_ => res(2), _ => res(3))
          values  <- program.useDiscard(ZIO.unit).ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      },
      test("Invokes cleanups on interrupt - 1") {
        implicit val canFail = CanFail
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1).flatMap(_ => ZManaged.interrupt).foldManaged(_ => res(2), _ => res(3))
          values  <- program.useDiscard(ZIO.unit).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 1)))
      } @@ zioTag(interruption),
      test("Invokes cleanups on interrupt - 2") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1).flatMap(_ => ZManaged.fail(())).foldManaged(_ => res(2), _ => res(3))
          values  <- program.useDiscard(ZIO.interrupt).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      } @@ zioTag(interruption),
      test("Invokes cleanups on interrupt - 3") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1).flatMap(_ => ZManaged.fail(())).foldManaged(_ => res(2) *> ZManaged.interrupt, _ => res(3))
          values  <- program.useDiscard(ZIO.unit).sandbox.ignore *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 2, 1)))
      } @@ zioTag(interruption)
    ),
    suite("foreach")(
      test("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreach(List(1, 2, 3, 4))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreach(List(1, 2, 3, 4))(_ => res))
      },
      test("Invokes cleanups in reverse order of acquisition") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = ZManaged.foreach(List(1, 2, 3))(res)
          values  <- program.useDiscard(ZIO.unit) *> effects.get
        } yield assert(values)(equalTo(List(1, 2, 3, 3, 2, 1)))
      }
    ),
    suite("foreach for Option")(
      test("Returns elements if Some") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreach(Some(3))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(Some(3)))))
      },
      test("Returns nothing if None") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreach(None)(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(None))))
      },
      test("Runs finalizers") {
        testFinalizersPar(1, res => ZManaged.foreach(Some(4))(_ => res))
      }
    ),
    suite("foreachPar")(
      test("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreachPar(List(1, 2, 3, 4))(res)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      },
      test("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      },
      test("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res))
      },
      test("Maintains finalizer ordering in inner ZManaged values") {
        check(Gen.int(5, 100))(l => testParallelNestedFinalizerOrdering(l, ZManaged.foreachPar(_)(identity)))
      }
    ),
    suite("foreachParN")(
      test("Returns elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.foreachPar(List(1, 2, 3, 4))(res).withParallelism(2)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      test("Uses at most n fibers for reservation") {
        testFinalizersPar(4, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      },
      test("Uses at most n fibers for acquisition") {
        testReservePar(2, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      },
      test("Runs finalizers") {
        testAcquirePar(2, res => ZManaged.foreachPar(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      },
      test("Maintains finalizer ordering in inner ZManaged values") {
        check(Gen.int(4, 10), Gen.int(5, 100)) { (n, l) =>
          testParallelNestedFinalizerOrdering(l, ZManaged.foreachPar(_)(identity)).withParallelism(n)
        }
      }
    ),
    suite("foreachDiscard")(
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreachDiscard(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreachParDiscard")(
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res))
      },
      test("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res))
      },
      test("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res))
      }
    ),
    suite("foreachParNDiscard")(
      test("Uses at most n fibers for reservation") {
        testFinalizersPar(4, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      },
      test("Uses at most n fibers for acquisition") {
        testReservePar(2, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      },
      test("Runs finalizers") {
        testReservePar(2, res => ZManaged.foreachParDiscard(List(1, 2, 3, 4))(_ => res)).withParallelism(2)
      }
    ),
    suite("fork")(
      test("Runs finalizers properly") {
        for {
          finalized <- Ref.make(false)
          latch     <- Promise.make[Nothing, Unit]
          _ <- ZManaged
                 .fromReservation(Reservation(latch.succeed(()) *> ZIO.never, _ => finalized.set(true)))
                 .fork
                 .useDiscard(latch.await)
          result <- finalized.get
        } yield assert(result)(isTrue)
      },
      test("Acquires interruptibly") {
        for {
          finalized    <- Ref.make(false)
          acquireLatch <- Promise.make[Nothing, Unit]
          useLatch     <- Promise.make[Nothing, Unit]
          fib <- ZManaged
                   .fromReservation(
                     Reservation(
                       acquireLatch.succeed(()) *> ZIO.never,
                       _ => finalized.set(true)
                     )
                   )
                   .fork
                   .useDiscard(useLatch.succeed(()) *> ZIO.never)
                   .fork
          _      <- acquireLatch.await
          _      <- useLatch.await
          _      <- fib.interrupt
          result <- finalized.get
        } yield assert(result)(isTrue)
      } @@ zioTag(interruption)
    ),
    suite("fromAutoCloseable")(
      test("Runs finalizers properly") {
        for {
          runtime <- ZIO.runtime[Any]
          effects <- Ref.make(List[String]())
          closeable = ZIO.succeed(new AutoCloseable {
                        def close(): Unit = Unsafe.unsafe { implicit unsafe =>
                          runtime.unsafe.run(effects.update("Closed" :: _)).getOrThrowFiberFailure()
                        }
                      })
          _      <- ZManaged.fromAutoCloseable(closeable).useDiscard(ZIO.unit)
          result <- effects.get
        } yield assert(result)(equalTo(List("Closed")))
      },
      test("is null-safe") {
        // Will be `null` because the file doesn't exist
        def loadNonExistingFile = ZIO.attempt(this.getClass.getResourceAsStream(s"this_file_doesnt_exist.json"))

        for {
          shouldBeNull <- loadNonExistingFile
          // Should not fail when closing a null resource
          // The test will fail if the resource is not closed properly
          _ <- ZManaged.fromAutoCloseable(loadNonExistingFile).useDiscard(ZIO.unit)
        } yield assert(shouldBeNull)(isNull)
      }
    ),
    suite("ifManaged")(
      test("runs `onTrue` if result of `b` is `true`") {
        val managed = ZManaged.ifManaged(ZManaged.succeed(true))(ZManaged.succeed(true), ZManaged.succeed(false))
        assertZIO(managed.use(ZIO.succeed(_)))(isTrue)
      },
      test("runs `onFalse` if result of `b` is `false`") {
        val managed = ZManaged.ifManaged(ZManaged.succeed(false))(ZManaged.succeed(true), ZManaged.succeed(false))
        assertZIO(managed.use(ZIO.succeed(_)))(isFalse)
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZManaged[R, E, Boolean]   = ZManaged.succeed(true)
        val onTrue: ZManaged[R1, E1, A]  = ZManaged.succeed(new A {})
        val onFalse: ZManaged[R1, E1, A] = ZManaged.succeed(new A {})
        val _                            = ZManaged.ifManaged(b)(onTrue, onFalse)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("onExecutor")(
      test("runs acquire, use, and release actions on the specified executor") {
        val executor: UIO[Executor] = ZIO.descriptorWith(descriptor => ZIO.succeed(descriptor.executor))
        val global                  = Executor.fromExecutionContext(ExecutionContext.global)
        for {
          default <- executor
          ref1    <- Ref.make[Executor](default)
          ref2    <- Ref.make[Executor](default)
          managed  = ZManaged.acquireRelease(executor.flatMap(ref1.set))(executor.flatMap(ref2.set)).onExecutor(global)
          before  <- executor
          use     <- managed.useDiscard(executor).onExecutor(before)
          acquire <- ref1.get
          release <- ref2.get
          after   <- executor
        } yield assert(before)(equalTo(default)) &&
          assert(acquire)(equalTo(global)) &&
          assert(use)(equalTo(global)) &&
          assert(release)(equalTo(global)) &&
          assert(after)(equalTo(default))
      },
      test("does not shift back to original executor if it was not locked") {
        val thread = ZIO.succeed(Thread.currentThread())

        val global =
          Executor.fromExecutionContext(scala.concurrent.ExecutionContext.global)
        for {
          which   <- Ref.make[Option[Thread]](None)
          beforeL <- ZIO.descriptor.map(_.isLocked)
          _       <- thread.flatMap(t => which.set(Some(t))).toManaged.onExecutor(global).useDiscard(ZIO.unit)
          after   <- thread
          during  <- which.get.some
          afterL  <- ZIO.descriptor.map(_.isLocked)
        } yield assert(beforeL)(isFalse) && assert(afterL)(isFalse) && assert(during)(equalTo(after))
      }
    ),
    suite("mergeAll")(
      test("Merges elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.mergeAll(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(List(4, 3, 2, 1)))))
      },
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAll(List.fill(4)(res))(()) { case (_, b) => b })
      }
    ),
    suite("mergeAllPar")(
      test("Merges elements") {
        def res(int: Int) =
          ZManaged.succeed(int)

        val managed = ZManaged.mergeAllPar(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(hasSameElements(List(4, 3, 2, 1)))))
      },
      test("Runs reservations in parallel") {
        testReservePar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      },
      test("Runs acquisitions in parallel") {
        testAcquirePar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      },
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(()) { case (_, b) => b })
      }
    ),
    suite("mergeAllParN")(
      test("Merges elements") {
        def res(int: Int) =
          ZManaged.succeed(int)
        val managed =
          ZManaged.mergeAllPar(List(1, 2, 3, 4).map(res))(List[Int]()) { case (acc, a) => a :: acc }.withParallelism(2)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(hasSameElements(List(4, 3, 2, 1)))))
      },
      test("Uses at most n fibers for reservation") {
        testReservePar(2, res => ZManaged.mergeAllPar(List.fill(4)(res))(0) { case (a, _) => a }).withParallelism(2)
      },
      test("Uses at most n fibers for acquisition") {
        testAcquirePar(2, res => ZManaged.mergeAllPar(List.fill(4)(res))(0) { case (a, _) => a }).withParallelism(2)
      },
      test("Runs finalizers") {
        testFinalizersPar(4, res => ZManaged.mergeAllPar(List.fill(4)(res))(0) { case (a, _) => a }).withParallelism(2)
      },
      test("All finalizers run even when finalizers have defects") {
        for {
          releases <- Ref.make[Int](0)
          _ <- ZManaged
                 .mergeAllPar(
                   List(
                     ZManaged.finalizer(ZIO.dieMessage("Boom")),
                     ZManaged.finalizer(releases.update(_ + 1)),
                     ZManaged.finalizer(ZIO.dieMessage("Boom")),
                     ZManaged.finalizer(releases.update(_ + 1)),
                     ZManaged.finalizer(ZIO.dieMessage("Boom")),
                     ZManaged.finalizer(releases.update(_ + 1))
                   )
                 )(())((_, _) => ())
                 .useDiscard(ZIO.unit)
                 .exit
                 .withParallelism(2)
          count <- releases.get
        } yield assert(count)(equalTo(3))
      }
    ),
    suite("onExit")(
      test("Calls the cleanup") {
        for {
          finalizersRef <- Ref.make[List[String]](Nil)
          resultRef     <- Ref.make[Option[Exit[Nothing, String]]](None)
          _ <- ZManaged
                 .acquireReleaseWith(ZIO.succeed("42"))(_ => finalizersRef.update("First" :: _))
                 .onExit(e => finalizersRef.update("Second" :: _) *> resultRef.set(Some(e)))
                 .useDiscard(ZIO.unit)
          finalizers <- finalizersRef.get
          result     <- resultRef.get
        } yield assert(finalizers)(equalTo(List("Second", "First"))) && assert(result)(isSome(succeeds(equalTo("42"))))
      }
    ),
    suite("option")(
      test("return success in Some") {
        implicit val canFail = CanFail
        val managed          = ZManaged.succeed(11).option
        assertZIO(managed.useNow)(equalTo(Some(11)))
      },
      test("return failure as None") {
        val managed = ZManaged.fail(123).option
        managed.use(res => ZIO.succeed(assert(res)(equalTo(None))))
      } @@ zioTag(errors),
      test("not catch throwable") {
        implicit val canFail                                          = CanFail
        val managed: Managed[Nothing, Exit[Nothing, Option[Nothing]]] = ZManaged.die(ExampleError).option.exit
        managed.use(res => ZIO.succeed(assert(res)(dies(equalTo(ExampleError)))))
      } @@ zioTag(errors),
      test("catch throwable after sandboxing") {
        val managed: Managed[Nothing, Option[Nothing]] = ZManaged.die(ExampleError).sandbox.option
        managed.use(res => ZIO.succeed(assert(res)(equalTo(None))))
      } @@ zioTag(errors)
    ),
    suite("optional")(
      test("fails when given Some error") {
        val managed: UManaged[Exit[String, Option[Int]]] = ZManaged.fail(Some("Error")).unsome.exit
        managed.use(res => ZIO.succeed(assert(res)(fails(equalTo("Error")))))
      } @@ zioTag(errors),
      test("succeeds with None given None error") {
        val managed: Managed[String, Option[Int]] = ZManaged.fail(None).unsome
        managed.use(res => ZIO.succeed(assert(res)(isNone)))
      } @@ zioTag(errors),
      test("succeeds with Some given a value") {
        val managed: Managed[String, Option[Int]] = ZManaged.succeed(1).unsome
        assertZIO(managed.useNow)(isSome(equalTo(1)))
      }
    ),
    suite("onExitFirst")(
      test("Calls the cleanup") {
        for {
          finalizersRef <- Ref.make[List[String]](Nil)
          resultRef     <- Ref.make[Option[Exit[Nothing, String]]](None)
          _ <- ZManaged
                 .acquireReleaseWith(ZIO.succeed("42"))(_ => finalizersRef.update("First" :: _))
                 .onExitFirst(e => finalizersRef.update("Second" :: _) *> resultRef.set(Some(e)))
                 .useDiscard(ZIO.unit)
          finalizers <- finalizersRef.get
          result     <- resultRef.get
        } yield assert(finalizers)(equalTo(List("First", "Second"))) && assert(result)(isSome(succeeds(equalTo("42"))))
      }
    ),
    suite("orElseFail")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        val managed          = ZManaged.succeed(true).orElseFail(false)
        assertZIO(managed.use(ZIO.succeed(_)))(isTrue)
      },
      test("otherwise fails with the specified error") {
        val managed = ZManaged.fail(false).orElseFail(true).flip
        assertZIO(managed.use(ZIO.succeed(_)))(isTrue)
      }
    ) @@ zioTag(errors),
    suite("orElseSucceed")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        val managed          = ZManaged.succeed(true).orElseSucceed(false)
        assertZIO(managed.use(ZIO.succeed(_)))(isTrue)
      },
      test("otherwise succeeds with the specified value") {
        val managed = ZManaged.fail(false).orElseSucceed(true)
        assertZIO(managed.use(ZIO.succeed(_)))(isTrue)
      }
    ) @@ zioTag(errors),
    suite("preallocate")(
      test("runs finalizer on interruption") {
        for {
          ref    <- Ref.make(0)
          res     = ZManaged.fromReservation(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))
          _      <- res.preallocate.exit.ignore
          result <- assertZIO(ref.get)(equalTo(1))
        } yield result
      } @@ zioTag(interruption),
      test("runs finalizer when resource closes") {
        for {
          ref    <- Ref.make(0)
          res     = ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
          _      <- res.preallocate.flatMap(_.useDiscard(ZIO.unit))
          result <- assertZIO(ref.get)(equalTo(1))
        } yield result
      },
      test("propagates failures in acquire") {
        for {
          exit <- ZManaged.fromZIO(ZIO.fail("boom")).preallocate.either
        } yield assert(exit)(isLeft(equalTo("boom")))
      } @@ zioTag(errors),
      test("propagates failures in reserve") {
        for {
          exit <- ZManaged.acquireReleaseWith(ZIO.fail("boom"))(_ => ZIO.unit).preallocate.either
        } yield assert(exit)(isLeft(equalTo("boom")))
      } @@ zioTag(errors)
    ),
    suite("preallocateManaged")(
      test("run release on interrupt while entering inner scope") {
        Ref.make(0).flatMap { ref =>
          ZManaged
            .fromReservation(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))
            .preallocateManaged
            .useDiscard(ZIO.unit)
            .exit *> assertZIO(ref.get)(equalTo(1))
        }
      } @@ zioTag(interruption),
      test("eagerly run acquisition when preallocateManaged is invoked") {
        for {
          ref <- Ref.make(0)
          result <- ZManaged
                      .fromReservation(Reservation(ref.update(_ + 1), _ => ZIO.unit))
                      .preallocateManaged
                      .use(r => ref.get.zip(r.useDiscard(ref.get)))
        } yield assert(result)(equalTo((1, 1)))
      },
      test("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged
            .fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            .preallocateManaged
            .useDiscard(ZIO.unit) *> assertZIO(
            ref.get
          )(equalTo(1))
        }
      },
      test("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged
            .fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            .preallocateManaged
            .use(_.useDiscard(ZIO.unit)) *> assertZIO(ref.get)(equalTo(1))
        }
      }
    ),
    suite("reduceAll")(
      test("Reduces elements in the correct order") {
        def res(int: Int) =
          ZManaged.succeed(List(int))

        val managed = ZManaged.reduceAll(ZManaged.succeed(Nil), List(1, 2, 3, 4).map(res)) { case (a1, a2) =>
          a1 ++ a2
        }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(equalTo(List(1, 2, 3, 4)))))
      },
      test("Runs finalizers") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAll(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        )
      }
    ),
    suite("reduceAllPar")(
      test("Reduces elements") {
        def res(int: Int) =
          ZManaged.succeed(List(int))

        val managed = ZManaged.reduceAllPar(ZManaged.succeed(Nil), List(1, 2, 3, 4).map(res)) { case (a1, a2) =>
          a1 ++ a2
        }
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(hasSameElements(List(1, 2, 3, 4)))))
      },
      test("Runs reservations in parallel") {
        testReservePar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      test("Runs acquisitions in parallel") {
        testAcquirePar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        )
      },
      test("Runs finalizers") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        )
      }
    ),
    suite("reduceAllParN")(
      test("Reduces elements") {
        def res(int: Int) =
          ZManaged.succeed(List(int))

        val managed = ZManaged
          .reduceAllPar(ZManaged.succeed(Nil), List(1, 2, 3, 4).map(res)) { case (acc, a) =>
            a ++ acc
          }
          .withParallelism(2)
        managed.use[Any, Nothing, TestResult](res => ZIO.succeed(assert(res)(hasSameElements(List(4, 3, 2, 1)))))
      },
      test("Uses at most n fibers for reservation") {
        testFinalizersPar(
          4,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        ).withParallelism(2)
      },
      test("Uses at most n fibers for acquisition") {
        testReservePar(
          2,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        ).withParallelism(2)
      },
      test("Runs finalizers") {
        testAcquirePar(
          2,
          res => ZManaged.reduceAllPar(ZManaged.succeed(0), List.fill(4)(res)) { case (a, _) => a }
        ).withParallelism(2)
      },
      test("All finalizers run even when finalizers have defects") {
        for {
          releases <- Ref.make[Int](0)
          _ <- ZManaged
                 .reduceAllPar(
                   ZManaged.finalizer(ZIO.dieMessage("Boom")),
                   List(
                     ZManaged.finalizer(releases.update(_ + 1)),
                     ZManaged.finalizer(ZIO.dieMessage("Boom")),
                     ZManaged.finalizer(releases.update(_ + 1)),
                     ZManaged.finalizer(ZIO.dieMessage("Boom")),
                     ZManaged.finalizer(releases.update(_ + 1))
                   )
                 )((_, _) => ())
                 .useDiscard(ZIO.unit)
                 .exit
                 .withParallelism(2)
          count <- releases.get
        } yield assert(count)(equalTo(3))
      }
    ),
    suite("some")(
      test("extracts the value from Some") {
        val managed: Managed[Option[Throwable], Int] = ZManaged.succeed(Some(1)).some
        assertZIO(managed.useNow)(equalTo(1))
      },
      test("fails on None") {
        val managed: Managed[Option[Throwable], Int] = ZManaged.succeed(None).some
        managed.exit.use(res => ZIO.succeed(assert(res)(fails(isNone))))
      } @@ zioTag(errors),
      test("fails when given an exception") {
        val ex                                       = new RuntimeException("Failed Task")
        val managed: Managed[Option[Throwable], Int] = ZManaged.fail(ex).some
        managed.exit.use(res => ZIO.succeed(assert(res)(fails(isSome(equalTo(ex))))))
      } @@ zioTag(errors)
    ),
    suite("someOrElse")(
      test("extracts the value from Some") {
        val managed: TaskManaged[Int] = ZManaged.succeed(Some(1)).someOrElse(2)
        assertZIO(managed.useNow)(equalTo(1))
      },
      test("falls back to the default value if None") {
        val managed: TaskManaged[Int] = ZManaged.succeed(None).someOrElse(42)
        assertZIO(managed.useNow)(equalTo(42))
      },
      test("works when the output of the default is an instance of a covariant type constructor applied to Nothing") {
        val managed: TaskManaged[List[String]] =
          ZManaged.succeed(Option.empty[List[String]]).someOrElseManaged(ZManaged.succeed(List.empty))
        assertZIO(managed.useNow)(equalTo(List.empty))
      },
      test("does not change failed state") {
        val managed: TaskManaged[Int] = ZManaged.fail(ExampleError).someOrElse(42)
        managed.exit.use(res => ZIO.succeed(assert(res)(fails(equalTo(ExampleError)))))
      } @@ zioTag(errors)
    ),
    suite("someOrElseManaged")(
      test("extracts the value from Some") {
        val managed: TaskManaged[Int] = ZManaged.succeed(Some(1)).someOrElseManaged(ZManaged.succeed(2))
        assertZIO(managed.useNow)(equalTo(1))
      },
      test("falls back to the default value if None") {
        val managed: TaskManaged[Int] = ZManaged.succeed(None).someOrElseManaged(ZManaged.succeed(42))
        assertZIO(managed.useNow)(equalTo(42))
      },
      test("works when the default contains an instance of a covariant type constructor applied to Nothing") {
        val managed: TaskManaged[List[String]] = ZManaged.succeed(Option.empty[List[String]]).someOrElse(List.empty)
        assertZIO(managed.useNow)(equalTo(List.empty))
      },
      test("does not change failed state") {
        val managed: TaskManaged[Int] = ZManaged.fail(ExampleError).someOrElseManaged(ZManaged.succeed(42))
        managed.exit.use(res => ZIO.succeed(assert(res)(fails(equalTo(ExampleError)))))
      } @@ zioTag(errors)
    ),
    suite("someOrFailException")(
      test("extracts the optional value") {
        val managed = ZManaged.succeed(Some(42)).someOrFailException
        assertZIO(managed.useNow)(equalTo(42))
      },
      test("fails when given a None") {
        val managed = ZManaged.succeed(Option.empty[Int]).someOrFailException
        managed.exit.use(res => ZIO.succeed(assert(res)(fails(isSubtype[NoSuchElementException](anything)))))
      } @@ zioTag(errors),
      suite("without another error type")(
        test("succeed something") {
          val managed = ZManaged.succeed(Option(3)).someOrFailException
          assertZIO(managed.useNow)(equalTo(3))
        },
        test("succeed nothing") {
          val managed = ZManaged.succeed(None: Option[Int]).someOrFailException.exit
          managed.use(res => ZIO.succeed(assert(res)(fails(anything))))
        } @@ zioTag(errors)
      ),
      suite("with throwable as base error type")(
        test("return something") {
          val managed = ZManaged.succeed(Option(3)).someOrFailException
          assertZIO(managed.useNow)(equalTo(3))
        }
      ),
      suite("with exception as base error type")(
        test("return something") {
          val managed = (ZManaged.succeed(Option(3)): Managed[Exception, Option[Int]]).someOrFailException
          assertZIO(managed.useNow)(equalTo(3))
        }
      )
    ),
    suite("reject")(
      test("returns failure ignoring value") {
        val goodCase =
          ZManaged.succeed(0).reject { case v if v != 0 => "Partial failed!" }.sandbox.either

        val badCase = ZManaged
          .succeed(1)
          .reject { case v if v != 0 => "Partial failed!" }
          .sandbox
          .either
          .map(_.left.map(_.failureOrCause))

        for {
          goodCaseCheck <- goodCase.use(r => ZIO.succeed(assert(r)(isRight(equalTo(0)))))
          badCaseCheck  <- badCase.use(r => ZIO.succeed(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
        } yield goodCaseCheck && badCaseCheck
      }
    ) @@ zioTag(errors),
    suite("rejectManaged")(
      test("returns failure ignoring value") {
        val goodCase =
          ZManaged
            .succeed(0)
            .rejectManaged[Any, String] { case v if v != 0 => ZManaged.succeed("Partial failed!") }
            .sandbox
            .either

        val partialBadCase =
          ZManaged
            .succeed(1)
            .rejectManaged { case v if v != 0 => ZManaged.fail("Partial failed!") }
            .sandbox
            .either
            .map(_.left.map(_.failureOrCause))

        val badCase =
          ZManaged
            .succeed(1)
            .rejectManaged { case v if v != 0 => ZManaged.fail("Partial failed!") }
            .sandbox
            .either
            .map(_.left.map(_.failureOrCause))

        for {
          r1 <- goodCase.use(r => ZIO.succeed(assert(r)(isRight(equalTo(0)))))
          r2 <- partialBadCase.use(r => ZIO.succeed(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
          r3 <- badCase.use(r => ZIO.succeed(assert(r)(isLeft(isLeft(equalTo("Partial failed!"))))))
        } yield r1 && r2 && r3
      }
    ) @@ zioTag(errors),
    suite("release")(
      test("closes the scope") {
        val expected = Chunk("acquiring a", "acquiring b", "releasing b", "acquiring c", "releasing c", "releasing a")
        for {
          ref    <- Ref.make[Chunk[String]](Chunk.empty)
          a       = ZManaged.acquireReleaseWith(ref.update(_ :+ "acquiring a"))(_ => ref.update(_ :+ "releasing a"))
          b       = ZManaged.acquireReleaseWith(ref.update(_ :+ "acquiring b"))(_ => ref.update(_ :+ "releasing b"))
          c       = ZManaged.acquireReleaseWith(ref.update(_ :+ "acquiring c"))(_ => ref.update(_ :+ "releasing c"))
          managed = a *> b.release *> c
          _      <- managed.useNow
          log    <- ref.get
        } yield assert(log)(equalTo(expected))
      }
    ),
    suite("retry")(
      test("Should retry the reservation") {
        for {
          retries <- Ref.make(0)
          program =
            ZManaged
              .acquireReleaseWith(retries.updateAndGet(_ + 1).flatMap(r => if (r == 3) ZIO.unit else ZIO.fail(())))(_ =>
                ZIO.unit
              )
          _ <- program.retry(Schedule.recurs(3)).use(_ => ZIO.unit).ignore
          r <- retries.get
        } yield assert(r)(equalTo(3))
      },
      test("Should retry the acquisition") {
        for {
          retries <- Ref.make(0)
          program = ZManaged.fromReservation(
                      Reservation(
                        retries.updateAndGet(_ + 1).flatMap(r => if (r == 3) ZIO.unit else ZIO.fail(())),
                        _ => ZIO.unit
                      )
                    )
          _ <- program.retry(Schedule.recurs(3)).use(_ => ZIO.unit).ignore
          r <- retries.get
        } yield assert(r)(equalTo(3))
      }
    ) @@ zioTag(errors),
    suite("preallocationScope")(
      test("runs finalizer on interruption") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            preallocate(ZManaged.fromReservation(Reservation(ZIO.interrupt, _ => ref.update(_ + 1)))).exit.ignore
          } *> assertZIO(ref.get)(equalTo(1))
        }
      } @@ zioTag(interruption),
      test("runs finalizer when resource closes") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            ref    <- Ref.make(0)
            res     = ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            _      <- preallocate(res).flatMap(_.useDiscard(ZIO.unit))
            result <- assertZIO(ref.get)(equalTo(1))
          } yield result
        }
      },
      test("propagates failures in acquire") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            exit <- preallocate(ZManaged.fromZIO(ZIO.fail("boom"))).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      test("propagates failures in reserve") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            exit <- preallocate(ZManaged.acquireReleaseWith(ZIO.fail("boom"))(_ => ZIO.unit)).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      test("eagerly run acquisition when preallocate is invoked") {
        ZManaged.preallocationScope.use { preallocate =>
          for {
            ref <- Ref.make(0)
            res <- preallocate(ZManaged.fromReservation(Reservation(ref.update(_ + 1), _ => ZIO.unit)))
            r1  <- ref.get
            _   <- res.useDiscard(ZIO.unit)
            r2  <- ref.get
          } yield assert(r1)(equalTo(1)) && assert(r2)(equalTo(1))
        }
      },
      test("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            preallocate(ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1))))
          } *> assertZIO(ref.get)(equalTo(1))
        }
      },
      test("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            preallocate(ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1))))
              .flatMap(_.useDiscard(ZIO.unit))
          } *> assertZIO(ref.get)(equalTo(1))
        }
      },
      test("can be used multiple times") {
        Ref.make(0).flatMap { ref =>
          ZManaged.preallocationScope.use { preallocate =>
            val res = ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            preallocate(res) *> preallocate(res)
          } *> assertZIO(ref.get)(equalTo(2))
        }
      }
    ),
    suite("scope")(
      test("runs finalizer on interruption") {
        for {
          ref    <- Ref.make(0)
          managed = makeTestManaged(ref)
          zio     = ZManaged.scope.use(scope => scope(managed).fork.flatMap(_.join))
          fiber  <- zio.fork
          _      <- fiber.interrupt
          result <- ref.get
        } yield assert(result)(equalTo(0))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("runs finalizer when close is called") {
        ZManaged.scope.use { scope =>
          for {
            ref <- Ref.make(0)
            res  = ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            result <- scope(res).flatMap { case (close, _) =>
                        for {
                          res1 <- ref.get
                          _    <- close(Exit.unit)
                          res2 <- ref.get
                        } yield (res1, res2)
                      }
          } yield assert(result)(equalTo((0, 1)))
        }
      },
      test("propagates failures in acquire") {
        ZManaged.scope.use { scope =>
          for {
            exit <- scope(ZManaged.fromZIO(ZIO.fail("boom"))).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      test("propagates failures in reserve") {
        ZManaged.scope.use { scope =>
          for {
            exit <- scope(ZManaged.acquireReleaseWith(ZIO.fail("boom"))(_ => ZIO.unit)).either
          } yield assert(exit)(isLeft(equalTo("boom")))
        }
      } @@ zioTag(errors),
      test("run release on scope exit") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            scope(ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1))))
          } *> assertZIO(ref.get)(equalTo(1))
        }
      },
      test("don't run release twice") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            scope(ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))).flatMap(_._1(Exit.unit))
          } *> assertZIO(ref.get)(equalTo(1))
        }
      },
      test("can be used multiple times") {
        Ref.make(0).flatMap { ref =>
          ZManaged.scope.use { scope =>
            val res = ZManaged.fromReservation(Reservation(ZIO.unit, _ => ref.update(_ + 1)))
            scope(res) *> scope(res)
          } *> assertZIO(ref.get)(equalTo(2))
        }
      }
    ),
    suite("tap")(
      test("Doesn't change the managed resource") {
        ZManaged
          .succeed(1)
          .tap(n => ZManaged.succeed(n + 1))
          .map(actual => assert(1)(equalTo(actual)))
          .useNow
      },
      test("Runs given effect") {
        Ref
          .make(0)
          .toManaged
          .tap(_.update(_ + 1).toManaged)
          .mapZIO(_.get)
          .map(i => assert(i)(equalTo(1)))
          .useNow
      }
    ),
    suite("tapBoth")(
      test("Doesn't change the managed resource") {
        ZManaged
          .fromEither(Right[String, Int](1))
          .tapBoth(_ => ZManaged.unit, n => ZManaged.succeed(n + 1))
          .map(actual => assert(1)(equalTo(actual)))
          .useNow
      },
      test("Runs given effect on failure") {
        (
          for {
            ref <- Ref.make(0).toManaged
            _ <- ZManaged
                   .fromEither(Left(1))
                   .tapBoth(e => ref.update(_ + e).toManaged, (_: Any) => ZManaged.unit)
            actual <- ref.get.toManaged
          } yield assert(actual)(equalTo(2))
        ).fold(e => assert(e)(equalTo(1)), identity).useNow
      } @@ zioTag(errors),
      test("Runs given effect on success") {
        (
          for {
            ref <- Ref.make(1).toManaged
            _ <- ZManaged
                   .fromEither(Right[String, Int](2))
                   .tapBoth(_ => ZManaged.unit, n => ref.update(_ + n).toManaged)
            actual <- ref.get.toManaged
          } yield assert(actual)(equalTo(3))
        ).useNow
      }
    ),
    suite("tapErrorCause")(
      test("effectually peeks at the cause of the failure of the acquired resource") {
        (for {
          ref    <- Ref.make(false).toManaged
          result <- ZManaged.dieMessage("die").tapErrorCause(_ => ref.set(true).toManaged).exit
          effect <- ref.get.toManaged
        } yield assert(result)(dies(hasMessage(equalTo("die")))) &&
          assert(effect)(isTrue)).useNow
      }
    ) @@ zioTag(errors),
    suite("tapError")(
      test("Doesn't change the managed resource") {
        ZManaged
          .fromEither(Right[String, Int](1))
          .tapError(str => ZManaged.succeed(str.length))
          .map(actual => assert(1)(equalTo(actual)))
          .useNow
      },
      test("Runs given effect on failure") {
        (
          for {
            ref <- Ref.make(0).toManaged
            _ <- ZManaged
                   .fromEither(Left(1))
                   .tapError(e => ref.update(_ + e).toManaged)
            actual <- ref.get.toManaged
          } yield assert(actual)(equalTo(2))
        ).fold(e => assert(e)(equalTo(1)), identity).useNow
      } @@ zioTag(errors),
      test("Doesn't run given effect on success") {
        (
          for {
            ref <- Ref.make(1).toManaged
            _ <- ZManaged
                   .fromEither(Right[Int, Int](2))
                   .tapError(n => ref.update(_ + n).toManaged)
            actual <- ref.get.toManaged
          } yield assert(actual)(equalTo(1))
        ).useNow
      }
    ),
    suite("timed")(
      test("Should time both the reservation and the acquisition") {
        val managed = ZManaged.fromReservationZIO(
          Clock.sleep(20.milliseconds) *> ZIO.succeed(Reservation(Clock.sleep(20.milliseconds), _ => ZIO.unit))
        )
        val test = managed.timed.use { case (duration, _) =>
          ZIO.succeed(assert(duration.toNanos)(isGreaterThanEqualTo(40.milliseconds.toNanos)))
        }
        for {
          f      <- test.fork
          _      <- TestClock.adjust(40.milliseconds)
          result <- f.join
        } yield result
      }
    ),
    suite("timeout")(
      test("Returns Some if the timeout isn't reached") {
        val managed = ZManaged.acquireReleaseWith(ZIO.succeed(1))(_ => ZIO.unit)
        managed.timeout(Duration.Infinity).use(res => ZIO.succeed(assert(res)(isSome(equalTo(1)))))
      },
      test("Returns None if the reservation takes longer than d") {
        for {
          latch  <- Promise.make[Nothing, Unit]
          managed = ZManaged.acquireReleaseWith(latch.await)(_ => ZIO.unit)
          res    <- managed.timeout(Duration.Zero).use(res => ZIO.succeed(assert(res)(isNone)))
          _      <- latch.succeed(())
        } yield res
      },
      test("Returns None if the acquisition takes longer than d") {
        for {
          latch  <- Promise.make[Nothing, Unit]
          managed = ZManaged.fromReservation(Reservation(latch.await, _ => ZIO.unit))
          res    <- managed.timeout(Duration.Zero).use(res => ZIO.succeed(assert(res)(isNone)))
          _      <- latch.succeed(())
        } yield res
      },
      test("Runs finalizers if returning None and reservation is successful") {
        for {
          releaseLatch <- Promise.make[Nothing, Unit]
          managed       = ZManaged.fromReservation(Reservation(TestClock.adjust(1.minute), _ => releaseLatch.succeed(())))
          res          <- managed.timeout(1.minute).use(ZIO.succeed(_))
          _            <- releaseLatch.await
        } yield assert(res)(isNone)
      },
      test("Runs finalizers if returning None and reservation is successful after timeout") {
        for {
          releaseLatch <- Promise.make[Nothing, Unit]
          managed = ZManaged.fromReservationZIO(
                      TestClock.adjust(1.minute) *> ZIO.succeed(Reservation(ZIO.unit, _ => releaseLatch.succeed(())))
                    )
          res <- managed.timeout(1.minute).use(ZIO.succeed(_))
          _   <- releaseLatch.await
        } yield assert(res)(isNone)
      }
    ),
    suite("withEarlyRelease")(
      test("Provides a canceler that can be used to eagerly evaluate the finalizer") {
        for {
          ref    <- Ref.make(false)
          managed = ZManaged.acquireReleaseWith(ZIO.unit)(_ => ref.set(true)).withEarlyRelease
          result <- managed.use { case (canceler, _) =>
                      canceler *> ref.get
                    }
        } yield assert(result)(isTrue)
      },
      test("The canceler should run uninterruptibly") {
        for {
          ref   <- Ref.make(true)
          latch <- Promise.make[Nothing, Unit]
          managed =
            ZManaged.acquireReleaseWith(ZIO.unit)(_ => latch.succeed(()) *> ZIO.never.whenZIO(ref.get)).withEarlyRelease
          result <- managed.use { case (canceler, _) =>
                      for {
                        fiber        <- canceler.forkDaemon
                        _            <- latch.await
                        interruption <- withLive(fiber.interrupt)(_.timeout(5.seconds))
                        _            <- ref.set(false)
                      } yield interruption
                    }
        } yield assert(result)(isNone)
      } @@ zioTag(interruption),
      test("If completed, the canceler should cause the regular finalizer to not run") {
        for {
          latch  <- Promise.make[Nothing, Unit]
          ref    <- Ref.make(0)
          managed = ZManaged.acquireReleaseWith(ZIO.unit)(_ => ref.update(_ + 1)).withEarlyRelease
          _      <- managed.use(_._1).ensuring(latch.succeed(()))
          _      <- latch.await
          result <- ref.get
        } yield assert(result)(equalTo(1))
      },
      test("The canceler will run with an exit value indicating the effect was interrupted") {
        for {
          ref    <- Ref.make(false)
          managed = ZManaged.acquireReleaseExitWith(ZIO.unit)((_, e) => ref.set(e.isInterrupted))
          _      <- managed.withEarlyRelease.use(_._1)
          result <- ref.get
        } yield assert(result)(isTrue)
      },
      test("The canceler disposes of all resources on a composite ZManaged") {
        for {
          ref      <- Ref.make(List[String]())
          managed   = (label: String) => ZManaged.finalizer(ref.update(label :: _))
          composite = (managed("1") *> managed("2") *> managed("3")).withEarlyRelease
          testResult <- composite.use { case (release, _) =>
                          release *>
                            ref.get.map(l => assert(l)(equalTo(List("1", "2", "3"))))
                        }
        } yield testResult
      }
    ) @@ zioTag(interruption),
    suite("withEarlyReleaseExit")(
      test("Allows specifying an exit value") {
        for {
          ref    <- Ref.make(false)
          managed = ZManaged.acquireReleaseExitWith(ZIO.unit)((_, e) => ref.set(e.isSuccess))
          _      <- managed.withEarlyReleaseExit(Exit.unit).use(_._1)
          result <- ref.get
        } yield assert(result)(isTrue)
      }
    ),
    suite("zipPar")(
      test("Does not swallow exit cause if one reservation fails") {
        (for {
          latch <- Promise.make[Nothing, Unit]
          first  = ZManaged.fromZIO(latch.succeed(()) *> ZIO.sleep(Duration.Infinity))
          second = ZManaged.fromZIO(latch.await *> ZIO.fail(()))
          _     <- first.zipPar(second).useDiscard(ZIO.unit)
        } yield ()).exit
          .map(assert(_)(fails(equalTo(()))))
      } @@ zioTag(errors),
      test("Runs finalizers if one acquisition fails") {
        for {
          releases <- Ref.make(0)
          first     = ZManaged.unit
          second    = ZManaged.fromReservation(Reservation(ZIO.fail(()), _ => releases.update(_ + 1)))
          _        <- first.zipPar(second).use(_ => ZIO.unit).ignore
          r        <- releases.get
        } yield assert(r)(equalTo(1))
      } @@ zioTag(errors),
      test("Does not swallow acquisition if one acquisition fails") {
        for {
          selfId <- ZIO.fiberId
          latch  <- Promise.make[Nothing, Unit]
          first   = ZManaged.fromZIO(latch.succeed(()) *> ZIO.sleep(Duration.Infinity))
          second  = ZManaged.fromReservation(Reservation(latch.await *> ZIO.fail(()), _ => ZIO.unit))
          exit   <- first.zipPar(second).useDiscard(ZIO.unit).exit
        } yield assert(exit)(failsCause(containsCause(Cause.fail((), StackTrace.none)))) &&
          assert(exit)(failsCause(containsCause(Cause.interrupt(selfId))))
      } @@ zioTag(errors),
      test("Run finalizers if one reservation fails") {
        for {
          reserveLatch <- Promise.make[Nothing, Unit]
          releases     <- Ref.make[Int](0)
          first         = ZManaged.fromReservation(Reservation(reserveLatch.succeed(()), _ => releases.update(_ + 1)))
          second        = ZManaged.fromZIO(reserveLatch.await *> ZIO.fail(()))
          _            <- first.zipPar(second).useDiscard(ZIO.unit).orElse(ZIO.unit)
          count        <- releases.get
        } yield assert(count)(equalTo(1))
      } @@ zioTag(errors),
      test("Runs finalizers if it is interrupted") {
        for {
          ref1    <- Ref.make(0)
          ref2    <- Ref.make(0)
          managed1 = makeTestManaged(ref1)
          managed2 = makeTestManaged(ref2)
          managed3 = managed1 <&> managed2
          fiber   <- managed3.useDiscard(ZIO.unit).fork
          _       <- fiber.interrupt
          result1 <- ref1.get
          result2 <- ref2.get
        } yield assert(result1)(equalTo(0)) && assert(result2)(equalTo(0))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky)
    ),
    suite("flatten")(
      test("Returns the same as ZManaged.flatten") {
        check(Gen.string(Gen.alphaNumericChar)) { str =>
          val test = for {
            flatten1 <- ZManaged.succeed(ZManaged.succeed(str)).flatten
            flatten2 <- ZManaged.flatten(ZManaged.succeed(ZManaged.succeed(str)))
          } yield assert(flatten1)(equalTo(flatten2))
          test.use[Any, Nothing, TestResult](r => ZIO.succeed(r))
        }
      }
    ),
    suite("absolve")(
      test("Returns the same as ZManaged.absolve") {
        check(Gen.string(Gen.alphaNumericChar)) { str =>
          val managedEither: ZManaged[Any, Nothing, Either[Nothing, String]] = ZManaged.succeed(Right(str))
          val test = for {
            abs1 <- managedEither.absolve
            abs2 <- ZManaged.absolve(managedEither)
          } yield assert(abs1)(equalTo(abs2))
          test.use[Any, Nothing, TestResult](result => ZIO.succeed(result))
        }
      }
    ),
    suite("switchable")(
      test("runs the right finalizer on interruption") {
        for {
          effects <- Ref.make(List[String]())
          latch   <- Promise.make[Nothing, Unit]
          fib <- ZManaged
                   .switchable[Any, Nothing, Unit]
                   .use { switch =>
                     switch(ZManaged.finalizer(effects.update("First" :: _))) *>
                       switch(ZManaged.finalizer(effects.update("Second" :: _))) *>
                       latch.succeed(()) *>
                       ZIO.never
                   }
                   .fork
          _      <- latch.await
          _      <- fib.interrupt
          result <- effects.get
        } yield assert(result)(equalTo(List("Second", "First")))
      } @@ zioTag(interruption)
    ),
    suite("memoize")(
      test("acquires and releases exactly once") {
        for {
          effects <- Ref.make[List[Int]](Nil)
          res      = (x: Int) => ZManaged.acquireReleaseWith(effects.update(x :: _))(_ => effects.update(x :: _))
          program  = res(1) *> res(2) *> res(3)
          memoized = program.memoize
          _ <- memoized.use { managed =>
                 val use = managed.useDiscard(ZIO.unit)
                 use *> use *> use
               }
          res <- effects.get
        } yield assert(res)(equalTo(List(1, 2, 3, 3, 2, 1)))
      },
      test("acquires and releases nothing if the inner managed is never used") {
        for {
          acquired <- Ref.make(false)
          released <- Ref.make(false)
          managed   = ZManaged.acquireReleaseWith(acquired.set(true))(_ => released.set(true))
          _        <- managed.memoize.useDiscard(ZIO.unit)
          res      <- assertZIO(acquired.get zip released.get)(equalTo((false, false)))
        } yield res
      },
      test("behaves like an ordinary ZManaged if flattened") {
        for {
          resource <- Ref.make(0)
          acquire   = resource.update(_ + 1)
          release   = resource.update(_ - 1)
          managed   = ZManaged.acquireReleaseWith(acquire)(_ => release).memoize.flatten
          res1     <- managed.useDiscard(assertZIO(resource.get)(equalTo(1)))
          res2     <- assertZIO(resource.get)(equalTo(0))
        } yield res1 && res2
      },
      test("properly raises an error if acquiring fails") {
        for {
          released <- Ref.make(false)
          error     = ":-o"
          managed   = ZManaged.acquireReleaseWith(ZIO.fail(error))(_ => released.set(true))
          res1 <- managed.memoize.use { memoized =>
                    for {
                      v1 <- memoized.useDiscard(ZIO.unit).either
                      v2 <- memoized.useDiscard(ZIO.unit).either
                    } yield assert(v1)(equalTo(v2)) && assert(v1)(isLeft(equalTo(error)))
                  }
          res2 <- assertZIO(released.get)(isFalse)
        } yield res1 && res2
      } @@ zioTag(errors),
      test("behaves properly if acquiring dies") {
        for {
          released <- Ref.make(false)
          ohNoes    = ";-0"
          managed   = ZManaged.acquireReleaseWith(ZIO.dieMessage(ohNoes))(_ => released.set(true))
          res1 <- managed.memoize.use { memoized =>
                    assertZIO(memoized.useDiscard(ZIO.unit).exit)(dies(hasMessage(equalTo(ohNoes))))
                  }
          res2 <- assertZIO(released.get)(isFalse)
        } yield res1 && res2
      },
      test("behaves properly if releasing dies") {
        val myBad   = "#@*!"
        val managed = ZManaged.acquireReleaseWith(ZIO.unit)(_ => ZIO.dieMessage(myBad))

        val program = managed.memoize.use(memoized => memoized.useDiscard(ZIO.unit))

        assertZIO(program.exit)(dies(hasMessage(equalTo(myBad))))
      },
      test("behaves properly if use dies") {
        val darn = "darn"
        for {
          latch    <- Promise.make[Nothing, Unit]
          released <- Ref.make(false)
          managed   = ZManaged.acquireReleaseWith(ZIO.unit)(_ => released.set(true) *> latch.succeed(()))
          v1       <- managed.memoize.use(memoized => memoized.useDiscard(ZIO.dieMessage(darn))).exit
          v2       <- released.get
        } yield assert(v1)(dies(hasMessage(equalTo(darn)))) && assert(v2)(isTrue)
      },
      test("behaves properly if use is interrupted") {
        for {
          latch1   <- Promise.make[Nothing, Unit]
          latch2   <- Promise.make[Nothing, Unit]
          latch3   <- Promise.make[Nothing, Unit]
          resource <- Ref.make(0)
          acquire   = resource.update(_ + 1)
          release   = resource.update(_ - 1) *> latch3.succeed(())
          managed   = ZManaged.acquireReleaseWith(acquire)(_ => release)
          fiber    <- managed.memoize.use(memoized => memoized.useDiscard(latch1.succeed(()) *> latch2.await)).fork
          _        <- latch1.await
          res1     <- assertZIO(resource.get)(equalTo(1))
          _        <- fiber.interrupt
          _        <- latch3.await
          res2     <- assertZIO(resource.get)(equalTo(0))
          res3     <- assertZIO(latch2.isDone)(isFalse)
        } yield res1 && res2 && res3
      } @@ zioTag(interruption)
    ),
    suite("memoize")(
      test("resources are properly acquired and released") {
        for {
          ref <- Ref.make[Map[Int, (Int, Int)]](Map.empty)
          acquire = (n: Int) =>
                      ref.update { map =>
                        map.getOrElse(n, (0, 0)) match {
                          case (acquired, released) => map.updated(n, ((acquired + 1, released)))
                        }
                      }
          release = (n: Int) =>
                      ref.update { map =>
                        map.getOrElse(n, (0, 0)) match {
                          case (acquired, released) => map.updated(n, ((acquired, released + 1)))
                        }
                      }
          managed = (n: Int) => ZManaged.acquireRelease(acquire(n))(release(n))
          _ <- ZManaged.memoize(managed).use { memoized =>
                 ZIO.foreachParDiscard(0 to 100)(n => memoized(n % 8))
               }
          map <- ref.get
        } yield assert(map.keys)(equalTo(Set(0, 1, 2, 3, 4, 5, 6, 7))) &&
          assert(map.values.map(_._1))(forall(equalTo(1))) &&
          assert(map.values.map(_._2))(forall(equalTo(1)))
      },
      test("resources are properly released in the event of interruption") {
        for {
          ref <- Ref.make[Map[Int, (Int, Int)]](Map.empty)
          acquire = (n: Int) =>
                      ref.update { map =>
                        map.getOrElse(n, (0, 0)) match {
                          case (acquired, released) => map.updated(n, ((acquired + 1, released)))
                        }
                      }
          release = (n: Int) =>
                      ref.update { map =>
                        map.getOrElse(n, (0, 0)) match {
                          case (acquired, released) => map.updated(n, ((acquired, released + 1)))
                        }
                      }
          managed = (n: Int) => ZManaged.acquireRelease(acquire(n))(release(n))
          fiber <- ZManaged
                     .memoize(managed)
                     .use { memoized =>
                       ZIO.foreachParDiscard(0 to 100)(n => memoized(n % 8) *> ZIO.never)
                     }
                     .fork
          _   <- fiber.interrupt
          map <- ref.get
        } yield assert(map.values.map(_._1))(forall(equalTo(1))) &&
          assert(map.values.map(_._2))(forall(equalTo(1)))
      }
    ),
    suite("merge")(
      test("on flipped result") {
        val managed: Managed[Int, Int] = ZManaged.succeed(1)

        for {
          a <- managed.merge.use(ZIO.succeed(_))
          b <- managed.flip.merge.use(ZIO.succeed(_))
        } yield assert(a)(equalTo(b))
      }
    ),
    suite("catch")(
      test("catchAllCause") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeed("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchAllCause(c => ZManaged.succeed(c.failureOption.getOrElse(c.toString)))
        assertZIO(errorToVal.use(ZIO.succeed(_)))(equalTo("Uh oh!"))
      },
      test("catchAllSomeCause transforms cause if matched") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeed("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchSomeCause { case Cause.Fail("Uh oh!", _) =>
          ZManaged.succeed("matched")
        }
        assertZIO(errorToVal.use(ZIO.succeed(_)))(equalTo("matched"))
      } @@ zioTag(errors),
      test("catchAllSomeCause keeps the failure cause if not matched") {
        val zm: ZManaged[Any, String, String] =
          for {
            _ <- ZManaged.succeed("foo")
            f <- ZManaged.fail("Uh oh!")
          } yield f

        val errorToVal = zm.catchSomeCause { case Cause.Fail("not matched", _) =>
          ZManaged.succeed("matched")
        }
        val executed = errorToVal.use[Any, String, String](ZIO.succeed(_)).exit
        assertZIO(executed)(fails(equalTo("Uh oh!")))
      } @@ zioTag(errors)
    ),
    suite("collect")(
      test("collectManaged maps value, if PF matched") {
        val managed = ZManaged.succeed(42).collectManaged("Oh No!") { case 42 =>
          ZManaged.succeed(84)
        }
        val effect: IO[String, Int] = managed.use(ZIO.succeed(_))

        assertZIO(effect)(equalTo(84))
      },
      test("collectManaged produces given error, if PF not matched") {
        val managed = ZManaged.succeed(42).collectManaged("Oh No!") { case 43 =>
          ZManaged.succeed(84)
        }
        val effect: IO[String, Int] = managed.use(ZIO.succeed(_))

        assertZIO(effect.exit)(fails(equalTo("Oh No!")))
      }
    ),
    suite("ReleaseMap")(
      test("sequential release works when empty") {
        ReleaseMap.make.flatMap(_.releaseAll(Exit.unit, ExecutionStrategy.Sequential)).as(assertCompletes)
      },
      test("runs all finalizers in the presence of defects") {
        Ref.make(List[Int]()).flatMap { ref =>
          ReleaseMap.make.flatMap { releaseMap =>
            releaseMap.add(_ => ref.update(1 :: _)) *>
              releaseMap.add(_ => ZIO.dieMessage("boom")) *>
              releaseMap.add(_ => ref.update(3 :: _)) *>
              releaseMap.releaseAll(Exit.unit, ExecutionStrategy.Sequential)
          }.exit *>
            ref.get.map(assert(_)(equalTo(List(1, 3))))
        }
      }
    ),
    suite("refineToOrDie")(
      test("does not compile when refine type is not a subtype of error type") {
        val result = typeCheck {
          """
          ZIO
            .fail(new RuntimeException("BOO!"))
            .refineToOrDie[Error]
            """
        }
        val expected =
          "type arguments [Error] do not conform to method refineToOrDie's type parameter bounds [E1 <: RuntimeException]"
        assertZIO(result)(isLeft(equalTo(expected)))
      } @@ scala2Only
    ),
    suite("ignoreReleaseFailures")(
      test("preserves acquire failures") {
        for {
          exit <-
            ZManaged.acquireRelease(ZIO.fail(2))(ZIO.dieMessage("die")).ignoreReleaseFailures.use(_ => ZIO.unit).exit
        } yield assert(exit)(fails(equalTo(2)))
      },
      test("preserves use failures") {
        for {
          exit <-
            ZManaged
              .acquireRelease(ZIO.succeed(2))(ZIO.dieMessage("die"))
              .ignoreReleaseFailures
              .use(n => ZIO.fail(n + 3))
              .exit
        } yield assert(exit)(fails(equalTo(5)))
      },
      test("ignores release failures") {
        for {
          exit <-
            ZManaged
              .acquireRelease(ZIO.succeed(2))(ZIO.dieMessage("die"))
              .ignoreReleaseFailures
              .use(n => ZIO.succeed(n + 3))
              .exit
        } yield assert(exit)(succeeds(equalTo(5)))
      }
    ),
    suite("from")(
      test("Attempt") {
        trait A
        lazy val a: A                                  = ???
        lazy val actual                                = ZManaged.from(a)
        lazy val expected: ZManaged[Any, Throwable, A] = actual
        lazy val _                                     = expected
        assertCompletes
      },
      test("Either") {
        trait E
        trait A
        lazy val either: Either[E, A]          = ???
        lazy val actual                        = ZManaged.from(either)
        lazy val expected: ZManaged[Any, E, A] = actual
        lazy val _                             = expected
        assertCompletes
      },
      test("EitherLeft") {
        trait E
        trait A
        lazy val eitherLeft: Left[E, A]        = ???
        lazy val actual                        = ZManaged.from(eitherLeft)
        lazy val expected: ZManaged[Any, E, A] = actual
        lazy val _                             = expected
        assertCompletes
      },
      test("EitherRight") {
        trait E
        trait A
        lazy val eitherRight: Right[E, A]      = ???
        lazy val actual                        = ZManaged.from(eitherRight)
        lazy val expected: ZManaged[Any, E, A] = actual
        lazy val _                             = expected
        assertCompletes
      },
      test("Option") {
        trait A
        lazy val option: Option[A]                           = ???
        lazy val actual                                      = ZManaged.from(option)
        lazy val expected: ZManaged[Any, Option[Nothing], A] = actual
        lazy val _                                           = expected
        assertCompletes
      },
      test("OptionNone") {
        lazy val optionNone: None.type                             = ???
        lazy val actual                                            = ZManaged.from(optionNone)
        lazy val expected: ZManaged[Any, Option[Nothing], Nothing] = actual
        lazy val _                                                 = expected
        assertCompletes
      },
      test("OptionSome") {
        trait A
        lazy val optionSome: Some[A]                         = ???
        lazy val actual                                      = ZManaged.from(optionSome)
        lazy val expected: ZManaged[Any, Option[Nothing], A] = actual
        lazy val _                                           = expected
        assertCompletes
      },
      test("Reservation") {
        trait R
        trait E
        trait A
        lazy val reservation: Reservation[R, E, A] = ???
        lazy val actual                            = ZManaged.from(reservation)
        lazy val expected: ZManaged[R, E, A]       = actual
        lazy val _                                 = expected
        assertCompletes
      },
      test("ReservationZIO") {
        trait R1
        trait R2
        trait R extends R1 with R2
        trait E
        trait E1 extends E
        trait E2 extends E
        trait A
        lazy val reservationZIO: ZIO[R1, E1, Reservation[R2, E2, A]] = ???
        lazy val actual                                              = ZManaged.from(reservationZIO)
        lazy val expected: ZManaged[R, E, A]                         = actual
        lazy val _                                                   = expected
        assertCompletes
      },
      test("Try") {
        trait A
        lazy val tryScala: scala.util.Try[A]           = ???
        lazy val actual                                = ZManaged.from(tryScala)
        lazy val expected: ZManaged[Any, Throwable, A] = actual
        lazy val _                                     = expected
        assertCompletes
      },
      test("TryFailure") {
        trait A
        lazy val tryFailure: scala.util.Failure[A]     = ???
        lazy val actual                                = ZManaged.from(tryFailure)
        lazy val expected: ZManaged[Any, Throwable, A] = actual
        lazy val _                                     = expected
        assertCompletes
      },
      test("TrySuccess") {
        trait A
        lazy val trySuccess: scala.util.Success[A]     = ???
        lazy val actual                                = ZManaged.from(trySuccess)
        lazy val expected: ZManaged[Any, Throwable, A] = actual
        lazy val _                                     = expected
        assertCompletes
      },
      test("ZIO") {
        trait R
        trait E
        trait A
        lazy val zio: ZIO[R, E, A]           = ???
        lazy val actual                      = ZManaged.from(zio)
        lazy val expected: ZManaged[R, E, A] = actual
        lazy val _                           = expected
        assertCompletes
      }
    ),
    test("scoped") {
      for {
        startLatch <- Promise.make[Nothing, Unit]
        endLatch   <- Promise.make[Nothing, Unit]
        release    <- Ref.make(false)
        managed = ZManaged.fromReservation(
                    Reservation(
                      acquire = startLatch.succeed(()) *> ZIO.never,
                      release = _ => release.set(true) *> endLatch.succeed(())
                    )
                  )
        scoped = managed.scoped
        fiber <- ZIO.scoped(scoped).fork
        _     <- startLatch.await
        _     <- fiber.interrupt
        _     <- endLatch.await
        res   <- release.get
      } yield assertTrue(res)
    },
    suite("toManaged")(
      test("preserve lifecycle of a layer converted to a managed value") {
        def sideEffectingLayer(ref: Ref[Int]): ZLayer[Any, Nothing, String] =
          ZLayer.scoped {
            ZIO.scopeWith(_.addFinalizerExit(_ => ref.update(_ + 2)))
          } >>>
            ZLayer(ref.update(_ + 1).as("Howdy"))

        for {
          ref <- Ref.make(0)
          res1 <- sideEffectingLayer(ref).toManaged.use { env =>
                    ref.get.map(_ -> env.get[String])
                  }
          res2 <- ref.get
        } yield assertTrue(res1._1 == 1) && assertTrue(res1._2 == "Howdy") && assertTrue(res2 == 3)
      }
    )
  )

  val ExampleError = new Throwable("Oh noes!")

  val ZManagedExampleError: ZManaged[Any, Throwable, Int] = ZManaged.fail[Throwable](ExampleError)

  val ZManagedExampleDie: ZManaged[Any, Throwable, Int] = ZManaged.succeed(throw ExampleError)

  def countDownLatch(n: Int): UIO[UIO[Unit]] =
    Ref.make(n).map { counter =>
      counter.update(_ - 1) *> {
        def await: UIO[Unit] = counter.get.flatMap { n =>
          if (n <= 0) ZIO.unit
          else Live.live(ZIO.sleep(10.milliseconds)) *> await
        }
        await
      }
    }

  def doInterrupt(
    managed: IO[Nothing, Unit] => ZManaged[Any, Nothing, Unit],
    expected: FiberId => Assertion[Option[Exit[Nothing, Unit]]]
  ): ZIO[Any, Nothing, TestResult] =
    for {
      fiberId            <- ZIO.fiberId
      never              <- Promise.make[Nothing, Unit]
      reachedAcquisition <- Promise.make[Nothing, Unit]
      managedFiber       <- managed(reachedAcquisition.succeed(()) *> never.await).useDiscard(ZIO.unit).forkDaemon
      _                  <- reachedAcquisition.await
      interruption       <- Live.live(managedFiber.interruptAs(fiberId).timeout(5.seconds))
    } yield assert(interruption)(expected(fiberId))

  def makeTestManaged(ref: Ref[Int]): Managed[Nothing, Unit] =
    ZManaged.fromReservationZIO {
      val reserve = ref.update(_ + 1)
      val acquire = ref.update(_ + 1)
      val release = ref.update(n => if (n > 0) 0 else -1)
      reserve.as(Reservation(acquire, _ => release))
    }

  def testFinalizersPar[R, E](
    n: Int,
    f: ZManaged[Any, Nothing, Unit] => ZManaged[R, E, Any]
  ): ZIO[R, E, TestResult] =
    for {
      releases <- Ref.make[Int](0)
      baseRes   = ZManaged.acquireReleaseWith(ZIO.succeed(()))(_ => releases.update(_ + 1))
      res       = f(baseRes)
      _        <- res.useDiscard(ZIO.unit)
      count    <- releases.get
    } yield assert(count)(equalTo(n))

  def testAcquirePar[R, E](
    n: Int,
    f: ZManaged[Any, Nothing, Unit] => ZManaged[R, E, Any]
  ): ZIO[R, Nothing, TestResult] =
    for {
      effects      <- Ref.make(0)
      countDown    <- countDownLatch(n + 1)
      reserveLatch <- Promise.make[Nothing, Unit]
      baseRes = ZManaged.fromReservation(
                  Reservation(effects.update(_ + 1) *> countDown *> reserveLatch.await, _ => ZIO.unit)
                )
      res    = f(baseRes)
      _     <- res.useDiscard(ZIO.unit).fork *> countDown
      count <- effects.get
      _     <- reserveLatch.succeed(())
    } yield assert(count)(equalTo(n))

  def testReservePar[R, E, A](
    n: Int,
    f: ZManaged[Any, Nothing, Unit] => ZManaged[R, E, A]
  ): ZIO[R, Nothing, TestResult] =
    for {
      effects      <- Ref.make(0)
      countDown    <- countDownLatch(n + 1)
      reserveLatch <- Promise.make[Nothing, Unit]
      baseRes       = ZManaged.acquireReleaseWith(effects.update(_ + 1) *> countDown *> reserveLatch.await)(_ => ZIO.unit)
      res           = f(baseRes)
      _            <- res.useDiscard(ZIO.unit).fork *> countDown
      count        <- effects.get
      _            <- reserveLatch.succeed(())
    } yield assert(count)(equalTo(n))

  def testParallelNestedFinalizerOrdering(
    listLength: Int,
    f: List[ZManaged[Any, Nothing, Ref[List[Int]]]] => ZManaged[Any, Nothing, List[Ref[List[Int]]]]
  ): ZIO[Any, Nothing, TestResult] = {
    val inner = Ref.make(List[Int]()).toManaged.flatMap { ref =>
      ZManaged.finalizer(ref.update(1 :: _)) *>
        ZManaged.finalizer(ref.update(2 :: _)) *>
        ZManaged.finalizer(ref.update(3 :: _)).as(ref)
    }

    f(List.fill(listLength)(inner)).useNow
      .flatMap(refs => ZIO.foreach(refs)(_.get))
      .map(results => assert(results)(forall(equalTo(List(1, 2, 3)))))
  }

}
