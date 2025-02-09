package zio

import zio.Cause._
import zio.LatchOps._
import zio.Clock

import zio.internal.Platform
import zio.test.Assertion._
import zio.test.TestAspect.{flaky, forked, ignore, jvm, jvmOnly, nonFlaky, scala2Only}
import zio.test._

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import zio.{ Clock, FiberId, Random, _ }
import zio.test.{ Gen, Live, Sized, ZIOSpecDefault }

object ZIOSpec extends ZIOSpecDefault {

  def spec: ZSpec[Environment, Failure] = suite("ZIOSpec")(
    suite("&&")(
      test("true and true is true") {
        assertM(ZIO.succeed(true) && ZIO.succeed(true))(isTrue)
      },
      test("true and false is false") {
        assertM(ZIO.succeed(true) && ZIO.succeed(false))(isFalse)
      },
      test("false and true is false") {
        assertM(ZIO.succeed(false) && ZIO.succeed(true))(isFalse)
      },
      test("false and false is false") {
        assertM(ZIO.succeed(false) && ZIO.succeed(false))(isFalse)
      },
      test("short circuiting") {
        assertM(ZIO.succeed(false) && ZIO.fail("fail"))(isFalse)
      }
    ),
    suite("unary_!")(
      test("not true is false") {
        assertM(!ZIO.succeed(true))(isFalse)
      },
      test("not false is true") {
        assertM(!ZIO.succeed(false))(isTrue)
      }
    ),
    suite("||")(
      test("true or true is true") {
        assertM(ZIO.succeed(true) || ZIO.succeed(true))(isTrue)
      },
      test("true or false is true") {
        assertM(ZIO.succeed(true) || ZIO.succeed(false))(isTrue)
      },
      test("false or true is true") {
        assertM(ZIO.succeed(false) || ZIO.succeed(true))(isTrue)
      },
      test("false or false is false") {
        assertM(ZIO.succeed(false) || ZIO.succeed(false))(isFalse)
      },
      test("short circuiting") {
        assertM(ZIO.succeed(true) || ZIO.fail("fail"))(isTrue)
      }
    ),
    suite("absorbWith")(
      test("on fail") {
        assertM(TaskExampleError.absorbWith(identity).exit)(fails(equalTo(ExampleError)))
      },
      test("on die") {
        assertM(TaskExampleDie.absorbWith(identity).exit)(fails(equalTo(ExampleError)))
      },
      test("on success") {
        assertM(ZIO.succeed(1).absorbWith(_ => ExampleError))(equalTo(1))
      }
    ),
    suite("bracket")(
      test("bracket happy path") {
        for {
          release  <- Ref.make(false)
          result   <-
            ZIO.acquireReleaseWith(ZIO.succeed(42), (_: Int) => release.set(true), (a: Int) => ZIO.succeed(a + 1))
          released <- release.get
        } yield assert(result)(equalTo(43)) && assert(released)(isTrue)
      },
      test("bracket_ happy path") {
        for {
          release  <- Ref.make(false)
          result   <- ZIO.succeed(42).acquireRelease(release.set(true), ZIO.succeed(0))
          released <- release.get
        } yield assert(result)(equalTo(0)) && assert(released)(isTrue)
      },
      test("bracketExit happy path") {
        for {
          release <- Ref.make(false)
          result <- ZIO.acquireReleaseExitWith(
                      ZIO.succeed(42),
                      (_: Int, _: Exit[Any, Any]) => release.set(true),
                      (_: Int) => ZIO.succeed(0L)
                    )
          released <- release.get
        } yield assert(result)(equalTo(0L)) && assert(released)(isTrue)
      },
      test("bracketExit error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int, Int](
                      ZIO.succeed(42),
                      (_, _) => ZIO.die(releaseDied),
                      _ => ZIO.fail("use failed")
                    )
                    .exit
          cause <- exit.foldZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
        } yield assert(cause.failures)(equalTo(List("use failed"))) &&
          assert(cause.defects)(equalTo(List(releaseDied)))
      }
    ),
    suite("bracket + disconnect")(
      test("bracket happy path") {
        for {
          release <- Ref.make(false)
          result <- ZIO
                      .acquireReleaseWith(ZIO.succeed(42), (_: Int) => release.set(true), (a: Int) => ZIO.succeed(a + 1))
                      .disconnect
          released <- release.get
        } yield assert(result)(equalTo(43)) && assert(released)(isTrue)
      },
      test("bracket_ happy path") {
        for {
          release  <- Ref.make(false)
          result   <- ZIO.succeed(42).acquireRelease(release.set(true), ZIO.succeed(0)).disconnect
          released <- release.get
        } yield assert(result)(equalTo(0)) && assert(released)(isTrue)
      },
      test("bracketExit happy path") {
        for {
          release <- Ref.make(false)
          result <- ZIO
                      .acquireReleaseExitWith(
                        ZIO.succeed(42),
                        (_: Int, _: Exit[Any, Any]) => release.set(true),
                        (_: Int) => ZIO.succeed(0L)
                      )
                      .disconnect
          released <- release.get
        } yield assert(result)(equalTo(0L)) && assert(released)(isTrue)
      },
      test("bracketExit error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int, Int](
                      ZIO.succeed(42),
                      (_, _) => ZIO.die(releaseDied),
                      _ => ZIO.fail("use failed")
                    )
                    .disconnect
                    .exit
          cause <- exit.foldZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
        } yield assert(cause.failures)(equalTo(List("use failed"))) &&
          assert(cause.defects)(equalTo(List(releaseDied)))
      },
      test("bracketExit beast mode error handling") {
        val releaseDied: Throwable = new RuntimeException("release died")
        for {
          released <- ZRef.make(false)
          exit <- ZIO
                    .acquireReleaseExitWith[Any, String, Int, Int](
                      ZIO.succeed(42),
                      (_, _) => released.set(true),
                      _ => throw releaseDied
                    )
                    .disconnect
                    .exit
          cause      <- exit.foldZIO(cause => ZIO.succeed(cause), _ => ZIO.fail("effect should have failed"))
          isReleased <- released.get
        } yield assert(cause.defects)(equalTo(List(releaseDied))) && assert(isReleased)(isTrue)
      }
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
          a                   <- cached
          _                   <- TestClock.adjust(59.minutes)
          b                   <- cached
          _                   <- invalidate
          c                   <- cached
          _                   <- TestClock.adjust(1.minute)
          d                   <- cached
          _                   <- TestClock.adjust(59.minutes)
          e                   <- cached
        } yield assert(a)(equalTo(b)) &&
          assert(b)(not(equalTo(c))) &&
          assert(c)(equalTo(d)) &&
          assert(d)(not(equalTo(e)))
      }
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
    ),
    suite("catchSomeCause")(
      test("catches matching cause") {
        ZIO.interrupt.catchSomeCause {
          case c if c.isInterrupted => ZIO.succeed(true)
        }.sandbox.map(
          assert(_)(isTrue)
        )
      },
      test("halts if cause doesn't match") {
        ZIO.fiberId.flatMap { fiberId =>
          ZIO.interrupt.catchSomeCause {
            case c if (!c.isInterrupted) => ZIO.succeed(true)
          }.sandbox.either.map(
            assert(_)(isLeft(equalTo(Cause.interrupt(fiberId))))
          )
        }
      }
    ),
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
    ),
    suite("collect")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.collect(s"value was not 0")({ case v @ 0 => v })).sandbox.either
          badCase <-
            exactlyOnce(1)(_.collect(s"value was not 0")({ case v @ 0 => v })).sandbox.either
              .map(_.left.map(_.failureOrCause))
        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("value was not 0"))))
      }
    ),
    suite("collectAllPar")(
      test("returns the list in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.collectAllPar(list)
        assertM(res)(equalTo(List(1, 2, 3)))
      },
      test("is referentially transparent") {
        for {
          counter <- Ref.make(0)
          op       = counter.getAndUpdate(_ + 1)
          ops3     = ZIO.collectAllPar(List(op, op, op))
          ops6     = ops3.zipPar(ops3)
          res     <- ops6
        } yield assert(res._1)(not(equalTo(res._2)))
      }
    ),
    suite("collectAllParN")(
      test("returns results in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.collectAllParN(2)(list)
        assertM(res)(equalTo(List(1, 2, 3)))
      }
    ),
    suite("collectAllParN_")(
      test("preserves failures") {
        val tasks = List.fill(10)(ZIO.fail(new RuntimeException))
        assertM(ZIO.collectAllParN_(5)(tasks).flip)(anything)
      }
    ),
    suite("collectFirst")(
      test("collects the first value for which the effectual functions returns Some") {
        check(Gen.listOf(Gen.int), Gen.partialFunction(Gen.int)) { (as, pf) =>
          def f(n: Int): UIO[Option[Int]] = ZIO.succeed(pf.lift(n))
          assertM(ZIO.collectFirst(as)(f))(equalTo(as.collectFirst(pf)))
        }
      }
    ),
    suite("collectM")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(
              _.collectZIO[Any, String, Int]("Predicate failed!")({ case v @ 0 => ZIO.succeed(v) })
            ).sandbox.either
          partialBadCase <-
            exactlyOnce(0)(
              _.collectZIO("Predicate failed!")({ case v @ 0 => ZIO.fail("Partial failed!") })
            ).sandbox.either
              .map(_.left.map(_.failureOrCause))
          badCase <-
            exactlyOnce(1)(_.collectZIO("Predicate failed!")({ case v @ 0 => ZIO.succeed(v) })).sandbox.either
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

        val fiberId = FiberId(0L, 123L)
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
        val executor = zio.Executor.fromExecutionContext(100) {
          scala.concurrent.ExecutionContext.Implicits.global
        }
        for {
          default <- ZIO.executor
          global  <- ZIO.executor.lock(executor)
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
    suite("repeatUntilM")(
      test("repeatUntilM repeat until effectful condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatUntilZIO(v => ZIO.succeed(v == 0))
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("repeatUntilM always evaluates effect at least once") {
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
    suite("repeatWhileM")(
      test("repeatWhileM repeats while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).repeatWhileZIO(v => ZIO.succeed(v >= 0))
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("repeatWhileM always evaluates effect at least once") {
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

        assertM(test)(equalTo(10))
      }
    ),
    suite("exists")(
      test("determines whether any element satisfies the effectual predicate") {
        check(Gen.listOf(Gen.int), Gen.function(Gen.boolean)) { (as, f) =>
          val actual   = ZIO.exists(as)(a => ZIO.succeed(f(a)))
          val expected = as.exists(f)
          assertM(actual)(equalTo(expected))
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
    suite("filterOrElse")(
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
    suite("filterOrElse_")(
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
    ),
    suite("flattenErrorOption")(
      test("fails when given Some error") {
        val task: IO[String, Int] = ZIO.fail(Some("Error")).flattenErrorOption("Default")
        assertM(task.exit)(fails(equalTo("Error")))
      },
      test("fails with Default when given None error") {
        val task: IO[String, Int] = ZIO.fail(None).flattenErrorOption("Default")
        assertM(task.exit)(fails(equalTo("Default")))
      },
      test("succeeds when given a value") {
        val task: IO[String, Int] = ZIO.succeed(1).flattenErrorOption("Default")
        assertM(task)(equalTo(1))
      }
    ),
    suite("foldLeft")(
      test("with a successful step function sums the list properly") {
        check(Gen.listOf(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(0)((acc, el) => ZIO.succeed(acc + el))
          assertM(res)(equalTo(l.sum))
        }
      },
      test("`with a failing step function returns a failed IO") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(0)((_, _) => ZIO.fail("fail"))
          assertM(res.exit)(fails(equalTo("fail")))
        }
      },
      test("run sequentially from left to right") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldLeft(l)(List.empty[Int])((acc, el) => ZIO.succeed(el :: acc))
          assertM(res)(equalTo(l.reverse))
        }
      }
    ),
    suite("foldRight")(
      test("with a successful step function sums the list properly") {
        check(Gen.listOf(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(0)((el, acc) => ZIO.succeed(acc + el))
          assertM(res)(equalTo(l.sum))
        }
      },
      test("`with a failing step function returns a failed IO") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(0)((_, _) => ZIO.fail("fail"))
          assertM(res.exit)(fails(equalTo("fail")))
        }
      },
      test("run sequentially from right to left") {
        check(Gen.listOf1(Gen.int)) { l =>
          val res = ZIO.foldRight(l)(List.empty[Int])((el, acc) => ZIO.succeed(el :: acc))
          assertM(res)(equalTo(l))
        }
      }
    ),
    suite("forall")(
      test("determines whether all elements satisfy the effectual predicate") {
        check(Gen.listOf(Gen.int), Gen.function(Gen.boolean)) { (as, f) =>
          val actual   = ZIO.forall(as)(a => ZIO.succeed(f(a)))
          val expected = as.forall(f)
          assertM(actual)(equalTo(expected))
        }
      }
    ),
    suite("foreach")(
      test("returns the list of results") {
        checkAll(functionIOGen, listGen) { (f, list) =>
          val res = ZIO.foreach(list)(f)
          assertM(res)(isSubtype[List[Int]](anything) && hasSize(equalTo(100)))
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
        assertM(res.exit)(dies(isSubtype[NumberFormatException](anything)))
      }
    ),
    suite("foreach_")(
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
        assertM(task)(isNone)
      },
      test("succeeds with Some given Some") {
        for {
          optRes <- ZIO.foreach(Some("success"))(str => ZIO.succeed(str.length))
        } yield assert(optRes)(equalTo(Some(7)))
      },
      test("fails if the optional effect fails") {
        val opt = Some("h")
        val res = ZIO.foreach(opt)(x => ZIO.succeed[Int](x.toInt))
        assertM(res.exit)(dies(isSubtype[NumberFormatException](anything)))
      }
    ),
    suite("foreachPar")(
      test("runs single task") {
        val as      = List(2)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertM(results)(equalTo(List(4)))
      },
      test("runs two tasks") {
        val as      = List(2, 3)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertM(results)(equalTo(List(4, 6)))
      },
      test("runs many tasks") {
        val as      = (1 to 1000)
        val results = ZIO.foreachPar(as)(a => ZIO.succeed(2 * a))
        assertM(results)(equalTo(as.map(2 * _)))
      },
      test("runs a task that fails") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.fail("Boom!")
            case a => ZIO.succeed(2 * a)
          }
          .flip
        assertM(results)(equalTo("Boom!"))
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
        assertM(results)(equalTo("Boom1!") || equalTo("Boom2!"))
      },
      test("runs a task that dies") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.dieMessage("Boom!")
            case a => ZIO.succeed(2 * a)
          }
          .exit
        assertM(results)(dies(hasMessage(equalTo("Boom!"))))
      },
      test("runs a task that is interrupted") {
        val as = (1 to 10)
        val results = ZIO
          .foreachPar(as) {
            case 5 => ZIO.interrupt
            case a => ZIO.succeed(2 * a)
          }
          .exit
        assertM(results)(isInterrupted)
      },
      test("runs a task that throws an unsuspended exception") {
        def f(i: Int): Task[Int] = throw new Exception(i.toString)
        for {
          _ <- ZIO.foreachPar(1 to 1)(f).exit
        } yield assertCompletes
      },
      test("returns results in the same order") {
        val list = List("1", "2", "3")
        val res  = ZIO.foreachPar(list)(x => ZIO.succeed[Int](x.toInt))
        assertM(res)(equalTo(List(1, 2, 3)))
      },
      test("returns results in the same order for Chunk") {
        val chunk = Chunk("1", "2", "3")
        val res   = ZIO.foreachPar(chunk)(x => ZIO.succeed[Int](x.toInt))
        assertM(res)(equalTo(Chunk(1, 2, 3)))
      },
      test("runs effects in parallel") {
        assertM(for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachPar(List(ZIO.never, p.succeed(())))(a => a).fork
          _ <- p.await
        } yield true)(isTrue)
      },
      test("runs effects in parallel for Chunk") {
        assertM(for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachPar(Chunk(ZIO.never, p.succeed(()), ZIO.never))(a => a).fork
          _ <- p.await
        } yield true)(isTrue)
      },
      test("propagates error") {
        val ints = List(1, 2, 3, 4, 5, 6)
        val odds = ZIO.foreachPar(ints)(n => if (n % 2 != 0) ZIO.succeed(n) else ZIO.fail("not odd"))
        assertM(odds.flip)(equalTo("not odd"))
      },
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
      },
      test("does not kill fiber when forked on the parent scope") {
        for {
          ref    <- Ref.make(0)
          fibers <- ZIO.foreachPar(1 to 100)(_ => ref.update(_ + 1).fork)
          _      <- ZIO.foreach(fibers)(_.await)
          value  <- ref.get
        } yield assert(value)(equalTo(100))
      }
    ),
    suite("foreachPar_")(
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
      },
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
        val res  = ZIO.foreachParN(2)(list)(x => ZIO.succeed(x.toString))
        assertM(res)(equalTo(List("1", "2", "3")))
      },
      test("works on large lists") {
        val n   = 10
        val seq = List.range(0, 100000)
        val res = ZIO.foreachParN(n)(seq)(ZIO.succeed(_))
        assertM(res)(equalTo(seq))
      },
      test("runs effects in parallel") {
        val io = for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.foreachParN(2)(List(ZIO.never, p.succeed(())))(identity).fork
          _ <- p.await
        } yield true
        assertM(io)(isTrue)
      },
      test("propagates error") {
        val ints = List(1, 2, 3, 4, 5, 6)
        val odds = ZIO.foreachParN(4)(ints)(n => if (n % 2 != 0) ZIO.succeed(n) else ZIO.fail("not odd"))
        assertM(odds.either)(isLeft(equalTo("not odd")))
      },
      test("interrupts effects on first failure") {
        val actions = List(
          ZIO.never,
          ZIO.succeed(1),
          ZIO.fail("C")
        )
        val io = ZIO.foreachParN(4)(actions)(a => a)
        assertM(io.either)(isLeft(equalTo("C")))
      }
    ),
    suite("foreachParN_")(
      test("runs all effects") {
        val as = Seq(1, 2, 3, 4, 5)
        for {
          ref <- Ref.make(Seq.empty[Int])
          _   <- ZIO.foreachParN_(2)(as)(a => ref.update(_ :+ a))
          rs  <- ref.get
        } yield assert(rs)(hasSize(equalTo(as.length))) &&
          assert(rs.toSet)(equalTo(as.toSet))
      }
    ),
    suite("forkAll")(
      test("returns the list of results in the same order") {
        val list = List(1, 2, 3).map(ZIO.succeed[Int](_))
        val res  = ZIO.forkAll(list).flatMap[Any, Nothing, List[Int]](_.join)
        assertM(res)(equalTo(List(1, 2, 3)))
      },
      test("happy-path") {
        val list = (1 to 1000).toList
        assertM(ZIO.forkAll(list.map(a => ZIO.succeed(a))).flatMap(_.join))(equalTo(list))
      },
      test("empty input") {
        assertM(ZIO.forkAll(List.empty[ZIO[Any, Nothing, Unit]]).flatMap(_.join))(equalTo(List.empty))
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
          fiber3 <- ZIO.forkAll(List(die, ZIO.succeed(42), ZIO.never))

          result1 <- joinDefect(fiber1)
          result2 <- joinDefect(fiber2)
          result3 <- joinDefect(fiber3)
        } yield {
          assert(result1)(equalTo(Cause.die(boom))) && {
            assert(result2)(equalTo(Cause.die(boom))) ||
            (assert(result2.dieOption)(isSome(equalTo(boom))) && assert(result2.isInterrupted)(isTrue))
          } && {
            assert(result3.dieOption)(isSome(equalTo(boom))) && assert(result3.isInterrupted)(isTrue)
          }
        }
      } @@ nonFlaky,
      test("infers correctly") {
        for {
          ref    <- Ref.make(0)
          worker  = ZIO.never
          workers = List.fill(4)(worker)
          fiber  <- ZIO.forkAll(workers)
          _      <- fiber.interrupt
          value  <- ref.get
        } yield assert(value)(equalTo(0))
      }
    ),
    suite("forkAs")(
      test("child has specified name") {
        for {
          fiber <- Fiber.fiberName.get.forkAs("child")
          name  <- fiber.join
        } yield assert(name)(isSome(equalTo("child")))
      },
      test("parent name is unchanged") {
        for {
          _    <- ZIO.unit.forkAs("child")
          name <- Fiber.fiberName.get
        } yield assert(name)(isNone)
      },
      test("parent does not inherit child name on join") {
        for {
          fiber <- ZIO.unit.forkAs("child")
          _     <- fiber.join
          name  <- Fiber.fiberName.get
        } yield assert(name)(isNone)
      }
    ),
    suite("forkIn") {
      test("fiber forked in a closed scope does not run") {
        for {
          ref   <- Ref.make(false)
          open  <- ZScope.make[Exit[Any, Any]]
          _     <- open.close(Exit.unit)
          fiber <- ref.set(true).forkIn(open.scope)
          exit  <- fiber.await
          value <- ref.get
        } yield assert(exit)(isInterrupted) && assert(value)(isFalse)
      }
    },
    suite("forkWithErrorHandler")(
      test("calls provided function when task fails") {
        for {
          p <- Promise.make[Nothing, Unit]
          _ <- ZIO.fail(()).forkWithErrorHandler(p.succeed(_).unit)
          _ <- p.await
        } yield assertCompletes
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
    ) @@ jvmOnly,
    suite("head")(
      test("on non empty list") {
        assertM(ZIO.succeed(List(1, 2, 3)).head.either)(isRight(equalTo(1)))
      },
      test("on empty list") {
        assertM(ZIO.succeed(List.empty).head.either)(isLeft(isNone))
      },
      test("on failure") {
        assertM(ZIO.fail("Fail").head.either)(isLeft(isSome(equalTo("Fail"))))
      }
    ),
    suite("ifM")(
      test("runs `onTrue` if result of `b` is `true`") {
        val zio = ZIO.ifZIO(ZIO.succeed(true))(ZIO.succeed(true), ZIO.succeed(false))
        assertM(zio)(isTrue)
      },
      test("runs `onFalse` if result of `b` is `false`") {
        val zio = ZIO.ifZIO(ZIO.succeed(false))(ZIO.succeed(true), ZIO.succeed(false))
        assertM(zio)(isFalse)
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
        assertM(ZIO.succeed(11).ignore)(equalTo(()))
      },
      test("return failure as Unit") {
        assertM(ZIO.fail(123).ignore)(equalTo(()))
      },
      test("not catch throwable") {
        assertM(ZIO.die(ExampleError).ignore.exit)(dies(equalTo(ExampleError)))
      }
    ),
    suite("isFailure")(
      test("returns true when the effect is a failure") {
        assertM(ZIO.fail("fail").isFailure)(isTrue)
      },
      test("returns false when the effect is a success") {
        assertM(ZIO.succeed("succeed").isFailure)(isFalse)
      }
    ),
    suite("isSuccess")(
      test("returns false when the effect is a failure") {
        assertM(ZIO.fail("fail").isSuccess)(isFalse)
      },
      test("returns true when the effect is a success") {
        assertM(ZIO.succeed("succeed").isSuccess)(isTrue)
      }
    ),
    suite("iterate")(
      test("iterates with the specified effectual function") {
        for {
          result <- ZIO.iterate(100)(_ > 0)(a => ZIO.succeed(a - 1))
        } yield assert(result)(equalTo(0))
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
    suite("loop_")(
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
          assertM(res)(isLeft(equalTo(i.toString)))
        }
      }
    ),
    suite("mapEffect")(
      test("returns an effect whose success is mapped by the specified side effecting function") {
        val task = ZIO.succeed("123").mapAttempt(_.toInt)
        assertM(task)(equalTo(123))
      },
      test("translates any thrown exceptions into typed failed effects") {
        val task = ZIO.succeed("hello").mapAttempt(_.toInt)
        assertM(task.exit)(fails(isSubtype[NumberFormatException](anything)))
      }
    ),
    suite("mapN")(
      test("with Tuple2") {
        check(Gen.int, Gen.alphaNumericString) { (int: Int, str: String) =>
          def f(i: Int, s: String): String = i.toString + s
          val actual                       = ZIO.mapN(ZIO.succeed(int), ZIO.succeed(str))(f)
          val expected                     = f(int, str)
          assertM(actual)(equalTo(expected))
        }
      },
      test("with Tuple3") {
        check(Gen.int, Gen.alphaNumericString, Gen.alphaNumericString) { (int: Int, str1: String, str2: String) =>
          def f(i: Int, s1: String, s2: String): String = i.toString + s1 + s2
          val actual                                    = ZIO.mapN(ZIO.succeed(int), ZIO.succeed(str1), ZIO.succeed(str2))(f)
          val expected                                  = f(int, str1, str2)
          assertM(actual)(equalTo(expected))
        }
      },
      test("with Tuple4") {
        check(Gen.int, Gen.alphaNumericString, Gen.alphaNumericString, Gen.alphaNumericString) {
          (int: Int, str1: String, str2: String, str3: String) =>
            def f(i: Int, s1: String, s2: String, s3: String): String = i.toString + s1 + s2 + s3
            val actual =
              ZIO.mapN(ZIO.succeed(int), ZIO.succeed(str1), ZIO.succeed(str2), ZIO.succeed(str3))(f)
            val expected = f(int, str1, str2, str3)
            assertM(actual)(equalTo(expected))
        }
      }
    ),
    suite("mapParN")(
      test("with Tuple2") {
        check(Gen.int, Gen.alphaNumericString) { (int: Int, str: String) =>
          def f(i: Int, s: String): String = i.toString + s
          val actual                       = ZIO.mapParN(ZIO.succeed(int), ZIO.succeed(str))(f)
          val expected                     = f(int, str)
          assertM(actual)(equalTo(expected))
        }
      },
      test("with Tuple3") {
        check(Gen.int, Gen.alphaNumericString, Gen.alphaNumericString) { (int: Int, str1: String, str2: String) =>
          def f(i: Int, s1: String, s2: String): String = i.toString + s1 + s2
          val actual                                    = ZIO.mapParN(ZIO.succeed(int), ZIO.succeed(str1), ZIO.succeed(str2))(f)
          val expected                                  = f(int, str1, str2)
          assertM(actual)(equalTo(expected))
        }
      },
      test("with Tuple4") {
        check(Gen.int, Gen.alphaNumericString, Gen.alphaNumericString, Gen.alphaNumericString) {
          (int: Int, str1: String, str2: String, str3: String) =>
            def f(i: Int, s1: String, s2: String, s3: String): String = i.toString + s1 + s2 + s3
            val actual =
              ZIO.mapParN(ZIO.succeed(int), ZIO.succeed(str1), ZIO.succeed(str2), ZIO.succeed(str3))(f)
            val expected = f(int, str1, str2, str3)
            assertM(actual)(equalTo(expected))
        }
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
        assertM(merged.exit)(fails(equalTo(1)))
      }
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
        assertM(merged.exit)(fails(equalTo(1)))
      }
    ),
    suite("none")(
      test("on Some fails with None") {
        val task: IO[Option[Throwable], Unit] = ZIO.attempt(Some(1)).none
        assertM(task.exit)(fails(isNone))
      },
      test("on None succeeds with ()") {
        val task: IO[Option[Throwable], Unit] = ZIO.attempt(None).none
        assertM(task)(isUnit)
      },
      test("fails with Some(ex) when effect fails with ex") {
        val ex                                = new RuntimeException("Failed Task")
        val task: IO[Option[Throwable], Unit] = ZIO.fail(ex).none
        assertM(task.exit)(fails(isSome(equalTo(ex))))
      }
    ),
    suite("negate")(
      test("on true returns false") {
        assertM(ZIO.succeed(true).negate)(equalTo(false))
      },
      test("on false returns true") {
        assertM(ZIO.succeed(false).negate)(equalTo(true))
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
      }
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
      }
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
                   case Exit.Failure(c) if c.died => ref.set(true)
                   case _                         => ZIO.unit
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
                     case _                                => ZIO.unit
                   }.fork
          _ <- latch1.await
          _ <- fiber.interrupt
          _ <- latch2.await
        } yield assertCompletes
      }
    ),
    suite("option")(
      test("return success in Some") {
        implicit val canFail = CanFail
        assertM(ZIO.succeed(11).option)(equalTo(Some(11)))
      },
      test("return failure as None") {
        assertM(ZIO.fail(123).option)(equalTo(None))
      },
      test("not catch throwable") {
        implicit val canFail = CanFail
        assertM(ZIO.die(ExampleError).option.exit)(dies(equalTo(ExampleError)))
      },
      test("catch throwable after sandboxing") {
        assertM(ZIO.die(ExampleError).sandbox.option)(equalTo(None))
      }
    ),
    suite("optional")(
      test("fails when given Some error") {
        val task: IO[String, Option[Int]] = ZIO.fail(Some("Error")).unsome
        assertM(task.exit)(fails(equalTo("Error")))
      },
      test("succeeds with None given None error") {
        val task: IO[String, Option[Int]] = ZIO.fail(None).unsome
        assertM(task)(isNone)
      },
      test("succeeds with Some given a value") {
        val task: IO[String, Option[Int]] = ZIO.succeed(1).unsome
        assertM(task)(isSome(equalTo(1)))
      }
    ),
    suite("orElse")(
      test("does not recover from defects") {
        val ex               = new Exception("Died")
        val fiberId          = FiberId(0L, 123L)
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
          case Traced(Die(e: Throwable), _) => ZIO.attempt(e.getMessage == "2")
          case _                            => ZIO.attempt(false)
        }
        assertM(orElse)(equalTo(true))
      },
      test("left failed and right failed with kept cause") {
        val z1                = ZIO.fail(new Throwable("1"))
        val z2: Task[Nothing] = ZIO.fail(new Throwable("2"))
        val orElse: Task[Boolean] = z1.orElse(z2).catchAllCause {
          case Traced(Fail(e: Throwable), _) => ZIO.attempt(e.getMessage == "2")
          case _                             => ZIO.attempt(false)
        }
        assertM(orElse)(equalTo(true))
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
    ),
    suite("orElseFail")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        assertM(ZIO.succeed(true).orElseFail(false))(isTrue)
      },
      test("otherwise fails with the specified error") {
        assertM(ZIO.fail(false).orElseFail(true).flip)(isTrue)
      }
    ),
    suite("orElseOptional")(
      test("produces the value of this effect if it succeeds") {
        val zio = ZIO.succeed("succeed").orElseOptional(ZIO.succeed("orElse"))
        assertM(zio)(equalTo("succeed"))
      },
      test("produces the value of this effect if it fails with some error") {
        val zio = ZIO.fail(Some("fail")).orElseOptional(ZIO.succeed("orElse"))
        assertM(zio.exit)(fails(isSome(equalTo("fail"))))
      },
      test("produces the value of the specified effect if it fails with none") {
        val zio = ZIO.fail(None).orElseOptional(ZIO.succeed("orElse"))
        assertM(zio)(equalTo("orElse"))
      }
    ),
    suite("orElseSucceed")(
      test("executes this effect and returns its value if it succeeds") {
        implicit val canFail = CanFail
        assertM(ZIO.succeed(true).orElseSucceed(false))(isTrue)
      },
      test("otherwise succeeds with the specified value") {
        assertM(ZIO.fail(false).orElseSucceed(true))(isTrue)
      }
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
      } @@ nonFlaky
    ),
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
      },
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partition(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a))
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      },
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
      },
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partitionPar(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a))
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      }
    ),
    suite("partitionParN")(
      test("collects a lot of successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 1000)
        for {
          res <- ZIO.partitionParN(3)(in)(a => ZIO.succeed(a))
        } yield assert(res._1)(isEmpty) && assert(res._2)(equalTo(in))
      },
      test("collects failures") {
        val in = List.fill(10)(0)
        for {
          res <- ZIO.partitionParN(3)(in)(a => ZIO.fail(a))
        } yield assert(res._1)(equalTo(in)) && assert(res._2)(isEmpty)
      },
      test("collects failures and successes") {
        val in = List.range(0, 10)
        for {
          res <- ZIO.partitionParN(3)(in)(a => if (a % 2 == 0) ZIO.fail(a) else ZIO.succeed(a))
        } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
      }
    ),
    suite("provideCustomLayer")(
      test("provides the part of the environment that is not part of the `ZEnv`") {
        val loggingLayer: ZLayer[Any, Nothing, Logging] = Logging.live
        val zio: ZIO[ZEnv with Logging, Nothing, Unit]  = ZIO.unit
        val zio2: URIO[ZEnv, Unit]                      = zio.provideCustomLayer(loggingLayer)
        assertM(zio2)(anything)
      }
    ),
    suite("provideSomeLayer")(
      test("can split environment into two parts") {
        val clockLayer: ZLayer[Any, Nothing, Clock]    = Clock.live
        val zio: ZIO[Clock with Random, Nothing, Unit] = ZIO.unit
        val zio2: URIO[Random, Unit]                   = zio.provideSomeLayer[Random](clockLayer)
        assertM(zio2)(anything)
      }
    ),
    suite("raceAll")(
      test("returns first success") {
        assertM(ZIO.fail("Fail").raceAll(List(ZIO.succeed(24))))(equalTo(24))
      },
      test("returns last failure") {
        assertM(Live.live(ZIO.sleep(100.millis) *> ZIO.fail(24)).raceAll(List(ZIO.fail(25))).flip)(equalTo(24))
      } @@ flaky,
      test("returns success when it happens after failure") {
        assertM(ZIO.fail(42).raceAll(List(ZIO.succeed(24) <* Live.live(ZIO.sleep(100.millis)))))(equalTo(24))
      }
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
        assertM(reduced.exit)(fails(equalTo(1)))
      },
      test("return error if it exists in list") {
        val zeroElement = ZIO.unit
        val effects     = List(ZIO.unit, ZIO.fail(1))
        val reduced     = ZIO.reduceAllPar(zeroElement, effects)((_, _) => ())
        assertM(reduced.exit)(fails(equalTo(1)))
      }
    ),
    suite("replicate")(
      test("zero") {
        val lst: Iterable[UIO[Int]] = ZIO.replicate(0)(ZIO.succeed(12))
        assertM(ZIO.collectAll(lst))(equalTo(List.empty))
      },
      test("negative") {
        val anotherList: Iterable[UIO[Int]] = ZIO.replicate(-2)(ZIO.succeed(12))
        assertM(ZIO.collectAll(anotherList))(equalTo(List.empty))
      },
      test("positive") {
        val lst: Iterable[UIO[Int]] = ZIO.replicate(2)(ZIO.succeed(12))
        assertM(ZIO.collectAll(lst))(equalTo(List(12, 12)))
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
    suite("retryUntilM")(
      test("retryUntilM retries until condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryUntilZIO(v => ZIO.succeed(v == 0)))
          result <- out.get
        } yield assert(result)(equalTo(10))
      },
      test("retryUntilM runs at least once") {
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
    suite("retryWhileM")(
      test("retryWhileM retries while condition is true") {
        for {
          in     <- Ref.make(10)
          out    <- Ref.make(0)
          _      <- (in.updateAndGet(_ - 1) <* out.update(_ + 1)).flipWith(_.retryWhileZIO(v => ZIO.succeed(v >= 0)))
          result <- out.get
        } yield assert(result)(equalTo(11))
      },
      test("retryWhileM runs at least once") {
        for {
          ref    <- Ref.make(0)
          _      <- ref.update(_ + 1).flipWith(_.retryWhileZIO(_ => ZIO.succeed(false)))
          result <- ref.get
        } yield assert(result)(equalTo(1))
      }
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
        assertM(result)(isLeft(equalTo(expected)))
      } @@ scala2Only
    ),
    suite("some")(
      test("extracts the value from Some") {
        val task: IO[Option[Throwable], Int] = ZIO.attempt(Some(1)).some
        assertM(task)(equalTo(1))
      },
      test("make a task from a defined option") {
        assertM(ZIO.getOrFail(Some(1)))(equalTo(1))
      },
      test("make a task from an empty option") {
        assertM(ZIO.getOrFail(None).exit)(fails(isSubtype[NoSuchElementException](anything)))
      },
      test("fails on None") {
        val task: IO[Option[Throwable], Int] = ZIO.attempt(None).some
        assertM(task.exit)(fails(isNone))
      },
      test("fails when given an exception") {
        val ex                               = new RuntimeException("Failed Task")
        val task: IO[Option[Throwable], Int] = ZIO.fail(ex).some
        assertM(task.exit)(fails(isSome(equalTo(ex))))
      }
    ),
    suite("someOrFailException")(
      test("extracts the optional value") {
        assertM(ZIO.some(42).someOrFailException)(equalTo(42))
      },
      test("fails when given a None") {
        val task = ZIO.succeed(Option.empty[Int]).someOrFailException
        assertM(task.exit)(fails(isSubtype[NoSuchElementException](anything)))
      },
      suite("without another error type")(
        test("succeed something") {
          assertM(ZIO.succeed(Option(3)).someOrFailException)(equalTo(3))
        },
        test("succeed nothing") {
          assertM(ZIO.succeed(None: Option[Int]).someOrFailException.exit)(fails(Assertion.anything))
        }
      ),
      suite("with throwable as base error type")(
        test("return something") {
          assertM(ZIO.attempt(Option(3)).someOrFailException)(equalTo(3))
        }
      ),
      suite("with exception as base error type")(
        test("return something") {
          assertM((ZIO.succeed(Option(3)): IO[Exception, Option[Int]]).someOrFailException)(equalTo(3))
        }
      )
    ),
    suite("reject")(
      test("returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(_.reject({ case v if v != 0 => "Partial failed!" })).sandbox.either
          badCase <-
            exactlyOnce(1)(_.reject({ case v if v != 0 => "Partial failed!" })).sandbox.either
              .map(_.left.map(_.failureOrCause))
        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Partial failed!"))))
      }
    ),
    suite("rejectM")(
      test("Check `rejectM` returns failure ignoring value") {
        for {
          goodCase <-
            exactlyOnce(0)(
              _.rejectZIO[Any, String]({ case v if v != 0 => ZIO.succeed("Partial failed!") })
            ).sandbox.either
          partialBadCase <-
            exactlyOnce(1)(_.rejectZIO({ case v if v != 0 => ZIO.fail("Partial failed!") })).sandbox.either
              .map(_.left.map(_.failureOrCause))
          badCase <-
            exactlyOnce(1)(_.rejectZIO({ case v if v != 0 => ZIO.fail("Partial failed!") })).sandbox.either
              .map(_.left.map(_.failureOrCause))

        } yield assert(goodCase)(isRight(equalTo(0))) &&
          assert(partialBadCase)(isLeft(isLeft(equalTo("Partial failed!")))) &&
          assert(badCase)(isLeft(isLeft(equalTo("Partial failed!"))))
      }
    ),
    suite("RTS synchronous correctness")(
      test("widen Nothing") {
        val op1 = ZIO.succeed[String]("1")
        val op2 = ZIO.succeed[String]("2")

        assertM(op1.zipWith(op2)(_ + _))(equalTo("12"))
      },
      test("succeed must be lazy") {
        val io =
          try {
            ZIO.succeed(throw ExampleError)
            ZIO.succeed(true)
          } catch {
            case _: Throwable => ZIO.succeed(false)
          }

        assertM(io)(isTrue)
      },
      test("effectSuspend must be lazy") {
        val io =
          try {
            ZIO.suspend(throw ExampleError)
            ZIO.succeed(false)
          } catch {
            case _: Throwable => ZIO.succeed(true)
          }

        assertM(io)(isFalse)
      },
      test("effectSuspendTotal must not catch throwable") {
        val io = ZIO.suspendSucceed[Any, Nothing, Any](throw ExampleError).sandbox.either
        assertM(io)(isLeft(equalTo(Cause.die(ExampleError))))
      },
      test("effectSuspend must catch throwable") {
        val io = ZIO.suspend[Any, Nothing](throw ExampleError).either
        assertM(io)(isLeft(equalTo(ExampleError)))
      },
      test("effectSuspendWith must catch throwable") {
        val io = ZIO.suspendWith[Any, Nothing]((_, _) => throw ExampleError).either
        assertM(io)(isLeft(equalTo(ExampleError)))
      },
      test("effectSuspendTotal must be evaluatable") {
        assertM(ZIO.suspendSucceed(ZIO.succeed(42)))(equalTo(42))
      },
      test("point, bind, map") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.succeed(n)
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertM(fibIo(10))(equalTo(fib(10)))
      },
      test("effect, bind, map") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.attempt(n)
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertM(fibIo(10))(equalTo(fib(10)))
      },
      test("effect, bind, map, redeem") {
        def fibIo(n: Int): Task[BigInt] =
          if (n <= 1) ZIO.attempt[BigInt](throw ExampleError).catchAll(_ => ZIO.attempt(n))
          else
            for {
              a <- fibIo(n - 1)
              b <- fibIo(n - 2)
            } yield a + b

        assertM(fibIo(10))(equalTo(fib(10)))
      },
      test("sync effect") {
        def sumIo(n: Int): Task[Int] =
          if (n <= 0) ZIO.succeed(0)
          else ZIO.succeed(n).flatMap(b => sumIo(n - 1).map(a => a + b))

        assertM(sumIo(1000))(equalTo(sum(1000)))
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

        assertM(l.zipWith(r)(_ && _))(isTrue)
      },
      test("flip must make error into value") {
        val io = ZIO.fail(ExampleError).flip
        assertM(io)(equalTo(ExampleError))
      },
      test("flip must make value into error") {
        val io = ZIO.succeed(42).flip
        assertM(io.either)(isLeft(equalTo(42)))
      },
      test("flipping twice returns identical value") {
        val io = ZIO.succeed(42)
        assertM(io.flip.flip)(equalTo(42))
      }
    ),
    suite("RTS failure")(
      test("error in sync effect") {
        val io = ZIO.attempt[Unit](throw ExampleError).fold[Option[Throwable]](Some(_), _ => None)
        assertM(io)(isSome(equalTo(ExampleError)))
      },
      test("attempt . fail") {
        val io1 = TaskExampleError.either
        val io2 = ZIO.suspendSucceed(ZIO.suspendSucceed(TaskExampleError).either)

        io1.zipWith(io2) { case (r1, r2) =>
          assert(r1)(isLeft(equalTo(ExampleError))) && assert(r2)(isLeft(equalTo(ExampleError)))
        }
      },
      test("deep attempt sync effect error") {
        assertM(deepErrorEffect(100).either)(isLeft(equalTo(ExampleError)))
      },
      test("deep attempt fail error") {
        assertM(deepErrorFail(100).either)(isLeft(equalTo(ExampleError)))
      },
      test("attempt . sandbox . terminate") {
        val io = ZIO.succeed[Int](throw ExampleError).sandbox.either
        assertM(io)(isLeft(equalTo(Cause.die(ExampleError))))
      },
      test("fold . sandbox . terminate") {
        val io = ZIO.succeed[Int](throw ExampleError).sandbox.fold(Some(_), Function.const(None))
        assertM(io)(isSome(equalTo(Cause.die(ExampleError))))
      },
      test("catch sandbox terminate") {
        val io = ZIO.succeed(throw ExampleError).sandbox.merge
        assertM(io)(equalTo(Cause.die(ExampleError)))
      },
      test("uncaught fail") {
        assertM(TaskExampleError.exit)(fails(equalTo(ExampleError)))
      },
      test("uncaught sync effect error") {
        val io = ZIO.succeed[Int](throw ExampleError)
        assertM(io.exit)(dies(equalTo(ExampleError)))
      },
      test("deep uncaught sync effect error") {
        assertM(deepErrorEffect(100).exit)(fails(equalTo(ExampleError)))
      },
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

        assertM(io.exit)(equalTo(Exit.failCause(expectedCause)))
      },
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

        assertM(io.exit)(equalTo(Exit.failCause(expectedCause)))
      },
      test("run preserves interruption status") {
        for {
          p    <- Promise.make[Nothing, Unit]
          f    <- (p.succeed(()) *> ZIO.never).exit.fork
          _    <- p.await
          _    <- f.interrupt
          test <- f.await.map(_.isInterrupted)
        } yield assert(test)(isTrue)
      },
      test("run swallows inner interruption") {
        for {
          p   <- Promise.make[Nothing, Int]
          _   <- ZIO.interrupt.exit *> p.succeed(42)
          res <- p.await
        } yield assert(res)(equalTo(42))
      },
      test("timeout a long computation") {
        val io = (Clock.sleep(5.seconds) *> ZIO.succeed(true)).timeout(10.millis)
        assertM(Live.live(io))(isNone)
      },
      test("timeout repetition of uninterruptible effect") {
        val effect = ZIO.unit.uninterruptible.forever

        assertM(Live.live(effect.timeout(1.second)))(isNone)
      } @@ jvmOnly,
      test("timeout in uninterruptible region") {
        val effect = (ZIO.unit.timeout(Duration.Infinity)).uninterruptible
        assertM(effect)(isSome(isUnit))
      },
      test("catchAllCause") {
        val io =
          for {
            _ <- ZIO.succeed(42)
            f <- ZIO.fail("Uh oh!")
          } yield f

        assertM(io.catchAllCause(ZIO.succeed(_)))(equalTo(Cause.fail("Uh oh!")))
      },
      test("exception in fromFuture does not kill fiber") {
        val io = ZIO.fromFuture(_ => throw ExampleError).either
        assertM(io)(isLeft(equalTo(ExampleError)))
      }
    ),
    suite("RTS finalizers")(
      test("fail ensuring") {
        var finalized = false

        val io = ZIO.fail(ExampleError).ensuring(ZIO.succeed { finalized = true; () })

        for {
          a1 <- assertM(io.exit)(fails(equalTo(ExampleError)))
          a2  = assert(finalized)(isTrue)
        } yield a1 && a2
      },
      test("fail on error") {
        @volatile var finalized = false

        val cleanup: Cause[Throwable] => UIO[Unit] =
          _ => ZIO.succeed[Unit] { finalized = true; () }

        val io = ZIO.fail(ExampleError).onError(cleanup)

        for {
          a1 <- assertM(io.exit)(fails(equalTo(ExampleError)))
          a2  = assert(finalized)(isTrue)
        } yield a1 && a2
      },
      test("finalizer errors not caught") {
        val e2 = new Error("e2")
        val e3 = new Error("e3")

        val io = TaskExampleError.ensuring(ZIO.die(e2)).ensuring(ZIO.die(e3))

        val expectedCause: Cause[Throwable] =
          Cause.Then(Cause.fail(ExampleError), Cause.Then(Cause.die(e2), Cause.die(e3)))

        assertM(io.sandbox.flip)(equalTo(expectedCause))
      },
      test("finalizer errors reported") {
        @volatile var reported: Exit[Nothing, Int] = null

        val io = ZIO
          .succeed[Int](42)
          .ensuring(ZIO.die(ExampleError))
          .fork
          .flatMap(_.await.flatMap[Any, Nothing, Any](e => ZIO.succeed { reported = e }))

        for {
          a1 <- assertM(io)(anything)
          a2  = assert(reported.succeeded)(isFalse)
        } yield a1 && a2
      },
      test("bracket exit is usage result") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.succeed[Int](42))
        assertM(io)(equalTo(42))
      },
      test("error in just acquisition") {
        val io = ZIO.acquireReleaseWith(TaskExampleError)(_ => ZIO.unit)(_ => ZIO.unit)
        assertM(io.exit)(fails(equalTo(ExampleError)))
      },
      test("error in just release") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(ExampleError))(_ => ZIO.unit)
        assertM(io.exit)(dies(equalTo(ExampleError)))
      },
      test("error in just usage") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.unit)(_ => ZIO.fail(ExampleError))
        assertM(io.exit)(fails(equalTo(ExampleError)))
      },
      test("rethrown caught error in acquisition") {
        val io = ZIO.absolve(ZIO.acquireReleaseWith(TaskExampleError)(_ => ZIO.unit)(_ => ZIO.unit).either)
        assertM(io.flip)(equalTo(ExampleError))
      },
      test("rethrown caught error in release") {
        val io = ZIO.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(ExampleError))(_ => ZIO.unit)
        assertM(io.exit)(dies(equalTo(ExampleError)))
      },
      test("rethrown caught error in usage") {
        val io = ZIO.absolve(ZIO.unit.acquireRelease(ZIO.unit)(TaskExampleError).either)
        assertM(io.exit)(fails(equalTo(ExampleError)))
      },
      test("test eval of async fail") {
        val io1 = ZIO.unit.acquireRelease(AsyncUnit[Nothing])(asyncExampleError[Unit])
        val io2 = AsyncUnit[Throwable].acquireRelease(ZIO.unit)(asyncExampleError[Unit])

        for {
          a1 <- assertM(io1.exit)(fails(equalTo(ExampleError)))
          a2 <- assertM(io2.exit)(fails(equalTo(ExampleError)))
          a3 <- assertM(ZIO.absolve(io1.either).exit)(fails(equalTo(ExampleError)))
          a4 <- assertM(ZIO.absolve(io2.either).exit)(fails(equalTo(ExampleError)))
        } yield a1 && a2 && a3 && a4
      },
      test("bracket regression 1") {
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

        assertM(Live.live(io))(hasSameElements(List("start 1", "release 1", "start 2", "release 2")))
      } @@ jvmOnly,
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

        assertM(Live.live(io))(isTrue)
      }
    ),
    suite("RTS synchronous stack safety")(
      test("deep map of now") {
        assertM(deepMapNow(10000))(equalTo(10000))
      },
      test("deep map of sync effect") {
        assertM(deepMapEffect(10000))(equalTo(10000))
      },
      test("deep attempt") {
        val io = (0 until 10000).foldLeft(ZIO.attempt(()))((acc, _) => acc.either.unit)
        assertM(io)(equalTo(()))
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

        assertM(fib(1000))(equalTo(expected))
      },
      test("deep absolve/attempt is identity") {
        implicit val canFail = CanFail
        val io               = (0 until 1000).foldLeft(ZIO.succeed(42))((acc, _) => ZIO.absolve(acc.either))

        assertM(io)(equalTo(42))
      },
      test("deep async absolve/attempt is identity") {
        val io = (0 until 1000).foldLeft(ZIO.async[Int, Int](k => k(ZIO.succeed(42)))) { (acc, _) =>
          ZIO.absolve(acc.either)
        }

        assertM(io)(equalTo(42))
      }
    ),
    suite("RTS asynchronous correctness")(
      test("simple effectAsync must return") {
        val io = ZIO.async[Throwable, Int](k => k(ZIO.succeed(42)))
        assertM(io)(equalTo(42))
      },
      test("simple effectAsyncM must return") {
        val io = ZIO.asyncZIO[Throwable, Int](k => ZIO.succeed(k(ZIO.succeed(42))))
        assertM(io)(equalTo(42))
      },
      test("deep effectAsyncM doesn't block threads") {
        def stackIOs(count: Int): URIO[Clock, Int] =
          if (count <= 0) ZIO.succeed(42)
          else asyncIO(stackIOs(count - 1))

        def asyncIO(cont: URIO[Clock, Int]): URIO[Clock, Int] =
          ZIO.asyncZIO[Clock, Nothing, Int] { k =>
            Clock.sleep(5.millis) *> cont *> ZIO.succeed(k(ZIO.succeed(42)))
          }

        val procNum = java.lang.Runtime.getRuntime.availableProcessors()

        val io = stackIOs(procNum + 1)

        assertM(Live.live(io))(equalTo(42))
      } @@ jvmOnly,
      test("interrupt of effectAsyncM register") {
        for {
          release <- Promise.make[Nothing, Unit]
          acquire <- Promise.make[Nothing, Unit]
          fiber <- ZIO
                     .asyncZIO[Nothing, Unit] { _ =>
                       // This will never complete because we never call the callback
                       acquire.succeed(()).acquireRelease(release.succeed(()))(ZIO.never)
                     }
                     .disconnect
                     .fork
          _ <- acquire.await
          _ <- fiber.interruptFork
          a <- release.await
        } yield assert(a)(isUnit)
      } @@ ignore,
      test("effectAsync should not resume fiber twice after interruption") {
        for {
          step            <- Promise.make[Nothing, Unit]
          unexpectedPlace <- Ref.make(List.empty[Int])
          runtime         <- ZIO.runtime[Live]
          fork <- ZIO
                    .async[Any, Nothing, Unit] { k =>
                      runtime.unsafeRunAsync {
                        step.await *> ZIO.succeed(k(unexpectedPlace.update(1 :: _)))
                      }
                    }
                    .ensuring(ZIO.async[Any, Nothing, Unit] { _ =>
                      runtime.unsafeRunAsync {
                        step.succeed(())
                      }
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
      } @@ flaky,
      test("effectAsyncMaybe should not resume fiber twice after synchronous result") {
        for {
          step            <- Promise.make[Nothing, Unit]
          unexpectedPlace <- Ref.make(List.empty[Int])
          runtime         <- ZIO.runtime[Live]
          fork <- ZIO
                    .asyncMaybe[Any, Nothing, Unit] { k =>
                      runtime.unsafeRunAsync {
                        step.await *> ZIO.succeed(k(unexpectedPlace.update(1 :: _)))
                      }
                      Some(ZIO.unit)
                    }
                    .flatMap { _ =>
                      ZIO.async[Any, Nothing, Unit] { _ =>
                        runtime.unsafeRunAsync {
                          step.succeed(())
                        }
                      //never complete
                      }
                    }
                    .ensuring(unexpectedPlace.update(2 :: _))
                    .uninterruptible
                    .forkDaemon
          result     <- Live.withLive(fork.interrupt)(_.timeout(5.seconds))
          unexpected <- unexpectedPlace.get
        } yield {
          assert(unexpected)(isEmpty) &&
          assert(result)(isNone) // timeout happens
        }
      } @@ flaky,
      test("sleep 0 must return") {
        assertM(Live.live(Clock.sleep(1.nanos)))(isUnit)
      },
      test("shallow bind of async chain") {
        val io = (0 until 10).foldLeft[Task[Int]](ZIO.succeed[Int](0)) { (acc, _) =>
          acc.flatMap(n => ZIO.async[Throwable, Int](_(ZIO.succeed(n + 1))))
        }

        assertM(io)(equalTo(10))
      },
      test("effectAsyncM can fail before registering") {
        val zio = ZIO
          .asyncZIO[Any, String, Nothing](_ => ZIO.fail("Ouch"))
          .flip

        assertM(zio)(equalTo("Ouch"))
      },
      test("effectAsyncM can defect before registering") {
        val zio = ZIO
          .asyncZIO[Any, String, Unit](_ => ZIO.succeed(throw new Error("Ouch")))
          .exit
          .map(_.fold(_.defects.headOption.map(_.getMessage), _ => None))

        assertM(zio)(isSome(equalTo("Ouch")))
      }
    ),
    suite("RTS concurrency correctness")(
      test("shallow fork/join identity") {
        for {
          f <- ZIO.succeed(42).fork
          r <- f.join
        } yield assert(r)(equalTo(42))
      },
      test("deep fork/join identity") {
        val n = 20
        assertM(concurrentFib(n))(equalTo(fib(n)))
      } @@ jvmOnly,
      test("effectAsyncM creation is interruptible") {
        for {
          release <- Promise.make[Nothing, Int]
          acquire <- Promise.make[Nothing, Unit]
          task = ZIO.asyncZIO[Nothing, Unit] { _ =>
                   // This will never complete because the callback is never invoked
                   ZIO.acquireReleaseWith(acquire.succeed(()))(_ => release.succeed(42).unit)(_ => ZIO.never)
                 }
          fiber <- task.fork
          _     <- acquire.await
          _     <- fiber.interrupt
          a     <- release.await
        } yield assert(a)(equalTo(42))
      } @@ ignore,
      test("asyncInterrupt runs cancel token on interrupt") {
        for {
          release <- Promise.make[Nothing, Int]
          latch    = scala.concurrent.Promise[Unit]()
          async    = ZIO.asyncInterrupt[Nothing, Nothing] { _ => latch.success(()); Left(release.succeed(42).unit) }
          fiber   <- async.fork
          _ <- ZIO.async[Throwable, Unit] { k =>
                 latch.future.onComplete {
                   case Success(a) => k(ZIO.succeed(a))
                   case Failure(t) => k(ZIO.fail(t))
                 }(scala.concurrent.ExecutionContext.global)
               }
          _      <- fiber.interrupt
          result <- release.await
        } yield assert(result)(equalTo(42))
      },
      test("daemon fiber is unsupervised") {
        def child(ref: Ref[Boolean]) = withLatch(release => (release *> ZIO.never).ensuring(ref.set(true)))

        for {
          ref   <- Ref.make[Boolean](false)
          fiber <- child(ref).forkDaemon.fork
          _     <- fiber.join
          b     <- ref.get
        } yield assert(b)(isFalse)
      },
      test("daemon fiber race interruption") {
        def plus1(latch: Promise[Nothing, Unit], finalizer: UIO[Any]) =
          (latch.succeed(()) *> ZIO.sleep(1.hour)).onInterrupt(finalizer)

        (for {
          interruptionRef <- Ref.make(0)
          latch1Start     <- Promise.make[Nothing, Unit]
          latch2Start     <- Promise.make[Nothing, Unit]
          inc              = interruptionRef.update(_ + 1)
          left             = plus1(latch1Start, inc)
          right            = plus1(latch2Start, inc)
          fiber           <- (left race right).fork
          _               <- latch1Start.await *> latch2Start.await *> fiber.interrupt
          interrupted     <- interruptionRef.get
        } yield assert(interrupted)(equalTo(2)))
      } @@ flaky,
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
        def makeChild(n: Int): URIO[Clock, Fiber[Nothing, Unit]] =
          (Clock.sleep(20.millis * n.toDouble) *> ZIO.infinity).fork

        val io =
          for {
            counter <- Ref.make(0)
            _ <- (makeChild(1) *> makeChild(2)).ensuringChildren { fs =>
                   fs.foldLeft(ZIO.unit)((acc, f) => acc *> f.interrupt *> counter.update(_ + 1))
                 }
            value <- counter.get
          } yield value

        assertM(Live.live(io))(equalTo(2))
      } @@ forked @@ flaky, // Due to weak supervision, this test is expected to fail sometimes
      test("race of fail with success") {
        val io = ZIO.fail(42).race(ZIO.succeed(24)).either
        assertM(io)(isRight(equalTo(24)))
      },
      test("race of terminate with success") {
        val io = ZIO.die(new Throwable {}).race(ZIO.succeed(24))
        assertM(io)(equalTo(24))
      },
      test("race of fail with fail") {
        val io = ZIO.fail(42).race(ZIO.fail(42)).either
        assertM(io)(isLeft(equalTo(42)))
      },
      test("race of value & never") {
        val io = ZIO.succeed(42).race(ZIO.never)
        assertM(io)(equalTo(42))
      },
      test("race in uninterruptible region") {
        val effect = (ZIO.unit.race(ZIO.infinity)).uninterruptible
        assertM(effect)(isUnit)
      },
      test("race of two forks does not interrupt winner") {
        for {
          ref    <- Ref.make(0)
          fibers <- Ref.make(Set.empty[Fiber[Any, Any]])
          latch  <- Promise.make[Nothing, Unit]
          scope  <- ZIO.descriptor.map(_.scope)
          effect = ZIO.uninterruptibleMask { restore =>
                     restore(latch.await.onInterrupt(ref.update(_ + 1))).forkIn(scope).tap(f => fibers.update(_ + f))
                   }
          awaitAll = fibers.get.flatMap(Fiber.awaitAll(_))
          _       <- effect race effect
          value2  <- latch.succeed(()) *> awaitAll *> ref.get
        } yield assert(value2)(isLessThanEqualTo(1))
      },
      test("firstSuccessOf of values") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0), List(ZIO.succeed(100))).either
        assertM(io)(isRight(equalTo(100)))
      },
      test("firstSuccessOf of failures") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0).delay(10.millis), List(ZIO.fail(101))).either
        assertM(Live.live(io))(isLeft(equalTo(101)))
      },
      test("firstSuccessOF of failures & 1 success") {
        val io = ZIO.firstSuccessOf(ZIO.fail(0), List(ZIO.succeed(102).delay(1.millis))).either
        assertM(Live.live(io))(isRight(equalTo(102)))
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
      },
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
      },
      test("mergeAll") {
        val io = ZIO.mergeAll(List("a", "aa", "aaa", "aaaa").map(ZIO.succeed[String](_)))(0)((b, a) => b + a.length)

        assertM(io)(equalTo(10))
      },
      test("mergeAllEmpty") {
        val io = ZIO.mergeAll(List.empty[UIO[Int]])(0)(_ + _)
        assertM(io)(equalTo(0))
      },
      test("reduceAll") {
        val io = ZIO.reduceAll(ZIO.succeed(1), List(2, 3, 4).map(ZIO.succeed[Int](_)))(_ + _)
        assertM(io)(equalTo(10))
      },
      test("reduceAll Empty List") {
        val io = ZIO.reduceAll(ZIO.succeed(1), Seq.empty)(_ + _)
        assertM(io)(equalTo(1))
      },
      test("timeout of failure") {
        val io = ZIO.fail("Uh oh").timeout(1.hour)
        assertM(Live.live(io).exit)(fails(equalTo("Uh oh")))
      },
      test("timeout of terminate") {
        val io: ZIO[Clock, Nothing, Option[Int]] = ZIO.die(ExampleError).timeout(1.hour)
        assertM(Live.live(io).exit)(dies(equalTo(ExampleError)))
      }
    ),
    suite("RTS option tests")(
      test("lifting a value to an option") {
        assertM(ZIO.some(42))(isSome(equalTo(42)))
      },
      test("using the none value") {
        assertM(ZIO.none)(isNone)
      }
    ),
    suite("RTS either helper tests")(
      test("lifting a value into right") {
        assertM(ZIO.right(42))(isRight(equalTo(42)))
      },
      test("lifting a value into left") {
        assertM(ZIO.left(42))(isLeft(equalTo(42)))
      }
    ),
    suite("RTS interruption")(
      test("sync forever is interruptible") {
        val io =
          for {
            f <- ZIO.succeed[Int](1).forever.fork
            _ <- f.interrupt
          } yield true

        assertM(io)(isTrue)
      },
      test("interrupt of never") {
        val io =
          for {
            fiber <- ZIO.never.fork
            _     <- fiber.interrupt
          } yield 42

        assertM(io)(equalTo(42))
      },
      test("effectAsyncM is interruptible") {
        val io =
          for {
            fiber <- ZIO.asyncZIO[Nothing, Nothing](_ => ZIO.never).fork
            _     <- fiber.interrupt
          } yield 42

        assertM(io)(equalTo(42))
      },
      test("effectAsync is interruptible") {
        val io =
          for {
            fiber <- ZIO.async[Nothing, Nothing](_ => ()).fork
            _     <- fiber.interrupt
          } yield 42

        assertM(io)(equalTo(42))
      },
      test("bracket is uninterruptible") {
        val io =
          for {
            promise <- Promise.make[Nothing, Unit]
            fiber   <- (promise.succeed(()) <* ZIO.never).acquireReleaseWith(_ => ZIO.unit)(_ => ZIO.unit).forkDaemon
            res     <- promise.await *> fiber.interrupt.timeoutTo(42)(_ => 0)(1.second)
          } yield res

        assertM(Live.live(io))(equalTo(42))
      },
      test("bracketExit is uninterruptible") {
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

        assertM(Live.live(io))(equalTo(42))
      },
      test("bracket use is interruptible") {
        for {
          fiber <- ZIO.unit.acquireReleaseWith(_ => ZIO.unit)(_ => ZIO.never).fork
          res   <- fiber.interrupt
        } yield assert(res)(isInterrupted)
      },
      test("bracketExit use is interruptible") {
        for {
          fiber <- ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => ZIO.unit)(_ => ZIO.never).fork
          res   <- fiber.interrupt.timeoutTo(42)(_ => 0)(1.second)
        } yield assert(res)(equalTo(0))
      },
      test("bracket release called on interrupt") {
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

        assertM(io.timeoutTo(42)(_ => 0)(1.second))(equalTo(0))
      },
      test("bracketExit release called on interrupt") {
        for {
          done <- Promise.make[Nothing, Unit]
          fiber <-
            withLatch { release =>
              ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => done.succeed(()))(_ => release *> ZIO.never)
                .fork
            }

          _ <- fiber.interrupt
          r <- done.await.timeoutTo(42)(_ => 0)(60.second)
        } yield assert(r)(equalTo(0))
      },
      test("bracket acquire returns immediately on interrupt") {
        for {
          p1 <- Promise.make[Nothing, Unit]
          p2 <- Promise.make[Nothing, Int]
          p3 <- Promise.make[Nothing, Unit]
          s <- (p1.succeed(()) *> p2.await)
                 .acquireReleaseWith(_ => p3.await)(_ => ZIO.unit)
                 .disconnect
                 .fork
          _   <- p1.await
          res <- s.interrupt
          _   <- p3.succeed(())
        } yield assert(res)(isInterrupted)
      },
      test("bracketExit disconnect acquire returns immediately on interrupt") {
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
      test("bracket disconnect use is interruptible") {
        for {
          fiber <- ZIO.unit.acquireReleaseWith(_ => ZIO.unit)(_ => ZIO.never).disconnect.fork
          res   <- fiber.interrupt
        } yield assert(res)(isInterrupted)
      },
      test("bracketExit disconnect use is interruptible") {
        for {
          fiber <- ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => ZIO.unit)(_ => ZIO.never).disconnect.fork
          res   <- Live.live(fiber.interrupt.timeoutTo(42)(_ => 0)(1.second))
        } yield assert(res)(equalTo(0))
      },
      test("bracket disconnect release called on interrupt in separate fiber") {
        val io =
          for {
            p1 <- Promise.make[Nothing, Unit]
            p2 <- Promise.make[Nothing, Unit]
            fiber <- ZIO
                       .acquireReleaseWith(ZIO.unit)(_ => p2.succeed(()) *> ZIO.unit)(_ => p1.succeed(()) *> ZIO.never)
                       .disconnect
                       .fork
            _ <- p1.await
            _ <- fiber.interrupt
            _ <- p2.await
          } yield ()

        assertM(Live.live(io).timeoutTo(false)(_ => true)(10.seconds))(isTrue)
      },
      test("bracketExit disconnect release called on interrupt in separate fiber") {
        for {
          done <- Promise.make[Nothing, Unit]
          fiber <-
            withLatch { release =>
              ZIO.acquireReleaseExitWith(ZIO.unit)((_, _: Exit[Any, Any]) => done.succeed(()))(_ => release *> ZIO.never)
                .disconnect
                .fork
            }

          _ <- fiber.interrupt
          r <- Live.live(done.await.timeoutTo(false)(_ => true)(10.seconds))
        } yield assert(r)(isTrue)
      },
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
      test("interruption of raced") {
        for {
          ref   <- Ref.make(0)
          cont1 <- Promise.make[Nothing, Unit]
          cont2 <- Promise.make[Nothing, Unit]
          make   = (p: Promise[Nothing, Unit]) => (p.succeed(()) *> ZIO.infinity).onInterrupt(ref.update(_ + 1))
          raced <- (make(cont1) race (make(cont2))).fork
          _     <- cont1.await *> cont2.await
          _     <- raced.interrupt
          count <- ref.get
        } yield assert(count)(equalTo(2))
      },
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
      },
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
        val io =
          for {
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
          } yield test

        assertM(Live.live(io))(isTrue)
      },
      test("cause reflects interruption") {
        val io =
          for {
            finished <- Ref.make(false)
            fiber    <- withLatch(release => (release *> ZIO.fail("foo")).catchAll(_ => finished.set(true)).fork)
            exit     <- fiber.interrupt
            finished <- finished.get
          } yield exit.isInterrupted == true || finished == true

        assertM(io)(isTrue)
      } @@ jvm(nonFlaky),
      test("bracket use inherits interrupt status") {
        val io =
          for {
            ref <- Ref.make(false)
            fiber1 <- withLatch { (release2, await2) =>
                        withLatch { release1 =>
                          release1
                            .acquireRelease(ZIO.unit, await2 *> Clock.sleep(10.millis) *> ref.set(true))
                            .uninterruptible
                            .fork
                        } <* release2
                      }
            _     <- fiber1.interrupt
            value <- ref.get
          } yield value

        assertM(Live.live(io))(isTrue)
      },
      test("bracket use inherits interrupt status 2") {
        val io =
          for {
            latch1 <- Promise.make[Nothing, Unit]
            latch2 <- Promise.make[Nothing, Unit]
            ref    <- Ref.make(false)
            fiber1 <- latch1
                        .succeed(())
                        .acquireReleaseExitWith[Clock, Nothing, Unit](
                          (_: Boolean, _: Exit[Any, Any]) => ZIO.unit,
                          (_: Boolean) => latch2.await *> Clock.sleep(10.millis) *> ref.set(true).unit
                        )
                        .uninterruptible
                        .fork
            _     <- latch1.await
            _     <- latch2.succeed(())
            _     <- fiber1.interrupt
            value <- ref.get
          } yield value

        assertM(Live.live(io))(isTrue)
      },
      test("async can be uninterruptible") {
        val io =
          for {
            ref <- Ref.make(false)
            fiber <- withLatch { release =>
                       (release *> Clock.sleep(10.millis) *> ref.set(true).unit).uninterruptible.fork
                     }
            _     <- fiber.interrupt
            value <- ref.get
          } yield value

        assertM(Live.live(io))(isTrue)
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
        } yield assert(value)(isTrue)
      },
      test("effectAsyncInterrupt cancelation") {
        for {
          ref <- ZIO.succeed(new java.util.concurrent.atomic.AtomicInteger(0))
          effect = ZIO.asyncInterrupt[Any, Nothing, Any] { _ =>
                     ref.incrementAndGet()
                     Left(ZIO.succeed(ref.decrementAndGet()))
                   }
          _     <- ZIO.unit.race(effect)
          value <- ZIO.succeed(ref.get())
        } yield assert(value)(equalTo(0))
      } @@ jvm(nonFlaky(100000))
    ),
    suite("RTS environment")(
      test("provide is modular") {
        val zio =
          for {
            v1 <- ZIO.environment[Int]
            v2 <- ZIO.environment[Int].provideService(2)
            v3 <- ZIO.environment[Int]
          } yield (v1, v2, v3)

        assertM(zio.provideService(4))(equalTo((4, 2, 4)))
      },
      test("effectAsync can use environment") {
        val zio = ZIO.async[Int, Nothing, Int](cb => cb(ZIO.environment[Int]))
        assertM(zio.provideService(10))(equalTo(10))
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
      },
      test("executor is heritable") {
        val executor = zio.Executor.fromExecutionContext(100) {
          scala.concurrent.ExecutionContext.Implicits.global
        }
        val pool = ZIO.succeed(Platform.getCurrentThreadGroup)
        val io = for {
          parentPool <- pool
          childPool  <- pool.fork.flatMap(_.join)
        } yield assert(parentPool)(equalTo(childPool))
        io.lock(executor)
      } @@ jvm(nonFlaky(100))
    ),
    suite("serviceWith")(
      test("effectfully accesses a service in the environment") {
        val zio = ZIO.serviceWithZIO[Int](int => ZIO.succeed(int + 3))
        assertM(zio.provideLayer(ZLayer.succeed(0)))(equalTo(3))
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
        assertM(ZIO.succeed(Some(1)).someOrElse(2))(equalTo(1))
      },
      test("falls back to the default value if None") {
        assertM(ZIO.succeed(None).someOrElse(42))(equalTo(42))
      },
      test("does not change failed state") {
        assertM(ZIO.fail(ExampleError).someOrElse(42).exit)(fails(equalTo(ExampleError)))
      }
    ),
    suite("someOrElseM")(
      test("extracts the value from Some") {
        assertM(ZIO.succeed(Some(1)).someOrElseZIO(ZIO.succeed(2)))(equalTo(1))
      },
      test("falls back to the default effect if None") {
        assertM(ZIO.succeed(None).someOrElseZIO(ZIO.succeed(42)))(equalTo(42))
      },
      test("does not change failed state") {
        assertM(ZIO.fail(ExampleError).someOrElseZIO(ZIO.succeed(42)).exit)(fails(equalTo(ExampleError)))
      }
    ),
    suite("someOrFail")(
      test("extracts the optional value") {
        val task: Task[Int] = ZIO.succeed(Some(42)).someOrFail(exampleError)
        assertM(task)(equalTo(42))
      },
      test("fails when given a None") {
        val task: Task[Int] = ZIO.succeed(Option.empty[Int]).someOrFail(exampleError)
        assertM(task.exit)(fails(equalTo(exampleError)))
      }
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
    suite("tapCause")(
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
      test("effectually peeks at the cause of the failure of this effect") {
        for {
          ref    <- Ref.make(false)
          result <- ZIO.dieMessage("die").tapErrorCause(_ => ref.set(true)).exit
          effect <- ref.get
        } yield assert(result)(dies(hasMessage(equalTo("die")))) &&
          assert(effect)(isTrue)
      }
    ),
    suite("tapEither")(
      test("effectually peeks at the failure of this effect") {
        for {
          ref <- Ref.make(0)
          _ <- ZIO.fail(42)
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
          _ <- ZIO.attempt(42).tapEither {
                 case Left(_)      => ref.set(-1)
                 case Right(value) => ref.set(value)
               }.exit
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
    suite("timeout disconnect")(
      test("returns `Some` with the produced value if the effect completes before the timeout elapses") {
        assertM(ZIO.unit.disconnect.timeout(100.millis))(isSome(isUnit))
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
    suite("unlessM")(
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
    suite("unrefine")(
      test("converts some fiber failures into errors") {
        val s    = "division by zero"
        val zio1 = ZIO.die(new IllegalArgumentException(s))
        val zio2 = zio1.unrefine { case e: IllegalArgumentException => e.getMessage }
        assertM(zio2.exit)(fails(equalTo(s)))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefine { case e: NumberFormatException => e.getMessage }
        assertM(zio2.exit)(dies(equalTo(t)))
      }
    ),
    suite("unrefineTo")(
      test("converts some fiber failures into errors") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineTo[IllegalArgumentException]
        assertM(zio2.exit)(fails(equalTo(t)))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineTo[NumberFormatException]
        assertM(zio2.exit)(dies(equalTo(t)))
      }
    ),
    suite("unrefineWith")(
      test("converts some fiber failures into errors") {
        val s    = "division by zero"
        val zio1 = ZIO.die(new IllegalArgumentException(s))
        val zio2 = zio1.unrefineWith { case e: IllegalArgumentException => Option(e.getMessage) }(_ => None)
        assertM(zio2.exit)(fails(isSome(equalTo(s))))
      },
      test("leaves the rest") {
        val t    = new IllegalArgumentException("division by zero")
        val zio1 = ZIO.die(t)
        val zio2 = zio1.unrefineWith { case e: NumberFormatException => Option(e.getMessage) }(_ => None)
        assertM(zio2.exit)(dies(equalTo(t)))
      },
      test("uses the specified function to convert the `E` into an `E1`") {
        val zio1 = ZIO.fail("fail")
        val zio2 = zio1.unrefineWith { case e: IllegalArgumentException => Option(e.getMessage) }(_ => None)
        assertM(zio2.exit)(fails(isNone))
      }
    ),
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
          } yield assert(result)(equalTo(c)) &&
            assert(result.prettyPrint)(equalTo(c.prettyPrint))
        }
      }
    ),
    suite("updateService")(
      test("updates a service in the environment") {
        val zio = for {
          a <- ZIO.service[Int].updateService[Int](_ + 1)
          b <- ZIO.service[Int]
        } yield (a, b)
        assertM(zio.provideLayer(ZLayer.succeed(0)))(equalTo((1, 0)))
      }
    ),
    suite("validate")(
      test("returns all errors if never valid") {
        val in                      = List.fill(10)(0)
        def fail[A](a: A): IO[A, A] = ZIO.fail(a)
        val res                     = ZIO.validate(in)(fail).flip
        assertM(res)(equalTo(in))
      },
      test("accumulate errors and ignore successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validate(in)(a => if (a % 2 == 0) ZIO.succeed(a) else ZIO.fail(a))
        assertM(res.flip)(equalTo(List(1, 3, 5, 7, 9)))
      },
      test("accumulate successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validate(in)(a => ZIO.succeed(a))
        assertM(res)(equalTo(in))
      }
    ),
    suite("validate_")(
      test("returns all errors if never valid") {
        val in                            = List.fill(10)(0)
        def fail[A](a: A): IO[A, Nothing] = ZIO.fail(a)
        val res                           = ZIO.validate(in)(fail).flip
        assertM(res)(equalTo(in))
      }
    ),
    suite("validatePar")(
      test("returns all errors if never valid") {
        val in                      = List.fill(1000)(0)
        def fail[A](a: A): IO[A, A] = ZIO.fail(a)
        val res                     = ZIO.validatePar(in)(fail).flip
        assertM(res)(equalTo(in))
      },
      test("accumulate errors and ignore successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validatePar(in)(a => if (a % 2 == 0) ZIO.succeed(a) else ZIO.fail(a))
        assertM(res.flip)(equalTo(List(1, 3, 5, 7, 9)))
      },
      test("accumulate successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = ZIO.validatePar(in)(a => ZIO.succeed(a))
        assertM(res)(equalTo(in))
      }
    ),
    suite("validatePar_")(test("returns all errors if never valid") {
      val in                            = List.fill(10)(0)
      def fail[A](a: A): IO[A, Nothing] = ZIO.fail(a)
      val res                           = ZIO.validateParDiscard(in)(fail).flip
      assertM(res)(equalTo(in))
    }),
    suite("validateFirst")(
      test("returns all errors if never valid") {
        val in  = List.fill(10)(0)
        val res = ZIO.validateFirst(in)(a => ZIO.fail(a)).flip
        assertM(res)(equalTo(in))
      },
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
      }
    ),
    suite("validateFirstPar")(
      test("returns all errors if never valid") {
        val in  = List.fill(1000)(0)
        val res = ZIO.validateFirstPar(in)(a => ZIO.fail(a)).flip
        assertM(res)(equalTo(in))
      },
      test("returns success if valid") {
        implicit val canFail = CanFail
        val in               = List.range(1, 10)
        val f                = (a: Int) => if (a == 6) ZIO.succeed(a) else ZIO.fail(a)
        val res              = ZIO.validateFirstPar(in)(f(_))
        assertM(res)(equalTo(6))
      }
    ),
    suite("validateWith")(
      test("succeeds") {
        assertM(ZIO(1).validateWith(ZIO(2))(_ + _))(equalTo(3))
      },
      test("fails") {
        assertM(ZIO(1).validate(ZIO.fail(2)).sandbox.either)(isLeft(equalTo(Cause.Fail(2))))
      },
      test("combines both cause") {
        assertM(ZIO.fail(1).validate(ZIO.fail(2)).sandbox.either)(
          isLeft(equalTo(Cause.Then(Cause.Fail(1), Cause.Fail(2))))
        )
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
    suite("whenCaseM")(
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
    suite("whenM")(
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
    suite("withFilter")(
      test("tuple value is extracted correctly from task") {
        for {
          (i, j, k) <- ZIO.attempt((1, 2, 3))
        } yield assert((i, j, k))(equalTo((1, 2, 3)))
      },
      test("condition in for-comprehension syntax works correctly for task") {
        for {
          n <- ZIO.attempt(3) if n > 0
        } yield assert(n)(equalTo(3))
      },
      test("unsatisfied condition should fail with NoSuchElementException") {
        val task =
          for {
            n <- ZIO.attempt(3) if n > 10
          } yield n
        assertM(task.exit)(fails(isSubtype[NoSuchElementException](anything)))
      },
      test("withFilter doesn't compile with IO that fails with type other than Throwable") {
        val result = typeCheck {
          """
            import zio._
            val io: IO[String, Int] = IO.succeed(1)
            for {
              n <- io if n > 0
            } yield n
              """
        }

        val expected =
          "Pattern guards are only supported when the error type is a supertype of NoSuchElementException. However, your effect has String for the error type."
        if (TestVersion.isScala2) assertM(result)(isLeft(equalTo(expected)))
        else assertM(result)(isLeft(anything))
      }
    ),
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
        assertM(interrupted)(isLeft(isTrue))
      },
      test("passes regression 1") {
        val io =
          ZIO.succeed[Int](1).zipPar(ZIO.succeed[Int](2)).flatMap(t => ZIO.succeed(t._1 + t._2)).map(_ == 3)
        assertM(io)(isTrue)
      } @@ jvm(nonFlaky),
      test("paralellizes simple success values") {
        def countdown(n: Int): UIO[Int] =
          if (n == 0) ZIO.succeed(0)
          else
            ZIO.succeed[Int](1).zipPar(ZIO.succeed[Int](2)).flatMap(t => countdown(n - 1).map(y => t._1 + t._2 + y))

        assertM(countdown(50))(equalTo(150))
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
      }
    ),
    suite("toFuture")(
      test("should fail with StackTrace attached") {
        for {
          future <- ZIO.fail(new Throwable(new IllegalArgumentException)).toFuture
          result <- ZIO.fromFuture(_ => future).either
        } yield assert(result)(isLeft(hasThrowableCause(hasThrowableCause(hasMessage(containsString("Fiber:Id("))))))
      }
    ),
    suite("resurrect")(
      test("should fail checked") {
        val error: Exception   = new Exception("msg")
        val effect: Task[Unit] = ZIO.fail(error).unit.orDie.resurrect
        assertM(effect.either)(isLeft(equalTo(error)))
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
        val func: String => String = s => s.toUpperCase
        for {
          promise <- ZIO.succeed(scala.concurrent.Promise[String]())
          _ <- ZIO.attempt {
                 Try(func(null)) match {
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
    )
  )

  def functionIOGen: Gen[Random with Sized, String => Task[Int]] =
    Gen.function[Random with Sized, String, Task[Int]](Gen.successes(Gen.int))

  def listGen: Gen[Random with Sized, List[String]] =
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
    ZIO.async[Throwable, A](_(ZIO.fail(ExampleError)))

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

  def AsyncUnit[E]: IO[E, Unit] = ZIO.async[E, Unit](_(ZIO.unit))

  type Logging = Logging.Service

  object Logging {
    trait Service
    val live: ZLayer[Any, Nothing, Logging] = ZLayer.succeed(new Logging.Service {})
  }
}
