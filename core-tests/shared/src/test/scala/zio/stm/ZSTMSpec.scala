package zio
package stm

import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._

object ZSTMSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("ZSTMSpec")(
    suite("Using `STM.atomically` to perform different computations and call:")(
      suite("absolve to convert")(
        test("A successful Right computation into the success channel") {
          assertZIO(STM.succeed(Right(42)).absolve.commit)(equalTo(42))
        },
        test("A successful Left computation into the error channel") {
          assertZIO(STM.succeed(Left("oh no!")).absolve.commit.exit)(fails(equalTo("oh no!")))
        } @@ zioTag(errors)
      ),
      test("catchAll errors") {
        val tx =
          for {
            _ <- ZSTM.fail("Uh oh!")
            f <- ZSTM.succeed("everything is fine")
          } yield f
        assertZIO(tx.catchAll(s => ZSTM.succeed(s"$s phew")).commit)(equalTo("Uh oh! phew"))
      } @@ zioTag(errors),
      suite("catchSome errors")(
        test("catch the specified error") {
          sealed trait ErrorTest
          case object Error1 extends ErrorTest

          val tx =
            for {
              _ <- ZSTM.fail[ErrorTest](Error1)
              f <- ZSTM.succeed("everything is fine")
            } yield f
          assertZIO(tx.catchSome { case Error1 => ZSTM.succeed("gotcha") }.commit)(equalTo("gotcha"))
        },
        test("lets the error pass") {
          sealed trait ErrorTest
          case object Error1 extends ErrorTest
          case object Error2 extends ErrorTest

          val tx =
            for {
              _ <- ZSTM.fail[ErrorTest](Error2)
              f <- ZSTM.succeed("everything is fine")
            } yield f
          assertZIO(tx.catchSome { case Error1 => ZSTM.succeed("gotcha") }.commit.exit)(fails(equalTo(Error2)))
        }
      ) @@ zioTag(errors),
      test("repeatWhile to run effect while it satisfies predicate") {
        (for {
          a <- TQueue.bounded[Int](5)
          _ <- a.offerAll(List(0, 0, 0, 1, 2))
          n <- a.take.repeatWhile(_ == 0)
        } yield assert(n)(equalTo(1))).commit
      },
      test("repeatUntil to run effect until it satisfies predicate") {
        (for {
          a <- TQueue.bounded[Int](5)
          _ <- a.offerAll(List(0, 0, 0, 1, 2))
          b <- a.take.repeatUntil(_ == 1)
        } yield assert(b)(equalTo(1))).commit
      },
      suite("either to convert")(
        test("A successful computation into Right(a)") {
          implicit val canFail = CanFail
          assertZIO(STM.succeed(42).either.commit)(isRight(equalTo(42)))
        },
        test("A failed computation into Left(e)") {
          assertZIO(STM.fail("oh no!").either.commit)(isLeft(equalTo("oh no!")))
        }
      ),
      test("eventually succeeds") {
        def effect(ref: TRef[Int]) =
          for {
            n <- ref.get
            r <- if (n < 10) ref.update(_ + 1) *> ZSTM.fail("Ouch")
                 else ZSTM.succeed(n)
          } yield r

        val tx = for {
          ref <- TRef.make(0)
          n   <- effect(ref).eventually
        } yield n

        assertZIO(tx.commit)(equalTo(10))
      },
      test("failed to make a failed computation and check the value") {
        assertZIO(STM.fail("Bye bye World").commit.exit)(fails(equalTo("Bye bye World")))
      },
      test("filter filters a collection using an effectual predicate") {
        val as = Iterable(2, 4, 6, 3, 5, 6)

        val tx =
          for {
            ref     <- TRef.make(List.empty[Int])
            results <- STM.filter(as)(a => ref.update(a :: _).as(a % 2 == 0))
            effects <- ref.get.map(_.reverse)
          } yield (results, effects)

        val expected = (List(2, 4, 6, 6), List(2, 4, 6, 3, 5, 6))

        assertZIO(tx.commit)(equalTo(expected))
      },
      test("filterOrDie dies when predicate fails") {
        val stm = ZSTM.succeed(1)
        assertZIO(stm.filterOrDie(_ != 1)(ExampleError).commit.exit)(dies(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("filterOrDieMessage dies with message when predicate fails ") {
        val stm = ZSTM.succeed(1)
        assertZIO(stm.filterOrDieMessage(_ != 1)("dies").commit.exit)(dies(hasMessage(equalTo("dies"))))
      } @@ zioTag(errors),
      suite("filterOrElse")(
        test("returns checked failure") {
          val stm1 = ZSTM.succeed(1)
          val stm2 = ZSTM.succeed(2)
          assertZIO(stm1.filterOrElse(_ == 1)(stm2).commit)(equalTo(1))
        } @@ zioTag(errors),
        test("returns held value") {
          val stm1 = ZSTM.succeed(1)
          val stm2 = ZSTM.succeed(2)
          assertZIO(stm1.filterOrElse(_ != 1)(stm2).commit)(equalTo(2))
        }
      ),
      suite("filterOrElseWith")(
        test("returns checked failure") {
          val stm1 = ZSTM.succeed(1)
          assertZIO(stm1.filterOrElseWith(_ == 1)(n => ZSTM.succeed(n + 1)).commit)(equalTo(1))
        } @@ zioTag(errors),
        test("returns held value") {
          val stm1 = ZSTM.succeed(1)
          assertZIO(stm1.filterOrElseWith(_ != 1)(n => ZSTM.succeed(n + 1)).commit)(equalTo(2))
        },
        test("returns error") {
          val stm1 = ZSTM.fail(ExampleError) *> ZSTM.succeed(1)
          assertZIO(stm1.filterOrElseWith(_ == 1)(n => ZSTM.succeed(n + 1)).commit.exit)(fails(equalTo(ExampleError)))
        } @@ zioTag(errors)
      ),
      test("filterOrFail returns failure when predicate fails") {
        val stm = ZSTM.succeed(1)
        assertZIO(stm.filterOrFail(_ != 1)(ExampleError).commit.exit)(fails(equalTo(ExampleError)))
      } @@ zioTag(errors),
      test("flatMapError to flatMap from one error to another") {
        assertZIO(STM.fail(-1).flatMapError(s => STM.succeed(s"log: $s")).commit.exit)(fails(equalTo("log: -1")))
      } @@ zioTag(errors),
      test("flatten") {
        check(Gen.alphaNumericString) { str =>
          val tx =
            for {
              flatten1 <- STM.succeed(STM.succeed(str)).flatten
              flatten2 <- STM.flatten(STM.succeed(STM.succeed(str)))
            } yield flatten1 == flatten2

          assertZIO(tx.commit)(isTrue)
        }
      },
      suite("flattenErrorOption")(
        test("with an existing error and return it") {
          assertZIO(STM.fail(Some("oh no!")).flattenErrorOption("default error").commit.exit)(fails(equalTo("oh no!")))
        },
        test("with no error and default to value") {
          assertZIO(STM.fail(None).flattenErrorOption("default error").commit.exit)(fails(equalTo("default error")))
        }
      ) @@ zioTag(errors),
      test("fold to handle both failure and success") {
        implicit val canFail = CanFail
        val stm = for {
          s <- STM.succeed("Yes!").fold(_ => -1, _ => 1)
          f <- STM.fail("No!").fold(_ => -1, _ => 1)
        } yield (s, f)
        assertZIO(stm.commit)(equalTo((1, -1)))
      } @@ zioTag(errors),
      test("foldSTM to fold over the `STM` effect, and handle failure and success") {
        implicit val canFail = CanFail
        val stm = for {
          s <- STM.succeed("Yes!").foldSTM(_ => STM.succeed("No!"), STM.succeed(_))
          f <- STM.fail("No!").foldSTM(STM.succeed(_), _ => STM.succeed("Yes!"))
        } yield (s, f)
        assertZIO(stm.commit)(equalTo(("Yes!", "No!")))
      } @@ zioTag(errors),
      suite("foldLeft")(
        test("with a successful step function sums the list properly") {
          check(Gen.listOf(Gen.int)) { l =>
            val tx = STM.foldLeft(l)(0)((acc, el) => STM.succeed(acc + el))
            assertZIO(tx.commit)(equalTo(l.sum))
          }
        },
        test("with a failing step function returns a failed transaction") {
          check(Gen.listOf1(Gen.int)) { l =>
            val tx = STM.foldLeft(l)(0)((_, _) => STM.fail("fail"))
            assertZIO(tx.commit.exit)(fails(equalTo("fail")))
          }
        },
        test("run sequentially from left to right") {
          check(Gen.listOf1(Gen.int)) { l =>
            val tx = STM.foldLeft(l)(List.empty[Int])((acc, el) => STM.succeed(el :: acc))
            assertZIO(tx.commit)(equalTo(l.reverse))
          }
        }
      ),
      suite("foldRight")(
        test("with a successful step function sums the list properly") {
          check(Gen.listOf(Gen.int)) { l =>
            val tx = STM.foldRight(l)(0)((el, acc) => STM.succeed(acc + el))
            assertZIO(tx.commit)(equalTo(l.sum))
          }
        },
        test("with a failing step function returns a failed transaction") {
          check(Gen.listOf1(Gen.int)) { l =>
            val tx = STM.foldRight(l)(0)((_, _) => STM.fail("fail"))
            assertZIO(tx.commit.exit)(fails(equalTo("fail")))
          }
        },
        test("run sequentially from right to left") {
          check(Gen.listOf1(Gen.int)) { l =>
            val tx = STM.foldRight(l)(List.empty[Int])((el, acc) => STM.succeed(el :: acc))
            assertZIO(tx.commit)(equalTo(l))
          }
        }
      ),
      suite("head")(
        test("extracts the value from the List") {
          assertZIO(ZSTM.succeed(List(1, 2)).head.commit)(equalTo(1))
        },
        test("returns None if list is Empty") {
          assertZIO(ZSTM.succeed(List.empty[Int]).head.commit.exit)(fails(isNone))
        },
        test("returns the Error around Some") {
          val ei: Either[String, List[Int]] = Left("my error")
          assertZIO(ZSTM.fromEither(ei).head.commit.exit)(fails(isSome(equalTo("my error"))))
        } @@ zioTag(errors)
      ),
      suite("ifSTM")(
        test("runs `onTrue` if result of `b` is `true`") {
          val transaction = ZSTM.ifSTM(ZSTM.succeed(true))(ZSTM.succeed(true), ZSTM.succeed(false))
          assertZIO(transaction.commit)(isTrue)
        },
        test("runs `onFalse` if result of `b` is `false`") {
          val transaction = ZSTM.ifSTM(ZSTM.succeed(false))(ZSTM.succeed(true), ZSTM.succeed(false))
          assertZIO(transaction.commit)(isFalse)
        },
        test("infers correctly") {
          trait R
          trait R1 extends R
          trait E1
          trait E extends E1
          trait A
          val b: ZSTM[R, E, Boolean]   = ZSTM.succeed(true)
          val onTrue: ZSTM[R1, E1, A]  = ZSTM.succeed(new A {})
          val onFalse: ZSTM[R1, E1, A] = ZSTM.succeed(new A {})
          val _                        = ZSTM.ifSTM(b)(onTrue, onFalse)
          ZIO.succeed(assertCompletes)
        }
      ),
      suite("left")(
        test("on Left value") {
          assertZIO(ZSTM.succeed(Left("Left")).left.commit)(equalTo("Left"))
        },
        test("on Right value") {
          assertZIO(ZSTM.succeed(Right("Right")).left.commit.exit)(fails(isRight(equalTo("Right"))))
        },
        test("on failure") {
          assertZIO(ZSTM.fail("Fail").left.commit.exit)(fails(isLeft(equalTo("Fail"))))
        } @@ zioTag(errors),
        test("lifting a value") {
          assertZIO(ZSTM.left(42).commit)(isLeft(equalTo(42)))
        }
      ),
      suite("mapBoth when")(
        test("having a success value") {
          implicit val canFail = CanFail
          assertZIO(STM.succeed(1).mapBoth(_ => -1, s => s"$s as string").commit)(equalTo("1 as string"))
        },
        test("having a fail value") {
          assertZIO(STM.fail(-1).mapBoth(s => s"$s as string", _ => 0).commit.exit)(fails(equalTo("-1 as string")))
        }
      ),
      test("mapError to map from one error to another") {
        assertZIO(STM.fail(-1).mapError(_ => "oh no!").commit.exit)(fails(equalTo("oh no!")))
      } @@ zioTag(errors),
      suite("merge")(
        test("on error with same type") {
          assertZIO(STM.fromEither[Int, Int](Left(1)).merge.commit)(equalTo(1))
        },
        test("when having a successful value") {
          assertZIO(STM.fromEither[Int, Int](Right(1)).merge.commit)(equalTo(1))
        }
      ),
      suite("none")(
        test("when A is None") {
          assertZIO(STM.succeed(None).none.commit)(isUnit)
        },
        test("when Error") {
          assertZIO(STM.fail(ExampleError).none.commit.exit)(fails(isSome(equalTo(ExampleError))))
        } @@ zioTag(errors),
        test("when A is Some(a)") {
          assertZIO(STM.succeed(Some(1)).none.commit.exit)(fails(isNone))
        } @@ zioTag(errors),
        test("lifting a value") {
          assertZIO(STM.none.commit)(isNone)
        }
      ),
      suite("option to convert:")(
        test("A successful computation into Some(a)") {
          implicit val canFail = CanFail
          assertZIO(STM.succeed(42).option.commit)(isSome(equalTo(42)))
        },
        test("A failed computation into None") {
          assertZIO(STM.fail("oh no!").option.commit)(isNone)
        }
      ),
      suite("optional to convert:")(
        test("A Some(e) in E to a e in E") {
          val ei: Either[Option[String], Int] = Left(Some("my Error"))
          assertZIO(ZSTM.fromEither(ei).unsome.commit.exit)(fails(equalTo("my Error")))
        },
        test("a None in E into None in A") {
          val ei: Either[Option[String], Int] = Left(None)
          assertZIO(ZSTM.fromEither(ei).unsome.commit)(isNone)
        },
        test("no error") {
          val ei: Either[Option[String], Int] = Right(42)
          assertZIO(ZSTM.fromEither(ei).unsome.commit)(isSome(equalTo(42)))
        }
      ),
      suite("orDie")(
        test("when failure should die") {
          implicit val canFail = CanFail
          assertZIO(STM.fail(throw ExampleError).orDie.commit.exit)(dies(equalTo(ExampleError)))
        },
        test("when succeed should keep going") {
          implicit val canFail = CanFail
          assertZIO(STM.succeed(1).orDie.commit)(equalTo(1))
        }
      ) @@ zioTag(errors),
      suite("orDieWith")(
        test("when failure should die") {
          implicit val canFail = CanFail
          assertZIO(STM.fail("-1").orDieWith(n => new Error(n)).commit.exit)(dies(hasMessage(equalTo("-1"))))
        },
        test("when succeed should keep going") {
          implicit val canFail = CanFail
          assertZIO(STM.fromEither[String, Int](Right(1)).orDieWith(n => new Error(n)).commit)(equalTo(1))
        }
      ) @@ zioTag(errors),
      suite("partition")(
        test("collects only successes") {
          implicit val canFail = CanFail
          val in               = List.range(0, 10)
          for {
            res <- STM.partition(in)(STM.succeed(_)).commit
          } yield assert(res._1)(isEmpty) && assert(res._2)(equalTo(in))
        },
        test("collects only failures") {
          val in = List.fill(10)(0)
          for {
            res <- STM.partition(in)(STM.fail(_)).commit
          } yield assert(res._1)(equalTo(in)) && assert(res._2)(isEmpty)
        } @@ zioTag(errors),
        test("collects failures and successes") {
          val in = List.range(0, 10)
          for {
            res <- STM.partition(in)(a => if (a % 2 == 0) STM.fail(a) else STM.succeed(a)).commit
          } yield assert(res._1)(equalTo(List(0, 2, 4, 6, 8))) && assert(res._2)(equalTo(List(1, 3, 5, 7, 9)))
        } @@ zioTag(errors),
        test("evaluates effects in correct order") {
          implicit val canFail = CanFail
          val as               = List(2, 4, 6, 3, 5, 6)
          val tx =
            for {
              ref     <- TRef.make(List.empty[Int])
              _       <- STM.partition(as)(a => ref.update(a :: _))
              effects <- ref.get.map(_.reverse)
            } yield effects

          assertZIO(tx.commit)(equalTo(List(2, 4, 6, 3, 5, 6)))
        }
      ),
      suite("reject")(
        test("returns failure ignoring value") {
          val tx = ZSTM.succeed(0).reject { case v if v != 0 => "Partial failed!" }
          assertZIO(tx.commit)(equalTo(0))
        },
        test("returns failure ignoring value") {
          val tx = ZSTM.succeed(1).reject { case v if v != 0 => "Partial failed!" }
          assertZIO(tx.commit.exit)(fails(equalTo("Partial failed!")))
        }
      ) @@ zioTag(errors),
      suite("rejectSTM")(
        test("doesnt collect value") {
          val tx = ZSTM.succeed(0).rejectSTM[Any, String] { case v if v != 0 => ZSTM.succeed("Partial failed!") }
          assertZIO(tx.commit)(equalTo(0))
        },
        test("returns failure ignoring value") {
          val tx = ZSTM.succeed(1).rejectSTM[Any, String] { case v if v != 0 => ZSTM.succeed("Partial failed!") }
          assertZIO(tx.commit.exit)(fails(equalTo("Partial failed!")))
        }
      ) @@ zioTag(errors),
      suite("replicate")(
        test("zero") {
          val list: Iterable[STM[Nothing, Int]] = STM.replicate(0)(STM.succeed(12))
          assertZIO(STM.collectAll(list).commit)(equalTo(List.empty))
        },
        test("negative") {
          val list: Iterable[STM[Nothing, Int]] = STM.replicate(-2)(STM.succeed(12))
          assertZIO(STM.collectAll(list).commit)(equalTo(List.empty))
        },
        test("positive") {
          val list: Iterable[STM[Nothing, Int]] = STM.replicate(2)(STM.succeed(12))
          assertZIO(STM.collectAll(list).commit)(equalTo(List(12, 12)))
        }
      ),
      suite("right")(
        test("on Right value") {
          assertZIO(STM.succeed(Right("Right")).right.commit)(equalTo("Right"))
        },
        test("on Left value") {
          assertZIO(STM.succeed(Left("Left")).right.commit.exit)(fails(isLeft(equalTo("Left"))))
        },
        test("on failure") {
          assertZIO(STM.fail("Fail").right.commit.exit)(fails(isRight(equalTo("Fail"))))
        },
        test("lifting a value") {
          assertZIO(ZSTM.right(42).commit)(isRight(equalTo(42)))
        }
      ),
      suite("some")(
        test("extracts the value from Some") {
          assertZIO(STM.succeed(Some(1)).some.commit)(equalTo(1))
        },
        test("fails on None") {
          assertZIO(STM.succeed(None).some.commit.exit)(fails(isNone))
        } @@ zioTag(errors),
        test("fails when given an exception") {
          assertZIO(STM.fail(ExampleError).some.commit.exit)(fails(isSome(equalTo(ExampleError))))
        } @@ zioTag(errors),
        test("lifting a value") {
          assertZIO(STM.some(42).commit)(isSome(equalTo(42)))
        }
      ),
      suite("someOrElse")(
        test("extracts the value from Some") {
          assertZIO(STM.succeed(Some(1)).someOrElse(2).commit)(equalTo(1))
        },
        test("falls back to the default value if None") {
          assertZIO(STM.succeed(None).someOrElse(42).commit)(equalTo(42))
        },
        test("works when the default is an instance of a covariant type constructor applied to Nothing") {
          assertZIO(STM.succeed(Option.empty[List[String]]).someOrElse(List.empty).commit)(equalTo(List.empty))
        },
        test("does not change failed state") {
          assertZIO(STM.fail(ExampleError).someOrElse(42).commit.exit)(fails(equalTo(ExampleError)))
        } @@ zioTag(errors)
      ),
      suite("someOrElseSTM")(
        test("extracts the value from Some") {
          assertZIO(STM.succeed(Some(1)).someOrElseSTM(STM.succeed(2)).commit)(equalTo(1))
        },
        test("falls back to the default value if None") {
          assertZIO(STM.succeed(None).someOrElseSTM(STM.succeed(42)).commit)(equalTo(42))
        },
        test("works when the output of the default is an instance of a covariant type constructor applied to Nothing") {
          val stm = STM.succeed(Option.empty[List[String]]).someOrElseSTM(STM.succeed(List.empty))
          assertZIO(stm.commit)(equalTo(List.empty))
        },
        test("does not change failed state") {
          assertZIO(STM.fail(ExampleError).someOrElseSTM(STM.succeed(42)).commit.exit)(fails(equalTo(ExampleError)))
        } @@ zioTag(errors)
      ),
      suite("someOrFail")(
        test("extracts the value from Some") {
          assertZIO(STM.succeed(Some(1)).someOrFail(ExampleError).commit)(equalTo(1))
        },
        test("fails on None") {
          assertZIO(STM.succeed(None).someOrFail(ExampleError).commit.exit)(fails(equalTo(ExampleError)))
        } @@ zioTag(errors),
        test("fails with the original error") {
          val nError = new Error("not example")
          assertZIO(STM.fail(ExampleError).someOrFail(nError).commit.exit)(fails(equalTo(ExampleError)))
        } @@ zioTag(errors)
      ),
      suite("someOrFailException")(
        test("extracts the optional value") {
          assertZIO(STM.succeed(Some(42)).someOrFailException.commit)(equalTo(42))
        },
        test("fails when given a None") {
          val tx = STM.succeed(Option.empty[Int]).someOrFailException
          assertZIO(tx.commit.exit)(fails(isSubtype[NoSuchElementException](anything)))
        } @@ zioTag(errors),
        suite("without another error type")(
          test("succeed something") {
            assertZIO(STM.succeed(Option(3)).someOrFailException.commit)(equalTo(3))
          },
          test("succeed nothing") {
            assertZIO(STM.succeed(Option.empty[Int]).someOrFailException.commit.exit)(fails(Assertion.anything))
          }
        ),
        test("with throwable as a base error type return something") {
          assertZIO(STM.succeed(Option(3)).someOrFailException.commit)(equalTo(3))
        },
        test("with exception as base error type return something") {
          val e: Either[Exception, Option[Int]] = Right(Some(3))
          assertZIO(STM.fromEither(e).someOrFailException.commit)(equalTo(3))
        }
      ),
      test("succeed to make a successful computation and check the value") {
        assertZIO(STM.succeed("Hello World").commit)(equalTo("Hello World"))
      },
      suite("summarized")(
        test("returns summary and value") {
          val tx = for {
            counter              <- TRef.make(0)
            increment             = counter.updateAndGet(_ + 1)
            result               <- increment.summarized(increment)((_, _))
            ((start, end), value) = result
          } yield (start, value, end)
          assertZIO(tx.commit)(equalTo((1, 2, 3)))
        }
      ),
      test("zip to return a tuple of two computations") {
        assertZIO((STM.succeed(1) <*> STM.succeed('A')).commit)(equalTo((1, 'A')))
      },
      test("zipWith to perform an action to two computations") {
        assertZIO(STM.succeed(578).zipWith(STM.succeed(2))(_ + _).commit)(equalTo(580))
      }
    ),
    suite("Make a new `TRef` and")(
      test("get its initial value") {
        (for {
          intVar <- TRef.make(14)
          v      <- intVar.get
        } yield assert(v)(equalTo(14))).commit
      },
      test("set a new value") {
        (for {
          intVar <- TRef.make(14)
          _      <- intVar.set(42)
          v      <- intVar.get
        } yield assert(v)(equalTo(42))).commit
      }
    ),
    suite("Using `STM.atomically` perform concurrent computations")(
      test("increment `TRef` 100 times in 100 fibers") {
        for {
          tVar  <- TRef.makeCommit(0)
          fiber <- ZIO.forkAll(List.fill(10)(incrementVarN(99, tVar)))
          _     <- fiber.join
          value <- tVar.get.commit
        } yield assert(value)(equalTo(1000))
      },
      test(
        "compute a `TRef` from 2 variables, increment the first `TRef` and decrement the second `TRef` in different fibers"
      ) {
        for {
          tVars <- STM
                     .atomically(
                       TRef.make(10000) <*> TRef.make(0) <*> TRef.make(0)
                     )
          (tvar1, tvar2, tvar3) = tVars
          fiber                <- ZIO.forkAll(List.fill(10)(compute3VarN(99, tvar1, tvar2, tvar3)))
          _                    <- fiber.join
          value                <- tvar3.get.commit
        } yield assert(value)(equalTo(10000))
      }
    ) @@ exceptJS(nonFlaky),
    suite("Using `STM.atomically` perform concurrent computations that")(
      suite("have a simple condition lock should suspend the whole transaction and")(
        test("resume directly when the condition is already satisfied") {
          for {
            tvar1 <- TRef.makeCommit(10)
            tvar2 <- TRef.makeCommit("Failed!")
            join <- (for {
                      v1 <- tvar1.get
                      _  <- STM.check(v1 > 0)
                      _  <- tvar2.set("Succeeded!")
                      v2 <- tvar2.get
                    } yield v2).commit
          } yield assert(join)(equalTo("Succeeded!"))
        },
        test(
          "resume directly when the condition is already satisfied and change again the tvar with non satisfying value, the transaction shouldn't be suspended."
        ) {
          for {
            tvar <- TRef.makeCommit(42)
            join <- tvar.get.retryUntil(_ == 42).commit
            _    <- tvar.set(9).commit
            v    <- tvar.get.commit
          } yield assert(v)(equalTo(9)) && assert(join)(equalTo(42))
        },
        test("resume after satisfying the condition") {
          val barrier = new UnpureBarrier
          for {
            done  <- Promise.make[Nothing, Unit]
            tvar1 <- TRef.makeCommit(0)
            tvar2 <- TRef.makeCommit("Failed!")
            fiber <- (STM.atomically {
                       for {
                         v1 <- tvar1.get
                         _  <- STM.succeed(barrier.open())
                         _  <- STM.check(v1 > 42)
                         _  <- tvar2.set("Succeeded!")
                         v2 <- tvar2.get
                       } yield v2
                     } <* done
                       .succeed(())).fork
            _    <- barrier.await
            old  <- tvar2.get.commit
            _    <- tvar1.set(43).commit
            _    <- done.await
            newV <- tvar2.get.commit
            join <- fiber.join
          } yield assert(old)(equalTo("Failed!")) && assert(newV)(equalTo(join))
        },
        suite("have a complex condition lock should suspend the whole transaction and")(
          test("resume directly when the condition is already satisfied") {
            for {
              sender    <- TRef.makeCommit(100)
              receiver  <- TRef.makeCommit(0)
              _         <- transfer(receiver, sender, 150).fork
              _         <- sender.update(_ + 100).commit
              _         <- sender.get.retryUntil(_ == 50).commit
              senderV   <- sender.get.commit
              receiverV <- receiver.get.commit
            } yield assert(senderV)(equalTo(50)) && assert(receiverV)(equalTo(150))
          }
        )
      ),
      suite("transfer an amount to a sender and send it back the account should contains the amount to transfer")(
        test("run both transactions sequentially in 10 fibers.") {
          for {
            sender    <- TRef.makeCommit(100)
            receiver  <- TRef.makeCommit(0)
            toReceiver = transfer(receiver, sender, 150)
            toSender   = transfer(sender, receiver, 150)
            f         <- ZIO.forkAll(List.fill(10)(toReceiver *> toSender))
            _         <- sender.update(_ + 50).commit
            _         <- f.join
            senderV   <- sender.get.commit
            receiverV <- receiver.get.commit
          } yield assert(senderV)(equalTo(150)) && assert(receiverV)(equalTo(0))
        },
        test("run 10 transactions `toReceiver` and 10 `toSender` concurrently.") {
          for {
            sender    <- TRef.makeCommit(50)
            receiver  <- TRef.makeCommit(0)
            toReceiver = transfer(receiver, sender, 100)
            toSender   = transfer(sender, receiver, 100)
            f1        <- ZIO.forkAll(List.fill(10)(toReceiver))
            f2        <- ZIO.forkAll(List.fill(10)(toSender))
            _         <- sender.update(_ + 50).commit
            _         <- f1.join
            _         <- f2.join
            senderV   <- sender.get.commit
            receiverV <- receiver.get.commit
          } yield assert(senderV)(equalTo(100)) && assert(receiverV)(equalTo(0))
        },
        test("run transactions `toReceiver` 10 times and `toSender` 10 times each in 100 fibers concurrently.") {
          for {
            sender      <- TRef.makeCommit(50)
            receiver    <- TRef.makeCommit(0)
            toReceiver10 = transfer(receiver, sender, 100).repeatN(9)
            toSender10   = transfer(sender, receiver, 100).repeatN(9)
            f           <- toReceiver10.zipPar(toSender10).fork
            _           <- sender.update(_ + 50).commit
            _           <- f.join
            senderV     <- sender.get.commit
            receiverV   <- receiver.get.commit
          } yield assert(senderV)(equalTo(100)) && assert(receiverV)(equalTo(0))
        }
      ),
      test(
        "Perform atomically a single transaction that has a tvar for 20 fibers, each one checks the value and increment it."
      ) {
        for {
          tvar <- TRef.makeCommit(0)
          fiber <- ZIO.forkAll(
                     (0 to 20).map(i =>
                       (for {
                         v <- tvar.get
                         _ <- STM.check(v == i)
                         _ <- tvar.update(_ + 1)
                       } yield ()).commit
                     )
                   )
          _ <- fiber.join
          v <- tvar.get.commit
        } yield assert(v)(equalTo(21))
      },
      suite("Perform atomically a transaction with a condition that couldn't be satisfied, it should be suspended")(
        test("interrupt the fiber should terminate the transaction") {
          val barrier = new UnpureBarrier
          for {
            tvar <- TRef.makeCommit(0)
            fiber <- (for {
                       v <- tvar.get
                       _ <- STM.succeed(barrier.open())
                       _ <- STM.check(v > 0)
                       _ <- tvar.update(10 / _)
                     } yield ()).commit.fork
            _ <- barrier.await
            _ <- fiber.interrupt
            _ <- tvar.set(10).commit
            v <- liveClockSleep(10.millis) *> tvar.get.commit
          } yield assert(v)(equalTo(10))
        } @@ zioTag(interruption),
        test(
          "interrupt the fiber that has executed the transaction in 100 different fibers, should terminate all transactions"
        ) {
          val barrier = new UnpureBarrier
          for {
            tvar <- TRef.makeCommit(0)
            fiber <- ZIO.forkAll(List.fill(100)((for {
                       v <- tvar.get
                       _ <- STM.succeed(barrier.open())
                       _ <- STM.check(v < 0)
                       _ <- tvar.set(10)
                     } yield ()).commit))
            _ <- barrier.await
            _ <- fiber.interrupt
            _ <- tvar.set(-1).commit
            v <- liveClockSleep(10.millis) *> tvar.get.commit
          } yield assert(v)(equalTo(-1))
        } @@ exceptJS(nonFlaky),
        test("interrupt the fiber and observe it, it should be resumed with Interrupted Cause") {
          for {
            selfId  <- ZIO.fiberId
            v       <- TRef.makeCommit(1)
            f       <- v.get.flatMap(v => STM.check(v == 0)).commit.fork
            _       <- f.interrupt
            observe <- f.join.sandbox.either
          } yield assert(observe)(isLeft(containsCause(Cause.interrupt(selfId))))
        } @@ zioTag(interruption)
      ),
      test("Using `collect` filter and map simultaneously the value produced by the transaction") {
        assertZIO(STM.succeed((1 to 20).toList).collect { case l if l.forall(_ > 0) => "Positive" }.commit)(
          equalTo("Positive")
        )
      },
      test("Using `collectSTM` filter and map simultaneously the value produced by the transaction") {
        assertZIO(
          STM
            .succeed((1 to 20).toList)
            .collectSTM[Any, Nothing, String] { case l if l.forall(_ > 0) => STM.succeed("Positive") }
            .commit
        )(equalTo("Positive"))
      }
    ),
    test("Permute 2 variables") {
      for {
        tvar1 <- TRef.makeCommit(1)
        tvar2 <- TRef.makeCommit(2)
        _     <- permutation(tvar1, tvar2).commit
        v1    <- tvar1.get.commit
        v2    <- tvar2.get.commit
      } yield assert(v1)(equalTo(2)) && assert(v2)(equalTo(1))
    },
    test("Permute 2 variables in 100 fibers, the 2 variables should contains the same values") {
      for {
        tvar1 <- TRef.makeCommit(1)
        tvar2 <- TRef.makeCommit(2)
        oldV1 <- tvar1.get.commit
        oldV2 <- tvar2.get.commit
        f     <- ZIO.forkAll(List.fill(100)(permutation(tvar1, tvar2).commit))
        _     <- f.join
        v1    <- tvar1.get.commit
        v2    <- tvar2.get.commit
      } yield assert(v1)(equalTo(oldV1)) && assert(v2)(equalTo(oldV2))
    } @@ exceptJS(nonFlaky),
    suite("collectAll")(
      test("collects a list of transactional effects to a single transaction that produces a list of values") {
        for {
          it    <- ZIO.succeed((1 to 100).map(TRef.make(_)))
          tvars <- STM.collectAll(it).commit
          res   <- ZIO.foreachPar(tvars)(_.get.commit)
        } yield assert(res)(equalTo((1 to 100)))
      },
      test("collects a chunk of transactional effects to a single transaction that produces a chunk of values") {
        for {
          it    <- ZIO.succeed((1 to 100).map(TRef.make(_)))
          tvars <- STM.collectAll(Chunk.fromIterable(it)).commit
          res   <- ZIO.foreachPar(tvars)(_.get.commit)
        } yield assert(res)(equalTo(Chunk.fromIterable((1 to 100).toList)))
      }
    ),
    suite("foreach")(
      test("performs an action on each list element and return a single transaction that contains the result") {
        for {
          tvar     <- TRef.makeCommit(0)
          list      = List(1, 2, 3, 4, 5)
          expectedV = list.sum
          _        <- STM.foreach(list)(a => tvar.update(_ + a)).commit
          v        <- tvar.get.commit
        } yield assert(v)(equalTo(expectedV))
      },
      test("performs an action on each chunk element and return a single transaction that contains the result") {
        for {
          tvar     <- TRef.makeCommit(0)
          chunk     = Chunk(1, 2, 3, 4, 5)
          expectedV = chunk.foldRight(0)(_ + _)
          _        <- STM.foreach(chunk)(a => tvar.update(_ + a)).commit
          v        <- tvar.get.commit
        } yield assert(v)(equalTo(expectedV))
      }
    ),
    suite("foreachDiscard")(
      test("performs actions in order given a list") {
        for {
          ref <- TRef.makeCommit(List.empty[Int])
          as   = List(1, 2, 3, 4, 5)
          _   <- STM.foreachDiscard(as)(a => ref.update(_ :+ a)).commit
          bs  <- ref.get.commit
        } yield assert(bs)(equalTo(as))
      },
      test("performs actions in order given a chunk") {
        for {
          ref <- TRef.makeCommit(List.empty[Int])
          as   = Chunk(1, 2, 3, 4, 5)
          _   <- STM.foreachDiscard(as)(a => ref.update(_ :+ a)).commit
          bs  <- ref.get.commit
        } yield assert(bs)(equalTo(as.toList))
      }
    ),
    suite("Failure must")(
      test("rollback full transaction") {
        for {
          tvar <- TRef.makeCommit(0)
          e <- (for {
                 _ <- tvar.update(_ + 10)
                 _ <- STM.fail("Error!")
               } yield ()).commit.either
          v <- tvar.get.commit
        } yield assert(e)(isLeft(equalTo("Error!"))) && assert(v)(equalTo(0))
      },
      test("be ignored") {
        for {
          tvar <- TRef.makeCommit(0)
          e <- (for {
                 _ <- tvar.update(_ + 10)
                 _ <- STM.fail("Error!")
               } yield ()).commit.ignore
          v <- tvar.get.commit
        } yield assert(e)(equalTo(())) && assert(v)(equalTo(0))
      }
    ) @@ zioTag(errors),
    suite("commitEither")(
      test("commits this transaction whether it is a success or a failure") {
        for {
          tvar <- TRef.makeCommit(false)
          e    <- (tvar.set(true) *> STM.fail("Error!")).commitEither.flip
          v    <- tvar.get.commit
        } yield assert(e)(equalTo("Error!")) && assert(v)(isTrue)
      }
    ),
    suite("orElse")(
      test("tries alternative once left retries") {
        for {
          ref  <- TRef.makeCommit(0)
          left  = ref.update(_ + 100) *> STM.retry
          right = ref.update(_ + 200)
          _    <- (left orElse right).commit
          v    <- ref.get.commit
        } yield assert(v)(equalTo(200))
      },
      test("tries alternative once left fails") {
        for {
          ref  <- TRef.makeCommit(0)
          left  = ref.update(_ + 100) *> STM.fail("boom")
          right = ref.update(_ + 200)
          _    <- (left orElse right).commit
          res  <- ref.get.commit
        } yield assert(res)(equalTo(200))
      },
      test("fail if alternative fails") {
        val left  = STM.fail("left")
        val right = STM.fail("right")

        (left orElse right).commit.exit.map(assert(_)(fails(equalTo("right"))))
      },
      test("retries on LHS variable change") {
        for {
          ref1 <- TRef.makeCommit(0)
          ref2 <- TRef.makeCommit(0)
          txn1 = ref1.get.flatMap {
                   case 0 => STM.retry
                   case n => STM.succeed(n)
                 } orElse ref2.get.flatMap {
                   case 0 => STM.retry
                   case n => STM.succeed(n)
                 }
          txn2    = ref1.set(1)
          fib    <- txn1.commit.forkDaemon
          _      <- liveClockSleep(1.second)
          _      <- txn2.commit
          result <- fib.join
        } yield assert(result)(equalTo(1))
      }
    ) @@ zioTag(errors),
    test("orElseEither returns result of the first successful transaction wrapped in either") {
      for {
        rv  <- STM.retry.orElseEither(STM.succeed(42)).commit
        lv1 <- STM.succeed(1).orElseEither(STM.succeed("No!")).commit
        lv2 <- STM.succeed(2).orElseEither(STM.retry).commit
      } yield assert(rv)(isRight(equalTo(42))) && assert(lv1)(isLeft(equalTo(1))) && assert(lv2)(isLeft(equalTo(2)))
    },
    suite("orElseFail")(
      test("tries left first") {
        val transaction = STM.succeed(true).orElseFail(false)
        assertZIO(transaction.commit)(isTrue)
      },
      test("fails with the specified error once left retries") {
        val transaction = STM.retry.orElseFail(false).either
        assertZIO(transaction.commit)(isLeft(isFalse))
      },
      test("fails with the specified error once left fails") {
        val transaction = STM.fail(true).orElseFail(false).either
        assertZIO(transaction.commit)(isLeft(isFalse))
      }
    ) @@ zioTag(errors),
    suite("orElseSucceed")(
      test("tries left first") {
        val transaction = STM.succeed(true).orElseSucceed(false)
        assertZIO(transaction.commit)(isTrue)
      },
      test("succeeds with the specified value if left retries") {
        val transaction = STM.retry.orElseSucceed(false)
        assertZIO(transaction.commit)(isFalse)
      },
      test("succeeds with the specified value if left fails") {
        val transaction = STM.fail(true).orElseSucceed(false)
        assertZIO(transaction.commit)(isFalse)
      }
    ),
    suite("alternative")(
      test("succeeds if left succeeds") {
        val left  = STM.succeed("left")
        val right = STM.succeed("right")
        (left <|> right).commit.map(assert(_)(equalTo("left")))
      },
      test("succeeds if right succeeds") {
        val left  = STM.retry
        val right = STM.succeed("right")
        (left <|> right).commit.map(assert(_)(equalTo("right")))
      },
      test("retries left after right retries") {
        for {
          ref    <- TRef.makeCommit(0)
          left    = ref.get.flatMap(v => STM.check(v > 500).as("left"))
          right   = STM.retry
          updater = ref.update(_ + 10).commit.forever
          res    <- (left <|> right).commit.race(updater)
        } yield assert(res)(equalTo("left"))
      },
      test("fails if left fails") {
        val left  = STM.fail("left")
        val right = STM.succeed("right")
        (left <|> right).commit.exit.map(assert(_)(fails(equalTo("left"))))
      } @@ zioTag(errors),
      test("fails if right fails") {
        val left  = STM.retry
        val right = STM.fail("right")
        (left <|> right).commit.exit.map(assert(_)(fails(equalTo("right"))))
      } @@ zioTag(errors)
    ),
    suite("mergeAll")(
      test("return zero element on empty input") {
        val zeroElement = 42
        val nonZero     = 43
        STM.mergeAll(Nil)(zeroElement)((_, _) => nonZero).commit.map {
          assert(_)(equalTo(zeroElement))
        }
      },
      test("merge list using function") {
        val effects = List(3, 5, 7).map(STM.succeed(_))
        STM.mergeAll(effects)(zero = 1)(_ + _).commit.map {
          assert(_)(equalTo(1 + 3 + 5 + 7))
        }
      },
      test("return error if it exists in list") {
        val effects = List(STM.unit, STM.fail(1))
        val merged  = STM.mergeAll(effects)(zero = ())((_, _) => ())
        assertZIO(merged.commit.exit)(fails(equalTo(1)))
      } @@ zioTag(errors)
    ),
    suite("ZSTM reduceAll")(
      test("reduceAll") {
        val tx = ZSTM.reduceAll(ZSTM.succeed(1), List(2, 3, 4).map(ZSTM.succeed(_)))(_ + _)
        assertZIO(tx.commit)(equalTo(10))
      },
      test("reduceAll Empty List") {
        val tx = ZSTM.reduceAll(ZSTM.succeed(1), List.empty)(_ + _)
        assertZIO(tx.commit)(equalTo(1))
      }
    ),
    suite("ZSTM validate")(
      test("returns all errors if never valid") {
        implicit val canFail         = CanFail
        val in                       = List.fill(10)(0)
        def fail[A](a: A): STM[A, A] = STM.fail(a)
        val res                      = STM.validate(in)(a => fail(a))
        assertZIO(res.commit.exit)(fails(equalTo(in)))
      } @@ zioTag(errors),
      test("accumulate errors and ignore successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = STM.validate(in)(a => if (a % 2 == 0) STM.succeed(a) else STM.fail(a))
        assertZIO(res.commit.exit)(fails(equalTo(List(1, 3, 5, 7, 9))))
      } @@ zioTag(errors),
      test("accumulate successes") {
        implicit val canFail = CanFail
        val in               = List.range(0, 10)
        val res              = STM.validate(in)(a => STM.succeed(a))
        assertZIO(res.commit)(equalTo(in))
      }
    ),
    suite("ZSTM validateFirst")(
      test("returns all errors if never valid") {
        implicit val canFail = CanFail
        val in               = List.fill(10)(0)
        val res              = STM.validateFirst(in)(a => STM.fail(a))
        assertZIO(res.commit.exit)(fails(equalTo(in)))
      } @@ zioTag(errors),
      test("runs sequentially and short circuits on first success validation") {
        implicit val canFail = CanFail
        val in               = List.range(1, 10)
        val f                = (a: Int) => if (a == 6) STM.succeed(a) else STM.fail(a)

        val tx = for {
          counter <- TRef.make(0)
          res     <- STM.validateFirst(in)(a => counter.update(_ + 1) *> f(a))
          cc      <- counter.get
        } yield (res, cc)

        assertZIO(tx.commit)(equalTo((6, 6)))
      },
      test("returns errors in correct order") {
        implicit val canFail = CanFail
        val as               = List(2, 4, 6, 3, 5, 6)
        val tx               = STM.validateFirst(as)(STM.fail(_))
        assertZIO(tx.commit.exit)(fails(equalTo(List(2, 4, 6, 3, 5, 6))))
      } @@ zioTag(errors)
    ),
    suite("when combinators")(
      test("when true") {
        for {
          ref    <- TRef.make(false).commit
          result <- (STM.when(true)(ref.set(true)) *> ref.get).commit
        } yield assert(result)(equalTo(true))
      },
      test("when false") {
        for {
          ref    <- TRef.make(false).commit
          result <- (STM.when(false)(ref.set(true)) *> ref.get).commit
        } yield assert(result)(equalTo(false))
      },
      test("whenCase executes correct branch only") {
        val tx = for {
          ref  <- TRef.make(false)
          _    <- ZSTM.whenCase(Option.empty[Int]) { case Some(_) => ref.set(true) }
          res1 <- ref.get
          _    <- ZSTM.whenCase(Some(0)) { case Some(_) => ref.set(true) }
          res2 <- ref.get
        } yield (res1, res2)
        assertZIO(tx.commit)(equalTo((false, true)))
      },
      test("whenCaseSTM executes condition effect and correct branch") {
        val tx = for {
          ref  <- TRef.make(false)
          _    <- ZSTM.whenCaseSTM(ZSTM.succeed(Option.empty[Int])) { case Some(_) => ref.set(true) }
          res1 <- ref.get
          _    <- ZSTM.whenCaseSTM(ZSTM.succeed(Some(0))) { case Some(_) => ref.set(true) }
          res2 <- ref.get
        } yield (res1, res2)
        assertZIO(tx.commit)(equalTo((false, true)))
      },
      test("whenSTM true") {
        for {
          ref    <- TRef.make(0).commit
          isZero  = ref.get.map(_ == 0)
          result <- (STM.whenSTM(isZero)(ref.update(_ + 1)) *> ref.get).commit
        } yield assert(result)(equalTo(1))
      },
      test("whenSTM false") {
        for {
          ref      <- TRef.make(0).commit
          isNotZero = ref.get.map(_ != 0)
          result   <- (STM.whenSTM(isNotZero)(ref.update(_ + 1)) *> ref.get).commit
        } yield assert(result)(equalTo(0))
      }
    ),
    suite("STM issue 2073") {
      test("read only STM shouldn't return partial state of concurrent read-write STM") {
        for {
          r0       <- TRef.makeCommit(0)
          r1       <- TRef.makeCommit(0)
          sumFiber <- r0.get.flatMap(v0 => r1.get.map(_ + v0)).commit.fork
          _        <- r0.update(_ + 1).flatMap(_ => r1.update(_ + 1)).commit
          sum      <- sumFiber.join
        } yield assert(sum)(equalTo(0) || equalTo(2))
      } @@ exceptJS(nonFlaky(5000))
    },
    suite("STM stack safety")(
      test("long alternative chains") {
        val tx =
          for {
            ref <- TRef.make(0)
            _   <- STM.loopDiscard(10000)(_ > 0, _ - 1)(_ => STM.retry <|> ref.getAndUpdate(_ + 1))
            res <- ref.get
          } yield res

        assertZIO(tx.commit)(equalTo(10000))
      },
      test("long map chains") {
        assertZIO(chain(10000)(_.map(_ + 1)))(equalTo(10000))
      },
      test("long collect chains") {
        assertZIO(chain(10000)(_.collect { case a: Int => a + 1 }))(equalTo(10000))
      },
      test("long collectSTM chains") {
        assertZIO(chain(10000)(_.collectSTM { case a: Int => STM.succeed(a + 1) }))(equalTo(10000))
      },
      test("long flatMap chains") {
        assertZIO(chain(10000)(_.flatMap(a => STM.succeed(a + 1))))(equalTo(10000))
      },
      test("long fold chains") {
        implicit val canFail = CanFail
        assertZIO(chain(10000)(_.fold(_ => 0, _ + 1)))(equalTo(10000))
      },
      test("long foldSTM chains") {
        implicit val canFail = CanFail
        assertZIO(chain(10000)(_.foldSTM(_ => STM.succeed(0), a => STM.succeed(a + 1))))(equalTo(10000))
      },
      test("long mapError chains") {
        def chain(depth: Int): IO[Int, Nothing] = {
          @annotation.tailrec
          def loop(n: Int, acc: STM[Int, Nothing]): IO[Int, Nothing] =
            if (n <= 0) acc.commit else loop(n - 1, acc.mapError(_ + 1))

          loop(depth, STM.fail(0))
        }

        assertZIO(chain(10000).exit)(fails(equalTo(10000)))
      } @@ zioTag(errors),
      test("long orElse chains") {
        val tx =
          for {
            ref <- TRef.make(0)
            _   <- STM.loopDiscard(10000)(_ > 0, _ - 1)(_ => STM.retry <> ref.getAndUpdate(_ + 1))
            res <- ref.get
          } yield res

        assertZIO(tx.commit)(equalTo(10000))
      } @@ zioTag(errors),
      test("long provide chains") {
        assertZIO(chain(10000)(_.provideEnvironment(ZEnvironment(0))))(equalTo(0))
      }
    ),
    suite("STM environment")(
      test("access environment and provide it outside transaction") {
        STMEnv.make(0).flatMap { env =>
          ZSTM.serviceWithSTM[STMEnv](_.ref.update(_ + 1)).commit.provideEnvironment(ZEnvironment(env)) *>
            assertZIO(env.ref.get.commit)(equalTo(1))
        }
      },
      test("access environment and provide it inside transaction") {
        STMEnv.make(0).flatMap { env =>
          ZSTM.serviceWithSTM[STMEnv](_.ref.update(_ + 1)).provideEnvironment(ZEnvironment(env)).commit *>
            assertZIO(env.ref.get.commit)(equalTo(1))
        }
      }
    ),
    test("STM collectAll ordering") {
      val tx = for {
        tq  <- TQueue.bounded[Int](3)
        _   <- tq.offer(1)
        _   <- tq.offer(2)
        _   <- tq.offer(3)
        ans <- ZSTM.collectAll(List(tq.take, tq.take, tq.take))
      } yield ans
      assertZIO(tx.commit)(equalTo(List(1, 2, 3)))
    },
    suite("taps")(
      test("tap should apply the transactional function to the effect result while keeping the effect itself") {
        val tx =
          for {
            refA <- TRef.make(10)
            refB <- TRef.make(0)
            a    <- refA.get.tap(v => refB.set(v + 1))
            b    <- refB.get
          } yield (a, b)

        assertZIO(tx.commit)(equalTo((10, 11)))
      },
      test("tapBoth applies the success function to success values while keeping the effect intact") {
        val tx =
          for {
            tapSuccess    <- TPromise.make[Nothing, Int]
            tapError      <- TPromise.make[Nothing, String]
            succeededSTM   = ZSTM.succeed(42): STM[String, Int]
            result        <- succeededSTM.tapBoth(e => tapError.succeed(e), a => tapSuccess.succeed(a))
            tappedSuccess <- tapSuccess.await
          } yield (result, tappedSuccess)

        assertZIO(tx.commit)(equalTo((42, 42)))
      },
      test("tapBoth applies the function to error and successful values while keeping the effect itself on error") {
        val tx =
          for {
            tapSuccess  <- TPromise.make[Nothing, Int]
            tapError    <- TPromise.make[Nothing, String]
            succeededSTM = ZSTM.fail("error"): STM[String, Int]
            result      <- succeededSTM.tapBoth(e => tapError.succeed(e), a => tapSuccess.succeed(a)).either
            tappedError <- tapError.await
          } yield (result, tappedError)

        assertZIO(tx.commit)(equalTo((Left("error"), "error")))
      },
      test("tapError should apply the transactional function to the error result while keeping the effect itself") {
        val tx =
          for {
            errorRef    <- TPromise.make[Nothing, String]
            failedStm    = ZSTM.fail("error") *> ZSTM.succeed(0)
            result      <- failedStm.tapError(e => errorRef.succeed(e)).either
            tappedError <- errorRef.await
          } yield (result, tappedError)

        assertZIO(tx.commit)(equalTo((Left("error"), "error")))
      } @@ zioTag(errors)
    ),
    test("onCommit executes the specified workflow when the transaction is committed") {
      def transaction(ref: Ref[Int]): STM[Nothing, Unit] =
        STM.onCommit(ref.update(_ + 2)) *> STM.onCommit(ref.update(_ * 3))
      for {
        ref   <- Ref.make(1)
        _     <- transaction(ref).commit
        value <- ref.get
      } yield assertTrue(value == 9)
    } @@ exceptJS(nonFlaky(10000)),
    suite("STM issue 9264")(
      test("transactions containing both reads and writes are consistent") {
        for {
          q1 <- TestQueue.make[String]
          q2 <- TestQueue.make[String]
          enq = (q1.enqueue("x") *> q2.enqueue("x")).commit.repeatN(8)
          deq = (q1.tryDequeue <*> q2.tryDequeue).commit.flatMap {
                  case (None, None)                     => ZIO.unit
                  case (Some(v1), Some(v2)) if v1 == v2 => ZIO.unit
                  case (x, y)                           => ZIO.dieMessage(s"Dequeued values are different: $x != $y")
                }
                  .repeatN(8)
          _ <- ZIO.foreachParDiscard(List(enq, deq, deq))(t => t)
        } yield assertCompletes
      } @@ exceptJS(nonFlaky(10000))
    )
  )

  val ExampleError = new Throwable("fail")

  trait STMEnv {
    val ref: TRef[Int]
  }
  object STMEnv {
    def make(i: Int): UIO[STMEnv] =
      TRef
        .makeCommit(i)
        .map { ref0 =>
          new STMEnv {
            val ref = ref0
          }
        }
  }

  def unpureSuspend(ms: Long): USTM[Unit] = STM.succeed {
    val t0 = java.lang.System.currentTimeMillis()
    while (java.lang.System.currentTimeMillis() - t0 < ms) {}
  }

  class UnpureBarrier {
    private var isOpen = false
    def open(): Unit   = isOpen = true
    def await: UIO[Any] =
      ZIO
        .suspend(ZIO.attempt(if (isOpen) () else throw new Exception()))
        .eventually
  }

  def liveClockSleep(d: Duration): ZIO[Any, Nothing, Unit] = Live.live(ZIO.sleep(d))

  def incrementVarN(n: Int, tvar: TRef[Int]): ZIO[Any, Nothing, Int] =
    STM
      .atomically(for {
        v <- tvar.get
        _ <- tvar.set(v + 1)
        v <- tvar.get
      } yield v)
      .repeatN(n)

  def compute3VarN(
    n: Int,
    tvar1: TRef[Int],
    tvar2: TRef[Int],
    tvar3: TRef[Int]
  ): ZIO[Any, Nothing, Int] =
    STM
      .atomically(for {
        v1 <- tvar1.get
        v2 <- tvar2.get
        _  <- tvar3.set(v1 + v2)
        v3 <- tvar3.get
        _  <- tvar1.set(v1 - 1)
        _  <- tvar2.set(v2 + 1)
      } yield v3)
      .repeatN(n)

  def transfer(receiver: TRef[Int], sender: TRef[Int], much: Int): UIO[Int] =
    STM.atomically {
      for {
        balance <- sender.get
        _       <- STM.check(balance >= much)
        _       <- receiver.update(_ + much)
        _       <- sender.update(_ - much)
        newAmnt <- receiver.get
      } yield newAmnt
    }

  def permutation(tvar1: TRef[Int], tvar2: TRef[Int]): STM[Nothing, Unit] =
    for {
      a <- tvar1.get
      b <- tvar2.get
      _ <- tvar1.set(b)
      _ <- tvar2.set(a)
    } yield ()

  def chain(depth: Int)(next: STM[Nothing, Int] => STM[Nothing, Int]): UIO[Int] = {
    @annotation.tailrec
    def loop(n: Int, acc: STM[Nothing, Int]): UIO[Int] =
      if (n <= 0) acc.commit else loop(n - 1, next(acc))

    loop(depth, STM.succeed(0))
  }

  final class TestQueue[A >: Null] private (head: TRef[TestQueue.Node[A]], tail: TRef[TestQueue.Node[A]]) {
    import TestQueue._

    def enqueue(a: A): USTM[Unit] =
      for {
        end <- TRef.make[Elem[A]](End[A]())
        node = Node(a, end)
        t   <- tail.get
        tn  <- t.next.get
        _ <- tn match {
               case End() => t.next.set(node).flatMap(_ => tail.set(node))
               case _     => ZSTM.dieMessage("this should never happen")
             }
      } yield ()

    def tryDequeue: USTM[Option[A]] =
      for {
        h  <- head.get
        hn <- h.next.get
        r <- hn match {
               case n @ Node(a, _) => head.set(n.copy(data = null)).as(Some(a))
               case End()          => ZSTM.succeed(None)
             }
      } yield r
  }

  object TestQueue {

    private sealed abstract class Elem[A]
    private final case class Node[A](data: A, next: TRef[Elem[A]]) extends Elem[A]
    private final case class End[A]()                              extends Elem[A]

    def make[A >: Null]: UIO[TestQueue[A]] = for {
      end     <- TRef.makeCommit[Elem[A]](End[A]())
      sentinel = Node[A](null, end)
      head    <- TRef.makeCommit[Node[A]](sentinel)
      tail    <- TRef.makeCommit[Node[A]](sentinel)
    } yield new TestQueue[A](head, tail)
  }

}
