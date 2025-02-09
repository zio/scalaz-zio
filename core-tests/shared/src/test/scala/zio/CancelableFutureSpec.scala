package zio

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._

import scala.concurrent.Future

object CancelableFutureSpec extends ZIOBaseSpec {

  import ZIOTag._

  def roundtrip[R, A](zio: RIO[R, A]): RIO[R, A] =
    for {
      future <- zio.toFuture
      a      <- ZIO.fromFuture(_ => future)
    } yield a

  def spec =
    suite("CancelableFutureSpec")(
      test("auto-kill regression") {
        val effect = ZIO.unit.delay(1.millisecond)

        val roundtrip = for {
          rt <- ZIO.runtime[Any]
          _  <- ZIO.fromFuture(_ => Unsafe.unsafe(implicit unsafe => rt.unsafe.runToFuture(effect)))
        } yield ()

        val result = roundtrip.orDie.as(0)

        assertZIO(Live.live(result))(equalTo(0))
      } @@ exceptJS(nonFlaky) @@ zioTag(supervision, regression),
      test("auto-kill regression 2") {
        val effect = Clock.nanoTime.map(_.toString()).delay(10.millisecond)

        val roundtrip = for {
          rt <- ZIO.runtime[Any]
          _  <- ZIO.fromFuture(_ => Unsafe.unsafe(implicit unsafe => rt.unsafe.runToFuture(effect)))
        } yield ()

        val result = roundtrip.orDie.forever

        assertZIO(Live.live(result.timeout(1.seconds)))(isNone)
      } @@ zioTag(supervision, regression),
      test("unsafeRunToFuture") {
        for {
          runtime <- ZIO.runtime[Any]
          _ <- ZIO.fromFuture { implicit ec =>
                 Future.traverse(0 to 1000) { _ =>
                   Unsafe.unsafe { implicit unsafe =>
                     runtime.unsafe.runToFuture(ZIO.unit)
                   }
                 }
               }
        } yield assertCompletes
      } @@ exceptJS(nonFlaky),
      test("unsafeRunToFuture interruptibility") {
        for {
          runtime <- ZIO.runtime[Any]
          f        = Unsafe.unsafe(implicit unsafe => runtime.unsafe.runToFuture(ZIO.never))
          _       <- ZIO.succeed(f.cancel())
          r       <- ZIO.fromFuture(_ => f).exit
        } yield assert(r.isSuccess)(isFalse) // not interrupted, as the Future fails when the effect in interrupted.
      } @@ exceptJS(nonFlaky) @@ zioTag(interruption),
      test("roundtrip preserves interruptibility") {
        for {
          start <- Promise.make[Nothing, Unit]
          end   <- Promise.make[Nothing, Int]
          fiber <- roundtrip((start.succeed(()) *> ZIO.infinity).onInterrupt(end.succeed(42))).fork
          _     <- start.await
          _     <- fiber.interrupt
          value <- end.await
        } yield assert(value)(equalTo(42))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("survives roundtrip without being auto-killed") {
        val exception = new Exception("Uh oh")
        val value     = 42

        for {
          failure <- roundtrip(ZIO.fail(exception)).either
          success <- roundtrip(ZIO.succeed(value)).either
        } yield assert(failure)(isLeft(equalTo(exception))) && assert(success)(isRight(equalTo(value)))
      } @@ zioTag(supervision) @@ exceptJS(nonFlaky),
      test("interrupts the underlying task on cancel") {
        for {
          p  <- Promise.make[Nothing, Unit]
          p2 <- Promise.make[Nothing, Int]
          f <- (p.succeed(()) *> ZIO.never)
                 .onInterrupt(p2.succeed(42))
                 .toFuture
          _    <- p.await
          _    <- ZIO.fromFuture(_ => f.cancel())
          test <- p2.await
        } yield assert(test)(equalTo(42))
      } @@ zioTag(interruption) @@ exceptJS(nonFlaky),
      test("cancel returns the exit reason") {
        val t = new Exception("test")

        for {
          f1 <- ZIO.succeed(42).toFuture
          f2 <- ZIO.fail(t).toFuture
          _  <- Fiber.fromFuture(f1).await
          _  <- Fiber.fromFuture(f2).await
          e1 <- ZIO.fromFuture(_ => f1.cancel())
          e2 <- ZIO.fromFuture(_ => f2.cancel())
        } yield assert(e1.isSuccess)(isTrue) && assert(e2.isSuccess)(isFalse)
      } @@ exceptJS(nonFlaky),
      test("is a scala.concurrent.Future") {
        for {
          f <- ZIO.succeed(42).toFuture
          v <- ZIO.fromFuture(_ => f)
        } yield {
          assert(v)(equalTo(42))
        }
      }
    ) @@ zioTag(future) @@ TestAspect.fibers
}
