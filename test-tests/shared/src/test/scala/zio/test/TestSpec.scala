package zio.test

import zio._
import zio.Clock._
import zio.internal.macros.StringUtils.StringOps
import zio.stm.STM
import zio.test.Assertion._
import zio.test.TestAspect.{failing, timeout}
import zio.test.TestUtils.execute

object TestSpec extends ZIOBaseSpec {

  def spec = suite("TestSpec")(
    test("assertZIO works correctly") {
      assertZIO(nanoTime)(equalTo(0L))
    },
    test("test error is test failure") {
      for {
        _      <- ZIO.fail("fail")
        result <- ZIO.succeed("succeed")
      } yield assert(result)(equalTo("succeed"))
    } @@ failing,
    test("rendering error is test failure") {
      case class Foo(a: Int) {
        override def productPrefix: String =
          throw new Exception("Boooom!")
      }
      val spec = test("properly report bug") {
        val foo1 = Foo(2)
        assertTrue(foo1 == foo1)
      }
      for {
        summary <- execute(spec)
      } yield assert(summary.fail)(equalTo(1)) &&
        assert(summary.failureDetails.unstyled)(containsString("Boooom!"))
    },
    test("test is polymorphic in error type") {
      for {
        _      <- ZIO.attempt(())
        result <- ZIO.succeed("succeed")
      } yield assert(result)(equalTo("succeed"))
    },
    test("test suspends effects") {
      var n = 0
      val spec = suite("suite")(
        test("test1") {
          n += 1
          ZIO.succeed(assertCompletes)
        },
        test("test2") {
          n += 1
          ZIO.succeed(assertCompletes)
        }
      ).filterLabels(_ == "test2").get
      for {
        _ <- execute(spec)
      } yield assert(n)(equalTo(1))
    },
    test("test does not wait to interrupt children") {
      for {
        promise <- Promise.make[Nothing, Unit]
        _       <- (promise.succeed(()) *> Live.live(ZIO.sleep(20.seconds))).uninterruptible.fork
        _       <- promise.await
      } yield assertCompletes
    } @@ timeout(10.seconds),
    test("scoped effects can be tested") {
      for {
        ref   <- Ref.make(false)
        _     <- ZIO.acquireRelease(ref.set(true))(_ => ref.set(false))
        value <- ref.get
      } yield assert(value)(isTrue)
    },
    test("transactional effects can be tested") {
      for {
        message <- STM.succeed("Hello from an STM transaction!")
      } yield assert(message)(anything)
    },
    test("either values can be tested") {
      for {
        message <- Right("Hello from an Either value!")
      } yield assert(message)(anything)
    },
    test("fail-fast assertion by automatic lifting to ZIO") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 for {
                   _ <- ref.update(_ + 1)
                   _ <- assertTrue(1 == 2)
                   _ <- ref.update(_ + 1)
                 } yield assertTrue(3 == 4)
               }
        summary <- execute(spec)
        count   <- ref.get
      } yield assert(count)(equalTo(1)) &&
        assert(summary.fail)(equalTo(1)) &&
        assert(summary.failureDetails.unstyled)(
          containsString("1 == 2") &&
            not(containsString("3 == 4"))
        )
    },
    test("composed assertions are lifted to ZIO and fully evaluated") {
      for {
        ref <- Ref.make(0)
        spec = test("test") {
                 for {
                   _ <- ref.update(_ + 1)
                   _ <- assertTrue(1 == 2) && assertTrue(3 == 4)
                   _ <- ref.update(_ + 1)
                 } yield assertCompletes
               }
        summary <- execute(spec)
        count   <- ref.get
      } yield assert(count)(equalTo(1)) &&
        assert(summary.fail)(equalTo(1)) &&
        assert(summary.failureDetails.unstyled)(
          containsString("1 == 2") &&
            containsString("3 == 4")
        )
    },
    suite("suites can be effectual") {
      ZIO.succeed {
        Chunk(
          test("a test in an effectual suite")(assertCompletes),
          test("another test in an effectual suite")(assertCompletes)
        )
      }
    },
    test("test does not interrupt fibers forked outside scope of test")(
      for {
        ref <- Ref.make[Option[Fiber[Any, Any]]](None)
        fork = ZIO.never.forkDaemon.flatMap(fiber => ref.set(Some(fiber)))
        spec = test("test") {
                 for {
                   _ <- fork
                 } yield assertCompletes
               }
        _     <- execute(spec)
        fiber <- ref.get.some
        exit  <- fiber.poll
      } yield assert(exit)(isNone)
    )
  )
}
