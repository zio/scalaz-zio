package zio

import zio.Cause._
import zio.LatchOps._
import zio.internal.{FiberRuntime, Platform}
import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, flaky, forked, jvmOnly, nonFlaky, scala2Only, timeout, withLiveClock}
import zio.test._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

object ZIOSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("ZIOSpec")(
    suite("heap")(
      test("unit.forever is safe") {
        for {
          fiber <- ZIO.unit.forever.fork
          _     <- Live.live(ZIO.sleep(5.seconds))
          _     <- fiber.interrupt
        } yield assertCompletes
      } @@ TestAspect.exceptNative
    ),
    suite("&&")(
      test("true and true is true") {
        assertZIO(ZIO.succeed(true) && ZIO.succeed(true))(isTrue)
      },
      test("true and false is false") {
        assertZIO(ZIO.succeed(true) && ZIO.succeed(false))(isFalse)
      },
      test("false and true is false") {
        assertZIO(ZIO.succeed(false) && ZIO.succeed(true))(isFalse)
      },
      test("false and false is false") {
        assertZIO(ZIO.succeed(false) && ZIO.succeed(false))(isFalse)
      },
      test("short circuiting") {
        assertZIO(ZIO.succeed(false) && ZIO.fail("fail"))(isFalse)
      }
    ),
    suite("||")(
      test("true or true is true") {
        assertZIO(ZIO.succeed(true) || ZIO.succeed(true))(isTrue)
      },
      test("true or false is true") {
        assertZIO(ZIO.succeed(true) || ZIO.succeed(false))(isTrue)
      },
      test("false or true is true") {
        assertZIO(ZIO.succeed(false) || ZIO.succeed(true))(isTrue)
      },
      test("false or false is false") {
        assertZIO(ZIO.succeed(false) || ZIO.succeed(false))(isFalse)
      },
      test("short circuiting") {
        assertZIO(ZIO.succeed(true) || ZIO.fail("fail"))(isTrue)
      }
    ),
    suite("absorbWith")(
      test("on fail") {
        assertZIO(TaskExampleError.absorbWith(identity).exit)(fails(equalTo(ExampleError)))
      },
      test("on die") {
        assertZIO(TaskExampleDie.absorbWith(identity).exit)(fails(equalTo(ExampleError)))
      },
      test("on success") {
        assertZIO(ZIO.succeed(1).absorbWith(_ => ExampleError))(equalTo(1))
      }
    ) @@ zioTag(errors),
    suite("acquireReleaseWith")(
      test("acquireReleaseWith happy path") {
        for {
          release <- Ref.make(false)
          result <-
            ZIO.acquireReleaseWith(ZIO.succeed(42))((_: Int) => release.set(true))((a: Int) => ZIO.succeed(a + 1))
          released <- release.get
        } yield assert(result)(equalTo(43)) && assert(released)(isTrue)
      },
      test("acquireRelease happy path") {
        for {
          release  <- Ref.make(false)
          result   <- ZIO.acquireReleaseWith(ZIO.succeed(42))(_ => release.set(true))(_ => ZIO.succeed(0))
          released <- release.get
        } yield assert(result)(equalTo(0)) && assert(released)(isTrue)
      },
      test("acquireReleaseExitWith happy path") {
        for {
          release <- Ref.make(false)
          result <-
            ZIO.acquireReleaseExitWith(ZIO.succeed(42))((_: Int, _: Exit[Any, Any]) => release.set(true))((_: Int) =>
              ZIO.succeed(0L)
            )
          released <- release.get
        } yield assert(result)(equalTo(0L)) && assert(released)(isTrue)
      },
      test("acquireReleaseExitWith error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int](ZIO.succeed(42))((_, _: Exit[Any, Any]) =>
                      ZIO.die(releaseDied)
                    )(_ => ZIO.fail("use failed"))
                    .exit
          cause <- exit.foldExitZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
        } yield assert(cause.failures)(equalTo(List("use failed"))) &&
          assert(cause.defects)(equalTo(List(releaseDied)))
      } @@ zioTag(errors)
    ),
    suite("acquireReleaseWith + disconnect")(
      test("acquireReleaseWith happy path") {
        for {
          release <- Ref.make(false)
          result <-
            ZIO
              .acquireReleaseWith(ZIO.succeed(42))((_: Int) => release.set(true))((a: Int) => ZIO.succeed(a + 1))
              .disconnect
          released <- release.get
        } yield assert(result)(equalTo(43)) && assert(released)(isTrue)
      },
      test("acquireRelease happy path") {
        for {
          release  <- Ref.make(false)
          result   <- ZIO.acquireReleaseWith(ZIO.succeed(42))(_ => release.set(true))(_ => ZIO.succeed(0)).disconnect
          released <- release.get
        } yield assert(result)(equalTo(0)) && assert(released)(isTrue)
      },
      test("acquireReleaseExitWith happy path") {
        for {
          release <- Ref.make(false)
          result <-
            ZIO
              .acquireReleaseExitWith(ZIO.succeed(42))((_: Int, _: Exit[Any, Any]) => release.set(true))((_: Int) =>
                ZIO.succeed(0L)
              )
              .disconnect
          released <- release.get
        } yield assert(result)(equalTo(0L)) && assert(released)(isTrue)
      },
      test("acquireReleaseExitWith error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int](ZIO.succeed(42))((_, _: Exit[Any, Any]) =>
                      ZIO.die(releaseDied)
                    )(_ => ZIO.fail("use failed"))
                    .disconnect
                    .exit
          cause <- exit.foldExitZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
        } yield assert(cause.failures)(equalTo(List("use failed"))) &&
          assert(cause.defects)(equalTo(List(releaseDied)))
      } @@ zioTag(errors),
      test("acquireReleaseExitWith beast mode error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          released <- Ref.make(false)
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int](ZIO.succeed(42))((_, _: Exit[Any, Any]) =>
                      released.set(true)
                    )(_ => throw releaseDied)
                    .disconnect
                    .exit
          cause      <- exit.foldExitZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
          isReleased <- released.get
        } yield assert(cause.defects)(equalTo(List(releaseDied))) && assert(isReleased)(isTrue)
      } @@ zioTag(errors)
    ),
    suite("cached")(
      test("returns new instances after duration") {
        def incrementAndGet(ref: Ref[Int]): UIO[Int] = ref.updateAndGet(_ + 1)
        for {
          ref   <- Ref.make(0)
          cache <- incrementAndGet(ref).cached(60.minutes)
          a     <- cache
          _     <- TestClock.adjust(59.minutes)
          b     <- cache
          _     <- TestClock.adjust(1.minute)
          c     <- cache
          _     <- TestClock.adjust(59.minutes)
          d     <- cache
        } yield assert(a)(equalTo(b)) && assert(b)(not(equalTo(c))) && assert(c)(equalTo(d))
      },
      test("correctly handles an infinite duration time to live") {
        for {
          ref            <- Ref.make(0)
          getAndIncrement = ref.modify(curr => (curr, curr + 1))
          cached         <- getAndIncrement.cached(Duration.Infinity)
          a              <- cached
          b              <- cached
          c              <- cached
        } yield assert((a, b, c))(equalTo((0, 0, 0)))
      }
    ),
    suite("cachedInvalidate")(
      test("returns new instances after duration") {
        def incrementAndGet(ref: Ref[Int]): UIO[Int] = ref.updateAndGet(_ + 1)
        for {
          ref                 <- Ref.make(0)
          tuple               <- incrementAndGet(ref).cachedInvalidate(60.minutes)
          (cached, invalidate) = tuple
          a                   <- ZIO.collectAllPar((1 to 10).toSet.map((_: Int) => cached))
          _                   <- TestClock.adjust(59.minutes)
          b                   <- cached
          _                   <- invalidate
          c                   <- cached
          _                   <- TestClock.adjust(1.minute)
          d                   <- cached
          _                   <- TestClock.adjust(59.minutes)
          e                   <- cached
        } yield assert(a)(equalTo(Set(1))) &&
          assert(b)(equalTo(1)) &&
          assert(c)(equalTo(2)) &&
          assert(d)(equalTo(2)) &&
          assert(e)(equalTo(3))
      },
      test("get is interruptible") {
        for {
          ref           <- Ref.make(0)
          startWaiting1 <- Promise.make[Nothing, Unit]
          startWaiting3 <- Promise.make[Nothing, Unit]
          tuple <- ref
                     .updateAndGet(_ + 1)
                     .flatMap { i =>
                       (i match {
                         case 1 => startWaiting1.succeed(()) *> ZIO.never
                         case 3 => startWaiting3.succeed(()) *> ZIO.never
                         case _ => ZIO.unit
                       }).as(i)
                     }
                     .cachedInvalidate(Duration.Infinity)
          (cached, invalidate) = tuple

          callFiber1 <- cached.fork
          _          <- startWaiting1.await
          _          <- callFiber1.interrupt

          _ <- invalidate

          v1 <- cached
          v2 <- cached

          _ <- invalidate

          callFiber3 <- cached.fork
          _          <- startWaiting3.await
          _          <- callFiber3.interrupt
        } yield assert(v1)(equalTo(2)) &&
          assert(v2)(equalTo(2))
      },
      test("issue 9015") {
        for {
          startWaiting <- Promise.make[Nothing, Unit]
          ref          <- Ref.make(true)
          tuple <- ZIO
                     .ifZIO(ref.get)(
                       onTrue = ref.set(false) *> ref.get,
                       onFalse = startWaiting.succeed(()) *> ZIO.never *> ref.get
                     )
                     .cachedInvalidate(Duration.Infinity)
          (call, invalidate) = tuple
          first             <- call
          _                 <- invalidate
          callFiber         <- call.fork
          _                 <- startWaiting.await
          _                 <- callFiber.interrupt
        } yield assert(first)(equalTo(false))
      }
    ),
    suite("catchNonFatalOrDie")(
      test("recovers from NonFatal") {
        val s   = "division by zero"
        val zio = ZIO.fail(new IllegalArgumentException(s))
        for {
          result <- zio.catchNonFatalOrDie(e => ZIO.succeed(e.getMessage)).exit
        } yield assert(result)(succeeds(equalTo(s)))
      },
      test("dies if fatal") {
        val e   = new OutOfMemoryError
        val zio = ZIO.fail(e)
        for {
          result <- zio.catchNonFatalOrDie(e => ZIO.succeed(e.getMessage)).exit
        } yield assert(result)(dies(equalTo(e)))
      } @@ jvmOnly // no fatal exceptions in JS
    ),
    suite("catchAllDefect")(
      test("recovers from all defects") {
        val s   = "division by zero"
        val zio = ZIO.die(new IllegalArgumentException(s))
        for {
          result <- zio.catchAllDefect(e => ZIO.succeed(e.getMessage))
        } yield assert(result)(equalTo(s))
      },
      test("leaves errors") {
        val t   = new IllegalArgumentException("division by zero")
        val zio = ZIO.fail(t)
        for {
          exit <- zio.catchAllDefect(e => ZIO.succeed(e.getMessage)).exit
        } yield assert(exit)(fails(equalTo(t)))
      },
      test("leaves values") {
        val t   = new IllegalArgumentException("division by zero")
        val zio = ZIO.succeed(t)
        for {
          result <- zio.catchAllDefect(e => ZIO.succeed(e.getMessage))
        } yield assert(result)((equalTo(t)))
      }
    ) @@ zioTag(errors),
    suite("catchSomeCause")(
      test("catches matching cause") {
        ZIO.interrupt.catchSomeCause {
          case c if c.isInterrupted => ZIO.succeed(true)
        }.sandbox.map(
          assert(_)(isTrue)
        )
      },
      test("fails if cause doesn't match") {
        ZIO.fiberId.flatMap { fiberId =>
          ZIO.interrupt.catchSomeCause {
            case c if (!c.isInterrupted) => ZIO.succeed(true)
          }.sandbox.either.map(
            assert(_)(isLeft(equalTo(Cause.interrupt(fiberId))))
          )
        }
      }
    ) @@ zioTag(errors),
    suite("catchSomeDefect")(
      test("recovers from some defects") {
        val s   = "division by zero"
        val zio = ZIO.die(new IllegalArgumentException(s))
        for {
          result <- zio.catchSomeDefect { case e: IllegalArgumentException =>
                      ZIO.succeed(e.getMessage)
                    }
        } yield assert(result)(equalTo(s))
      },
      test("leaves the rest") {
        val t   = new IllegalArgumentException("division by zero")
        val zio = ZIO.die(t)
        for {
          exit <- zio.catchSomeDefect { case e: NumberFormatException =>
                    ZIO.succeed(e.getMessage)
                  }.exit
        } yield assert(exit)(dies(equalTo(t)))
      },
      test("leaves errors") {
        val t   = new IllegalArgumentException("division by zero")
        val zio = ZIO.fail(t)
        for {
          exit <- zio.catchSomeDefect { case e: IllegalArgumentException =>
                    ZIO.succeed(e.getMessage)
                  }.exit
        } yield assert(exit)(fails(equalTo(t)))
      },
      test("leaves values") {
        val t   = new IllegalArgumentException("division by zero")
        val zio = ZIO.succeed(t)
        for {
          result <- zio.catchSomeDefect { case e: IllegalArgumentException =>
                      ZIO.succeed(e.getMessage)
                    }
        } yield assert(result)((equalTo(t)))
      }
    ) @@ zioTag(errors),
    suite("collect")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.collect(s"value was not 0") { case v @ 0 => v }).sandbox.either
          badCase <-
            exactlyOnce(1)(_.collect(s"value was not 0") { case v @ 0 => v }).sandbox.either
              .map(_.left.map(_.failureOrCause))
        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("value was not 0"))))
      }
    ),
    suite("collectAllPar")(
      test("returns the list in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.collectAllPar(list)
        assertZIO(res)(equalTo(List(1, 2, 3)))
      },
      test("is referentially transparent") {
        for {
          counter <- Ref.make(0)
          op       = counter.getAndUpdate(_ + 1)
          ops3     = ZIO.collectAllPar(List(op, op, op))
          ops6     = ops3.zipPar(ops3)
          res     <- ops6
        } yield assert(res._1)(not(equalTo(res._2)))
      },
      test("preserves failures") {
        ZIO.uninterruptible {
          for {
            promise1 <- Promise.make[Nothing, Unit]
            promise2 <- Promise.make[Nothing, Unit]
            left      = promise2.await.interruptible
            right1    = promise1.await *> ZIO.fail("fail")
            right2    = (promise1.succeed(()) *> ZIO.never).ensuring(promise2.interrupt).interruptible
            exit     <- ZIO.collectAllPar(List(left, ZIO.collectAllPar(List(right1, right2)))).exit
          } yield assert(exit)(failsCause(containsCause(Cause.fail("fail"))))
        }
      } @@ exceptJS(nonFlaky(500))
    ),
    suite("collectAllParN")(
      test("returns results in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.collectAllPar(list).withParallelism(2)
        assertZIO(res)(equalTo(List(1, 2, 3)))
      }
    ),
    suite("collectAllParNDiscard")(
      test("preserves failures") {
        val tasks = List.fill(10)(ZIO.fail(new RuntimeException))
        assertZIO(ZIO.collectAllParDiscard(tasks).withParallelism(5).flip)(anything)
      }
    ),
    suite("collectAllSuccesses")(
      test("propagates defects") {
        val tasks = List(ZIO.unit, ZIO.fail("fail"), ZIO.dieMessage("die"))
        assertZIO(ZIO.collectAllSuccesses(tasks).exit)(dies(hasMessage(equalTo("die"))))
      }
    ),
    suite("collectAllSuccessesPar")(
      test("propagates defects") {
        val tasks = List(ZIO.unit, ZIO.fail("fail"), ZIO.dieMessage("die"))
        assertZIO(ZIO.collectAllSuccessesPar(tasks).exit)(dies(hasMessage(equalTo("die"))))
      }
    ),
    suite("collectFirst")(
      test("collects the first value for which the effectual functions returns Some") {
        check(Gen.listOf(Gen.int), Gen.partialFunction(Gen.int)) { (as, pf) =>
          def f(n: Int): UIO[Option[Int]] = ZIO.succeed(pf.lift(n))
          assertZIO(ZIO.collectFirst(as)(f))(equalTo(as.collectFirst(pf)))
        }
      }
    ),
    suite("collectZIO")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(
              _.collectZIO[Any, String, Int]("Predicate failed!") { case v @ 0 => ZIO.succeed(v) }
            ).sandbox.either
          partialBadCase <-
            exactlyOnce(0)(
              _.collectZIO("Predicate failed!") { case v @ 0 => ZIO.fail("Partial failed!") }
            ).sandbox.either
              .map(_.left.map(_.failureOrCause))
          badCase <-
            exactlyOnce(1)(_.collectZIO("Predicate failed!") { case v @ 0 => ZIO.succeed(v) }).sandbox.either
              .map(_.left.map(_.failureOrCause))
        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(partialBadCase)(isLeft(isLeft(equalTo("Partial failed!")))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Predicate failed!"))))
      }
    ),
    suite("companion object method consistency")(
      test("absolve") {
        check(Gen.alphaNumericString) { str =>
          val ioEither: UIO[Either[Nothing, String]] = ZIO.succeed(Right(str))
          for {
            abs1 <- ioEither.absolve
            abs2 <- ZIO.absolve(ioEither)
          } yield assert(abs1)(equalTo(abs2))
        }
      },
      test("firstSuccessOf") {
        val io  = ZIO.succeed(testString)
        val ios = List.empty[UIO[String]]
        for {
          race1 <- io.firstSuccessOf(ios)
          race2 <- ZIO.firstSuccessOf(io, ios)
        } yield assert(race1)(equalTo(race2))
      },
      test("flatten") {
        check(Gen.alphaNumericString) { str =>
          for {
            flatten1 <- ZIO.succeed(ZIO.succeed(str)).flatten
            flatten2 <- ZIO.flatten(ZIO.succeed(ZIO.succeed(str)))
          } yield assert(flatten1)(equalTo(flatten2))
        }
      },
      test("raceAll") {
        val io  = ZIO.succeed(testString)
        val ios = List.empty[UIO[String]]
        for {
          race1 <- io.raceAll(ios)
          race2 <- ZIO.raceAll(io, ios)
        } yield assert(race1)(equalTo(race2))
      }
    ),
    suite("done")(
      test("Check done lifts exit result into IO") {

        val fiberId = FiberId(0, 123, Trace.empty)
        val error   = exampleError

        for {
          completed   <- ZIO.done(Exit.succeed(1))
          interrupted <- ZIO.done(Exit.interrupt(fiberId)).exit
          terminated  <- ZIO.done(Exit.die(error)).exit
          failed      <- ZIO.done(Exit.fail(error)).exit
        } yield assert(completed)(equalTo(1)) &&
          assert(interrupted)(isInterrupted) &&
          assert(terminated)(dies(equalTo(error))) &&
          assert(failed)(fails(equalTo(error)))
      }
    ),
    suite("executor")(
      test("retrieves the current executor for this effect") {
        val executor = Executor.fromExecutionContext {
          scala.concurrent.ExecutionContext.Implicits.global
        }
        for {
          default <- ZIO.executor
          global  <- ZIO.executor.onExecutor(executor)
        } yield assert(default)(not(equalTo(global)))
      }
    ),
    suite("repeatUntil")(
      test("repeatUntil repeats until condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatUntil(_ == 0)
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("repeatUntil always evaluates effect at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).repeatUntil(_ => true)
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("repeatUntilEquals")(
      test("repeatUntilEquals repeats until result is equal to predicate") {
        for {
          q      <- Queue.unbounded[Int]
          _      <- q.offerAll(List(1, 2, 3, 4, 5, 6))
          acc    <- Ref.make(0)
          _      <- (q.take <* acc.update(_ + 1)).repeatUntilEquals(5)
          result <- acc.get
        } yield assert(result)(equalTo(5))
      }
    ),
    suite("repeatUntilZIO")(
      test("repeatUntilZIO repeat until effectful condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatUntilZIO(v => ZIO.succeed(v == 0))
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("repeatUntilZIO always evaluates effect at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).repeatUntilZIO(_ => ZIO.succeed(true))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("repeatWhile")(
      test("repeatWhile repeats while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatWhile(_ >= 0)
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("repeatWhile always evaluates effect at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).repeatWhile(_ => false)
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("repeatWhileEquals")(
      test("repeatWhileEquals repeats while result equals predicate") {
        for {
          q      <- Queue.unbounded[Int]
          _      <- q.offerAll(List(0, 0, 0, 0, 1, 2))
          acc    <- Ref.make(0)
          _      <- (q.take <* acc.update(_ + 1)).repeatWhileEquals(0)
          result <- acc.get
        } yield assert(result)(equalTo(5))
      }
    ),
    suite("repeatWhileZIO")(
      test("repeatWhileZIO repeats while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatWhileZIO(v => ZIO.succeed(v >= 0))
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("repeatWhileZIO always evaluates effect at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).repeatWhileZIO(_ => ZIO.succeed(false))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("eventually")(
      test("succeeds eventually") {
        def effect(ref: Ref[Int]) =
          ref.get.flatMap(n => if (n < 10) ref.update(_ + 1) *> ZIO.fail("Ouch") else ZIO.succeed(n))

        val test = for {
          ref <- Ref.make(0)
          n   <- effect(ref).eventually
        } yield n

        assertZIO(test)(equalTo(10))
      }
    ),
    suite("exists")(
      test("determines whether any element satisfies the effectual predicate") {
        check(Gen.listOf(Gen.int), Gen.function(Gen.boolean)) { (as, f) =>
          val actual   = ZIO.exists(as)(a => ZIO.succeed(f(a)))
          val expected = as.exists(f)
          assertZIO(actual)(equalTo(expected))
        }
      }
    ),
    suite("filter")(
      test("filters a collection using an effectual predicate") {
        val as = Iterable(2, 4, 6, 3, 5, 6)
        for {
          ref     <- Ref.make(List.empty[Int])
          results <- ZIO.filter(as)(a => ref.update(a :: _).as(a % 2 == 0))
          effects <- ref.get.map(_.reverse)
        } yield assert(results)(equalTo(List(2, 4, 6, 6))) &&
          assert(effects)(equalTo(List(2, 4, 6, 3, 5, 6)))
      },
      test("filters a set using an effectual predicate") {
        val as = Set(2, 3, 4, 5, 6, 7)
        for {
          ref     <- Ref.make(Set.empty[Int])
          results <- ZIO.filter(as)(a => ref.update(_ + a).as(a % 2 == 0))
          effects <- ref.get.map(_.map(_ + 1))
        } yield assert(results)(equalTo(Set(2, 4, 6))) &&
          assert(effects)(equalTo(Set(3, 4, 5, 6, 7, 8)))
      }
    ),
    suite("filterNot")(
      test("filters a collection using an effectual predicate") {
        val as = Iterable(2, 4, 6, 3, 5, 6)
        for {
          ref     <- Ref.make(List.empty[Int])
          results <- ZIO.filterNot(as)(a => ref.update(a :: _).as(a % 2 == 0))
          effects <- ref.get.map(_.reverse)
        } yield assert(results)(equalTo(List(3, 5))) &&
          assert(effects)(equalTo(List(2, 4, 6, 3, 5, 6)))
      },
      test("filters a set using an effectual predicate") {
        val as = Set(2, 3, 4, 5, 6, 7)
        for {
          ref     <- Ref.make(Set.empty[Int])
          results <- ZIO.filterNot(as)(a => ref.update(_ + a).as(a % 2 == 0))
          effects <- ref.get.map(_.map(_ + 1))
        } yield assert(results)(equalTo(Set(3, 5, 7))) &&
          assert(effects)(equalTo(Set(3, 4, 5, 6, 7, 8)))
      }
    ),
    suite("filterPar")(
      test("filters a collection in parallel using an effectual predicate") {
        val as = Iterable(2, 4, 6, 3, 5, 6, 10, 11, 15, 17, 20, 22, 23, 25, 28)
        for {
          results <- ZIO.filterPar(as)(a => ZIO.succeed(a % 2 == 0))
        } yield assert(results)(equalTo(List(2, 4, 6, 6, 10, 20, 22, 28)))
      }
    ),
    suite("filterNotPar")(
      test(
        "filters a collection in parallel using an effectual predicate, removing all elements that satisfy the predicate"
      ) {
        val as = Iterable(2, 4, 6, 3, 5, 6, 10, 11, 15, 17, 20, 22, 23, 25, 28)
        for {
          results <- ZIO.filterNotPar(as)(a => ZIO.succeed(a % 2 == 0))
        } yield assert(results)(equalTo(List(3, 5, 11, 15, 17, 23, 25)))
      }
    ),
    suite("filterOrElseWith")(
      test("returns checked failure from held value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.filterOrElseWith[Any, String, Int](_ == 0)(a => ZIO.fail(s"$a was not 0"))).sandbox.either

          badCase <-
            exactlyOnce(1)(_.filterOrElseWith[Any, String, Int](_ == 0)(a => ZIO.fail(s"$a was not 0"))).sandbox.either
              .map(_.left.map(_.failureOrCause))

        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("1 was not 0"))))
      }
    ),
    suite("filterOrElse")(
      test("returns checked failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.filterOrElse[Any, String, Int](_ == 0)(ZIO.fail(s"Predicate failed!"))).sandbox.either

          badCase <-
            exactlyOnce(1)(_.filterOrElse[Any, String, Int](_ == 0)(ZIO.fail(s"Predicate failed!"))).sandbox.either
              .map(_.left.map(_.failureOrCause))

        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Predicate failed!"))))
      }
    ),
    suite("filterOrFail")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.filterOrFail(_ == 0)("Predicate failed!")).sandbox.either

          badCase <-
            exactlyOnce(1)(_.filterOrFail(_ == 0)("Predicate failed!")).sandbox.either
              .map(_.left.map(_.failureOrCause))

        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Predicate failed!"))))
      }
    ) @@ zioTag(errors),
    suite("flattenErrorOption")(
      test("fails when given Some error") {
        val task: IO[String, Int] = ZIO.fail(Some("Error")).flattenErrorOption("Default")
        assertZIO(task.exit)(fails(equalTo("Error")))
      },
      test("fails with Default when given None error") {
        val task: IO[String, Int] = ZIO.fail(None).flattenErrorOption("Default")
        assertZIO(task.exit)(fails(equalTo("Default")))
      },
      test("succeeds when given a value") {
        val task: IO[String, Int] = ZIO.succeed(1).flattenErrorOption("Default")
        assertZIO(task)(equalTo(1))
      }
    ) @@ zioTag(errors),
    suite("foldLeft")(
      test("with a successful step function sums the list properly") {
        check(Gen.listOf(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(0)((acc, el) => ZIO.succeed(acc + el))
          assertZIO(res)(equalTo(l.sum))
        }
      },
      test("`with a failing step function returns a failed IO") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(0)((_, _) => ZIO.fail("fail"))
          assertZIO(res.exit)(fails(equalTo("fail")))
        }
      } @@ zioTag(errors),
      test("run sequentially from left to right") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(List.empty[Int])((acc, el) => ZIO.succeed(el :: acc))
          assertZIO(res)(equalTo(l.reverse))
        }
      }
    ),
    suite("foldRight")(
      test("with a successful step function sums the list properly") {
        check(Gen.listOf(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(0)((el, acc) => ZIO.succeed(acc + el))
          assertZIO(res)(equalTo(l.sum))
        }
      },
      test("`with a failing step function returns a failed IO") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(0)((_, _) => ZIO.fail("fail"))
          assertZIO(res.exit)(fails(equalTo("fail")))
        }
      } @@ zioTag(errors),
      test("run sequentially from right to left") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(List.empty[Int])((el, acc) => ZIO.succeed(el :: acc))
          assertZIO(res)(equalTo(l))
        }
      }
    ),
    suite("forall")(
      test("determines whether all elements satisfy the effectual predicate") {
        check(Gen.listOf(Gen.int), Gen.function(Gen.boolean)) { (as, f) =>
          val actual   = ZIO.forall(as)(a => ZIO.succeed(f(a)))
          val expected = as.forall(f)
          assertZIO(actual)(equalTo(expected))
        }
      }
    ),
    suite("foreach")(
      test("returns the list of results") {
        checkAll(functionIOGen, listGen) { (f, list) =>
          val res = ZIO.foreach(list)(f)
          assertZIO(res)(isSubtype[List[Int]](anything) && hasSize(equalTo(100)))
        }
      },
      test("returns the list of results with async nodes") {
        checkAll(functionIOGen, listGen) { (f, list) =>
          val res = ZIO.foreach(list)(a => ZIO.async[Any, Nothing, Unit](k => k(ZIO.unit)) *> f(a))
          assertZIO(res)(isSubtype[List[Int]](anything) && hasSize(equalTo(100)))
        }
      },
      test("both evaluates effects and returns the list of results in the same order") {
        val list = List("1", "2", "3")
        for {
          ref     <- Ref.make(List.empty[String])
          res     <- ZIO.foreach(list)(x => ref.update(_ :+ x) *> ZIO.succeed[Int](x.toInt))
          effects <- ref.get
        } yield assert(effects)(equalTo(list)) && assert(res)(equalTo(List(1, 2, 3)))
      },
      test("fails if one of the effects fails") {
        val list = List("1", "h", "3")
        val res  = ZIO.foreach(list)(x => ZIO.succeed[Int](x.toInt))
        assertZIO(res.exit)(dies(isSubtype[NumberFormatException](anything)))
      } @@ zioTag(errors)
    ),
    suite("foreachDiscard")(
      test("runs effects in order") {
        val as = List(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(List.empty[Int])
          _   <- ZIO.foreachDiscard(as)(a => ref.update(_ :+ a))
          rs  <- ref.get
        } yield assert(rs)(equalTo(as))
      },
      test("can be run twice") {
        val as = List(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(0)
          zio  = ZIO.foreachDiscard(as)(a => ref.update(_ + a))
          _   <- zio
          _   <- zio
          sum <- ref.get
        } yield assert(sum)(equalTo(30))
      }
    ),
    suite("foreach for Option")(
      test("succeeds with None given None") {
        val task: UIO[Option[Int]] = ZIO.foreach(None)((str: String) => ZIO.succeed(str.length))
        assertZIO(task)(isNone)
      },
      test("succeeds with Some given Some") {
        for {
          optRes <- ZIO.foreach(Some("success"))(str => ZIO.succeed(str.length))
        } yield assert(optRes)(equalTo(Some(7)))
      },
      test("fails if the optional effect fails") {
        val opt = Some("h")
        val res = ZIO.foreach(opt)(x => ZIO.succeed[Int](x.toInt))
        assertZIO(res.exit)(dies(isSubtype[NumberFormatException](anything)))
      }
    ),
    suite("foreachPar")(
      test("runs single task") {
        val as      = List(2)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertZIO(results)(equalTo(List(4)))
      },
      test("runs two tasks") {
        val as      = List(2, 3)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertZIO(results)(equalTo(List(4, 6)))
      },
      test("runs many tasks") {
        val as      = (1 to 1000)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertZIO(results)(equalTo(as.map(2 * _)))
      },
      test("runs a task that fails") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.fail("Boom!")
            case a => ZIO.succeed(2 * a)
          }
          .flip
        assertZIO(results)(equalTo("Boom!"))
      },
      test("runs two failed tasks") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.fail("Boom1!")
            case 8 => ZIO.fail("Boom2!")
            case a => ZIO.succeed(2 * a)
          }
          .flip
        assertZIO(results)(equalTo("Boom1!") || equalTo("Boom2!"))
      },
      test("runs a task that dies") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.dieMessage("Boom!")
            case a => ZIO.succeed(2 * a)
          }
          .exit
        assertZIO(results)(dies(hasMessage(equalTo("Boom!"))))
      },
      test("runs a task that is interrupted") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.interrupt
            case a => ZIO.succeed(2 * a)
          }
          .exit
        assertZIO(results)(isInterrupted)
      } @@ zioTag(interruption),
      test("runs a task that throws an unsuspended exception") {
        def f(i: Int): Task[Int] = throw new Exception(i.toString)
        for {
          _ <- ZIO.foreachPar(1 to 1)(f).exit
        } yield assertCompletes
      },
      test("returns results in the same order") {
        val list = List("1", "2", "3")
        val res  = ZIO.foreachPar(list)(x => ZIO.succeed[Int](x.toInt))
        assertZIO(res)(equalTo(List(1, 2, 3)))
      },
      test("returns results in the same order for Chunk") {
        val chunk = Chunk("1", "2", "3")
        val res   = ZIO.foreachPar(chunk)(x => ZIO.succeed[Int](x.toInt))
        assertZIO(res)(equalTo(Chunk(1, 2, 3)))
      },
      test("runs effects in parallel") {
        assertZIO(for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachPar(List(ZIO.never, p.succeed(())))(a => a).fork
          _ <- p.await
        } yield true)(isTrue)
      },
      test("runs effects in parallel for Chunk") {
        assertZIO(for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachPar(Chunk(ZIO.never, p.succeed(()), ZIO.never))(a => a).fork
          _ <- p.await
        } yield true)(isTrue)
      },
      test("propagates error") {
        val ints = List(1, 2, 3, 4, 5, 6)
        val odds = ZIO.foreachPar(ints)(n => if (n % 2 != 0) ZIO.succeed(n) else ZIO.fail("not odd"))
        assertZIO(odds.flip)(equalTo("not odd"))
      } @@ zioTag(errors),
      test("interrupts effects on first failure") {
        for {
          ref     <- Ref.make(false)
          promise <- Promise.make[Nothing, Unit]
          actions = List(
                      ZIO.never,
                      ZIO.succeed(1),
                      ZIO.fail("C"),
                      promise.await *> ref.set(true)
                    )
          e <- ZIO.foreachPar(actions)(a => a).flip
          v <- ref.get
        } yield assert(e)(equalTo("C")) && assert(v)(isFalse)
      } @@ zioTag(interruption),
      test("does not kill fiber when forked on the parent scope") {
        for {
          ref    <- Ref.make(0)
          fibers <- ZIO.foreachPar(1 to 100)(_ => ref.update(_ + 1).fork)
          _      <- ZIO.foreach(fibers)(_.await)
          value  <- ref.get
        } yield assert(value)(equalTo(100))
      }
    ),
    suite("foreachParDiscard")(
      test("accumulates errors") {
        def task(started: Ref[Int], trigger: Promise[Nothing, Unit])(i: Int): IO[Int, Unit] =
          started.updateAndGet(_ + 1) flatMap { count =>
            ZIO.when(count == 3)(trigger.succeed(())) *> trigger.await *> ZIO.fail(i)
          }

        for {
          started <- Ref.make(0)
          trigger <- Promise.make[Nothing, Unit]

          errors <- ZIO
                      .foreachParDiscard(1 to 3)(i => task(started, trigger)(i).uninterruptible)
                      .foldCause(cause => cause.failures.toSet, _ => Set.empty[Int])
        } yield assert(errors)(equalTo(Set(1, 2, 3)))
      } @@ zioTag(errors),
      test("runs all effects") {
        val as = Seq(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(Seq.empty[Int])
          _   <- ZIO.foreachParDiscard(as)(a => ref.update(_ :+ a))
          rs  <- ref.get
        } yield assert(rs)(hasSize(equalTo(as.length))) &&
          assert(rs.toSet)(equalTo(as.toSet))
      },
      test("runs all effects for Chunk") {
        val as = Chunk(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(Seq.empty[Int])
          _   <- ZIO.foreachParDiscard(as)(a => ref.update(_ :+ a))
          rs  <- ref.get
        } yield assert(rs)(hasSize(equalTo(as.length))) &&
          assert(rs.toSet)(equalTo(as.toList.toSet))
      },
      test("completes on empty input") {
        ZIO.foreachParDiscard(Nil)(_ => ZIO.unit).as(assertCompletes)
      }
    ),
    suite("foreachParN")(
      test("returns the list of results in the appropriate order") {
        val list = List(1, 2, 3)
        val res  = ZIO.foreachPar(list)(x => ZIO.succeed(x.toString)).withParallelism(2)
        assertZIO(res)(equalTo(List("1", "2", "3")))
      },
      test("works on large lists") {
        val n   = 10
        val seq = List.range(0, 100000)
        val res = ZIO.foreachPar(seq)(ZIO.succeed(_)).withParallelism(n)
        assertZIO(res)(equalTo(seq))
      },
      test("runs effects in parallel") {
        val io = for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachPar(List(ZIO.never, p.succeed(())))(identity).withParallelism(2).fork
          _ <- p.await
        } yield true
        assertZIO(io)(isTrue)
      },
      test("propagates error") {
        val ints = List(1, 2, 3, 4, 5, 6)
        val odds = ZIO.foreachPar(ints)(n => if (n % 2 != 0) ZIO.succeed(n) else ZIO.fail("not odd")).withParallelism(4)
        assertZIO(odds.either)(isLeft(equalTo("not odd")))
      } @@ zioTag(errors),
      test("interrupts effects on first failure") {
        val actions = List(
          ZIO.never,
          ZIO.succeed(1),
          ZIO.fail("C")
        )
        val io = ZIO.foreachPar(actions)(a => a).withParallelism(4)
        assertZIO(io.either)(isLeft(equalTo("C")))
      } @@ zioTag(errors, interruption)
    ),
    suite("foreachParNDiscard")(
      test("runs all effects") {
        val as = Seq(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(Seq.empty[Int])
          _   <- ZIO.foreachParDiscard(as)(a => ref.update(_ :+ a)).withParallelism(2)
          rs  <- ref.get
        } yield assert(rs)(hasSize(equalTo(as.length))) &&
          assert(rs.toSet)(equalTo(as.toSet))
      }
    ),
    suite("forkAll")(
      test("returns the list of results in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.forkAll(list).flatMap[Any, Nothing, List[Int]](_.join)
        assertZIO(res)(equalTo(List(1, 2, 3)))
      },
      test("happy-path") {
        val list = (1 to 1000).toList
        assertZIO(ZIO.forkAll(list.map(a => ZIO.succeed(a))).flatMap(_.join))(equalTo(list))
      },
      test("empty input") {
        assertZIO(ZIO.forkAll(List.empty[ZIO[Any, Nothing, Unit]]).flatMap(_.join))(equalTo(List.empty))
      },
      test("propagate failures") {
        val boom             = new Exception
        val fail: Task[Unit] = ZIO.fail(boom)
        for {
          fiber  <- ZIO.forkAll(List(fail))
          result <- fiber.join.flip
        } yield assert(result)(equalTo(boom))
      },
      test("propagates defects") {
        val boom                                 = new Exception("boom")
        val die: UIO[Unit]                       = ZIO.die(boom)
        def joinDefect(fiber: Fiber[Nothing, _]) = fiber.join.sandbox.flip
        for {
          fiber1 <- ZIO.forkAll(List(die))
          fiber2 <- ZIO.forkAll(List(die, ZIO.succeed(42)))

          result1 <- joinDefect(fiber1).map(_.untraced)
          result2 <- joinDefect(fiber2).map(_.untraced)
        } yield {
          assert(result1.dieOption)(isSome(equalTo(boom))) && {
            assert(result2.dieOption)(isSome(equalTo(boom))) ||
            (assert(result2.dieOption)(isSome(equalTo(boom))) && assert(result2.isInterrupted)(isTrue))
          }
        }
      } @@ exceptJS(nonFlaky),
      test("infers correctly") {
        for {
          ref    <- Ref.make(0)
          worker  = ZIO.never
          workers = List.fill(4)(worker)
          fiber  <- ZIO.forkAll(workers)
          _      <- fiber.interrupt
          value  <- ref.get
        } yield assert(value)(equalTo(0))
      },
      test("infers correctly with error type") {
        for {
          ref    <- Ref.make(0)
          worker  = ZIO.fail(new RuntimeException("fail")).forever
          workers = List.fill(4)(worker)
          fiber  <- ZIO.forkAll(workers)
          _      <- fiber.interrupt
          value  <- ref.get
        } yield assert(value)(equalTo(0))
      }
    ),
    suite("forkWithErrorHandler")(
      test("calls provided function when task fails") {
        for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.fail(()).forkWithErrorHandler(p.succeed(_).unit)
          _ <- p.await
        } yield assertCompletes
      }
    ) @@ zioTag(errors),
    suite("from")(
      test("Attempt") {
        trait A
        lazy val a: A                             = ???
        lazy val actual                           = ZIO.from(a)
        lazy val expected: ZIO[Any, Throwable, A] = actual
        lazy val _                                = expected
        assertCompletes
      },
      test("Either") {
        trait E
        trait A
        lazy val either: Either[E, A]     = ???
        lazy val actual                   = ZIO.from(either)
        lazy val expected: ZIO[Any, E, A] = actual
        lazy val _                        = expected
        assertCompletes
      },
      test("EitherCause") {
        trait E
        trait A
        lazy val eitherCause: Either[Cause[E], A] = ???
        lazy val actual                           = ZIO.from(eitherCause)
        lazy val expected: ZIO[Any, E, A]         = actual
        lazy val _                                = expected
        assertCompletes
      },
      test("EitherCauseLeft") {
        trait E
        trait A
        lazy val eitherCauseLeft: Left[Cause[E], A] = ???
        lazy val actual                             = ZIO.from(eitherCauseLeft)
        lazy val expected: ZIO[Any, E, A]           = actual
        lazy val _                                  = expected
        assertCompletes
      },
      test("EitherCauseRight") {
        trait E
        trait A
        lazy val eitherCauseRight: Right[Cause[E], A] = ???
        lazy val actual                               = ZIO.from(eitherCauseRight)
        lazy val expected: ZIO[Any, E, A]             = actual
        lazy val _                                    = expected
        assertCompletes
      },
      test("EitherLeft") {
        trait E
        trait A
        lazy val eitherLeft: Left[E, A]   = ???
        lazy val actual                   = ZIO.from(eitherLeft)
        lazy val expected: ZIO[Any, E, A] = actual
        lazy val _                        = expected
        assertCompletes
      },
      test("EitherRight") {
        trait E
        trait A
        lazy val eitherRight: Right[E, A] = ???
        lazy val actual                   = ZIO.from(eitherRight)
        lazy val expected: ZIO[Any, E, A] = actual
        lazy val _                        = expected
        assertCompletes
      },
      test("Fiber") {
        trait E
        trait A
        lazy val fiber: Fiber[E, A]       = ???
        lazy val actual                   = ZIO.from(fiber)
        lazy val expected: ZIO[Any, E, A] = actual
        lazy val _                        = expected
        assertCompletes
      },
      test("FiberRuntime") {
        trait E
        trait A
        lazy val fiberRuntime: Fiber.Runtime[E, A] = ???
        lazy val actual                            = ZIO.from(fiberRuntime)
        lazy val expected: ZIO[Any, E, A]          = actual
        lazy val _                                 = expected
        assertCompletes
      },
      test("FiberSynthetic") {
        trait E
        trait A
        lazy val fiberSynthetic: Fiber.Synthetic[E, A] = ???
        lazy val actual                                = ZIO.from(fiberSynthetic)
        lazy val expected: ZIO[Any, E, A]              = actual
        lazy val _                                     = expected
        assertCompletes
      },
      test("FiberZIO") {
        trait R
        trait E
        trait E1 extends E
        trait E2 extends E
        trait A
        lazy val fiberZIO: ZIO[R, E1, Fiber[E2, A]] = ???
        lazy val actual                             = ZIO.from(fiberZIO)
        lazy val expected: ZIO[R, E, A]             = actual
        lazy val _                                  = expected
        assertCompletes
      },
      test("FiberZIORuntime") {
        trait R
        trait E
        trait E1 extends E
        trait E2 extends E
        trait A
        lazy val fiberZIORuntime: ZIO[R, E1, Fiber.Runtime[E2, A]] = ???
        lazy val actual                                            = ZIO.from(fiberZIORuntime)
        lazy val expected: ZIO[R, E, A]                            = actual
        lazy val _                                                 = expected
        assertCompletes
      },
      test("FiberZIOSynthetic") {
        trait R
        trait E
        trait E1 extends E
        trait E2 extends E
        trait A
        lazy val fiberZIOSynthetic: ZIO[R, E1, Fiber.Synthetic[E2, A]] = ???
        lazy val actual                                                = ZIO.from(fiberZIOSynthetic)
        lazy val expected: ZIO[R, E, A]                                = actual
        lazy val _                                                     = expected
        assertCompletes
      },
      test("Future") {
        trait A
        trait FutureLike[A] extends scala.concurrent.Future[A]
        lazy val future: FutureLike[A]            = ???
        lazy val actual                           = ZIO.from(future)
        lazy val expected: ZIO[Any, Throwable, A] = actual
        lazy val _                                = expected
        assertCompletes
      },
      test("FutureExecutionContext") {
        trait A
        trait FutureLike[A] extends scala.concurrent.Future[A]
        lazy val futureExecutionContext: scala.concurrent.ExecutionContext => FutureLike[A] = ???
        lazy val actual                                                                     = ZIO.from(futureExecutionContext)
        lazy val expected: ZIO[Any, Throwable, A]                                           = actual
        lazy val _                                                                          = expected
        assertCompletes
      },
      test("Option") {
        trait A
        lazy val option: Option[A]                      = ???
        lazy val actual                                 = ZIO.from(option)
        lazy val expected: ZIO[Any, Option[Nothing], A] = actual
        lazy val _                                      = expected
        assertCompletes
      },
      test("OptionNone") {
        lazy val optionNone: None.type                        = ???
        lazy val actual                                       = ZIO.from(optionNone)
        lazy val expected: ZIO[Any, Option[Nothing], Nothing] = actual
        lazy val _                                            = expected
        assertCompletes
      },
      test("OptionSome") {
        trait A
        lazy val optionSome: Some[A]                    = ???
        lazy val actual                                 = ZIO.from(optionSome)
        lazy val expected: ZIO[Any, Option[Nothing], A] = actual
        lazy val _                                      = expected
        assertCompletes
      },
      test("PromiseScala") {
        trait A
        trait PromiseLike[A] extends scala.concurrent.Promise[A]
        lazy val promiseScala: PromiseLike[A]     = ???
        lazy val actual                           = ZIO.from(promiseScala)
        lazy val expected: ZIO[Any, Throwable, A] = actual
        lazy val _                                = expected
        assertCompletes
      },
      test("Try") {
        trait A
        lazy val tryScala: scala.util.Try[A]      = ???
        lazy val actual                           = ZIO.from(tryScala)
        lazy val expected: ZIO[Any, Throwable, A] = actual
        lazy val _                                = expected
        assertCompletes
      },
      test("TryFailure") {
        trait A
        lazy val tryFailure: scala.util.Failure[A] = ???
        lazy val actual                            = ZIO.from(tryFailure)
        lazy val expected: ZIO[Any, Throwable, A]  = actual
        lazy val _                                 = expected
        assertCompletes
      },
      test("TrySuccess") {
        trait A
        lazy val trySuccess: scala.util.Success[A] = ???
        lazy val actual                            = ZIO.from(trySuccess)
        lazy val expected: ZIO[Any, Throwable, A]  = actual
        lazy val _                                 = expected
        assertCompletes
      }
    ),
    suite("fromFutureInterrupt")(
      test("running Future can be interrupted") {
        import java.util.concurrent.atomic.AtomicInteger
        import scala.concurrent.{ExecutionContext, Future}
        def infiniteFuture(ref: AtomicInteger)(implicit ec: ExecutionContext): Future[Nothing] =
          Future(ref.getAndIncrement()).flatMap(_ => infiniteFuture(ref))
        for {
          ref   <- ZIO.succeed(new AtomicInteger(0))
          fiber <- ZIO.fromFutureInterrupt(ec => infiniteFuture(ref)(ec)).fork
          _     <- fiber.interrupt
          v1    <- ZIO.succeed(ref.get)
          _     <- Live.live(Clock.sleep(10.milliseconds))
          v2    <- ZIO.succeed(ref.get)
        } yield assert(v1)(equalTo(v2))
      },
      test("interruption blocks on interruption of the Future") {
        import scala.concurrent.Promise
        for {
          latch  <- ZIO.succeed(Promise[Unit]())
          fiber  <- ZIO.fromFutureInterrupt { _ => latch.success(()); Promise[Unit]().future }.forkDaemon
          _      <- ZIO.fromFuture(_ => latch.future).orDie
          result <- Live.withLive(fiber.interrupt)(_.timeout(10.milliseconds))
        } yield assert(result)(isNone)
      }
    ) @@ zioTag(future, interruption) @@ exceptJS,
    suite("head")(
      test("on non empty list") {
        assertZIO(ZIO.succeed(List(1, 2, 3)).head.either)(isRight(equalTo(1)))
      },
      test("on empty list") {
        assertZIO(ZIO.succeed(List.empty).head.either)(isLeft(isNone))
      },
      test("on failure") {
        assertZIO(ZIO.fail("Fail").head.either)(isLeft(isSome(equalTo("Fail"))))
      } @@ zioTag(errors)
    ),
    suite("ifZIO")(
      test("runs `onTrue` if result of `b` is `true`") {
        val zio = ZIO.ifZIO(ZIO.succeed(true))(ZIO.succeed(true), ZIO.succeed(false))
        assertZIO(zio)(isTrue)
      },
      test("runs `onFalse` if result of `b` is `false`") {
        val zio = ZIO.ifZIO(ZIO.succeed(false))(ZIO.succeed(true), ZIO.succeed(false))
        assertZIO(zio)(isFalse)
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZIO[R, E, Boolean]   = ZIO.succeed(true)
        val onTrue: ZIO[R1, E1, A]  = ZIO.succeed(new A {})
        val onFalse: ZIO[R1, E1, A] = ZIO.succeed(new A {})
        val _                       = ZIO.ifZIO(b)(onTrue, onFalse)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("ignore")(
      test("return success as Unit") {
        assertZIO(ZIO.succeed(11).ignore)(equalTo(()))
      },
      test("return failure as Unit") {
        assertZIO(ZIO.fail(123).ignore)(equalTo(()))
      } @@ zioTag(errors),
      test("not catch throwable") {
        assertZIO(ZIO.die(ExampleError).ignore.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors)
    ),
    suite("isFailure")(
      test("returns true when the effect is a failure") {
        assertZIO(ZIO.fail("fail").isFailure)(isTrue)
      },
      test("returns false when the effect is a success") {
        assertZIO(ZIO.succeed("succeed").isFailure)(isFalse)
      }
    ),
    suite("isSuccess")(
      test("returns false when the effect is a failure") {
        assertZIO(ZIO.fail("fail").isSuccess)(isFalse)
      },
      test("returns true when the effect is a success") {
        assertZIO(ZIO.succeed("succeed").isSuccess)(isTrue)
      }
    ),
    suite("iterate")(
      test("iterates with the specified effectual function") {
        for {
          result <- ZIO.iterate(100)(_ > 0)(a => ZIO.succeed(a - 1))
        } yield assert(result)(equalTo(0))
      }
    ),
    suite("left")(
      test("on Left value") {
        assertZIO(ZIO.succeed(Left("Left")).left)(equalTo("Left"))
      },
      test("on Right value") {
        assertZIO(ZIO.succeed(Right("Right")).left.exit)(fails(isRight(equalTo("Right"))))
      },
      test("on failure") {
        assertZIO(ZIO.fail("Fail").left.exit)(fails(isLeft(equalTo("Fail"))))
      } @@ zioTag(errors)
    ),
    suite("onExecutor")(
      test("effects continue on current executor if no executor is specified") {
        val thread = ZIO.succeed(Thread.currentThread())

        val global =
          Executor.fromExecutionContext(scala.concurrent.ExecutionContext.global)
        for {
          which   <- Ref.make[Option[Thread]](None)
          beforeL <- ZIO.descriptor.map(_.isLocked)
          _       <- thread.flatMap(t => which.set(Some(t))).onExecutor(global)
          after   <- thread
          during  <- which.get.some
          afterL  <- ZIO.descriptor.map(_.isLocked)
        } yield assert(beforeL)(isFalse) && assert(afterL)(isFalse) && assert(during)(equalTo(after))
      },
      test("effects are shifted back if executor is specified") {
        val default = Runtime.defaultExecutor
        val global =
          Executor.fromExecutionContext(scala.concurrent.ExecutionContext.global)
        val effect = for {
          _        <- ZIO.unit.onExecutor(global)
          executor <- ZIO.descriptor.map(_.executor)
        } yield assert(executor)(equalTo(default))
        effect.onExecutor(default)
      },
      test("effects are shifted back at next yield") {
        val default = Runtime.defaultExecutor
        val global =
          Executor.fromExecutionContext(scala.concurrent.ExecutionContext.global)
        for {
          _        <- ZIO.unit.onExecutor(global)
          executor <- ZIO.executor
        } yield assert(executor)(equalTo(default))
      }
    ),
    suite("loop")(
      test("loops with the specified effectual function") {
        for {
          ref    <- Ref.make(List.empty[Int])
          _      <- ZIO.loop(0)(_ < 5, _ + 1)(a => ref.update(a :: _))
          result <- ref.get.map(_.reverse)
        } yield assert(result)(equalTo(List(0, 1, 2, 3, 4)))
      },
      test("collects the results into a list") {
        for {
          result <- ZIO.loop(0)(_ < 5, _ + 2)(a => ZIO.succeed(a * 3))
        } yield assert(result)(equalTo(List(0, 6, 12)))
      }
    ),
    suite("loopDiscard")(
      test("loops with the specified effectual function") {
        for {
          ref    <- Ref.make(List.empty[Int])
          _      <- ZIO.loopDiscard(0)(_ < 5, _ + 1)(a => ref.update(a :: _))
          result <- ref.get.map(_.reverse)
        } yield assert(result)(equalTo(List(0, 1, 2, 3, 4)))
      }
    ),
    suite("mapBoth")(
      test("maps over both error and value channels") {
        check(Gen.int) { i =>
          val res = ZIO.fail(i).mapBoth(_.toString, identity).either
          assertZIO(res)(isLeft(equalTo(i.toString)))
        }
      }
    ),
    suite("mapEffect")(
      test("returns an effect whose success is mapped by the specified side effecting function") {
        val task = ZIO.succeed("123").mapAttempt(_.toInt)
        assertZIO(task)(equalTo(123))
      },
      test("translates any thrown exceptions into typed failed effects") {
        val task = ZIO.succeed("hello").mapAttempt(_.toInt)
        assertZIO(task.exit)(fails(isSubtype[NumberFormatException](anything)))
      }
    ),
    suite("mapError")(
      test("preserves the cause") {
        val task = ZIO.logSpan("parent")(ZIO.logSpan("child")(ZIO.logAnnotate("key", "value")(ZIO.fail("fail"))))
        for {
          leftCause       <- task.cause
          rightCause      <- task.mapError(identity).cause
          leftTrace        = leftCause.trace.stackTrace.head
          rightTrace       = rightCause.trace.stackTrace.head
          leftSpans        = leftCause.spans.map(_.label)
          rightSpans       = rightCause.spans.map(_.label)
          leftAnnotations  = leftCause.annotations
          rightAnnotations = rightCause.annotations
        } yield assertTrue(leftTrace == rightTrace) &&
          assertTrue(leftSpans == rightSpans) &&
          assertTrue(leftAnnotations == rightAnnotations)
      }
    ),
    suite("memoize")(
      test("non-memoized returns new instances on repeated calls") {
        val io = Random.nextString(10)
        (io <*> io)
          .map(tuple => assert(tuple._1)(not(equalTo(tuple._2))))
      },
      test("memoized returns the same instance on repeated calls") {
        val ioMemo = Random.nextString(10).memoize
        ioMemo
          .flatMap(io => io <*> io)
          .map(tuple => assert(tuple._1)(equalTo(tuple._2)))
      },
      test("memoized function returns the same instance on repeated calls") {
        for {
          memoized <- ZIO.memoize((n: Int) => Random.nextString(n))
          a        <- memoized(10)
          b        <- memoized(10)
          c        <- memoized(11)
          d        <- memoized(11)
        } yield assert(a)(equalTo(b)) &&
          assert(b)(not(equalTo(c))) &&
          assert(c)(equalTo(d))
      }
    ),
    suite("merge")(
      test("on flipped result") {
        val zio: IO[Int, Int] = ZIO.succeed(1)

        for {
          a <- zio.merge
          b <- zio.flip.merge
        } yield assert(a)(equalTo(b))
      }
    ),
    suite("mergeAll")(
      test("return zero element on empty input") {
        val zeroElement = 42
        val nonZero     = 43
        ZIO.mergeAll(Nil)(zeroElement)((_, _) => nonZero).map {
          assert(_)(equalTo(zeroElement))
        }
      },
      test("merge list using function") {
        val effects = List(3, 5, 7).map(ZIO.succeed(_))
        ZIO.mergeAll(effects)(zero = 1)(_ + _).map {
          assert(_)(equalTo(1 + 3 + 5 + 7))
        }
      },
      test("return error if it exists in list") {
        val effects = List(ZIO.unit, ZIO.fail(1))
        val merged  = ZIO.mergeAll(effects)(zero = ())((_, _) => ())
        assertZIO(merged.exit)(fails(equalTo(1)))
      } @@ zioTag(errors)
    ),
    suite("mergeAllPar")(
      test("return zero element on empty input") {
        val zeroElement = 42
        val nonZero     = 43
        ZIO.mergeAllPar(Nil)(zeroElement)((_, _) => nonZero).map {
          assert(_)(equalTo(zeroElement))
        }
      },
      test("merge list using function") {
        val effects = List(3, 5, 7).map(ZIO.succeed(_))
        ZIO.mergeAllPar(effects)(zero = 1)(_ + _).map {
          assert(_)(equalTo(1 + 3 + 5 + 7))
        }
      },
      test("return error if it exists in list") {
        val effects = List(ZIO.unit, ZIO.fail(1))
        val merged  = ZIO.mergeAllPar(effects)(zero = ())((_, _) => ())
        assertZIO(merged.exit)(fails(equalTo(1)))
      } @@ zioTag(errors)
    ),
    suite("none")(
      test("on Some fails with None") {
        val task: IO[Option[Throwable], Unit] = ZIO.attempt(Some(1)).none
        assertZIO(task.exit)(fails(isNone))
      },
      test("on None succeeds with ()") {
        val task: IO[Option[Throwable], Unit] = ZIO.attempt(None).none
        assertZIO(task)(isUnit)
      },
      test("fails with Some(ex) when effect fails with ex") {
        val ex                                = new RuntimeException("Failed Task")
        val task: IO[Option[Throwable], Unit] = ZIO.fail(ex).none
        assertZIO(task.exit)(fails(isSome(equalTo(ex))))
      } @@ zioTag(errors)
    ),
    suite("fork")(
      test("propagates interruption") {
        assertZIO(ZIO.never.fork.flatMap(_.interrupt))(isJustInterrupted)
      },
      test("propagates interruption with zip of defect") {
        for {
          latch <- Promise.make[Nothing, Unit]
          fiber <- (latch.succeed(()) *> ZIO.die(new Error)).zipPar(ZIO.never).fork
          _     <- latch.await
          exit  <- fiber.interrupt.map(_.mapErrorCauseExit((cause: Cause[Nothing]) => cause.untraced))
        } yield assert(exit)(isInterrupted)
      } @@ exceptJS(nonFlaky),
      test("child fibers can be created on interrupted parents within unininterruptible regions") {
        for {
          latch1       <- Promise.make[Nothing, Unit]
          isInterupted <- Promise.make[Nothing, Boolean]
          parent <- (latch1.succeed(()) *> ZIO.never).onInterrupt {
                      for {
                        latch2 <- Promise.make[Nothing, Unit]
                        child  <- latch2.await.fork
                        _      <- ZIO.sleep(5.millis)
                        _      <- isInterupted.done(Exit.succeed(child.asInstanceOf[FiberRuntime[?, ?]].isInterrupted()))
                        _      <- latch2.succeed(())
                      } yield ()
                    }.forkDaemon
          _   <- latch1.await
          _   <- parent.interrupt
          res <- isInterupted.await
        } yield assertTrue(!res)
      } @@ withLiveClock @@ exceptJS(nonFlaky) @@ timeout(20.seconds) @@ zioTag(interruption)
    ),
    suite("negate")(
      test("on true returns false") {
        assertZIO(ZIO.succeed(true).negate)(equalTo(false))
      },
      test("on false returns true") {
        assertZIO(ZIO.succeed(false).negate)(equalTo(true))
      }
    ),
    suite("noneOrFail")(
      test("on None succeeds with Unit") {
        val option: Option[String] = None
        for {
          value <- ZIO.noneOrFail(option)
        } yield {
          assert(value)(equalTo(()))
        }
      },
      test("on Some fails") {
        for {
          value <- ZIO.noneOrFail(Some("v")).catchAll(e => ZIO.succeed(e))
        } yield {
          assert(value)(equalTo("v"))
        }
      } @@ zioTag(errors)
    ),
    suite("noneOrFailWith")(
      test("on None succeeds with Unit") {
        val option: Option[String]       = None
        val adaptError: String => String = identity
        for {
          value <- ZIO.noneOrFailWith(option)(adaptError)
        } yield {
          assert(value)(equalTo(()))
        }
      },
      test("on Some fails") {
        for {
          value <- ZIO.noneOrFailWith(Some("value"))((v: String) => v + v).catchAll(e => ZIO.succeed(e))
        } yield {
          assert(value)(equalTo("valuevalue"))
        }
      } @@ zioTag(errors)
    ),
    suite("once")(
      test("returns an effect that will only be executed once") {
        for {
          ref    <- Ref.make(0)
          zio    <- ref.update(_ + 1).once
          _      <- ZIO.collectAllPar(ZIO.replicate(100)(zio))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("onExit")(
      test("executes that a cleanup function runs when effect succeeds") {
        for {
          ref <- Ref.make(false)
          _ <- ZIO.unit.onExit {
                 case Exit.Success(_) => ref.set(true)
                 case _               => ZIO.unit
               }
          p <- ref.get
        } yield assert(p)(isTrue)
      },
      test("ensures that a cleanup function runs when an effect fails") {
        for {
          ref <- Ref.make(false)
          _ <- ZIO
                 .die(new RuntimeException)
                 .onExit {
                   case Exit.Failure(c) if c.isDie => ref.set(true)
                   case _                          => ZIO.unit
                 }
                 .sandbox
                 .ignore
          p <- ref.get
        } yield assert(p)(isTrue)
      },
      test("ensures that a cleanup function runs when an effect is interrupted") {
        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          fiber <- (latch1.succeed(()) *> ZIO.never).onExit {
                     case Exit.Failure(c) if c.isInterrupted => latch2.succeed(())
                     case _                                  => ZIO.unit
                   }.fork
          _ <- latch1.await
          _ <- fiber.interrupt
          _ <- latch2.await
        } yield assertCompletes
      } @@ zioTag(interruption)
    ),
    suite("option")(
      test("return success in Some") {
        implicit val canFail = CanFail
        assertZIO(ZIO.succeed(11).option)(equalTo(Some(11)))
      },
      test("return failure as None") {
        assertZIO(ZIO.fail(123).option)(equalTo(None))
      } @@ zioTag(errors),
      test("not catch throwable") {
        implicit val canFail = CanFail
        assertZIO(ZIO.die(ExampleError).option.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("catch throwable after sandboxing") {
        assertZIO(ZIO.die(ExampleError).sandbox.option)(equalTo(None))
      } @@ zioTag(errors)
    ),
    suite("unsome")(
      test("fails when given Some error") {
        val task: IO[String, Option[Int]] = ZIO.fail(Some("Error")).unsome
        assertZIO(task.exit)(fails(equalTo("Error")))
      } @@ zioTag(errors),
      test("succeeds with None given None error") {
        val task: IO[String, Option[Int]] = ZIO.fail(None).unsome
        assertZIO(task)(isNone)
      } @@ zioTag(errors),
      test("succeeds with Some given a value") {
        val task: IO[String, Option[Int]] = ZIO.succeed(1).unsome
        assertZIO(task)(isSome(equalTo(1)))
      }
    ),
    suite("orElse")(
      test("does not recover from defects") {
        val ex               = new Exception("Died")
        val fiberId          = FiberId(0, 123, Trace.empty)
        implicit val canFail = CanFail
        for {
          plain <- (ZIO.die(ex) <> ZIO.unit).exit
          both  <- (ZIO.failCause(Cause.Both(interrupt(fiberId), die(ex))) <> ZIO.unit).exit
          thn   <- (ZIO.failCause(Cause.Then(interrupt(fiberId), die(ex))) <> ZIO.unit).exit
          fail  <- (ZIO.fail(ex) <> ZIO.unit).exit
        } yield assert(plain)(dies(equalTo(ex))) &&
          assert(both)(dies(equalTo(ex))) &&
          assert(thn)(dies(equalTo(ex))) &&
          assert(fail)(succeeds(isUnit))
      },
      test("left failed and right died with kept cause") {
        val z1                = ZIO.fail(new Throwable("1"))
        val z2: Task[Nothing] = ZIO.die(new Throwable("2"))
        val orElse: Task[Boolean] = z1.orElse(z2).catchAllCause {
          case Die(e: Throwable, _) => ZIO.attempt(e.getMessage == "2")
          case _                    => ZIO.attempt(false)
        }
        assertZIO(orElse)(equalTo(true))
      },
      test("left failed and right failed with kept cause") {
        val z1                = ZIO.fail(new Throwable("1"))
        val z2: Task[Nothing] = ZIO.fail(new Throwable("2"))
        val orElse: Task[Boolean] = z1.orElse(z2).catchAllCause {
          case Fail(e: Throwable, _) => ZIO.attempt(e.getMessage == "2")
          case _                     => ZIO.attempt(false)
        }
        assertZIO(orElse)(equalTo(true))
      },
      test("is associative") {
        val smallInts = Gen.int(0, 100)
        val causes    = Gen.causes(smallInts, Gen.throwable)
        val successes = Gen.successes(smallInts)
        val exits     = Gen.either(causes, successes).map(_.fold(Exit.failCause, Exit.succeed))
        check(exits, exits, exits) { (exit1, exit2, exit3) =>
          val zio1  = ZIO.done(exit1)
          val zio2  = ZIO.done(exit2)
          val zio3  = ZIO.done(exit3)
          val left  = (zio1 orElse zio2) orElse zio3
          val right = zio1 orElse (zio2 orElse zio3)
          for {
            left  <- left.exit
            right <- right.exit
          } yield assert(left)(equalTo(right))
        }
      }
    ) @@ zioTag(errors),
    suite("orElseFail")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        assertZIO(ZIO.succeed(true).orElseFail(false))(isTrue)
      },
      test("otherwise fails with the specified error") {
        assertZIO(ZIO.fail(false).orElseFail(true).flip)(isTrue)
      } @@ zioTag(errors)
    ),
    suite("orElseOptional")(
      test("produces the value of this effect if it succeeds") {
        val zio = ZIO.succeed("succeed").orElseOptional(ZIO.succeed("orElse"))
        assertZIO(zio)(equalTo("succeed"))
      },
      test("produces the value of this effect if it fails with some error") {
        val zio = ZIO.fail(Some("fail")).orElseOptional(ZIO.succeed("orElse"))
        assertZIO(zio.exit)(fails(isSome(equalTo("fail"))))
      },
      test("produces the value of the specified effect if it fails with none") {
        val zio = ZIO.fail(None).orElseOptional(ZIO.succeed("orElse"))
        assertZIO(zio)(equalTo("orElse"))
      }
    ),
    suite("orElseSucceed")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        assertZIO(ZIO.succeed(true).orElseSucceed(false))(isTrue)
      },
      test("otherwise succeeds with the specified value") {
        assertZIO(ZIO.fail(false).orElseSucceed(true))(isTrue)
      } @@ zioTag(errors)
    ),
    suite("parallelErrors")(
      test("oneFailure") {
        for {
          f1     <- ZIO.fail("error1").fork
          f2     <- ZIO.succeed("success1").fork
          errors <- f1.zip(f2).join.parallelErrors[String].flip
        } yield assert(errors)(equalTo(List("error1")))
      },
      test("allFailures") {
        for {
          f1     <- ZIO.fail("error1").fork
          f2     <- ZIO.fail("error2").fork
          errors <- f1.zip(f2).join.parallelErrors[String].flip
        } yield assert(errors)(
          equalTo(List("error1", "error2")) ||
            equalTo(List("error1")) ||
            equalTo(List("error2"))
        )
      } @@ exceptJS(nonFlaky)
    ) @@ zioTag(errors),
    suite("partition")(
      test("collects only successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        for {
          res <- ZIO.partition(in)(a => ZIO.succeed(a))
        } yield assert(res._1)(isEmpty) && assert(res._2)(equalTo(in))
      },
      test("collects only failures") {
        val in = List.fill(10)(0)
        for {
          res <- ZIO.partition(in)(a => ZIO.fail(a))
        } yield assert(res._1)(equalTo(in)) && assert(res._2)(isEmpty)
      } @@ zioTag(errors),
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partition(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a))
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      } @@ zioTag(errors),
      test("evaluates effects in correct order") {
        implicit val canFail = CanFail
        val as               = List(2, 4, 6, 3, 5, 6)
        for {
          ref     <- Ref.make(List.empty[Int])
          _       <- ZIO.partition(as)(a => ref.update(a :: _))
          effects <- ref.get.map(_.reverse)
        } yield assert(effects)(equalTo(List(2, 4, 6, 3, 5, 6)))
      }
    ),
    suite("partitionPar")(
      test("collects a lot of successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 1000)
        for {
          res <- ZIO.partitionPar(in)(a => ZIO.succeed(a))
        } yield assert(res._1)(isEmpty) && assert(res._2)(equalTo(in))
      },
      test("collects failures") {
        val in = List.fill(10)(0)
        for {
          res <- ZIO.partitionPar(in)(a => ZIO.fail(a))
        } yield assert(res._1)(equalTo(in)) && assert(res._2)(isEmpty)
      } @@ zioTag(errors),
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partitionPar(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a))
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      } @@ zioTag(errors)
    ),
    suite("partitionParN")(
      test("collects a lot of successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 1000)
        for {
          res <- ZIO.partitionPar(in)(a => ZIO.succeed(a)).withParallelism(3)
        } yield assert(res._1)(isEmpty) && assert(res._2)(equalTo(in))
      },
      test("collects failures") {
        val in = List.fill(10)(0)
        for {
          res <- ZIO.partitionPar(in)(a => ZIO.fail(a)).withParallelism(3)
        } yield assert(res._1)(equalTo(in)) && assert(res._2)(isEmpty)
      } @@ zioTag(errors),
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partitionPar(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a)).withParallelism(3)
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      } @@ zioTag(errors)
    ),
    test("provideSomeLayer") {
      for {
        ref    <- Ref.make(0)
        scope  <- Scope.make
        scoped  = ZIO.acquireRelease(ref.update(_ + 1))(_ => ref.update(_ - 1))
        layer   = ZLayer.scoped(scoped)
        _      <- scope.extend(layer.build.provideSomeLayer[Scope](ZLayer.empty))
        before <- ref.get
        _      <- scope.close(Exit.unit)
        after  <- ref.get
      } yield assertTrue(before == 1) &&
        assertTrue(after == 0)
    },
    suite("raceAll")(
      test("returns first success") {
        assertZIO(ZIO.fail("Fail").raceAll(List(ZIO.succeed(24))))(equalTo(24))
      },
      test("returns last failure") {
        assertZIO(Live.live(ZIO.sleep(100.millis) *> ZIO.fail(24)).raceAll(List(ZIO.fail(25))).flip)(equalTo(24))
      } @@ flaky @@ zioTag(errors),
      test("returns success when it happens after failure") {
        assertZIO(ZIO.fail(42).raceAll(List(ZIO.succeed(24) <* Live.live(ZIO.sleep(100.millis)))))(equalTo(24))
      } @@ zioTag(errors)
    ),
    suite("reduceAllPar")(
      test("return zero element on empty input") {
        val zeroElement = 42
        val nonZero     = 43
        ZIO.reduceAllPar(ZIO.succeed(zeroElement), Nil)((_, _) => nonZero).map {
          assert(_)(equalTo(zeroElement))
        }
      },
      test("reduce list using function") {
        val zeroElement  = ZIO.succeed(1)
        val otherEffects = List(3, 5, 7).map(ZIO.succeed(_))
        ZIO.reduceAllPar(zeroElement, otherEffects)(_ + _).map {
          assert(_)(equalTo(1 + 3 + 5 + 7))
        }
      },
      test("return error if zero is an error") {
        val zeroElement  = ZIO.fail(1)
        val otherEffects = List(ZIO.unit, ZIO.unit)
        val reduced      = ZIO.reduceAllPar(zeroElement, otherEffects)((_, _) => ())
        assertZIO(reduced.exit)(fails(equalTo(1)))
      } @@ zioTag(errors),
      test("return error if it exists in list") {
        val zeroElement = ZIO.unit
        val effects     = List(ZIO.unit, ZIO.fail(1))
        val reduced     = ZIO.reduceAllPar(zeroElement, effects)((_, _) => ())
        assertZIO(reduced.exit)(fails(equalTo(1)))
      } @@ zioTag(errors)
    ),
    suite("replicate")(
      test("zero") {
        val lst: Iterable[UIO[Int]] = ZIO.replicate(0)(ZIO.succeed(12))
        assertZIO(ZIO.collectAll(lst))(equalTo(List.empty))
      },
      test("negative") {
        val anotherList: Iterable[UIO[Int]] = ZIO.replicate(-2)(ZIO.succeed(12))
        assertZIO(ZIO.collectAll(anotherList))(equalTo(List.empty))
      },
      test("positive") {
        val lst: Iterable[UIO[Int]] = ZIO.replicate(2)(ZIO.succeed(12))
        assertZIO(ZIO.collectAll(lst))(equalTo(List(12, 12)))
      }
    ),
    suite("retryUntil")(
      test("retryUntil retries until condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryUntil(_ == 0))
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("retryUntil runs at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).flipWith(_.retryUntil(_ => true))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("retryUntilEquals")(
      test("retryUntilEquals retries until error equals predicate") {
        for {
          q      <- Queue.unbounded[Int]
          _      <- q.offerAll(List(1, 2, 3, 4, 5, 6))
          acc    <- Ref.make(0)
          _      <- (q.take <* acc.update(_ + 1)).flipWith(_.retryUntilEquals(5))
          result <- acc.get
        } yield assert(result)(equalTo(5))
      }
    ),
    suite("retryUntilZIO")(
      test("retryUntilZIO retries until condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryUntilZIO(v => ZIO.succeed(v == 0)))
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("retryUntilZIO runs at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).flipWith(_.retryUntilZIO(_ => ZIO.succeed(true)))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("retryWhile")(
      test("retryWhile retries while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryWhile(_ >= 0))
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("retryWhile runs at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).flipWith(_.retryWhile(_ => false))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("retryWhileEquals")(
      test("retryWhileEquals retries while error equals predicate") {
        for {
          q      <- Queue.unbounded[Int]
          _      <- q.offerAll(List(0, 0, 0, 0, 1, 2))
          acc    <- Ref.make(0)
          _      <- (q.take <* acc.update(_ + 1)).flipWith(_.retryWhileEquals(0))
          result <- acc.get
        } yield assert(result)(equalTo(5))
      }
    ),
    suite("retryWhileZIO")(
      test("retryWhileZIO retries while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryWhileZIO(v => ZIO.succeed(v >= 0)))
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("retryWhileZIO runs at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).flipWith(_.retryWhileZIO(_ => ZIO.succeed(false)))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
    ),
    suite("right")(
      test("on Right value") {
        assertZIO(ZIO.succeed(Right("Right")).right)(equalTo("Right"))
      },
      test("on Left value") {
        assertZIO(ZIO.succeed(Left("Left")).right.exit)(fails(isLeft(equalTo("Left"))))
      },
      test("on failure") {
        assertZIO(ZIO.fail("Fail").right.exit)(fails(isRight(equalTo("Fail"))))
      } @@ zioTag(errors)
    ),
    suite("refineToOrDie")(
      test("does not compile when refined type is not subtype of error type") {
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
      } @@ scala2Only,
      test("preserves the cause") {
        val task = ZIO.logAnnotate("key", "value")(ZIO.fail(new RuntimeException("fail")))
        for {
          leftCause       <- task.cause
          rightCause      <- task.refineToOrDie[RuntimeException].cause
          leftTrace        = leftCause.trace.stackTrace.head
          rightTrace       = rightCause.trace.stackTrace.head
          leftSpans        = leftCause.spans.map(_.label)
          rightSpans       = rightCause.spans.map(_.label)
          leftAnnotations  = leftCause.annotations
          rightAnnotations = rightCause.annotations
        } yield assertTrue(leftTrace == rightTrace) &&
          assertTrue(leftSpans == rightSpans) &&
          assertTrue(leftAnnotations == rightAnnotations)
      }
    ) @@ zioTag(errors),
    suite("some")(
      test("extracts the value from Some") {
        val task: IO[Option[Throwable], Int] = ZIO.attempt(Some(1)).some
        assertZIO(task)(equalTo(1))
      },
      test("make a task from a defined option") {
        assertZIO(ZIO.getOrFail(Some(1)))(equalTo(1))
      },
      test("make a task from an empty option") {
        assertZIO(ZIO.getOrFail(None).exit)(fails(isSubtype[NoSuchElementException](anything)))
      } @@ zioTag(errors),
      test("fails on None") {
        val task: IO[Option[Throwable], Int] = ZIO.attempt(None).some
        assertZIO(task.exit)(fails(isNone))
      } @@ zioTag(errors),
      test("fails when given an exception") {
        val ex                               = new RuntimeException("Failed Task")
        val task: IO[Option[Throwable], Int] = ZIO.fail(ex).some
        assertZIO(task.exit)(fails(isSome(equalTo(ex))))
      } @@ zioTag(errors)
    ),
    suite("someOrFailException")(
      test("extracts the optional value") {
        assertZIO(ZIO.some(42).someOrFailException)(equalTo(42))
      },
      test("fails when given a None") {
        val task = ZIO.succeed(Option.empty[Int]).someOrFailException
        assertZIO(task.exit)(fails(isSubtype[NoSuchElementException](anything)))
      } @@ zioTag(errors),
      suite("without another error type")(
        test("succeed something") {
          assertZIO(ZIO.succeed(Option(3)).someOrFailException)(equalTo(3))
        },
        test("succeed nothing") {
          assertZIO(ZIO.succeed(None: Option[Int]).someOrFailException.exit)(fails(Assertion.anything))
        } @@ zioTag(errors)
      ),
      suite("with throwable as base error type")(
        test("return something") {
          assertZIO(ZIO.attempt(Option(3)).someOrFailException)(equalTo(3))
        }
      ),
      suite("with exception as base error type")(
        test("return something") {
          assertZIO((ZIO.succeed(Option(3)): IO[Exception, Option[Int]]).someOrFailException)(equalTo(3))
        }
      )
    ),
    suite("reject")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.reject { case v if v != 0 => "Partial failed!" }).sandbox.either
          badCase <-
            exactlyOnce(1)(_.reject { case v if v != 0 => "Partial failed!" }).sandbox.either
              .map(_.left.map(_.failureOrCause))
        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Partial failed!"))))
      }
    ) @@ zioTag(errors),
    suite("rejectZIO")(
      test("Check `rejectZIO` returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(
              _.rejectZIO[Any, String] { case v if v != 0 => ZIO.succeed("Partial failed!") }
            ).sandbox.either
          partialBadCase <-
            exactlyOnce(1)(_.rejectZIO { case v if v != 0 => ZIO.fail("Partial failed!") }).sandbox.either
              .map(_.left.map(_.failureOrCause))
          badCase <-
            exactlyOnce(1)(_.rejectZIO { case v if v != 0 => ZIO.fail("Partial failed!") }).sandbox.either
              .map(_.left.map(_.failureOrCause))

        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(partialBadCase)(isLeft(isLeft(equalTo("Partial failed!")))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Partial failed!"))))
      }
    ),
    suite("RTS synchronous correctness")(
      test("fast-path resumption") {
        for {
          _ <- ZIOAppDefault.fromZIO(ZIO.yieldNow).invoke(Chunk.empty)
        } yield assertCompletes
      },
      test("widen Nothing") {
        val op1 = ZIO.succeed[String]("1")
        val op2 = ZIO.succeed[String]("2")

        assertZIO(op1.zipWith(op2)(_ + _))(equalTo("12"))
      },
      test("succeed must be lazy") {
        val io =
          try {
            ZIO.succeed(throw ExampleError)
            ZIO.succeed(true)
          } catch {
            case _: Throwable => ZIO.succeed(false)
          }

        assertZIO(io)(isTrue)
      },
      test("suspend must be lazy") {
        val io =
          try {
            ZIO.suspend[Any, Nothing](throw ExampleError)
            ZIO.succeed(false)
          } catch {
            case _: Throwable => ZIO.succeed(true)
          }

        assertZIO(io)(isFalse)
      },
      test("suspendSucceed must not catch throwable") {
        val io = ZIO.suspendSucceed[Any, Nothing, Any](throw ExampleError).sandbox.either
        assertZIO(io)(isLeft(equalTo(Cause.die(ExampleError))))
      },
      test("suspend must catch throwable") {
        val io = ZIO.suspend[Any, Nothing](throw ExampleError).either
        assertZIO(io)(isLeft(equalTo(ExampleError)))
      },
      test("suspendSucceed must be evaluatable") {
        assertZIO(ZIO.suspendSucceed(ZIO.succeed(42)))(equalTo(42))
      },
      test("point, bind, map") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.succeed(n)
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertZIO(fibIo(10))(equalTo(fib(10)))
      },
      test("effect, bind, map") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.attempt(n)
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertZIO(fibIo(10))(equalTo(fib(10)))
      },
      test("effect, bind, map, redeem") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.attempt[BigInt](throw ExampleError).catchAll(_ => ZIO.attempt(n))
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertZIO(fibIo(10))(equalTo(fib(10)))
      },
      test("sync effect") {
        def sumIo(n: Int): Task[Int] =
          if (n <= 0) ZIO.succeed(0)
          else ZIO.succeed(n).flatMap(b => sumIo(n - 1).map(a => a + b))

        assertZIO(sumIo(1000))(equalTo(sum(1000)))
      },
      test("deep effects") {
        def incLeft(n: Int, ref: Ref[Int]): Task[Int] =
          if (n <= 0) ref.get
          else incLeft(n - 1, ref) <* ref.update(_ + 1)

        def incRight(n: Int, ref: Ref[Int]): Task[Int] =
          if (n <= 0) ref.get
          else ref.update(_ + 1) *> incRight(n - 1, ref)

        val l =
          for {
            ref <- Ref.make(0)
            v   <- incLeft(100, ref)
          } yield v == 0

        val r =
          for {
            ref <- Ref.make(0)
            v   <- incRight(1000, ref)
          } yield v == 1000

        assertZIO(l.zipWith(r)(_ && _))(isTrue)
      },
      test("flip must make error into value") {
        val io = ZIO.fail(ExampleError).flip
        assertZIO(io)(equalTo(ExampleError))
      } @@ zioTag(errors),
      test("flip must make value into error") {
        val io = ZIO.succeed(42).flip
        assertZIO(io.either)(isLeft(equalTo(42)))
      } @@ zioTag(errors),
      test("flipping twice returns identical value") {
        val io = ZIO.succeed(42)
        assertZIO(io.flip.flip)(equalTo(42))
      }
    ),
    suite("Timeout don't lose all values from parent fiber")(
      test("When effect is winner and failed") {
        val (initial, update) = ("initial", "update")
        def effect(fiberRef: FiberRef[String]) =
          fiberRef.set(update) *> ZIO.fail(new Throwable("Effect failed"))
        for {
          fiberRef <- FiberRef.make[String](initial)
          _ <- effect(fiberRef)
                 .timeout(Duration.Infinity)
                 .catchAll(_ => ZIO.unit)
          res <- fiberRef.get
        } yield assert(res)(equalTo(update))
      },
      test("When effect is loser") {
        val (initial, update) = ("initial", "update")
        def effect(fiberRef: FiberRef[String]) =
          fiberRef.set(update) *>
            Clock.sleep(1.second)
        for {
          fiberRef <- FiberRef.make[String](initial)
          _        <- effect(fiberRef).timeout(10.millis)
          res      <- fiberRef.get
        } yield assert(res)(equalTo(update))
      }
    ) @@ TestAspect.withLiveClock,
    suite("RTS failure")(
      test("error in sync effect") {
        val io = ZIO.attempt[Unit](throw ExampleError).fold[Option[Throwable]](Some(_), _ => None)
        assertZIO(io)(isSome(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("attempt . fail") {
        val io1 = TaskExampleError.either
        val io2 = ZIO.suspendSucceed(ZIO.suspendSucceed(TaskExampleError).either)

        io1.zipWith(io2) { case (r1, r2) =>
          assert(r1)(isLeft(equalTo(ExampleError))) && assert(r2)(isLeft(equalTo(ExampleError)))
        }
      } @@ zioTag(errors),
      test("deep attempt sync effect error") {
        assertZIO(deepErrorEffect(100).either)(isLeft(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("deep attempt fail error") {
        assertZIO(deepErrorFail(100).either)(isLeft(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("attempt . sandbox . terminate") {
        val io = ZIO.succeed[Int](throw ExampleError).sandbox.either
        assertZIO(io)(isLeft(equalTo(Cause.die(ExampleError))))
      } @@ zioTag(errors),
      test("fold . sandbox . terminate") {
        val io = ZIO.succeed[Int](throw ExampleError).sandbox.fold(Some(_), Function.const(None))
        assertZIO(io)(isSome(equalTo(Cause.die(ExampleError))))
      } @@ zioTag(errors),
      test("catch sandbox terminate") {
        val io = ZIO.succeed(throw ExampleError).sandbox.merge
        assertZIO(io)(equalTo(Cause.die(ExampleError)))
      } @@ zioTag(errors),
      test("uncaught fail") {
        assertZIO(TaskExampleError.exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("uncaught sync effect error") {
        val io = ZIO.succeed[Int](throw ExampleError)
        assertZIO(io.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("deep uncaught sync effect error") {
        assertZIO(deepErrorEffect(100).exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("catch failing finalizers with fail") {
        val io = ZIO
          .fail(ExampleError)
          .ensuring(ZIO.succeed(throw InterruptCause1))
          .ensuring(ZIO.succeed(throw InterruptCause2))
          .ensuring(ZIO.succeed(throw InterruptCause3))

        val expectedCause = Cause.fail(ExampleError) ++
          Cause.die(InterruptCause1) ++
          Cause.die(InterruptCause2) ++
          Cause.die(InterruptCause3)

        assertZIO(io.exit)(equalTo(Exit.failCause(expectedCause)))
      } @@ zioTag(errors),
      test("catch failing finalizers with terminate") {
        val io = ZIO
          .die(ExampleError)
          .ensuring(ZIO.succeed(throw InterruptCause1))
          .ensuring(ZIO.succeed(throw InterruptCause2))
          .ensuring(ZIO.succeed(throw InterruptCause3))

        val expectedCause = Cause.die(ExampleError) ++
          Cause.die(InterruptCause1) ++
          Cause.die(InterruptCause2) ++
          Cause.die(InterruptCause3)

        assertZIO(io.exit)(equalTo(Exit.failCause(expectedCause)))
      } @@ zioTag(errors),
      test("run preserves interruption status") {
        for {
          p    <- Promise.make[Nothing, Unit]
          f    <- (p.succeed(()) *> ZIO.never).fork
          _    <- p.await
          exit <- f.interrupt
        } yield assert(exit.mapErrorCauseExit((cause: Cause[Nothing]) => cause.untraced))(isJustInterrupted)
      } @@ zioTag(interruption),
      test("run swallows inner interruption") {
        for {
          p   <- Promise.make[Nothing, Int]
          _   <- ZIO.interrupt.exit *> p.succeed(42)
          res <- p.await
        } yield assert(res)(equalTo(42))
      } @@ zioTag(interruption),
      test("timeout a long computation") {
        val io = (Clock.sleep(5.seconds) *> ZIO.succeed(true)).timeout(10.millis)
        assertZIO(Live.live(io))(isNone)
      },
      test("timeout a long computation with an error") {
        val io = (Clock.sleep(5.seconds) *> ZIO.succeed(true)).timeoutFail(false)(10.millis)
        assertZIO(Live.live(io.exit))(fails(equalTo(false)))
      },
      test("timeout a long computation with a cause") {
        val cause = Cause.die(new Error("BOOM"))
        val io    = (Clock.sleep(5.seconds) *> ZIO.succeed(true)).timeoutFailCause(cause)(10.millis)
        assertZIO(Live.live(io.sandbox.flip))(equalTo(cause))
      },
      test("timeout repetition of uninterruptible effect") {
        val effect = ZIO.unit.uninterruptible.forever

        assertZIO(Live.live(effect.timeout(1.second)))(isNone)
      } @@ exceptJS @@ zioTag(interruption),
      test("timeout in uninterruptible region") {
        val effect = (ZIO.unit.timeout(Duration.Infinity)).uninterruptible
        assertZIO(effect)(isSome(isUnit))
      } @@ zioTag(interruption),
      test("timeout preserves uninterruptibility") {
        def run(start: Promise[Nothing, Unit], end: Promise[Nothing, Unit]) =
          ZIO.scoped {
            for {
              promise <- Promise.make[Nothing, Unit]
              _       <- promise.succeed(())
              _       <- start.succeed(()).withFinalizer(_ => promise.await.timeout(10.seconds).disconnect *> end.succeed(()))
              _       <- ZIO.never
            } yield ()
          }
        for {
          start <- Promise.make[Nothing, Unit]
          end   <- Promise.make[Nothing, Unit]
          fiber <- run(start, end).forkDaemon
          _     <- start.await
          _     <- fiber.interrupt
          _     <- end.await
        } yield assertCompletes
      },
      test("timeout does not allow interruption to be observed in uninterruptible region") {
        ZIO.uninterruptible {
          for {
            promise <- Promise.make[Nothing, Unit]
            fiber   <- (promise.succeed(()) *> ZIO.sleep(2.second).timeout(1.seconds)).fork
            _       <- promise.await
            exit    <- fiber.interrupt
          } yield assert(exit)(not(isInterrupted))
        }
      } @@ TestAspect.withLiveClock,
      test("catchAllCause") {
        val io =
          for {
            _ <- ZIO.succeed(42)
            f <- ZIO.fail("Uh oh!")
          } yield f

        assertZIO(io.catchAllCause(ZIO.succeed(_)))(equalTo(Cause.fail("Uh oh!")))
      } @@ zioTag(errors),
      test("exception in fromFuture does not kill fiber") {
        val io = ZIO.fromFuture(_ => throw ExampleError).either
        assertZIO(io)(isLeft(equalTo(ExampleError)))
      } @@ zioTag(errors, future)
    ),
    suite("RTS finalizers")(
      test("fail ensuring") {
        var finalized = false

        val io = ZIO.fail(ExampleError).ensuring(ZIO.succeed { finalized = true; () })

        for {
          a1 <- assertZIO(io.exit)(fails(equalTo(ExampleError)))
          a2  = assert(finalized)(isTrue)
        } yield a1 && a2
      } @@ zioTag(errors),
      test("fail on error") {
        @volatile var finalized = false

        val cleanup: Cause[Throwable] => UIO[Unit] =
          _ => ZIO.succeed[Unit] { finalized = true; () }

        val io = ZIO.fail(ExampleError).onError(cleanup)

        for {
          a1 <- assertZIO(io.exit)(fails(equalTo(ExampleError)))
          a2  = assert(finalized)(isTrue)
        } yield a1 && a2
      } @@ zioTag(errors),
      test("finalizer errors not caught") {
        val e2 = new Error("e2")
        val e3 = new Error("e3")

        val io = TaskExampleError.ensuring(ZIO.die(e2)).ensuring(ZIO.die(e3))

        val expectedCause: Cause[Throwable] =
          Cause.Then(Cause.fail(ExampleError), Cause.Then(Cause.die(e2), Cause.die(e3)))

        assertZIO(io.sandbox.flip.map(_.untraced))(equalTo(expectedCause))
      } @@ zioTag(errors),
      test("finalizer errors reported") {
        @volatile var reported: Exit[Nothing, Int] = null

        val io = ZIO
          .succeed[Int](42)
          .ensuring(ZIO.die(ExampleError))
          .fork
          .flatMap(_.await.flatMap[Any, Nothing, Any](e => ZIO.succeed { reported = e }))

        for {
          a1 <- assertZIO(io)(anything)
          a2  = assert(reported.isSuccess)(isFalse)
        } yield a1 && a2
      } @@ zioTag(errors),
      test("acquireReleaseWith exit is usage result") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.succeed[Int](42))
        assertZIO(io)(equalTo(42))
      },
      test("error in just acquisition") {
        val io = ZIO.acquireReleaseWith(TaskExampleError)(_ => ZIO.unit)(_ => ZIO.unit)
        assertZIO(io.exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("error in just release") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(ExampleError))(_ => ZIO.unit)
        assertZIO(io.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("error in just usage") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.fail(ExampleError))
        assertZIO(io.exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("rethrown caught error in acquisition") {
        val io = ZIO.absolve(ZIO.acquireReleaseWith(TaskExampleError)(_ => ZIO.unit)(_ => ZIO.unit).either)
        assertZIO(io.flip)(equalTo(ExampleError))
      } @@ zioTag(errors),
      test("rethrown caught error in release") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(ExampleError))(_ => ZIO.unit)
        assertZIO(io.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("rethrown caught error in usage") {
        val io = ZIO.absolve(ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => TaskExampleError).either)
        assertZIO(io.exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("test eval of async fail") {
        val io1 = ZIO.acquireReleaseWith(ZIO.unit)(_ => AsyncUnit[Nothing])(_ => asyncExampleError[Unit])
        val io2 = ZIO.acquireReleaseWith(AsyncUnit[Throwable])(_ => ZIO.unit)(_ => asyncExampleError[Unit])

        for {
          a1 <- assertZIO(io1.exit)(fails(equalTo(ExampleError)))
          a2 <- assertZIO(io2.exit)(fails(equalTo(ExampleError)))
          a3 <- assertZIO(ZIO.absolve(io1.either).exit)(fails(equalTo(ExampleError)))
          a4 <- assertZIO(ZIO.absolve(io2.either).exit)(fails(equalTo(ExampleError)))
        } yield a1 && a2 && a3 && a4
      },
      test("acquireReleaseWith regression 1") {
        def makeLogger: Ref[List[String]] => String => UIO[Unit] =
          (ref: Ref[List[String]]) => (line: String) => ref.update(_ ::: List(line))

        val io =
          for {
            ref <- Ref.make[List[String]](Nil)
            log  = makeLogger(ref)
            f <- ZIO
                   .acquireReleaseWith(
                     ZIO.acquireReleaseWith(ZIO.unit)(_ =>
                       log("start 1") *> Clock.sleep(10.millis) *> log("release 1")
                     )(_ => ZIO.unit)
                   )(_ => log("start 2") *> Clock.sleep(10.millis) *> log("release 2"))(_ => ZIO.unit)
                   .fork
            _ <- (ref.get <* Clock.sleep(1.millis)).repeatUntil(_.contains("start 1"))
            _ <- f.interrupt
            _ <- (ref.get <* Clock.sleep(1.millis)).repeatUntil(_.contains("release 2"))
            l <- ref.get
          } yield l

        assertZIO(Live.live(io))(hasSameElements(List("start 1", "release 1", "start 2", "release 2")))
      } @@ zioTag(regression) @@ jvmOnly,
      test("interrupt waits for finalizer") {
        val io =
          for {
            r  <- Ref.make(false)
            p1 <- Promise.make[Nothing, Unit]
            p2 <- Promise.make[Nothing, Int]
            s <- (p1.succeed(()) *> p2.await)
                   .ensuring(r.set(true) *> Clock.sleep(10.millis))
                   .fork
            _    <- p1.await
            _    <- s.interrupt
            test <- r.get
          } yield test

        assertZIO(Live.live(io))(isTrue)
      } @@ zioTag(interruption)
    ),
    suite("RTS synchronous stack safety")(
      test("deep map of now") {
        assertZIO(deepMapNow(10000))(equalTo(10000))
      },
      test("deep map of sync effect") {
        assertZIO(deepMapEffect(10000))(equalTo(10000))
      },
      test("deep attempt") {
        val io = (0 until 10000).foldLeft(ZIO.attempt(()))((acc, _) => acc.either.unit)
        assertZIO(io)(equalTo(()))
      },
      test("deep flatMap") {
        def fib(n: Int, a: BigInt = 0, b: BigInt = 1): IO[Error, BigInt] =
          ZIO.succeed(a + b).flatMap { b2 =>
            if (n > 0)
              fib(n - 1, b, b2)
            else
              ZIO.succeed(b2)
          }

        val expected = BigInt(
          "113796925398360272257523782552224175572745930353730513145086634176691092536145985470146129334641866902783673042322088625863396052888690096969577173696370562180400527049497109023054114771394568040040412172632376"
        )

        assertZIO(fib(1000))(equalTo(expected))
      },
      test("deep absolve/attempt is identity") {
        implicit val canFail = CanFail
        val io               = (0 until 1000).foldLeft(ZIO.succeed(42))((acc, _) => ZIO.absolve(acc.either))

        assertZIO(io)(equalTo(42))
      },
      test("deep async absolve/attempt is identity") {
        val io = (0 until 1000).foldLeft(ZIO.async[Any, Int, Int](k => k(ZIO.succeed(42)))) { (acc, _) =>
          ZIO.absolve(acc.either)
        }

        assertZIO(io)(equalTo(42))
      }
    ),
    suite("RTS asynchronous correctness")(
      test("simple async must return") {
        val io = ZIO.async[Any, Throwable, Int](k => k(ZIO.succeed(42)))
        assertZIO(io)(equalTo(42))
      },
      test("simple asyncZIO must return") {
        val io = ZIO.asyncZIO[Any, Throwable, Int](k => ZIO.succeed(k(ZIO.succeed(42))))
        assertZIO(io)(equalTo(42))
      },
      test("deep asyncZIO doesn't block threads") {
        def stackIOs(count: Int): UIO[Int] =
          if (count <= 0) ZIO.succeed(42)
          else asyncIO(stackIOs(count - 1))

        def asyncIO(cont: UIO[Int]): UIO[Int] =
          ZIO.asyncZIO[Any, Nothing, Int] { k =>
            Clock.sleep(5.millis) *> cont *> ZIO.succeed(k(ZIO.succeed(42)))
          }

        val procNum = java.lang.Runtime.getRuntime.availableProcessors()

        val io = stackIOs(procNum + 1)

        assertZIO(Live.live(io))(equalTo(42))
      } @@ jvmOnly,
      test("interrupt of asyncZIO register") {
        for {
          release <- Promise.make[Nothing, Unit]
          acquire <- Promise.make[Nothing, Unit]
          fiber <- ZIO
                     .asyncZIO[Any, Nothing, Unit] { _ =>
                       // This will never complete because we never call the callback
                       ZIO.acquireReleaseWith(acquire.succeed(()))(_ => release.succeed(()))(_ => ZIO.never)
                     }
                     .disconnect
                     .fork
          _ <- acquire.await
          _ <- fiber.interruptFork
          a <- release.await
        } yield assert(a)(isUnit)
      } @@ zioTag(interruption),
      test("fatal errors in registering async callback are captured") {
        val error = new Exception("Uh oh!")

        for {
          exit <- ZIO.async[Any, Nothing, Int](_ => throw error).exit
        } yield assertTrue(exit.causeOption.get.defects(0) == error)
      },
      test("resumption takes precedence over fatal error in registering callback") {
        val error = new Exception("Uh oh!")

        for {
          value <- ZIO.async[Any, Nothing, Int] { k => k(ZIO.succeed(42)); throw error }
        } yield assertTrue(value == 42)
      },
      test("async should not resume fiber twice after interruption") {
        for {
          step            <- Promise.make[Nothing, Unit]
          unexpectedPlace <- Ref.make(List.empty[Int])
          runtime         <- ZIO.runtime[Live]
          fork <- ZIO
                    .async[Any, Nothing, Unit] { k =>
                      Unsafe.unsafe { implicit unsafe =>
                        runtime.unsafe.fork {
                          step.await *> ZIO.succeed(k(unexpectedPlace.update(1 :: _)))
                        }
                      }
                      ()
                    }
                    .ensuring(ZIO.async[Any, Nothing, Unit] { _ =>
                      Unsafe.unsafe { implicit unsafe =>
                        runtime.unsafe.fork {
                          step.succeed(())
                        }
                      }
                      ()
                    //never complete
                    })
                    .ensuring(unexpectedPlace.update(2 :: _))
                    .forkDaemon
          result     <- Live.withLive(fork.interrupt)(_.timeout(5.seconds))
          unexpected <- unexpectedPlace.get
        } yield {
          assert(unexpected)(isEmpty) &&
          assert(result)(isNone) // timeout happens
        }
      } @@ zioTag(interruption) @@ flaky,
      test("test interruption of infinite async in uninterruptible region") {
        for {
          finalized <- Ref.make(false)
          fork <- ZIO
                    .asyncMaybe[Any, Nothing, Unit] { _ =>
                      Some(ZIO.unit)
                    }
                    .flatMap { _ =>
                      ZIO.never
                    }
                    .ensuring(finalized.set(true))
                    .uninterruptible
                    .forkDaemon
          _      <- Live.withLive(fork.interrupt)(_.timeout(5.seconds))
          result <- finalized.get
        } yield assertTrue(result == false)
      } @@ zioTag(interruption),
      test("sleep 0 must return") {
        assertZIO(Live.live(Clock.sleep(1.nanos)))(isUnit)
      },
      test("shallow bind of async chain") {
        val io = (0 until 10).foldLeft[Task[Int]](ZIO.succeed[Int](0)) { (acc, _) =>
          acc.flatMap(n => ZIO.async[Any, Throwable, Int](_(ZIO.succeed(n + 1))))
        }

        assertZIO(io)(equalTo(10))
      },
      test("asyncZIO can fail before registering") {
        val zio = ZIO
          .asyncZIO[Any, String, Nothing](_ => ZIO.fail("Ouch"))
          .flip

        assertZIO(zio)(equalTo("Ouch"))
      } @@ zioTag(errors),
      test("asyncZIO can defect before registering") {
        val zio = ZIO
          .asyncZIO[Any, String, Unit](_ => ZIO.succeed(throw new Error("Ouch")))
          .exit
          .map(_.foldExit(_.defects.headOption.map(_.getMessage), _ => None))

        assertZIO(zio)(isSome(equalTo("Ouch")))
      } @@ zioTag(errors)
    ),
    suite("interruption semantics") {
      test("self-interruption triggers onInterrupt") {
        for {
          ref   <- Ref.make(false)
          fiber <- ZIO.interrupt.onInterrupt(ref.set(true)).fork
          _     <- fiber.await
          value <- ref.get
        } yield assertTrue(value == true)
      } +
        test("self-interruption can be averted") {
          for {
            ref   <- Ref.make(false)
            fiber <- ZIO.interrupt.catchAllCause(_ => ref.set(true)).fork
            _     <- fiber.await
            value <- ref.get
          } yield assertTrue(value == true)
        } +
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
          } yield assertTrue(cause1.size == 1 && cause2.size == 2)
        } +
        test("child interrupted cause cannot be seen from parent") {
          for {
            parentId     <- ZIO.fiberId
            parentBefore <- FiberRef.interruptedCause.get
            child        <- FiberRef.interruptedCause.set(Cause.interrupt(parentId)).fork
            _            <- child.join
            parentAfter  <- FiberRef.interruptedCause.get
          } yield assertTrue(parentBefore == parentAfter)
        } +
        test("interruption cannot be be seen from within the region it originates 1") {
          for {
            startLatch <- Promise.make[Nothing, Unit]
            failLatch  <- Promise.make[Nothing, Unit]
            cause1     <- ZIO.fail("foo").sandbox.fork.flatMap(_.join).flip
            fiber      <- (startLatch.succeed(()) *> failLatch.await *> ZIO.fail("foo")).sandbox.flip.fork.uninterruptible
            _          <- startLatch.await *> fiber.interruptFork *> failLatch.succeed(())
            cause2     <- fiber.join
          } yield assertTrue(cause2 == cause1)
        } +
        test("interruption cannot be be seen from within the region it originates 2") {
          for {
            startLatch <- Promise.make[Nothing, Unit]
            failLatch  <- Promise.make[Nothing, Unit]
            ref        <- Ref.make(false)
            fiber <- (startLatch.succeed(()) *> failLatch.await *> ZIO
                       .fail("foo")).onInterrupt(ref.set(true)).sandbox.flip.fork.uninterruptible
            _     <- startLatch.await *> fiber.interruptFork *> failLatch.succeed(())
            value <- fiber.join *> ref.get
          } yield assertTrue(value == false)
        } +
        test("interrupters can be seen even in uninterruptible regions") {
          for {
            parentId   <- ZIO.fiberId
            startLatch <- Promise.make[Nothing, Unit]
            endLatch   <- Promise.make[Nothing, Unit]
            fiber      <- (startLatch.succeed(()) *> endLatch.await *> ZIO.descriptor).fork.uninterruptible
            _          <- startLatch.await *> fiber.interruptFork *> endLatch.succeed(())
            descriptor <- fiber.join
          } yield assertTrue(descriptor.interrupters.contains(parentId))
        } +
        test("interruption cannot be caught in interruptible regions") {
          for {
            started   <- Promise.make[Nothing, Unit]
            finalized <- Ref.make(false)
            fiber     <- (started.succeed(()) *> ZIO.never).catchAllCause(_ => finalized.set(true)).fork
            _         <- started.await *> fiber.interrupt
            result    <- finalized.get
          } yield assertTrue(result == false)
        } +
        test("interruption cannot be caught at the end of uninterruptible regions") {
          for {
            started   <- Promise.make[Nothing, Unit]
            latch     <- Promise.make[Nothing, Unit]
            finalized <- Ref.make(false)
            fiber <- ZIO.uninterruptible {
                       started.succeed(()) *> latch.await
                     }.catchAllCause(_ => finalized.set(true)).fork
            _      <- started.await *> fiber.interruptFork *> latch.succeed(()) *> fiber.await
            result <- finalized.get
          } yield assertTrue(result == false)
        } @@ exceptJS(nonFlaky) +
        test("interruption can be caught at the beginning of uninterruptible regions") {
          for {
            started   <- Promise.make[Nothing, Unit]
            latch     <- Promise.make[Nothing, Nothing]
            finalized <- Ref.make(false)
            fiber <- ZIO.uninterruptibleMask { restore =>
                       restore((started.succeed(()) *> latch.await)).catchAllCause(_ => finalized.set(true))
                     }.fork
            _      <- started.await *> fiber.interrupt
            result <- finalized.get
          } yield assertTrue(result == true)
        } + // - asyncInterrupt cancelation
        test("previous typed errors are discarded after interruptible region") {

          for {
            ensuring <- Promise.make[Nothing, Unit]
            fiber <- ZIO.uninterruptibleMask { restore =>
                       restore(
                         ZIO
                           .fail("Uh oh!")
                           .ensuring(ensuring.succeed(()) *> ZIO.interruptible(ZIO.never))
                           .mapError(_ => 42)
                       )
                         .catchAllCause(cause => ZIO.succeed(cause.failures))
                         .fork
                     }
            failures <- ensuring.await *> fiber.interrupt.flatMap(ZIO.done(_))
          } yield assertTrue(failures.length == 0)
        } +
        test("previous untyped errors are retained even after interruptible region") {
          val err = new Exception("Uh oh!")
          for {
            ensuring <- Promise.make[Nothing, Unit]
            fiber <- ZIO.uninterruptibleMask { restore =>
                       restore(
                         (ZIO.die(err): Task[Nothing])
                           .ensuring(ensuring.succeed(()) *> ZIO.interruptible(ZIO.never))
                           .mapError(_ => 42)
                       )
                         .catchAllCause(cause => ZIO.succeed(cause))
                         .fork
                     }
            cause <- ensuring.await *> fiber.interrupt.flatMap(ZIO.done(_))
          } yield assertTrue(cause.defects.length == 1)
        } @@ exceptJS(nonFlaky)

    } @@ zioTag(interruption),
    suite("RTS concurrency correctness")(
      test("shallow fork/join identity") {
        for {
          f <- ZIO.succeed(42).fork
          r <- f.join
        } yield assert(r)(equalTo(42))
      },
      test("deep fork/join identity") {
        val n = 20
        assertZIO(concurrentFib(n))(equalTo(fib(n)))
      } @@ jvmOnly,
      test("asyncZIO creation is interruptible") {
        for {
          release <- Promise.make[Nothing, Int]
          acquire <- Promise.make[Nothing, Unit]
          task = ZIO.asyncZIO[Any, Nothing, Unit] { _ =>
                   // This will never complete because the callback is never invoked
                   ZIO.acquireReleaseWith(acquire.succeed(()))(_ => release.succeed(42).unit)(_ => ZIO.never)
                 }
          fiber <- task.fork
          _     <- acquire.await
          _     <- fiber.interrupt
          a     <- release.await
        } yield assert(a)(equalTo(42))
      } @@ zioTag(interruption),
      test("asyncInterrupt runs cancel token on interrupt") {
        for {
          release <- Promise.make[Nothing, Int]
          latch    = scala.concurrent.Promise[Unit]()
          async    = ZIO.asyncInterrupt[Any, Nothing, Nothing] { _ => latch.success(()); Left(release.succeed(42).unit) }
          fiber   <- async.fork
          _ <- ZIO.async[Any, Throwable, Unit] { k =>
                 latch.future.onComplete {
                   case Success(a) => k(ZIO.succeed(a))
                   case Failure(t) => k(ZIO.fail(t))
                 }(scala.concurrent.ExecutionContext.global)
               }
          _      <- fiber.interrupt
          result <- release.await
        } yield assert(result)(equalTo(42))
      } @@ zioTag(interruption),
      test("daemon fiber is unsupervised") {
        def child(ref: Ref[Boolean]) = withLatch(release => (release *> ZIO.never).ensuring(ref.set(true)))

        for {
          ref   <- Ref.make[Boolean](false)
          fiber <- child(ref).forkDaemon.fork
          _     <- fiber.join
          b     <- ref.get
        } yield assert(b)(isFalse)
      } @@ zioTag(supervision),
      test("auto-interruption in fork of race") {
        def plus1(start: Promise[Nothing, Unit], done: Promise[Nothing, Unit], finalizer: UIO[Any]) =
          (start.succeed(()) *> ZIO.sleep(1.hour)).onInterrupt(finalizer *> done.succeed(()))

        for {
          interruptionRef <- Ref.make(0)
          latch1Start     <- Promise.make[Nothing, Unit]
          latch2Start     <- Promise.make[Nothing, Unit]
          latch1Done      <- Promise.make[Nothing, Unit]
          latch2Done      <- Promise.make[Nothing, Unit]
          inc              = interruptionRef.update(_ + 1)
          left             = plus1(latch1Start, latch1Done, inc)
          right            = plus1(latch2Start, latch2Done, inc)
          fiber           <- left.race(right).fork
          _               <- latch1Start.await *> latch2Start.await
          _               <- fiber.interrupt *> latch1Done.await *> latch2Done.await
          interrupted     <- interruptionRef.get
        } yield assertTrue(interrupted == 2)
      } @@ zioTag(interruption),
      test("race in daemon is executed") {
        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          p1     <- Promise.make[Nothing, Unit]
          p2     <- Promise.make[Nothing, Unit]
          loser1  = ZIO.acquireReleaseWith(latch1.succeed(()))(_ => p1.succeed(()))(_ => ZIO.infinity)
          loser2  = ZIO.acquireReleaseWith(latch2.succeed(()))(_ => p2.succeed(()))(_ => ZIO.infinity)
          fiber  <- (loser1 race loser2).forkDaemon
          _      <- latch1.await
          _      <- latch2.await
          _      <- fiber.interrupt
          res1   <- p1.await
          res2   <- p2.await
        } yield assert(res1)(isUnit) && assert(res2)(isUnit)
      },
      test("supervise fibers") {
        def makeChild(n: Int): UIO[Fiber[Nothing, Unit]] =
          (Clock.sleep(20.millis * n.toDouble) *> ZIO.infinity).fork

        val io =
          for {
            counter <- Ref.make(0)
            _ <- (makeChild(1) *> makeChild(2)).ensuringChildren { fs =>
                   fs.foldLeft(ZIO.unit)((acc, f) => acc *> f.interrupt *> counter.update(_ + 1))
                 }
            value <- counter.get
          } yield value

        assertZIO(Live.live(io))(equalTo(2))
      } @@ zioTag(supervision) @@ forked @@ flaky, // Due to weak supervision, this test is expected to fail sometimes
      test("race of fail with success") {
        val io = ZIO.fail(42).race(ZIO.succeed(24)).either
        assertZIO(io)(isRight(equalTo(24)))
      },
      test("race of terminate with success") {
        val io = ZIO.die(new Throwable {}).race(ZIO.succeed(24))
        assertZIO(io)(equalTo(24))
      },
      test("race of fail with fail") {
        val io = ZIO.fail(42).race(ZIO.fail(42)).either
        assertZIO(io)(isLeft(equalTo(42)))
      },
      test("race of value & never") {
        val io = ZIO.succeed(42).race(ZIO.never)
        assertZIO(io)(equalTo(42))
      },
      test("firstSuccessOf of values") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0), List(ZIO.succeed(100))).either
        assertZIO(io)(isRight(equalTo(100)))
      },
      test("firstSuccessOf of failures") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0).delay(10.millis), List(ZIO.fail(101))).either
        assertZIO(Live.live(io))(isLeft(equalTo(101)))
      },
      test("firstSuccessOF of failures & 1 success") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0), List(ZIO.succeed(102).delay(1.millis))).either
        assertZIO(Live.live(io))(isRight(equalTo(102)))
      },
      test("raceFirst interrupts loser on success") {
        for {
          s      <- Promise.make[Nothing, Unit]
          effect <- Promise.make[Nothing, Int]
          winner  = s.await *> ZIO.fromEither(Right(()))
          loser   = ZIO.acquireReleaseWith(s.succeed(()))(_ => effect.succeed(42))(_ => ZIO.infinity)
          race    = winner raceFirst loser
          _      <- race
          b      <- effect.await
        } yield assert(b)(equalTo(42))
      } @@ zioTag(interruption),
      test("raceFirst interrupts loser on failure") {
        for {
          s      <- Promise.make[Nothing, Unit]
          effect <- Promise.make[Nothing, Int]
          winner  = s.await *> ZIO.fromEither(Left(new Exception))
          loser   = ZIO.acquireReleaseWith(s.succeed(()))(_ => effect.succeed(42))(_ => ZIO.infinity)
          race    = winner raceFirst loser
          _      <- race.either
          b      <- effect.await
        } yield assert(b)(equalTo(42))
      } @@ zioTag(interruption),
      test("raceFirst interrupts losers on success") {
        for {
          s      <- Promise.make[Nothing, Unit]
          effect <- Promise.make[Nothing, Int]
          winner  = s.await *> ZIO.fromEither(Right(()))
          losers  = List(ZIO.acquireReleaseWith(s.succeed(()))(_ => effect.succeed(42))(_ => ZIO.infinity))
          race    = ZIO.raceFirst(winner, losers)
          _      <- race
          b      <- effect.await
        } yield assert(b)(equalTo(42))
      } @@ zioTag(interruption),
      test("raceFirst interrupts losers on failure") {
        for {
          s      <- Promise.make[Nothing, Unit]
          effect <- Promise.make[Nothing, Int]
          winner  = s.await *> ZIO.fromEither(Left(new Exception))
          losers  = List(ZIO.acquireReleaseWith(s.succeed(()))(_ => effect.succeed(42))(_ => ZIO.infinity))
          race    = ZIO.raceFirst(winner, losers)
          _      <- race.either
          b      <- effect.await
        } yield assert(b)(equalTo(42))
      } @@ zioTag(interruption),
      test("mergeAll") {
        val io = ZIO.mergeAll(List("a", "aa", "aaa", "aaaa").map(ZIO.succeed[String](_)))(0)((b, a) => b + a.length)

        assertZIO(io)(equalTo(10))
      },
      test("mergeAllEmpty") {
        val io = ZIO.mergeAll(List.empty[UIO[Int]])(0)(_ + _)
        assertZIO(io)(equalTo(0))
      },
      test("reduceAll") {
        val io = ZIO.reduceAll(ZIO.succeed(1), List(2, 3, 4).map(ZIO.succeed[Int](_)))(_ + _)
        assertZIO(io)(equalTo(10))
      },
      test("reduceAll Empty List") {
        val io = ZIO.reduceAll(ZIO.succeed(1), Seq.empty)(_ + _)
        assertZIO(io)(equalTo(1))
      },
      test("timeout of failure") {
        val io = ZIO.fail("Uh oh").timeout(1.hour)
        assertZIO(Live.live(io).exit)(fails(equalTo("Uh oh")))
      },
      test("timeout of terminate") {
        val io: ZIO[Any, Nothing, Option[Int]] = ZIO.die(ExampleError).timeout(1.hour)
        assertZIO(Live.live(io).exit)(dies(equalTo(ExampleError)))
      }
    ),
    suite("RTS option tests")(
      test("lifting a value to an option") {
        assertZIO(ZIO.some(42))(isSome(equalTo(42)))
      },
      test("using the none value") {
        assertZIO(ZIO.none)(isNone)
      }
    ),
    suite("RTS either helper tests")(
      test("lifting a value into right") {
        assertZIO(ZIO.right(42))(isRight(equalTo(42)))
      },
      test("lifting a value into left") {
        assertZIO(ZIO.left(42))(isLeft(equalTo(42)))
      }
    ),
    suite("RTS interruption")(
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
        } yield assertCompletes
      },
      test("sync forever is interruptible") {
        for {
          latch <- Promise.make[Nothing, Unit]
          f     <- (latch.succeed(()) *> ZIO.succeed[Int](1).forever).fork
          _     <- latch.await
          _     <- f.interrupt
        } yield assertTrue(true)
      },
      test("interrupt of never is interrupted with cause") {
        for {
          fiber <- ZIO.never.fork
          exit  <- fiber.interrupt
        } yield assert(exit)(isJustInterrupted)
      },
      test("asyncZIO is interruptible") {
        val io =
          for {
            fiber <- ZIO.asyncZIO[Any, Nothing, Nothing](_ => ZIO.never).fork
            _     <- fiber.interrupt
          } yield 42

        assertZIO(io)(equalTo(42))
      },
      test("async is interruptible") {
        val io =
          for {
            fiber <- ZIO.async[Any, Nothing, Nothing](_ => ()).fork
            _     <- fiber.interrupt
          } yield 42

        assertZIO(io)(equalTo(42))
      },
      test("acquireReleaseWith is uninterruptible") {
        val io =
          for {
            promise <- Promise.make[Nothing, Unit]
            fiber   <- ZIO.acquireReleaseWith(promise.succeed(()) <* ZIO.never)(_ => ZIO.unit)(_ => ZIO.unit).forkDaemon
            res     <- promise.await *> fiber.interrupt.timeoutTo(42)(_ => 0)(1.second)
          } yield res

        assertZIO(Live.live(io))(equalTo(42))
      },
      test("acquireReleaseExitWith is uninterruptible") {
        val io =
          for {
            promise <- Promise.make[Nothing, Unit]
            fiber <-
              ZIO
                .acquireReleaseExitWith(promise.succeed(()) *> ZIO.never *> ZIO.succeed(1))((_, _: Exit[Any, Any]) =>
                  ZIO.unit
                )(_ => ZIO.unit: IO[Nothing, Unit])
                .forkDaemon
            res <- promise.await *> fiber.interrupt.timeoutTo(42)(_ => 0)(1.second)
          } yield res

        assertZIO(Live.live(io))(equalTo(42))
      },
      test("acquireReleaseWith use is interruptible") {
        for {
          fiber <- ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.never).fork
          res   <- fiber.interrupt
        } yield assert(res)(isInterrupted)
      },
      test("acquireReleaseExitWith use is interruptible") {
        for {
          fiber <- ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => ZIO.unit)(_ => ZIO.never).fork
          res   <- fiber.interrupt.timeoutTo(42)(_ => 0)(1.second)
        } yield assert(res)(equalTo(0))
      },
      test("acquireReleaseWith release called on interrupt") {
        val io =
          for {
            p1 <- Promise.make[Nothing, Unit]
            p2 <- Promise.make[Nothing, Unit]
            fiber <-
              ZIO.acquireReleaseWith(ZIO.unit)(_ => p2.succeed(()) *> ZIO.unit)(_ => p1.succeed(()) *> ZIO.never).fork
            _ <- p1.await
            _ <- fiber.interrupt
            _ <- p2.await
          } yield ()

        assertZIO(io.timeoutTo(42)(_ => 0)(1.second))(equalTo(0))
      },
      test("acquireReleaseExitWith release called on interrupt") {
        for {
          done <- Promise.make[Nothing, Unit]
          fiber <-
            withLatch { release =>
              ZIO
                .acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => done.succeed(()))(_ => release *> ZIO.never)
                .fork
            }

          _ <- fiber.interrupt
          r <- done.await.timeoutTo(42)(_ => 0)(60.second)
        } yield assert(r)(equalTo(0))
      },
      test("acquireReleaseWith acquire returns immediately on interrupt") {
        for {
          p1  <- Promise.make[Nothing, Unit]
          p2  <- Promise.make[Nothing, Int]
          p3  <- Promise.make[Nothing, Unit]
          s   <- ZIO.acquireReleaseWith(p1.succeed(()) *> p2.await)(_ => p3.await)(_ => ZIO.unit).disconnect.fork
          _   <- p1.await
          res <- s.interrupt
          _   <- p3.succeed(())
        } yield assert(res)(isInterrupted)
      },
      test("acquireReleaseExitWith disconnect acquire returns immediately on interrupt") {
        for {
          p1 <- Promise.make[Nothing, Unit]
          p2 <- Promise.make[Nothing, Unit]
          p3 <- Promise.make[Nothing, Unit]
          s <- ZIO
                 .acquireReleaseExitWith(p1.succeed(()) *> p2.await)((_, _: Exit[Any, Any]) => p3.await)(_ =>
                   ZIO.unit: IO[Nothing, Unit]
                 )
                 .disconnect
                 .fork
          _   <- p1.await
          res <- s.interrupt
          _   <- p3.succeed(())
        } yield assert(res)(isInterrupted)
      },
      test("acquireReleaseWith disconnect use is interruptible") {
        for {
          fiber <- ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.never).disconnect.fork
          res   <- fiber.interrupt
        } yield assert(res)(isInterrupted)
      },
      test("acquireReleaseExitWith disconnect use is interruptible") {
        for {
          fiber <-
            ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => ZIO.unit)(_ => ZIO.never).disconnect.fork
          res <- Live.live(fiber.interrupt.timeoutTo(42)(_ => 0)(1.second))
        } yield assert(res)(equalTo(0))
      },
      test("acquireReleaseWith disconnect release called on interrupt in separate fiber") {
        Live.live(for {
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
          result <- releaseLatch.await.timeoutTo(false)(_ => true)(1.second)
        } yield assertTrue(result == true))
      } @@ flaky,
      test("acquireReleaseExitWith disconnect release called on interrupt in separate fiber") {
        for {
          done <- Promise.make[Nothing, Unit]
          fiber <-
            withLatch { release =>
              ZIO
                .acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => done.succeed(()))(_ => release *> ZIO.never)
                .disconnect
                .fork
            }

          _ <- fiber.interrupt
          r <- Live.live(done.await.timeoutTo(false)(_ => true)(1.second))
        } yield assert(r)(isTrue)
      } @@ flaky,
      test("catchAll + ensuring + interrupt") {
        implicit val canFail = CanFail
        for {
          cont <- Promise.make[Nothing, Unit]
          p1   <- Promise.make[Nothing, Boolean]
          f1   <- (cont.succeed(()) *> ZIO.never).catchAll(ZIO.fail).ensuring(p1.succeed(true)).fork
          _    <- cont.await
          _    <- f1.interrupt
          res  <- p1.await
        } yield assert(res)(isTrue)
      },
      test("finalizer can detect interruption") {
        for {
          p1 <- Promise.make[Nothing, Boolean]
          c  <- Promise.make[Nothing, Unit]
          f1 <- (c.succeed(()) *> ZIO.never)
                  .ensuring(ZIO.descriptor.flatMap(d => p1.succeed(d.interrupters.nonEmpty)))
                  .fork
          _   <- c.await
          _   <- f1.interrupt
          res <- p1.await
        } yield assert(res)(isTrue)
      },
      test("interrupted cause persists after catching") {
        def process(list: List[Exit[Nothing, Any]]): List[Exit[Nothing, Any]] =
          list.map(_.mapErrorCauseExit((cause: Cause[Nothing]) => cause.untraced))

        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          exits  <- Ref.make[List[Exit[Nothing, Any]]](Nil)
          fiber <- ZIO.uninterruptibleMask { restore =>
                     restore(ZIO.uninterruptibleMask { restore =>
                       restore(latch1.succeed(()) *> latch2.await).onExit(exit => exits.update(exit :: _))
                     } *> ZIO.unit).exit.flatMap(exit => exits.update(exit :: _))
                   }.fork
          _    <- latch1.await *> fiber.interrupt
          list <- exits.get.map(process)
        } yield assertTrue(list.length == 2 && list.forall(_.causeOption.get.isInterruptedOnly))
      },
      test("interruption of raced") {
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
        } yield assert(count)(equalTo(2))
      }, // FIXME: @@ exceptJS(nonFlaky),
      test("recovery of error in finalizer") {
        for {
          recovered <- Ref.make(false)
          fiber <- withLatch { release =>
                     (release *> ZIO.never)
                       .ensuring(
                         (ZIO.unit *> ZIO.fail("Uh oh")).catchAll(_ => recovered.set(true))
                       )
                       .fork
                   }
          _     <- fiber.interrupt
          value <- recovered.get
        } yield assert(value)(isTrue)
      } @@ zioTag(errors),
      test("recovery of interruptible") {
        for {
          recovered <- Ref.make(false)
          fiber <- withLatch { release =>
                     (release *> ZIO.never.interruptible)
                       .foldCauseZIO(
                         cause => recovered.set(cause.isInterrupted),
                         _ => recovered.set(false)
                       )
                       .uninterruptible
                       .fork
                   }
          _     <- fiber.interrupt
          value <- recovered.get
        } yield assert(value)(isTrue)
      },
      test("sandbox of ordinary failure") {
        for {
          cause <- ZIO.fail("Uh oh!").sandbox.flip
        } yield assertTrue(cause == Cause.fail("Uh oh!"))
      },
      test("sandbox of ordinary failure - fork/join") {
        for {
          fiber <- ZIO.fail("Uh oh!").sandbox.flip.fork
          cause <- fiber.join
        } yield assertTrue(cause == Cause.fail("Uh oh!"))
      },
      test("sandbox of interruptible") {
        for {
          selfId    <- ZIO.fiberId
          recovered <- Ref.make[Option[Either[Cause[Nothing], Any]]](None)
          fiber <- withLatch { release =>
                     (release *> ZIO.never.interruptible).sandbox.either
                       .flatMap(exit => recovered.set(Some(exit)))
                       .uninterruptible
                       .fork
                   }
          _     <- fiber.interrupt
          value <- recovered.get
        } yield assert(value)(isSome(isLeft(equalTo(Cause.interrupt(selfId)))))
      },
      test("run of interruptible") {
        for {
          recovered <- Ref.make[Option[Exit[Nothing, Any]]](None)
          fiber <- withLatch { release =>
                     (release *> ZIO.never.interruptible).exit
                       .flatMap(exit => recovered.set(Some(exit)))
                       .uninterruptible
                       .fork
                   }
          _     <- fiber.interrupt
          value <- recovered.get
        } yield assert(value)(isSome(isInterrupted))
      },
      test("alternating interruptibility") {
        for {
          counter <- Ref.make(0)
          fiber <- withLatch { release =>
                     ((((release *> ZIO.never.interruptible.exit *> counter
                       .update(_ + 1)).uninterruptible).interruptible).exit
                       *> counter.update(_ + 1)).uninterruptible.fork
                   }
          _     <- fiber.interrupt
          value <- counter.get
        } yield assert(value)(equalTo(2))
      },
      test("interruption after defect") {
        for {
          ref <- Ref.make(false)
          fiber <- withLatch { release =>
                     (ZIO.attempt(throw new Error).exit *> release *> ZIO.never)
                       .ensuring(ref.set(true))
                       .fork
                   }
          _     <- fiber.interrupt
          value <- ref.get
        } yield assert(value)(isTrue)
      },
      test("interruption after defect 2") {
        for {
          ref <- Ref.make(false)
          fiber <- withLatch { release =>
                     (ZIO.attempt(throw new Error).exit *> release *> ZIO.unit.forever)
                       .ensuring(ref.set(true))
                       .fork
                   }
          _     <- fiber.interrupt
          value <- ref.get
        } yield assert(value)(isTrue)
      },
      test("disconnect returns immediately on interrupt") {
        for {
          p1 <- Promise.make[Nothing, Unit]
          s <- (p1.succeed(()) *> ZIO.never)
                 .ensuring(ZIO.never)
                 .disconnect
                 .fork
          _   <- p1.await
          res <- s.interrupt
        } yield assert(res)(isInterrupted)
      },
      test("disconnected effect that is then interrupted eventually performs interruption") {
        Live.live(for {
          r  <- Ref.make(false)
          p1 <- Promise.make[Nothing, Unit]
          p3 <- Promise.make[Nothing, Unit]
          s <- (p1.succeed(()) *> ZIO.never)
                 .ensuring(r.set(true) *> Clock.sleep(10.millis) *> p3.succeed(()))
                 .disconnect
                 .fork
          _    <- p1.await
          _    <- s.interrupt
          _    <- p3.await
          test <- r.get
        } yield assertTrue(test == true))
      },
      test("disconnect does not allow interruption to be observed in uninterruptible regions") {
        for {
          promise <- Promise.make[Nothing, Unit]
          ref     <- Ref.make(false)
          fiber <- (promise.succeed(()) *> ZIO.never)
                     .ensuring(ZIO.unit.disconnect *> ref.set(true))
                     .forkDaemon
          _     <- promise.await
          _     <- fiber.interrupt
          value <- ref.get
        } yield assertTrue(value)
      },
      test("cause reflects interruption") {
        for {
          fiber <- withLatch(release => (release *> ZIO.fail("foo")).fork)
          exit  <- fiber.interrupt
        } yield assert(exit)(isJustInterrupted) || assert(exit)(Assertion.fails(equalTo("foo")))
      } @@ exceptJS(nonFlaky),
      test("acquireRelease use inherits interrupt status") {
        Live.live(for {
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
        } yield assertTrue(value == true))
      },
      test("acquireRelease use inherits interrupt status 2") {
        Live.live(for {
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
        } yield assertTrue(value == true))
      },
      test("async can be uninterruptible") {
        Live.live(for {
          ref <- Ref.make(false)
          fiber <- withLatch { release =>
                     (release *> Clock.sleep(10.millis) *> ref.set(true).unit).uninterruptible.fork
                   }
          _     <- fiber.interrupt
          value <- ref.get
        } yield assertTrue(value == true))
      },
      test("closing scope is uninterruptible") {
        for {
          ref     <- Ref.make(false)
          promise <- Promise.make[Nothing, Unit]
          child    = promise.succeed(()) *> Live.live(ZIO.sleep(10.milliseconds)) *> ref.set(true)
          parent   = child.uninterruptible.fork *> promise.await
          fiber   <- parent.fork
          _       <- promise.await
          _       <- fiber.interrupt
          value   <- ref.get
        } yield assertTrue(value == true)
      } @@ exceptJS(nonFlaky) @@ TestAspect.fibers,
      test("asyncInterrupt cancelation") {
        for {
          ref       <- ZIO.succeed(new java.util.concurrent.atomic.AtomicInteger(0))
          finalized <- Promise.make[Nothing, Unit]
          started   <- Promise.make[Nothing, Unit]
          effect = (started.succeed(()) *> ZIO
                     .asyncInterrupt[Any, Nothing, Any] { _ =>
                       ref.incrementAndGet()
                       Left(ZIO.succeed(ref.decrementAndGet()))
                     })
                     .ensuring(finalized.succeed(()))
          _     <- (started.await *> ZIO.unit).race(effect)
          value <- finalized.await *> ZIO.succeed(ref.get())
        } yield assert(value)(equalTo(0))
      } @@ exceptJS(nonFlaky),
      test("interruption is not inherited on fork") {
        for {
          promise1 <- Promise.make[Nothing, Unit]
          promise2 <- Promise.make[Nothing, Unit]
          finalizer = ZIO.interruptible(promise2.succeed(())).forkDaemon
          resource  = (promise1.succeed(()) *> ZIO.never).ensuring(finalizer)
          fiber    <- resource.forkDaemon
          _        <- promise1.await
          _        <- fiber.interrupt
          _        <- promise2.await
        } yield assertCompletes
      },
      test("interruption is propagated to grandchild in race in uninterruptible region") {
        ZIO.uninterruptible {
          for {
            promise <- Promise.make[Nothing, Unit]
            ref     <- Ref.make(false)
            left     = promise.await
            right    = ZIO.never.race((promise.succeed(()) *> ZIO.never.interruptible).ensuring(ref.set(true)))
            _       <- left.race(right).forkDaemon
            _       <- ref.get.repeatUntilEquals(true)
          } yield assertCompletes
        }
      } @@ exceptJS(nonFlaky),
      test("child can outlive parent in race") {
        for {
          promise <- Promise.make[Nothing, Unit]
          race = ZIO.unit.raceWith(promise.await)(
                   (_, fiber) => ZIO.succeed(fiber),
                   (_, fiber) => ZIO.succeed(fiber)
                 )
          fiber <- ZIO.transplant(graft => graft(race).fork.flatMap(_.join))
          _     <- promise.succeed(())
          exit  <- fiber.await
        } yield assertTrue(exit.isSuccess)
      } @@ exceptJS(nonFlaky)
    ) @@ zioTag(interruption),
    suite("RTS environment")(
      test("provide is modular") {
        val zio =
          for {
            v1 <- ZIO.service[Int]
            v2 <- ZIO.service[Int].provideEnvironment(ZEnvironment(2))
            v3 <- ZIO.service[Int]
          } yield (v1, v2, v3)

        assertZIO(zio.provideEnvironment(ZEnvironment(4)))(equalTo((4, 2, 4)))
      },
      test("async can use environment") {
        val zio = ZIO.async[Int, Nothing, Int](cb => cb(ZIO.service[Int]))
        assertZIO(zio.provideEnvironment(ZEnvironment(10)))(equalTo(10))
      }
    ),
    suite("RTS forking inheritability")(
      test("interruption status is heritable") {
        for {
          latch <- Promise.make[Nothing, Unit]
          ref   <- Ref.make(InterruptStatus.interruptible)
          _     <- ZIO.uninterruptible((ZIO.checkInterruptible(ref.set) *> latch.succeed(())).fork *> latch.await)
          v     <- ref.get
        } yield assert(v)(equalTo(InterruptStatus.uninterruptible))
      } @@ zioTag(interruption),
      test("executor is heritable") {
        val executor = Executor.fromExecutionContext {
          scala.concurrent.ExecutionContext.Implicits.global
        }
        val pool = ZIO.succeed(Unsafe.unsafe(implicit unsafe => Platform.getCurrentThreadGroup()))
        val io = for {
          parentPool <- pool
          childPool  <- pool.fork.flatMap(_.join)
        } yield assert(parentPool)(equalTo(childPool))
        io.onExecutor(executor)
      } @@ exceptJS(nonFlaky(100))
    ),
    suite("serviceWith")(
      test("effectfully accesses a service in the environment") {
        val zio = ZIO.serviceWithZIO[Int](int => ZIO.succeed(int + 3))
        assertZIO(zio.provide(ZLayer.succeed(0)))(equalTo(3))
      }
    ),
    suite("schedule")(
      test("runs effect for each recurrence of the schedule") {
        for {
          ref     <- Ref.make[List[Duration]](List.empty)
          effect   = Clock.nanoTime.flatMap(duration => ref.update(duration.nanoseconds :: _))
          schedule = Schedule.spaced(1.second) && Schedule.recurs(5)
          _       <- effect.schedule(schedule).fork
          _       <- TestClock.adjust(5.seconds)
          value   <- ref.get.map(_.reverse)
        } yield assert(value)(equalTo(List(1.second, 2.seconds, 3.seconds, 4.seconds, 5.seconds)))
      }
    ),
    suite("someOrElse")(
      test("extracts the value from Some") {
        assertZIO(ZIO.succeed(Some(1)).someOrElse(2))(equalTo(1))
      },
      test("falls back to the default value if None") {
        assertZIO(ZIO.succeed(None).someOrElse(42))(equalTo(42))
      },
      test("works when the default is an instance of a covariant type constructor applied to Nothing") {
        assertZIO(ZIO.succeed(Option.empty[List[String]]).someOrElse(List.empty))(equalTo(List.empty))
      },
      test("does not change failed state") {
        assertZIO(ZIO.fail(ExampleError).someOrElse(42).exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors)
    ),
    suite("someOrElseZIO")(
      test("extracts the value from Some") {
        assertZIO(ZIO.succeed(Some(1)).someOrElseZIO(ZIO.succeed(2)))(equalTo(1))
      },
      test("falls back to the default effect if None") {
        assertZIO(ZIO.succeed(None).someOrElseZIO(ZIO.succeed(42)))(equalTo(42))
      },
      test("works when the output of the default is an instance of a covariant type constructor applied to Nothing") {
        assertZIO(ZIO.succeed(Option.empty[List[String]]).someOrElseZIO(ZIO.succeed(List.empty)))(equalTo(List.empty))
      },
      test("does not change failed state") {
        assertZIO(ZIO.fail(ExampleError).someOrElseZIO(ZIO.succeed(42)).exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors)
    ),
    suite("someOrFail")(
      test("extracts the optional value") {
        val task: Task[Int] = ZIO.succeed(Some(42)).someOrFail(exampleError)
        assertZIO(task)(equalTo(42))
      },
      test("fails when given a None") {
        val task: Task[Int] = ZIO.succeed(Option.empty[Int]).someOrFail(exampleError)
        assertZIO(task.exit)(fails(equalTo(exampleError)))
      } @@ zioTag(errors)
    ),
    suite("summarized")(
      test("returns summary and value") {
        for {
          counter  <- Ref.make(0)
          increment = counter.updateAndGet(_ + 1)
          result   <- increment.summarized(increment)((_, _))
        } yield {
          val ((start, end), value) = result
          assert(start)(equalTo(1)) &&
          assert(value)(equalTo(2)) &&
          assert(end)(equalTo(3))
        }
      }
    ),
    suite("tapErrorCause")(
      test("effectually peeks at the cause of the failure of this effect") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.dieMessage("die").tapErrorCause(_ => ref.set(true)).exit
          effect <- ref.get
        } yield assert(result)(dies(hasMessage(equalTo("die")))) &&
          assert(effect)(isTrue)
      }
    ),
    suite("tapDefect")(
      test("effectually peeks at defects") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.dieMessage("die").tapDefect(_ => ref.set(true)).exit
          effect <- ref.get
        } yield assert(result)(dies(hasMessage(equalTo("die")))) &&
          assert(effect)(isTrue)
      },
      test("leaves failures") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.fail("fail").tapDefect(_ => ref.set(true)).exit
          effect <- ref.get
        } yield assert(result)(fails(equalTo("fail"))) &&
          assert(effect)(isFalse)
      }
    ),
    suite("tapEither")(
      test("effectually peeks at the failure of this effect") {
        for {
          ref <- Ref.make(0)
          _ <- ZIO
                 .fail(42)
                 .tapEither {
                   case Left(value) => ref.set(value)
                   case Right(_)    => ref.set(-1)
                 }
                 .exit
          effect <- ref.get
        } yield assert(effect)(equalTo(42))
      },
      test("effectually peeks at the success of this effect") {
        for {
          ref <- Ref.make(0)
          _ <- ZIO
                 .attempt(42)
                 .tapEither {
                   case Left(_)      => ref.set(-1)
                   case Right(value) => ref.set(value)
                 }
                 .exit
          effect <- ref.get
        } yield assert(effect)(equalTo(42))
      }
    ),
    suite("tapSome")(
      test("is identity if the function doesn't match") {
        for {
          ref    <- Ref.make(false)
          result <- ref.set(true).as(42).tapSome(PartialFunction.empty)
          effect <- ref.get
        } yield assert(result)(equalTo(42)) &&
          assert(effect)(isTrue)
      },
      test("runs the effect if the function matches") {
        for {
          ref    <- Ref.make(0)
          result <- ref.set(10).as(42).tapSome { case r => ref.set(r) }
          effect <- ref.get
        } yield assert(result)(equalTo(42)) &&
          assert(effect)(equalTo(42))
      }
    ),
    suite("tapSomeError")(
      test("is identity if the function doesn't match") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.fail("die").tapSomeError { case "alive" => ref.set(true) }.exit
          effect <- ref.get
        } yield assert(result)(fails(equalTo("die"))) && assert(effect)(isFalse)
      },
      test("runs the effect if the function matches") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.fail("die").tapSomeError { case "die" => ref.set(true) }.exit
          effect <- ref.get
        } yield assert(result)(fails(equalTo("die"))) && assert(effect)(isTrue)
      }
    ),
    suite("timeout disconnect")(
      test("returns `Some` with the produced value if the effect completes before the timeout elapses") {
        assertZIO(ZIO.unit.disconnect.timeout(100.millis))(isSome(isUnit))
      },
      test("returns `None` otherwise") {
        for {
          fiber  <- ZIO.never.uninterruptible.disconnect.timeout(100.millis).fork
          _      <- TestClock.adjust(100.millis)
          result <- fiber.join
        } yield assert(result)(isNone)
      }
    ),
    suite("transplant")(
      test("preserves supervision relationship of nested fibers") {
        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          fiber <- ZIO.transplant { grafter =>
                     grafter {
                       val zio = for {
                         _ <- (latch1.succeed(()) *> ZIO.infinity).onInterrupt(latch2.succeed(())).fork
                         _ <- ZIO.infinity
                       } yield ()
                       zio.fork
                     }
                   }
          _ <- latch1.await
          _ <- fiber.interrupt
          _ <- latch2.await
        } yield assertCompletes
      }
    ),
    test("unleft") {
      check(Gen.oneOf(Gen.failures(Gen.int), Gen.successes(Gen.either(Gen.int, Gen.int)))) { zio =>
        for {
          actual   <- zio.left.unleft.exit
          expected <- zio.exit
        } yield assert(actual)(equalTo(expected))
      }
    },
    suite("unless")(
      test("executes correct branch only") {
        for {
          effectRef <- Ref.make(0)
          _         <- effectRef.set(1).unless(true)
          val1      <- effectRef.get
          _         <- effectRef.set(2).unless(false)
          val2      <- effectRef.get
          failure    = new Exception("expected")
          _         <- ZIO.fail(failure).unless(true)
          failed    <- ZIO.fail(failure).unless(false).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(val2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      }
    ),
    suite("unlessDiscard")(
      test("executes correct branch only") {
        for {
          effectRef <- Ref.make(0)
          _         <- effectRef.set(1).unlessDiscard(true)
          val1      <- effectRef.get
          _         <- effectRef.set(2).unlessDiscard(false)
          val2      <- effectRef.get
          failure    = new Exception("expected")
          _         <- ZIO.fail(failure).unlessDiscard(true)
          failed    <- ZIO.fail(failure).unless(false).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(val2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      }
    ),
    suite("unlessZIO")(
      test("executes condition effect and correct branch") {
        for {
          effectRef     <- Ref.make(0)
          conditionRef  <- Ref.make(0)
          conditionTrue  = conditionRef.update(_ + 1).as(true)
          conditionFalse = conditionRef.update(_ + 1).as(false)
          _             <- effectRef.set(1).unlessZIO(conditionTrue)
          val1          <- effectRef.get
          conditionVal1 <- conditionRef.get
          _             <- effectRef.set(2).unlessZIO(conditionFalse)
          val2          <- effectRef.get
          conditionVal2 <- conditionRef.get
          failure        = new Exception("expected")
          _             <- ZIO.fail(failure).unlessZIO(conditionTrue)
          failed        <- ZIO.fail(failure).unlessZIO(conditionFalse).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(conditionVal1)(equalTo(1)) &&
          assert(val2)(equalTo(2)) &&
          assert(conditionVal2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZIO[R, E, Boolean] = ZIO.succeed(true)
        val zio: ZIO[R1, E1, A]   = ZIO.succeed(new A {})
        val _                     = ZIO.unlessZIO(b)(zio)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("unlessZIODiscard")(
      test("executes condition effect and correct branch") {
        for {
          effectRef     <- Ref.make(0)
          conditionRef  <- Ref.make(0)
          conditionTrue  = conditionRef.update(_ + 1).as(true)
          conditionFalse = conditionRef.update(_ + 1).as(false)
          _             <- effectRef.set(1).unlessZIODiscard(conditionTrue)
          val1          <- effectRef.get
          conditionVal1 <- conditionRef.get
          _             <- effectRef.set(2).unlessZIODiscard(conditionFalse)
          val2          <- effectRef.get
          conditionVal2 <- conditionRef.get
          failure        = new Exception("expected")
          _             <- ZIO.fail(failure).unlessZIODiscard(conditionTrue)
          failed        <- ZIO.fail(failure).unlessZIO(conditionFalse).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(conditionVal1)(equalTo(1)) &&
          assert(val2)(equalTo(2)) &&
          assert(conditionVal2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZIO[R, E, Boolean] = ZIO.succeed(true)
        val zio: ZIO[R1, E1, A]   = ZIO.succeed(new A {})
        val _                     = ZIO.unlessZIODiscard(b)(zio)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("unrefine")(
      test("converts some fiber failures into errors") {
        val s    = "division by zero"
        val zio1 = ZIO.die(new IllegalArgumentException(s))
        val zio2 = zio1.unrefine { case e: IllegalArgumentException => e.getMessage }
        assertZIO(zio2.exit)(fails(equalTo(s)))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefine { case e: NumberFormatException => e.getMessage }
        assertZIO(zio2.exit)(dies(equalTo(t)))
      }
    ),
    suite("unrefineTo")(
      test("converts some fiber failures into errors") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineTo[IllegalArgumentException]
        assertZIO(zio2.exit)(fails(equalTo(t)))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineTo[NumberFormatException]
        assertZIO(zio2.exit)(dies(equalTo(t)))
      }
    ) @@ zioTag(errors),
    suite("unrefineWith")(
      test("converts some fiber failures into errors") {
        val s    = "division by zero"
        val zio1 = ZIO.die(new IllegalArgumentException(s))
        val zio2 = zio1.unrefineWith { case e: IllegalArgumentException => Option(e.getMessage) }(_ => None)
        assertZIO(zio2.exit)(fails(isSome(equalTo(s))))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineWith { case e: NumberFormatException => Option(e.getMessage) }(_ => None)
        assertZIO(zio2.exit)(dies(equalTo(t)))
      },
      test("uses the specified function to convert the `E` into an `E1`") {
        val zio1 = ZIO.fail("fail")
        val zio2 = zio1.unrefineWith { case e: IllegalArgumentException => Option(e.getMessage) }(_ => None)
        assertZIO(zio2.exit)(fails(isNone))
      }
    ) @@ zioTag(errors),
    test("right") {
      check(Gen.oneOf(Gen.failures(Gen.int), Gen.successes(Gen.either(Gen.int, Gen.int)))) { zio =>
        for {
          actual   <- zio.right.unright.exit
          expected <- zio.exit
        } yield assert(actual)(equalTo(expected))
      }
    },
    suite("unsandbox")(
      test("unwraps exception") {
        val failure: IO[Cause[Exception], String] = ZIO.fail(fail(new Exception("fail")))
        val success: IO[Cause[Any], Int]          = ZIO.succeed(100)
        for {
          message <- failure.unsandbox.foldZIO(e => ZIO.succeed(e.getMessage), _ => ZIO.succeed("unexpected"))
          result  <- success.unsandbox
        } yield assert(message)(equalTo("fail")) && assert(result)(equalTo(100))
      },
      test("no information is lost during composition") {
        val causes = Gen.causes(Gen.string, Gen.throwable)
        def cause[R, E](zio: ZIO[R, E, Nothing]): ZIO[R, Nothing, Cause[E]] =
          zio.foldCauseZIO(ZIO.succeed(_), ZIO.fail)
        check(causes) { c =>
          for {
            result <- cause(ZIO.failCause(c).sandbox.mapErrorCause(e => e.untraced).unsandbox)
          } yield assert(result)(equalTo(c))
        }
      }
    ),
    suite("updateService")(
      test("updates a service in the environment") {
        val zio = for {
          a <- ZIO.service[Int].updateService[Int](_ + 1)
          b <- ZIO.service[Int]
        } yield (a, b)
        assertZIO(zio.provideLayer(ZLayer.succeed(0)))(equalTo((1, 0)))
      }
    ),
    suite("validate")(
      test("returns all errors if never valid") {
        val in                      = List.fill(10)(0)
        def fail[A](a: A): IO[A, A] = ZIO.fail(a)
        val res                     = ZIO.validate(in)(fail).flip
        assertZIO(res)(equalTo(in))
      } @@ zioTag(errors),
      test("accumulate errors and ignore successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validate(in)(a => if (a % 2 == 0) ZIO.succeed(a) else ZIO.fail(a))
        assertZIO(res.flip)(equalTo(List(1, 3, 5, 7, 9)))
      } @@ zioTag(errors),
      test("accumulate successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validate(in)(a => ZIO.succeed(a))
        assertZIO(res)(equalTo(in))
      }
    ),
    suite("validateDiscard")(
      test("returns all errors if never valid") {
        val in                            = List.fill(10)(0)
        def fail[A](a: A): IO[A, Nothing] = ZIO.fail(a)
        val res                           = ZIO.validate(in)(fail).flip
        assertZIO(res)(equalTo(in))
      } @@ zioTag(errors)
    ),
    suite("validatePar")(
      test("returns all errors if never valid") {
        val in                      = List.fill(1000)(0)
        def fail[A](a: A): IO[A, A] = ZIO.fail(a)
        val res                     = ZIO.validatePar(in)(fail).flip
        assertZIO(res)(equalTo(in))
      } @@ zioTag(errors),
      test("accumulate errors and ignore successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validatePar(in)(a => if (a % 2 == 0) ZIO.succeed(a) else ZIO.fail(a))
        assertZIO(res.flip)(equalTo(List(1, 3, 5, 7, 9)))
      } @@ zioTag(errors),
      test("accumulate successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validatePar(in)(a => ZIO.succeed(a))
        assertZIO(res)(equalTo(in))
      }
    ),
    suite("validateParDiscard")(test("returns all errors if never valid") {
      val in                            = List.fill(10)(0)
      def fail[A](a: A): IO[A, Nothing] = ZIO.fail(a)
      val res                           = ZIO.validateParDiscard(in)(fail).flip
      assertZIO(res)(equalTo(in))
    } @@ zioTag(errors)),
    suite("validateFirst")(
      test("returns all errors if never valid") {
        val in  = List.fill(10)(0)
        val res = ZIO.validateFirst(in)(a => ZIO.fail(a)).flip
        assertZIO(res)(equalTo(in))
      } @@ zioTag(errors),
      test("runs sequentially and short circuits on first success validation") {
        implicit val canFail = CanFail
        val in               = List.range(1, 10)
        val f                = (a: Int) => if (a == 6) ZIO.succeed(a) else ZIO.fail(a)

        for {
          counter <- Ref.make(0)
          result  <- ZIO.validateFirst(in)(a => counter.update(_ + 1) *> f(a))
          count   <- counter.get
        } yield assert(result)(equalTo(6)) && assert(count)(equalTo(6))
      },
      test("returns errors in correct order") {
        val as = List(2, 4, 6, 3, 5, 6)
        for {
          results <- ZIO.validateFirst(as)(ZIO.fail(_)).flip
        } yield assert(results)(equalTo(List(2, 4, 6, 3, 5, 6)))
      } @@ zioTag(errors)
    ),
    suite("validateFirstPar")(
      test("returns all errors if never valid") {
        val in  = List.fill(1000)(0)
        val res = ZIO.validateFirstPar(in)(a => ZIO.fail(a)).flip
        assertZIO(res)(equalTo(in))
      } @@ zioTag(errors),
      test("returns success if valid") {
        implicit val canFail = CanFail
        val in               = List.range(1, 10)
        val f                = (a: Int) => if (a == 6) ZIO.succeed(a) else ZIO.fail(a)
        val res              = ZIO.validateFirstPar(in)(f(_))
        assertZIO(res)(equalTo(6))
      }
    ),
    suite("validateWith")(
      test("succeeds") {
        assertZIO(ZIO.attempt(1).validateWith(ZIO.attempt(2))(_ + _))(equalTo(3))
      },
      test("fails") {
        assertZIO(ZIO.attempt(1).validate(ZIO.fail(2)).sandbox.either)(isLeft(equalTo(Cause.Fail(2, StackTrace.none))))
      },
      test("combines both cause") {
        assertZIO(ZIO.fail(1).validate(ZIO.fail(2)).sandbox.either)(
          isLeft(equalTo(Cause.Then(Cause.Fail(1, StackTrace.none), Cause.Fail(2, StackTrace.none))))
        )
      }
    ),
    suite("onDone and onDoneCause")(
      test("onDone - should execute success callback synchronously on success") {
        for {
          ref    <- Ref.make(false)
          latch  <- Promise.make[Nothing, Unit]
          _      <- ZIO.succeed(42).onDone(_ => ZIO.unit, _ => ref.set(true) *> latch.succeed(()))
          _      <- latch.await
          result <- ref.get
        } yield assert(result)(isTrue)
      },
      test("onDone - should execute error callback synchronously on failure") {
        for {
          ref   <- Ref.make(false)
          latch <- Promise.make[Nothing, Unit]
          _ <- ZIO
                 .fail("Error")
                 .onDone(_ => ref.set(true) *> latch.succeed(()), _ => ZIO.unit)
          _      <- latch.await
          result <- ref.get
        } yield assert(result)(isTrue)
      },
      test("onDoneCause - should execute success callback synchronously on success") {
        for {
          ref    <- Ref.make(false)
          latch  <- Promise.make[Nothing, Unit]
          _      <- ZIO.succeed(42).onDoneCause(_ => ZIO.unit, _ => ref.set(true) *> latch.succeed(()))
          _      <- latch.await
          result <- ref.get
        } yield assert(result)(isTrue)
      },
      test("onDoneCause - should execute error callback synchronously on failure with cause") {
        for {
          ref   <- Ref.make(false)
          latch <- Promise.make[Nothing, Unit]
          _ <- ZIO
                 .fail("Error")
                 .onDoneCause(_ => ref.set(true) *> latch.succeed(()), _ => ZIO.unit)
          _      <- latch.await
          result <- ref.get
        } yield assert(result)(isTrue)
      }
    ),
    suite("when")(
      test("executes correct branch only") {
        for {
          effectRef <- Ref.make(0)
          _         <- effectRef.set(1).when(false)
          val1      <- effectRef.get
          _         <- effectRef.set(2).when(true)
          val2      <- effectRef.get
          failure    = new Exception("expected")
          _         <- ZIO.fail(failure).when(false)
          failed    <- ZIO.fail(failure).when(true).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(val2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      }
    ),
    suite("whenDiscard")(
      test("executes correct branch only") {
        for {
          effectRef <- Ref.make(0)
          _         <- effectRef.set(1).whenDiscard(false)
          val1      <- effectRef.get
          _         <- effectRef.set(2).whenDiscard(true)
          val2      <- effectRef.get
          failure    = new Exception("expected")
          _         <- ZIO.fail(failure).whenDiscard(false)
          failed    <- ZIO.fail(failure).when(true).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(val2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      }
    ),
    suite("whenCase")(
      test("executes correct branch only") {
        val v1: Option[Int] = None
        val v2: Option[Int] = Some(0)
        for {
          ref  <- Ref.make(false)
          _    <- ZIO.whenCase(v1) { case Some(_) => ref.set(true) }
          res1 <- ref.get
          _    <- ZIO.whenCase(v2) { case Some(_) => ref.set(true) }
          res2 <- ref.get
        } yield assert(res1)(isFalse) && assert(res2)(isTrue)
      }
    ),
    suite("whenCaseDiscard")(
      test("executes correct branch only") {
        val v1: Option[Int] = None
        val v2: Option[Int] = Some(0)
        for {
          ref <- Ref.make(1)
          _ <- ZIO.whenCaseDiscard(v1) {
                 case Some(1) => ref.update(_ + 1)
                 case Some(3) => ref.update(_ + 100)
               }
          res1 <- ref.get
          _ <- ZIO.whenCaseDiscard(v2) { case Some(_) =>
                 ref.set(5)
               }
          res2 <- ref.get
        } yield assertTrue(
          res1 == 1,
          res2 == 5
        )
      }
    ),
    suite("whenCaseZIODiscard")(
      test("executes condition effect and correct branch") {
        val v1: Option[Int] = None
        val v2: Option[Int] = Some(0)
        for {
          ref  <- Ref.make(false)
          _    <- ZIO.whenCaseZIODiscard(ZIO.succeed(v1)) { case Some(_) => ref.set(true) }
          res1 <- ref.get
          _    <- ZIO.whenCaseZIODiscard(ZIO.succeed(v2)) { case Some(_) => ref.set(true) }
          res2 <- ref.get
        } yield assert(res1)(isFalse) && assert(res2)(isTrue)
      }
    ),
    suite("whenCaseZIO")(
      test("executes condition effect and correct branch") {
        val v1: Option[Int] = None
        val v2: Option[Int] = Some(0)
        for {
          ref  <- Ref.make(false)
          _    <- ZIO.whenCaseZIO(ZIO.succeed(v1)) { case Some(_) => ref.set(true) }
          res1 <- ref.get
          _    <- ZIO.whenCaseZIO(ZIO.succeed(v2)) { case Some(_) => ref.set(true) }
          res2 <- ref.get
        } yield assert(res1)(isFalse) && assert(res2)(isTrue)
      }
    ),
    suite("whenZIO")(
      test("executes condition effect and correct branch") {
        for {
          effectRef     <- Ref.make(0)
          conditionRef  <- Ref.make(0)
          conditionTrue  = conditionRef.update(_ + 1).as(true)
          conditionFalse = conditionRef.update(_ + 1).as(false)
          _             <- effectRef.set(1).whenZIO(conditionFalse)
          val1          <- effectRef.get
          conditionVal1 <- conditionRef.get
          _             <- effectRef.set(2).whenZIO(conditionTrue)
          val2          <- effectRef.get
          conditionVal2 <- conditionRef.get
          failure        = new Exception("expected")
          _             <- ZIO.fail(failure).whenZIO(conditionFalse)
          failed        <- ZIO.fail(failure).whenZIO(conditionTrue).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(conditionVal1)(equalTo(1)) &&
          assert(val2)(equalTo(2)) &&
          assert(conditionVal2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZIO[R, E, Boolean] = ZIO.succeed(true)
        val zio: ZIO[R1, E1, A]   = ZIO.succeed(new A {})
        val _                     = ZIO.whenZIO(b)(zio)
        ZIO.succeed(assertCompletes)
      }
    ),
    suite("whenZIODiscard")(
      test("executes condition effect and correct branch") {
        for {
          effectRef     <- Ref.make(0)
          conditionRef  <- Ref.make(0)
          conditionTrue  = conditionRef.update(_ + 1).as(true)
          conditionFalse = conditionRef.update(_ + 1).as(false)
          _             <- effectRef.set(1).whenZIODiscard(conditionFalse)
          val1          <- effectRef.get
          conditionVal1 <- conditionRef.get
          _             <- effectRef.set(2).whenZIODiscard(conditionTrue)
          val2          <- effectRef.get
          conditionVal2 <- conditionRef.get
          failure        = new Exception("expected")
          _             <- ZIO.fail(failure).whenZIODiscard(conditionFalse)
          failed        <- ZIO.fail(failure).whenZIO(conditionTrue).either
        } yield {
          assert(val1)(equalTo(0)) &&
          assert(conditionVal1)(equalTo(1)) &&
          assert(val2)(equalTo(2)) &&
          assert(conditionVal2)(equalTo(2)) &&
          assert(failed)(isLeft(equalTo(failure)))
        }
      },
      test("infers correctly") {
        trait R
        trait R1 extends R
        trait E1
        trait E extends E1
        trait A
        val b: ZIO[R, E, Boolean] = ZIO.succeed(true)
        val zio: ZIO[R1, E1, A]   = ZIO.succeed(new A {})
        val _                     = ZIO.whenZIODiscard(b)(zio)
        ZIO.succeed(assertCompletes)
      }
    ),
    test("zip is compositional") {
      lazy val x1: Task[Int]                          = ???
      lazy val x2: Task[Unit]                         = ???
      lazy val x3: Task[String]                       = ???
      lazy val x4: Task[Boolean]                      = ???
      lazy val actual                                 = x1 <*> x2 <*> x3 <*> x4
      lazy val expected: Task[(Int, String, Boolean)] = actual
      lazy val _                                      = expected
      assertCompletes
    },
    suite("zipPar")(
      test("does not swallow exit causes of loser") {
        ZIO.interrupt.zipPar(ZIO.interrupt).exit.map {
          case Exit.Failure(cause) => assert(cause.interruptors)(not(isEmpty))
          case _                   => assert(false)(isTrue)
        }
      },
      test("does not report failure when interrupting loser after it succeeded") {
        val io          = ZIO.interrupt.zipPar(ZIO.succeed(1))
        val interrupted = io.sandbox.either.map(_.left.map(_.isInterrupted))
        assertZIO(interrupted)(isLeft(isTrue))
      } @@ zioTag(interruption),
      test("passes regression 1") {
        val io =
          ZIO.succeed[Int](1).zipPar(ZIO.succeed[Int](2)).flatMap(t => ZIO.succeed(t._1 + t._2)).map(_ == 3)
        assertZIO(io)(isTrue)
      } @@ zioTag(regression) @@ exceptJS(nonFlaky),
      test("paralellizes simple success values") {
        def countdown(n: Int): UIO[Int] =
          if (n == 0) ZIO.succeed(0)
          else
            ZIO.succeed[Int](1).zipPar(ZIO.succeed[Int](2)).flatMap(t => countdown(n - 1).map(y => t._1 + t._2 + y))

        assertZIO(countdown(50))(equalTo(150))
      },
      test("does not kill fiber when forked on parent scope") {

        for {
          latch1 <- Promise.make[Nothing, Unit]
          latch2 <- Promise.make[Nothing, Unit]
          latch3 <- Promise.make[Nothing, Unit]
          ref1   <- Ref.make(false)
          left = ZIO
                   .uninterruptibleMask(restore => latch2.succeed(()) *> restore(latch1.await *> ZIO.succeed("foo")))
                   .onInterrupt(ref1.set(true))
          right                         = latch3.succeed(()).as(42)
          _                            <- (latch2.await *> latch3.await *> latch1.succeed(())).fork
          result                       <- left.fork zipPar right
          (leftInnerFiber, rightResult) = result
          leftResult                   <- leftInnerFiber.await
          interrupted                  <- ref1.get
        } yield assert(interrupted)(isFalse) && assert(leftResult)(succeeds(equalTo("foo"))) && assert(rightResult)(
          equalTo(42)
        )
      },
      test("preserves failures") {
        ZIO.uninterruptible {
          for {
            promise1 <- Promise.make[Nothing, Unit]
            promise2 <- Promise.make[Nothing, Unit]
            left      = promise2.await.interruptible
            right1    = promise1.await *> ZIO.fail("fail")
            right2    = (promise1.succeed(()) *> ZIO.never).ensuring(promise2.interrupt).interruptible
            exit     <- left.zipPar(right1.zipPar(right2)).exit
          } yield assert(exit)(failsCause(containsCause(Cause.fail("fail"))))
        }
      } @@ exceptJS(nonFlaky(500)),
      test("is interruptible") {
        for {
          promise1 <- Promise.make[Nothing, Unit]
          promise2 <- Promise.make[Nothing, Unit]
          left      = promise1.succeed(()) *> ZIO.never
          right     = promise2.succeed(()) *> ZIO.never
          fiber    <- left.zipPar(right).fork
          _        <- promise1.await
          _        <- promise2.await
          _        <- fiber.interrupt
        } yield assertCompletes
      },
      test("propagates interruption") {
        val zio = ZIO.never <&> ZIO.never <&> ZIO.fail("fail")
        assertZIO(zio.exit)(fails(equalTo("fail")))
      },
      test("propagates FiberRef values") {
        for {
          fiberRef <- FiberRef.make(5)
          workflow  = fiberRef.set(10).delay(2.seconds) <&> fiberRef.set(15)
          fiber    <- workflow.fork
          _        <- TestClock.adjust(2.seconds)
          _        <- fiber.join
          value    <- fiberRef.get
        } yield assertTrue(value == 10)
      }
    ),
    suite("toFuture")(
      test("should fail with StackTrace attached") {
        for {
          future <- ZIO.fail(new Throwable(new IllegalArgumentException)).toFuture
          result <- ZIO.fromFuture(_ => future).either
        } yield assert(result)(isLeft(hasSuppressed(exists(hasMessage(containsString("zio-fiber"))))))
      }
    ) @@ zioTag(future),
    suite("resurrect")(
      test("should fail checked") {
        val error: Exception   = new Exception("msg")
        val effect: Task[Unit] = ZIO.fail(error).unit.orDie.resurrect
        assertZIO(effect.either)(isLeft(equalTo(error)))
      }
    ),
    suite("options")(
      test("basic option test") {
        for {
          value <- ZIO.getOrFailUnit(Some("foo"))
        } yield {
          assert(value)(equalTo("foo"))
        }
      },
      test("side effect unit in option test") {
        for {
          value <- ZIO.getOrFailUnit(None).catchAll(_ => ZIO.succeed("Controlling unit side-effect"))
        } yield {
          assert(value)(equalTo("Controlling unit side-effect"))
        }
      }
    ),
    suite("promises")(
      test("promise test") {
        val func: String => String = s => s.toUpperCase
        for {
          promise <- ZIO.succeed(scala.concurrent.Promise[String]())
          _ <- ZIO.attempt {
                 Try(func("hello world from future")) match {
                   case Success(value)     => promise.success(value)
                   case Failure(exception) => promise.failure(exception)
                 }
               }.fork
          value <- ZIO.fromPromiseScala(promise)
        } yield {
          assert(value)(equalTo("HELLO WORLD FROM FUTURE"))
        }
      },
      test("promise supplier test") {
        val func: Unit => String = _ => "hello again from future"
        for {
          promise <- ZIO.succeed(scala.concurrent.Promise[String]())
          _ <- ZIO.attempt {
                 Try(func(())) match {
                   case Success(value)     => promise.success(value)
                   case Failure(exception) => promise.failure(exception)
                 }
               }.fork
          value <- ZIO.fromPromiseScala(promise)
        } yield {
          assert(value)(equalTo("hello again from future"))
        }
      },
      test("promise ugly path test") {
        val func: String => String = _ => throw new Exception("side-effect")
        for {
          promise <- ZIO.succeed(scala.concurrent.Promise[String]())
          _ <- ZIO.attempt {
                 Try(func("hello world from future")) match {
                   case Success(value)     => promise.success(value)
                   case Failure(exception) => promise.failure(exception)
                 }
               }.fork
          value <- ZIO
                     .fromPromiseScala(promise)
                     .catchAll(_ => ZIO.succeed("Controlling side-effect of function passed to promise"))
        } yield {
          assert(value)(equalTo("Controlling side-effect of function passed to promise"))
        }
      }
    ),
    test("fail captures the spans") {
      for {
        cause <- ZIO.logSpan("parent")(ZIO.logSpan("child")(ZIO.fail("fail"))).cause
      } yield assertTrue(cause.spans.map(_.label) == List("child", "parent"))
    },
    test("fail captures the annotations") {
      for {
        cause <- ZIO.logAnnotate("key", "value")(ZIO.fail("fail")).cause
      } yield assertTrue(cause.annotations == Map("key" -> "value"))
    },
    test("orDie preserves the cause") {
      for {
        cause <- ZIO.fail(new RuntimeException("fail")).ensuring(ZIO.die(new RuntimeException("die"))).orDie.cause
      } yield assertTrue(cause.size == 2)
    },
    suite("ignore")(
      test("ignores successes") {
        var evaluated = false
        val workflow  = ZIO.ignore { evaluated = true }
        for {
          _ <- workflow
        } yield assertTrue(evaluated)
      },
      test("ignores failures") {
        var evaluated = false
        val workflow  = ZIO.ignore { evaluated = true; throw new Exception("fail") }
        for {
          _ <- workflow
        } yield assertTrue(evaluated)
      }
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
          _      <- ZIO.scoped(ZIO.fromAutoCloseable(closeable))
          result <- effects.get
        } yield assert(result)(equalTo(List("Closed")))
      }
    )
  )

  def functionIOGen: Gen[Any, String => ZIO[Any, Throwable, Int]] =
    Gen.function[Any, String, Task[Int]](Gen.successes(Gen.int))

  def listGen: Gen[Any, List[String]] =
    Gen.listOfN(100)(Gen.alphaNumericString)

  val exampleError = new Error("something went wrong")

  def exactlyOnce[R, A, A1](value: A)(func: UIO[A] => ZIO[R, String, A1]): ZIO[R, String, A1] =
    Ref.make(0).flatMap { ref =>
      for {
        res   <- func(ref.update(_ + 1) *> ZIO.succeed(value))
        count <- ref.get
        _ <- if (count != 1) {
               ZIO.fail("Accessed more than once")
             } else {
               ZIO.unit
             }
      } yield res
    }

  val testString = "supercalifragilisticexpialadocious"

  val ExampleError    = new Throwable("Oh noes!")
  val InterruptCause1 = new Throwable("Oh noes 1!")
  val InterruptCause2 = new Throwable("Oh noes 2!")
  val InterruptCause3 = new Throwable("Oh noes 3!")

  val TaskExampleError: Task[Int] = ZIO.fail[Throwable](ExampleError)

  val TaskExampleDie: Task[Int] = ZIO.succeed(throw ExampleError)

  def asyncExampleError[A]: Task[A] =
    ZIO.async[Any, Throwable, A](_(ZIO.fail(ExampleError)))

  def sum(n: Int): Int =
    if (n <= 0) 0
    else n + sum(n - 1)

  def deepMapNow(n: Int): UIO[Int] = {
    @tailrec
    def loop(n: Int, acc: UIO[Int]): UIO[Int] =
      if (n <= 0) acc
      else loop(n - 1, acc.map(_ + 1))

    loop(n, ZIO.succeed(0))
  }

  def deepMapEffect(n: Int): UIO[Int] = {
    @tailrec
    def loop(n: Int, acc: UIO[Int]): UIO[Int] =
      if (n <= 0) acc
      else loop(n - 1, acc.map(_ + 1))

    loop(n, ZIO.succeed(0))
  }

  def deepErrorEffect(n: Int): Task[Unit] =
    if (n == 0) ZIO.attempt(throw ExampleError)
    else ZIO.unit *> deepErrorEffect(n - 1)

  def deepErrorFail(n: Int): Task[Unit] =
    if (n == 0) ZIO.fail(ExampleError)
    else ZIO.unit *> deepErrorFail(n - 1)

  def fib(n: Int): BigInt =
    if (n <= 1) n
    else fib(n - 1) + fib(n - 2)

  def concurrentFib(n: Int): Task[BigInt] =
    if (n <= 1) ZIO.succeed[BigInt](n)
    else
      for {
        f1 <- concurrentFib(n - 1).fork
        f2 <- concurrentFib(n - 2).fork
        v1 <- f1.join
        v2 <- f2.join
      } yield v1 + v2

  def AsyncUnit[E]: IO[E, Unit] = ZIO.async[Any, E, Unit](_(ZIO.unit))

  type Logging = Logging.Service

  object Logging {
    trait Service
    val live: ZLayer[Any, Nothing, Logging] = ZLayer.succeed(new Logging.Service {})
  }

}
