package zio

import zio.test._
import zio.test.TestAspect.nonFlaky

object ZPoolSpec extends ZIOBaseSpec {
  def spec =
    suite("ZPoolSpec") {
      test("preallocates pool items") {
        for {
          count <- Ref.make(0)
          get    = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
          _     <- ZPool.make(get, 10)
          _     <- count.get.repeatUntil(_ == 10)
          value <- count.get
        } yield assertTrue(value == 10)
      } +
        test("cleans up items when shut down") {
          for {
            count <- Ref.make(0)
            get    = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            scope <- Scope.make
            _     <- scope.extend(ZPool.make(get, 10))
            _     <- count.get.repeatUntil(_ == 10)
            _     <- scope.close(Exit.succeed(()))
            value <- count.get
          } yield assertTrue(value == 0)
        } +
        test("acquire one item") {
          for {
            count <- Ref.make(0)
            get    = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            pool  <- ZPool.make(get, 10)
            _     <- count.get.repeatUntil(_ == 10)
            item  <- ZIO.scoped(pool.get)
          } yield assertTrue(item == 1)
        } +
        test("reports failures via get") {
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1).flatMap(ZIO.fail(_)))(_ => count.update(_ - 1))
            pool   <- ZPool.make[Scope, Int, Any](get, 10)
            _      <- count.get.repeatUntil(_ == 10)
            values <- ZIO.collectAll(List.fill(10)(pool.get.flip))
          } yield assertTrue(values == List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        } +
        test("blocks when item not available") {
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            pool   <- ZPool.make(get, 10)
            _      <- count.get.repeatUntil(_ == 10)
            _      <- ZIO.collectAll(List.fill(10)(pool.get))
            result <- Live.live(ZIO.scoped(pool.get).disconnect.timeout(1.millis))
          } yield assertTrue(result == None)
        } +
        test("reuse released items") {
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            pool   <- ZPool.make(get, 10)
            _      <- ZIO.scoped(pool.get).repeatN(99)
            result <- count.get
          } yield assertTrue(result == 10)
        } +
        test("invalidate item") {
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            pool   <- ZPool.make(get, 10)
            _      <- count.get.repeatUntil(_ == 10)
            _      <- pool.invalidate(1)
            result <- ZIO.scoped(pool.get)
          } yield assertTrue(result == 2)
        } +
        test("compositional retry") {
          def cond(i: Int) = if (i <= 10) ZIO.fail(i) else ZIO.succeed(i)
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1).flatMap(cond(_)))(_ => count.update(_ - 1))
            pool   <- ZPool.make(get, 10)
            _      <- count.get.repeatUntil(_ == 10)
            result <- ZIO.scoped(pool.get).eventually
          } yield assertTrue(result == 11)
        } +
        test("max pool size") {
          for {
            promise <- Promise.make[Nothing, Unit]
            count   <- Ref.make(0)
            get      = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            pool    <- ZPool.make(get, 10 to 15, 60.seconds)
            _       <- ZIO.scoped(pool.get.flatMap(_ => promise.await)).fork.repeatN(14)
            _       <- count.get.repeatUntil(_ == 15)
            _       <- promise.succeed(())
            max     <- count.get
            _       <- TestClock.adjust(60.seconds)
            min     <- count.get
          } yield assertTrue(min == 10 && max == 15)
        } +
        test("shutdown robustness") {
          for {
            count  <- Ref.make(0)
            get     = ZIO.acquireRelease(count.updateAndGet(_ + 1))(_ => count.update(_ - 1))
            scope  <- Scope.make
            pool   <- scope.extend(ZPool.make(get, 10))
            _      <- ZIO.scoped(pool.get).fork.repeatN(99)
            _      <- scope.close(Exit.succeed(()))
            result <- count.get
          } yield assertTrue(result == 0)
        } @@ nonFlaky
    }
}
