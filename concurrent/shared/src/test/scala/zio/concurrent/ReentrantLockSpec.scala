package zio.concurrent

import zio.test.Assertion._
import zio.test.TestAspect._
import zio.test._
import zio._

object ReentrantLockSpec extends DefaultRunnableSpec {
  val spec =
    suite("ReentrantLockSpec")(
      testM("1 lock") {
        for {
          lock  <- ReentrantLock.make()
          count <- lock.withLock.use(UIO.succeed(_))
        } yield assert(count)(equalTo(1))
      },
      testM("2 locks") {
        for {
          lock  <- ReentrantLock.make()
          count <- lock.withLock.use(_ => lock.withLock.use(UIO.succeed(_)))
        } yield assert(count)(equalTo(2))
      },
      testM("2 locks from different fibers") {
        for {
          lock    <- ReentrantLock.make()
          mlatch  <- Promise.make[Nothing, Unit]
          wlatch  <- Promise.make[Nothing, Unit]
          _       <- lock.withLock.use(count => mlatch.succeed(()) as count).fork
          _       <- mlatch.await
          reader2 <- lock.withLock.use(count => wlatch.succeed(()) as count).fork
          _       <- wlatch.await
          count2  <- reader2.join
        } yield assert(count2)(equalTo(1))
      },
      testM("Cleans up interrupted waiters") {
        for {
          lock     <- ReentrantLock.make()
          mlatch   <- Promise.make[Nothing, Unit]
          latch1   <- CountdownLatch.make(2)
          wlatch   <- Promise.make[Nothing, Unit]
          wlatch2  <- Promise.make[Nothing, Unit]
          ref      <- Ref.make(0)
          _        <- lock.withLock.use(_ => mlatch.succeed(()) *> wlatch.await).fork
          _        <- mlatch.await
          f1       <- (latch1.countDown *> lock.withLock.use(_ => ref.update(_ + 10))).fork
          _        <- (latch1.countDown *> lock.withLock.use(_ => ref.update(_ + 10) <* wlatch2.succeed(()))).fork
          _        <- latch1.await
          waiters1 <- lock.queueLength
          _        <- f1.interrupt
          _        <- wlatch.succeed(()) *> wlatch2.await
          waiters2 <- lock.queueLength
          cnt      <- ref.get
        } yield assert(waiters1)(equalTo(2)) && assert(waiters2)(equalTo(0)) && assert(cnt)(equalTo(10))
      } @@ flaky,
      testM("Fairness assigns lock to fibers in order") {
        val f1 = (x: Int) => x * 2
        val f2 = (x: Int) => x - 10
        val f3 = (x: Int) => x / 4
        val f4 = (x: Int) => x + 100

        val f = f1.andThen(f2).andThen(f3).andThen(f4)

        for {
          lock  <- ReentrantLock.make(true)
          ref   <- Ref.make(1)
          p0    <- Promise.make[Nothing, Unit]
          _     <- lock.withLock.use(_ => p0.await).fork
          p1    <- Promise.make[Nothing, Unit]
          f1    <- (p1.succeed(()) *> lock.withLock.use(_ => ref.update(f1))).fork
          p2    <- Promise.make[Nothing, Unit]
          f2    <- (p1.await *> p2.succeed(()) *> lock.withLock.use(_ => ref.update(f2))).fork
          p3    <- Promise.make[Nothing, Unit]
          f3    <- (p2.await *> p3.succeed(()) *> lock.withLock.use(_ => ref.update(f3))).fork
          f4    <- (p3.await *> lock.withLock.use(_ => ref.update(f4))).fork
          fibers = List(f1, f2, f3, f4)
          _     <- p0.succeed(())
          _     <- ZIO.foreach_(fibers)(_.join)
          x     <- ref.get
        } yield assert(x)(equalTo(f(1)))
      } @@ flaky,
      testM("Assigns lock to fibers randomly") {
        val f1 = (x: Int) => x * 2
        val f2 = (x: Int) => x - 10
        val f3 = (x: Int) => x / 4
        val f4 = (x: Int) => x + 100

        val f = f1.andThen(f2).andThen(f3).andThen(f4)

        val program = for {
          lock  <- ReentrantLock.make()
          ref   <- Ref.make(1)
          p0    <- Promise.make[Nothing, Unit]
          latch <- CountdownLatch.make(4)
          _     <- lock.withLock.use(_ => p0.await).fork
          f1    <- (latch.countDown *> lock.withLock.use(_ => ref.update(f1))).fork
          f2    <- (latch.countDown *> lock.withLock.use(_ => ref.update(f2))).fork
          f3    <- (latch.countDown *> lock.withLock.use(_ => ref.update(f3))).fork
          f4    <- (latch.countDown *> lock.withLock.use(_ => ref.update(f4))).fork
          fibers = List(f1, f2, f3, f4)
          _     <- latch.await
          _     <- p0.succeed(())
          _     <- ZIO.foreach_(fibers)(_.join)
          x     <- ref.get
        } yield x == f(1)

        for {
          results <- ZIO.collectAll(ZIO.replicate(100)(program))
        } yield assert(results.collect { case true => true }.size)(isLessThan(100))
      }
    )
}
