package zio

import zio.Cause.{Both, Then, empty}
import zio.test.Assertion._
import zio.test.TestAspect.samples
import zio.test._

object CauseSpec extends ZIOBaseSpec {

  def spec = suite("CauseSpec")(
    suite("Cause")(
      test("`Cause#died` and `Cause#stripFailures` are consistent") {
        check(causes)(c => assert(c.keepDefects)(if (c.isDie) isSome(anything) else isNone))
      },
      test("`Cause.equals` is symmetric") {
        check(causes, causes)((a, b) => assert(a == b)(equalTo(b == a)))
      },
      test("`Cause.equals` and `Cause.hashCode` satisfy the contract") {
        check(equalCauses) { case (a, b) =>
          assert(a.hashCode)(equalTo(b.hashCode))
        }
      },
      test("`Cause.equals` discriminates between `Die` and `Fail`") {
        val t     = new RuntimeException
        val left  = Cause.die(t)
        val right = Cause.fail(t)
        assert(left)(not(equalTo(right)))
      },
      test("`Cause#untraced` removes all traces") {
        check(causes) { c =>
          assert(c.untraced.traces.headOption)(isNone || isSome(equalTo(StackTrace.none)))
        }
      },
      test("`Cause.failures is stack safe") {
        val n     = 100000
        val cause = List.fill(n)(Cause.fail("fail")).reduce(_ && _)
        assert(cause.failures.length)(equalTo(n))
      }
    ),
    suite("Then")(
      test("`Then.equals` satisfies associativity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert((a ++ b) ++ c)(equalTo(a ++ (b ++ c))) &&
          assert(a ++ (b ++ c))(equalTo((a ++ b) ++ c))
        }
      },
      test("`Then.equals` satisfies distributivity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert(a ++ (b && c))(equalTo((a ++ b) && (a ++ c))) &&
          assert((a && b) ++ c)(equalTo((a ++ c) && (b ++ c)))
        }
      },
      test("`Then.equals` distributes `Then` over `Both` even in the presence of `Empty`") {
        check(causes, causes) { (a, b) =>
          assert(a ++ (empty && b))(equalTo(a && (a ++ b))) &&
          assert(a ++ (b && empty))(equalTo((a ++ b) && a)) &&
          assert(a ++ (empty && empty))(equalTo(a && a)) &&
          assert((empty && b) ++ a)(equalTo(a && (b ++ a))) &&
          assert((b && empty) ++ a)(equalTo((b ++ a) && a)) &&
          assert((empty && empty) ++ a)(equalTo(a && a))
        }
      }
    ),
    suite("Both")(
      test("`Both.equals` satisfies associativity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert((a && b) && c)(equalTo(a && (b && c))) &&
          assert(a && (b && c))(equalTo((a && b) && c))
        }
      },
      test("`Both.equals` satisfies distributivity") {
        check(causes, causes, causes) { (a, b, c) =>
          assert((a ++ b) && (a ++ c))(equalTo(a ++ (b && c))) &&
          assert((a ++ c) && (b ++ c))(equalTo((a && b) ++ c))
        }
      },
      test("`Both.equals` satisfies commutativity") {
        check(causes, causes)((a, b) => assert(Both(a, b))(equalTo(Both(b, a))))
      },
      test("`Both.equals` distributes `Then` over `Both` even in the presence of `Empty`") {
        check(causes, causes) { (a, b) =>
          assert(a && (a ++ b))(equalTo(a ++ (empty && b))) &&
          assert((a ++ b) && a)(equalTo(a ++ (b && empty))) &&
          assert(a && a)(equalTo(a ++ (empty && empty))) &&
          assert(a && (b ++ a))(equalTo((empty && b) ++ a)) &&
          assert((b ++ a) && a)(equalTo((b && empty) ++ a)) &&
          assert(a && a)(equalTo((empty && empty) ++ a))
        }
      }
    ),
    suite("Stackless")(
      test("`Stackless` is excluded from equals") {
        check(causes) { c =>
          assert(Cause.stackless(c))(equalTo(c)) &&
          assert(c)(equalTo(Cause.stackless(c)))
        }
      },
      test("`Stackless` is excluded from hashCode") {
        check(causes)(c => assert(Cause.stackless(c).hashCode)(equalTo(c.hashCode)))
      }
    ),
    suite("Empty")(
      test("`Empty` is empty element for `Then`") {
        check(causes) { c =>
          assert(c ++ empty)(equalTo(c)) &&
          assert(empty ++ c)(equalTo(c))
        }
      },
      test("`Empty` is empty element for `Both`") {
        check(causes) { c =>
          assert(c && empty)(equalTo(c)) &&
          assert(empty && c)(equalTo(c))
        }
      }
    ),
    suite("Monad Laws:")(
      test("Left identity") {
        check(causes)(c => assert(c.flatMap(Cause.fail(_)))(equalTo(c)))
      },
      test("Right identity") {
        check(errors, errorCauseFunctions)((e, f) => assert(Cause.fail(e).flatMap(f))(equalTo(f(e))))
      },
      test("Associativity") {
        check(causes, errorCauseFunctions, errorCauseFunctions) { (c, f, g) =>
          assert(c.flatMap(f).flatMap(g))(equalTo(c.flatMap(e => f(e).flatMap(g))))
        }
      }
    ),
    suite("squashTraceWith")(
      test("converts Cause to original exception with ZTraces in root cause") {
        val throwable = (Gen.alphaNumericString <*> Gen.alphaNumericString).flatMap { case (msg1, msg2) =>
          Gen
            .elements(
              new IllegalArgumentException(msg2),
              // null cause can't be replaced using Throwable.initCause() on the JVM
              new IllegalArgumentException(msg2, null)
            )
            .map(new Throwable(msg1, _))
        }
        val failOrDie = Gen.elements[Throwable => Cause[Throwable]](Cause.fail(_), Cause.die(_))
        check(throwable, failOrDie) { (e, makeCause) =>
          val rootCause        = makeCause(e)
          val cause            = rootCause
          val causeMessage     = e.getCause.getMessage
          val throwableMessage = e.getMessage
          val renderedCause    = Cause.stackless(cause).prettyPrint
          val squashed         = cause.squashTraceWith(identity)

          assert(squashed)(
            equalTo(e) &&
              hasMessage(equalTo(throwableMessage)) &&
              hasThrowableCause(isSubtype[IllegalArgumentException](hasMessage(equalTo(causeMessage)))) &&
              hasSuppressed(exists(hasMessage(equalTo(renderedCause))))
          )
        }
      } @@ TestAspect.unix
    ),
    suite("stripSomeDefects")(
      test("returns `Some` with remaining causes") {
        val c1       = Cause.die(new NumberFormatException("can't parse to int"))
        val c2       = Cause.die(new ArithmeticException("division by zero"))
        val cause    = Cause.Both(c1, c2)
        val stripped = cause.stripSomeDefects { case _: NumberFormatException => }
        assert(stripped)(isSome(equalTo(c2)))
      },
      test("returns `None` if there are no remaining causes") {
        val cause    = Cause.die(new NumberFormatException("can't parse to int"))
        val stripped = cause.stripSomeDefects { case _: NumberFormatException => }
        assert(stripped)(isNone)
      }
    ),
    suite("filter")(
      test("fail.filter(false)") {
        val f1 = Cause.fail(())
        val f2 = f1.filter(_ => false)
        assertTrue(f2.isEmpty)
      },
      test("fail.filter(true)") {
        val f1 = Cause.fail(())
        val f2 = f1.filter(_ => true)
        assert(f2)(Assertion.equalTo(f1))
      },
      test("interrupt.filter(false)") {
        val f1 = Cause.interrupt(FiberId.apply(0, 42, implicitly))
        val f2 = f1.filter(_ => false)
        assertTrue(f2.isEmpty)
      },
      test("interrupt.filter(true)") {
        val f1 = Cause.interrupt(FiberId.apply(0, 42, implicitly))
        val f2 = f1.filter(_ => true)
        assert(f2)(Assertion.equalTo(f1))
      },
      test("die.filter(false)") {
        val f1 = Cause.die(new RuntimeException())
        val f2 = f1.filter(_ => false)
        assertTrue(f2.isEmpty)
      },
      test("die.filter(true)") {
        val f1 = Cause.die(new RuntimeException())
        val f2 = f1.filter(_ => true)
        assert(f2)(Assertion.equalTo(f1))
      },
      test("stackless.filter(false)") {
        val f1 = Cause.Stackless(Cause.fail(()), true)
        val f2 = f1.filter(_ => false)
        assertTrue(f2.isEmpty)
      },
      test("stackless.filter(true)") {
        val f1 = Cause.Stackless(Cause.fail(()), true)
        val f2 = f1.filter(_ => true)
        assert(f2)(Assertion.equalTo(f1))
      }, {
        val f1      = Cause.fail(())
        val f2      = Cause.interrupt(FiberId.apply(0, 42, implicitly))
        val andThen = Cause.Then(f1, f2)
        suite("andThen")(
          test("filter(false)") {
            val filt = andThen.filter(_ => false)
            assertTrue(filt.isEmpty)
          },
          test("filter(true)") {
            val filt = andThen.filter(_ => true)
            assert(filt)(Assertion.equalTo(andThen))
          },
          test("filter(isInterruped)") {
            val filt = andThen.filter(_.isInterrupted)
            assert(filt)(Assertion.equalTo(f2))
          },
          test("filter(isFailure)") {
            val filt = andThen.filter(_.isFailure)
            assert(filt)(Assertion.equalTo(f1))
          }
        )
      }, {
        val f1   = Cause.fail(())
        val f2   = Cause.interrupt(FiberId.apply(0, 42, implicitly))
        val both = Cause.Both(f1, f2)
        suite("both")(
          test("filter(false)") {
            val filt = both.filter(_ => false)
            assertTrue(filt.isEmpty)
          },
          test("filter(true)") {
            val filt = both.filter(_ => true)
            assert(filt)(Assertion.equalTo(both))
          },
          test("filter(isInterruped)") {
            val filt = both.filter(_.isInterrupted)
            assert(filt)(Assertion.equalTo(f2))
          },
          test("filter(isFailure)") {
            val filt = both.filter(_.isFailure)
            assert(filt)(Assertion.equalTo(f1))
          }
        )
      },
      test("traversal") {
        val c1   = Cause.fail("foo")
        val c2   = Cause.fail("bar")
        val c3   = Cause.fail("baz")
        val c23  = Cause.Both(c2, c3)
        val c123 = Cause.Both(c1, c23)

        val bldr = Seq.newBuilder[(Seq[String], Boolean)]
        val c = c123.filter { c =>
          val res = !c.isInstanceOf[Cause.Both[?]]
          println(s"applying filter on: ${c.failures} -> $res")
          bldr += (c.failures -> res)
          res
        }

        println(s"\nfiltered cause: $c")
        zio.test.assert(c)(Assertion.equalTo(c1)) &&
        zio.test.assert(bldr.result()) {
          equalTo {
            Seq(
              Seq("foo")        -> true,
              Seq("bar")        -> true,
              Seq("baz")        -> true,
              Seq("bar", "baz") -> false
            )
          }
        }
      }
    )
  ) @@ samples(10)

  val causes: Gen[Any, Cause[String]] =
    Gen.causes(Gen.string, Gen.string.map(s => new RuntimeException(s)))

  val equalCauses: Gen[Any, (Cause[String], Cause[String])] =
    (causes <*> causes <*> causes).flatMap { case (a, b, c) =>
      Gen.elements(
        (a, a),
        (Then(Then(a, b), c), Then(a, Then(b, c))),
        (Then(a, Both(b, c)), Both(Then(a, b), Then(a, c))),
        (Both(Both(a, b), c), Both(a, Both(b, c))),
        (Both(Then(a, c), Then(b, c)), Then(Both(a, b), c)),
        (Both(a, b), Both(b, a)),
        (a, Cause.stackless(a)),
        (a, Then(a, Cause.empty)),
        (a, Both(a, Cause.empty))
      )
    }

  val errorCauseFunctions: Gen[Any, String => Cause[String]] =
    Gen.function(causes)

  val errors: Gen[Any, String] =
    Gen.string

  val fiberIds: Gen[Any, FiberId] =
    Gen.int.zipWith(Gen.int)(FiberId(_, _, Trace.empty))

  val throwables: Gen[Any, Throwable] =
    Gen.throwable
}
