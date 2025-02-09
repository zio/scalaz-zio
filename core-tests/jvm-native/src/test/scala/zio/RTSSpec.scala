package zio

import zio.test.Assertion._
import zio.test.TestAspect.{nonFlaky, silent}
import zio.test._

import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicInteger

object RTSSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec = suite("Blocking specs (to be migrated to ZIOSpecJvm)")(
    test("blocking caches threads") {

      def runAndTrack(ref: Ref[Set[Thread]]): ZIO[Any, Nothing, Boolean] =
        ZIO.blocking {
          ZIO
            .succeed(Thread.currentThread())
            .flatMap(thread => ref.modify(set => (set.contains(thread), set + thread))) <* ZIO
            .sleep(1.millis)
        }

      val io =
        for {
          accum <- Ref.make(Set.empty[Thread])
          b     <- runAndTrack(accum).repeatUntil(_ == true)
        } yield b
      assertZIO(Live.live(io))(isTrue)
    },
    test("blocking IO is effect blocking") {
      for {
        done  <- Ref.make(false)
        start <- Promise.make[Nothing, Unit]
        fiber <- ZIO.attemptBlockingInterrupt {
                   Unsafe.unsafe { implicit unsafe => start.unsafe.done(ZIO.unit); Thread.sleep(60L * 60L * 1000L) }
                 }
                   .ensuring(done.set(true))
                   .fork
        _     <- start.await
        res   <- fiber.interrupt
        value <- done.get
      } yield assert(res)(isInterrupted) && assert(value)(isTrue)
    } @@ nonFlaky,
    test("cancelation is guaranteed") {
      val io =
        for {
          release <- Promise.make[Nothing, Int]
          latch   <- Promise.make[Nothing, Unit]
          async = ZIO.asyncInterruptUnsafe[Any, Nothing, Unit] { implicit unsafe => _ =>
                    latch.unsafe.done(ZIO.unit); Left(release.succeed(42).unit)
                  }
          fiber  <- async.fork
          _      <- latch.await
          _      <- fiber.interrupt.fork
          result <- release.await
        } yield result == 42

      assertZIO(io)(isTrue)
    } @@ nonFlaky,
    test("Fiber dump looks correct") {
      for {
        promise <- Promise.make[Nothing, Int]
        fiber   <- promise.await.fork
        dump    <- fiber.dump
        dumpStr <- dump.prettyPrint
        _       <- Console.printLine(dumpStr)
      } yield assert(dumpStr)(anything)
    } @@ silent,
    test("interruption causes") {
      for {
        queue    <- Queue.bounded[Int](100)
        producer <- queue.offer(42).forever.fork
        rez      <- producer.interrupt
        _        <- Console.printLine(rez.foldExit(_.prettyPrint, _ => ""))
      } yield assert(rez)(anything)
    } @@ zioTag(interruption) @@ silent,
    test("interruption of unending acquireReleaseWith") {
      val io =
        for {
          startLatch <- Promise.make[Nothing, Int]
          exitLatch  <- Promise.make[Nothing, Int]
          acquireRelease = ZIO.acquireReleaseExitWith(ZIO.succeed(21))((r: Int, exit: Exit[Any, Any]) =>
                             if (exit.isInterrupted) exitLatch.succeed(r)
                             else ZIO.die(new Error("Unexpected case"))
                           )(a => startLatch.succeed(a) *> ZIO.never *> ZIO.succeed(1))
          fiber      <- acquireRelease.fork
          startValue <- startLatch.await
          _          <- fiber.interrupt.fork
          exitValue  <- exitLatch.await
        } yield (startValue + exitValue) == 42

      assertZIO(io)(isTrue)
    } @@ zioTag(interruption) @@ nonFlaky,
    test("deadlock regression 1") {
      import java.util.concurrent.Executors

      val rts = Runtime.default
      val e   = Executors.newSingleThreadExecutor()

      (0 until 1000).foreach { _ =>
        Unsafe.unsafe { implicit unsafe =>
          rts.unsafe.run {
            ZIO.async[Any, Nothing, Int] { k =>
              val c: Callable[Unit] = () => k(ZIO.succeed(1))
              val _                 = e.submit(c)
            }
          }.getOrThrowFiberFailure()
        }
      }

      assertZIO(ZIO.attempt(e.shutdown()))(isUnit)
    } @@ zioTag(regression),
    test("second callback call is ignored") {
      for {
        _ <- ZIO.async[Any, Throwable, Int] { k =>
               k(ZIO.succeed(42))
               Thread.sleep(500)
               k(ZIO.succeed(42))
             }
        res <- ZIO.async[Any, Throwable, String] { k =>
                 Thread.sleep(1000)
                 k(ZIO.succeed("ok"))
               }
      } yield assert(res)(equalTo("ok"))
    },
    test("check interruption regression 1") {
      val c = new AtomicInteger(0)

      def test =
        ZIO
          .attempt(if (c.incrementAndGet() <= 1) throw new RuntimeException("x"))
          .forever
          .ensuring(ZIO.unit)
          .either
          .forever

      val zio =
        for {
          f <- test.fork
          c <- (ZIO.succeed[Int](c.get) <* Clock.sleep(1.millis))
                 .repeatUntil(_ >= 1) <* f.interrupt
        } yield c

      assertZIO(Live.live(zio))(isGreaterThanEqualTo(1))
    } @@ zioTag(interruption, regression)
  )
}
