package zio

import zio.test.Assertion._
import zio.test.TestAspect.{exceptJS, nonFlaky}
import zio.test._

object RefSpec extends ZIOBaseSpec {

  def spec = suite("RefSpec")(
    suite("Atomic")(
      test("race") {
        for {
          _ <- ZIO.unit.race(ZIO.unit)
        } yield assertCompletes
      } @@ exceptJS(nonFlaky),
      test("get") {
        for {
          ref   <- Ref.make(current)
          value <- ref.get
        } yield assert(value)(equalTo(current))
      },
      test("getAndSet") {
        for {
          ref    <- Ref.make(current)
          value1 <- ref.getAndSet(update)
          value2 <- ref.get
        } yield assert(value1)(equalTo(current)) && assert(value2)(equalTo(update))
      },
      test("getAndUpdate") {
        for {
          ref    <- Ref.make(current)
          value1 <- ref.getAndUpdate(_ => update)
          value2 <- ref.get
        } yield assert(value1)(equalTo(current)) && assert(value2)(equalTo(update))
      },
      test("getAndUpdateSome") {
        for {
          ref    <- Ref.make[State](Active)
          value1 <- ref.getAndUpdateSome { case Closed => Changed }
          value2 <- ref.get
        } yield assert(value1)(equalTo(Active)) && assert(value2)(equalTo(Active))
      },
      test("getAndUpdateSome twice") {
        for {
          ref    <- Ref.make[State](Active)
          value1 <- ref.getAndUpdateSome { case Active => Changed }
          value2 <- ref.getAndUpdateSome {
                      case Active  => Changed
                      case Changed => Closed
                    }
          value3 <- ref.get
        } yield assert(value1)(equalTo(Active)) && assert(value2)(equalTo(Changed)) && assert(value3)(equalTo(Closed))
      },
      test("modify") {
        for {
          ref   <- Ref.make(current)
          r     <- ref.modify(_ => ("hello", update))
          value <- ref.get
        } yield assert(r)(equalTo("hello")) && assert(value)(equalTo(update))
      },
      test("modifySome") {
        for {
          ref   <- Ref.make[State](Active)
          value <- ref.modifySome("State doesn't change") { case Closed => ("active", Active) }
        } yield assert(value)(equalTo("State doesn't change"))
      },
      test("modifySome twice") {
        for {
          ref    <- Ref.make[State](Active)
          value1 <- ref.modifySome("doesn't change the state") { case Active => ("changed", Changed) }
          value2 <- ref.modifySome("doesn't change the state") {
                      case Active  => ("changed", Changed)
                      case Changed => ("closed", Closed)
                    }
        } yield assert(value1)(equalTo("changed")) && assert(value2)(equalTo("closed"))
      },
      test("set") {
        for {
          ref   <- Ref.make(current)
          _     <- ref.set(update)
          value <- ref.get
        } yield assert(value)(equalTo(update))
      },
      test("toString") {
        assertZIO(Ref.make(42).map(_.toString))(equalTo("Ref(42)"))
      },
      test("update") {
        for {
          ref   <- Ref.make(current)
          _     <- ref.update(_ => update)
          value <- ref.get
        } yield assert(value)(equalTo(update))
      },
      test("updateAndGet") {
        for {
          ref   <- Ref.make(current)
          value <- ref.updateAndGet(_ => update)
        } yield assert(value)(equalTo(update))
      },
      test("updateSome") {
        for {
          ref   <- Ref.make[State](Active)
          _     <- ref.updateSome { case Closed => Changed }
          value <- ref.get
        } yield assert(value)(equalTo(Active))
      },
      test("updateSome twice") {
        for {
          ref    <- Ref.make[State](Active)
          _      <- ref.updateSome { case Active => Changed }
          value1 <- ref.get
          _ <- ref.updateSomeAndGet {
                 case Active  => Changed
                 case Changed => Closed
               }
          value2 <- ref.get
        } yield assert(value1)(equalTo(Changed)) && assert(value2)(equalTo(Closed))
      },
      test("updateSomeAndGet") {
        for {
          ref   <- Ref.make[State](Active)
          value <- ref.updateSomeAndGet { case Closed => Changed }
        } yield assert(value)(equalTo(Active))
      },
      test("updateSomeAndGet twice") {
        for {
          ref    <- Ref.make[State](Active)
          value1 <- ref.updateSomeAndGet { case Active => Changed }
          value2 <- ref.updateSomeAndGet {
                      case Active  => Changed
                      case Changed => Closed
                    }
        } yield assert(value1)(equalTo(Changed)) && assert(value2)(equalTo(Closed))
      },
      test("getAndIncrement[Int]") {
        for {
          ref    <- Ref.make(1)
          value1 <- ref.getAndIncrement
          value2 <- ref.get
        } yield assertTrue(value1 == 1, value2 == 2)
      },
      test("getAndIncrement[Long]") {
        for {
          ref    <- Ref.make(1L)
          value1 <- ref.getAndIncrement
          value2 <- ref.get
        } yield assertTrue(value1 == 1L, value2 == 2L)
      },
      test("getAndDecrement[Int]") {
        for {
          ref    <- Ref.make(1)
          value1 <- ref.getAndDecrement
          value2 <- ref.get
        } yield assertTrue(value1 == 1, value2 == 0)
      },
      test("getAndDecrement[Long]") {
        for {
          ref    <- Ref.make(1L)
          value1 <- ref.getAndDecrement
          value2 <- ref.get
        } yield assertTrue(value1 == 1L, value2 == 0L)
      },
      test("getAndAdd[Int]") {
        for {
          ref    <- Ref.make(1)
          value1 <- ref.getAndAdd(10)
          value2 <- ref.get
        } yield assertTrue(value1 == 1, value2 == 11)
      },
      test("getAndAdd[Long]") {
        for {
          ref    <- Ref.make(1L)
          value1 <- ref.getAndAdd(10)
          value2 <- ref.getAndAdd(20L)
          value3 <- ref.get
        } yield assertTrue(value1 == 1L, value2 == 11L, value3 == 31L)
      },
      test("incrementAndGet[Int]") {
        for {
          ref   <- Ref.make(1)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2)
      },
      test("incrementAndGet[Long]") {
        for {
          ref   <- Ref.make(1L)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2L)
      },
      test("decrementAndGet[Int]") {
        for {
          ref   <- Ref.make(1)
          value <- ref.decrementAndGet
        } yield assertTrue(value == 0)
      },
      test("decrementAndGet[Long]") {
        for {
          ref   <- Ref.make(1L)
          value <- ref.decrementAndGet
        } yield assertTrue(value == 0L)
      },
      test("addAndGet[Int]") {
        for {
          ref   <- Ref.make(1)
          value <- ref.addAndGet(10)
        } yield assertTrue(value == 11)
      },
      test("addAndGet[Long]") {
        for {
          ref    <- Ref.make(1L)
          value1 <- ref.addAndGet(10)
          value2 <- ref.addAndGet(20L)
        } yield assertTrue(value1 == 11L, value2 == 31L)
      },
      test("incrementAndGet[Byte]") {
        for {
          ref   <- Ref.make(1.toByte)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2.toByte)
      },
      test("incrementAndGet[Char]") {
        for {
          ref   <- Ref.make(1.toChar)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2.toChar)
      },
      test("incrementAndGet[Short]") {
        for {
          ref   <- Ref.make(1.toShort)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2.toShort)
      },
      test("incrementAndGet[Double]") {
        for {
          ref   <- Ref.make(1.0d)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2.0d)
      },
      test("incrementAndGet[Float]") {
        for {
          ref   <- Ref.make(1.0f)
          value <- ref.incrementAndGet
        } yield assertTrue(value == 2.0f)
      },
      test("incrementAndGet[BigInt]") {
        for {
          ref   <- Ref.make(BigInt(1))
          value <- ref.incrementAndGet
        } yield assertTrue(value == BigInt(2))
      },
      test("Ref[String].incrementAndGet does not compile") {
        val result   = typeCheck(""" Ref.make("").incrementAndGet """)
        val expected = "value incrementAndGet is not a member of zio.UIO[zio.Ref[String]]"
        assertZIO(result)(isLeft(startsWithString(expected)))
      }
    )
  )

  val (current, update) = ("value", "new value")

  sealed trait State
  case object Active  extends State
  case object Changed extends State
  case object Closed  extends State
}
