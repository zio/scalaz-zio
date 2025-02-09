package zio

import zio.QueueSpecUtil._
import zio.test.Assertion._
import zio.test.TestAspect.{jvm, exceptJS, nonFlaky, samples, sequential}
import zio.test._

object QueueSpec extends ZIOBaseSpec {
  import ZIOTag._

  def spec = suite("QueueSpec")(
    test("sequential offer and take") {
      for {
        queue <- Queue.bounded[Int](100)
        o1    <- queue.offer(10)
        v1    <- queue.take
        o2    <- queue.offer(20)
        v2    <- queue.take
      } yield assert(v1)(equalTo(10)) &&
        assert(v2)(equalTo(20)) &&
        assert(o1)(isTrue) &&
        assert(o2)(isTrue)
    },
    test("sequential take and offer") {
      for {
        queue <- Queue.bounded[String](100)
        f1    <- queue.take.zipWith(queue.take)(_ + _).fork
        _     <- queue.offer("don't ") *> queue.offer("give up :D")
        v     <- f1.join
      } yield assert(v)(equalTo("don't give up :D"))
    },
    test("parallel takes and sequential offers ") {
      for {
        queue <- Queue.bounded[Int](10)
        f     <- ZIO.forkAll(List.fill(10)(queue.take))
        values = Range.inclusive(1, 10).toList
        _     <- values.map(queue.offer).foldLeft[UIO[Boolean]](ZIO.succeed(false))(_ *> _)
        v     <- f.join
      } yield assert(v.toSet)(equalTo(values.toSet))
    },
    test("parallel offers and sequential takes") {
      for {
        queue <- Queue.bounded[Int](10)
        values = Range.inclusive(1, 10).toList
        f     <- ZIO.forkAll(values.map(queue.offer))
        _     <- waitForSize(queue, 10)
        out   <- Ref.make[List[Int]](Nil)
        _     <- queue.take.flatMap(i => out.update(i :: _)).repeatN(9)
        l     <- out.get
        _     <- f.join
      } yield assert(l.toSet)(equalTo(values.toSet))
    },
    test("offers are suspended by back pressure") {
      for {
        queue        <- Queue.bounded[Int](10)
        _            <- queue.offer(1).repeatN(9)
        refSuspended <- Ref.make[Boolean](true)
        f            <- (queue.offer(2) *> refSuspended.set(false)).fork
        _            <- waitForSize(queue, 11)
        isSuspended  <- refSuspended.get
        _            <- f.interrupt
      } yield assertTrue(isSuspended)
    },
    test("back pressured offers are retrieved") {
      for {
        queue <- Queue.bounded[Int](5)
        values = Range.inclusive(1, 10).toList
        f     <- ZIO.forkAll(values.map(queue.offer))
        _     <- waitForSize(queue, 10)
        out   <- Ref.make[List[Int]](Nil)
        _     <- queue.take.flatMap(i => out.update(i :: _)).repeatN(9)
        l     <- out.get
        _     <- f.join
      } yield assert(l.toSet)(equalTo(values.toSet))
    },
    test("take interruption") {
      for {
        queue <- Queue.bounded[Int](100)
        f     <- queue.take.fork
        _     <- waitForSize(queue, -1)
        _     <- f.interrupt
        size  <- queue.size
      } yield assert(size)(equalTo(0))
    } @@ zioTag(interruption),
    test("offer interruption") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offer(1)
        _     <- queue.offer(1)
        f     <- queue.offer(1).fork
        _     <- waitForSize(queue, 3)
        _     <- f.interrupt
        size  <- queue.size
      } yield assert(size)(equalTo(2))
    } @@ zioTag(interruption),
    test("queue is ordered") {
      for {
        queue <- Queue.unbounded[Int]
        _     <- queue.offer(1)
        _     <- queue.offer(2)
        _     <- queue.offer(3)
        v1    <- queue.take
        v2    <- queue.take
        v3    <- queue.take
      } yield assert(v1)(equalTo(1)) &&
        assert(v2)(equalTo(2)) &&
        assert(v3)(equalTo(3))
    },
    test("takeAll") {
      for {
        queue <- Queue.unbounded[Int]
        _     <- queue.offer(1)
        _     <- queue.offer(2)
        _     <- queue.offer(3)
        v     <- queue.takeAll
      } yield assert(v)(equalTo(Chunk(1, 2, 3)))
    },
    test("takeAll with empty queue") {
      for {
        queue <- Queue.unbounded[Int]
        c     <- queue.takeAll
        _     <- queue.offer(1)
        _     <- queue.take
        v     <- queue.takeAll
      } yield assert(c)(equalTo(Chunk.empty)) &&
        assert(v)(equalTo(Chunk.empty))
    },
    test("takeAll doesn't return more than the queue size") {
      for {
        queue <- Queue.bounded[Int](4)
        values = List(1, 2, 3, 4)
        _     <- values.map(queue.offer).foldLeft(ZIO.succeed(false))(_ *> _)
        _     <- queue.offer(5).fork
        _     <- waitForSize(queue, 5)
        v     <- queue.takeAll
        c     <- queue.take
      } yield assert(v.toSet)(equalTo(values.toSet)) &&
        assert(c)(equalTo(5))
    },
    test("takeUpTo") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        _     <- queue.offer(20)
        chunk <- queue.takeUpTo(2)
      } yield assert(chunk)(equalTo(Chunk(10, 20)))
    },
    test("takeUpTo with empty queue") {
      for {
        queue <- Queue.bounded[Int](100)
        chunk <- queue.takeUpTo(2)
      } yield assert(chunk.isEmpty)(isTrue)
    },
    test("takeUpTo with empty queue, with max higher than queue size") {
      for {
        queue <- Queue.bounded[Int](100)
        chunk <- queue.takeUpTo(101)
      } yield assert(chunk.isEmpty)(isTrue)
    },
    test("takeUpTo with remaining items") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        _     <- queue.offer(20)
        _     <- queue.offer(30)
        _     <- queue.offer(40)
        chunk <- queue.takeUpTo(2)
      } yield assert(chunk)(equalTo(Chunk(10, 20)))
    },
    test("takeUpTo with not enough items") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        _     <- queue.offer(20)
        _     <- queue.offer(30)
        _     <- queue.offer(40)
        chunk <- queue.takeUpTo(10)
      } yield assert(chunk)(equalTo(Chunk(10, 20, 30, 40)))
    },
    test("takeUpTo 0") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        _     <- queue.offer(20)
        _     <- queue.offer(30)
        _     <- queue.offer(40)
        chunk <- queue.takeUpTo(0)
      } yield assert(chunk.isEmpty)(isTrue)
    },
    test("takeUpTo -1") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        chunk <- queue.takeUpTo(-1)
      } yield assert(chunk.isEmpty)(isTrue)
    },
    test("takeUpTo Int.MaxValue") {
      for {
        queue <- Queue.bounded[Int](100)
        _     <- queue.offer(10)
        chunk <- queue.takeUpTo(Int.MaxValue)
      } yield assert(chunk)(equalTo(Chunk(10)))
    },
    test("multiple takeUpTo") {
      for {
        queue  <- Queue.bounded[Int](100)
        _      <- queue.offer(10)
        _      <- queue.offer(20)
        chunk1 <- queue.takeUpTo(2)
        _      <- queue.offer(30)
        _      <- queue.offer(40)
        chunk2 <- queue.takeUpTo(2)
      } yield assert(chunk1)(equalTo(Chunk(10, 20))) &&
        assert(chunk2)(equalTo(Chunk(30, 40)))
    },
    test("consecutive takeUpTo") {
      for {
        queue  <- Queue.bounded[Int](100)
        _      <- queue.offer(10)
        _      <- queue.offer(20)
        _      <- queue.offer(30)
        _      <- queue.offer(40)
        chunk1 <- queue.takeUpTo(2)
        chunk2 <- queue.takeUpTo(2)
      } yield assert(chunk1)(equalTo(Chunk(10, 20))) &&
        assert(chunk2)(equalTo(Chunk(30, 40)))
    },
    test("takeUpTo doesn't return back-pressured offers") {
      for {
        queue <- Queue.bounded[Int](4)
        values = List(1, 2, 3, 4)
        _     <- values.map(queue.offer).foldLeft(ZIO.succeed(false))(_ *> _)
        f     <- queue.offer(5).fork
        _     <- waitForSize(queue, 5)
        c     <- queue.takeUpTo(5)
        _     <- f.interrupt
      } yield assert(c)(equalTo(Chunk(1, 2, 3, 4)))
    },
    suite("takeBetween")(
      test("returns immediately if there is enough elements") {
        for {
          queue <- Queue.bounded[Int](100)
          _     <- queue.offer(10)
          _     <- queue.offer(20)
          _     <- queue.offer(30)
          res   <- queue.takeBetween(2, 5)
        } yield assert(res)(equalTo(Chunk(10, 20, 30)))
      },
      test("returns an empty list if boundaries are inverted") {
        for {
          queue <- Queue.bounded[Int](100)
          _     <- queue.offer(10)
          _     <- queue.offer(20)
          _     <- queue.offer(30)
          res   <- queue.takeBetween(5, 2)
        } yield assert(res)(isEmpty)
      },
      test("returns an empty list if boundaries are negative") {
        for {
          queue <- Queue.bounded[Int](100)
          _     <- queue.offer(10)
          _     <- queue.offer(20)
          _     <- queue.offer(30)
          res   <- queue.takeBetween(-5, -2)
        } yield assert(res)(isEmpty)
      },
      test("blocks until a required minimum of elements is collected") {
        for {
          queue  <- Queue.bounded[Int](100)
          updater = queue.offer(10).forever
          getter  = queue.takeBetween(5, 10)
          res    <- getter.race(updater)
        } yield assert(res)(hasSize(isGreaterThanEqualTo(5)))
      },
      test("returns elements in the correct order") {
        check(Gen.chunkOf(Gen.int(-10, 10))) { as =>
          for {
            queue <- Queue.bounded[Int](100)
            f     <- ZIO.foreach(as)(queue.offer).fork
            bs    <- queue.takeBetween(as.length, as.length)
            _     <- f.interrupt
          } yield assert(as)(equalTo(bs))
        }
      }
    ),
    suite("takeN")(
      test("returns immediately if there is enough elements") {
        for {
          queue <- Queue.bounded[Int](100)
          _     <- queue.offerAll(List(1, 2, 3, 4, 5))
          res   <- queue.takeN(3)
        } yield assert(res)(equalTo(Chunk(1, 2, 3)))
      },
      test("returns an empty list if a negative number or zero is specified") {
        for {
          queue       <- Queue.bounded[Int](100)
          _           <- queue.offerAll(List(1, 2, 3))
          resNegative <- queue.takeN(-3)
          resZero     <- queue.takeN(0)
        } yield assert(resNegative)(isEmpty) && assert(resZero)(isEmpty)
      },
      test("blocks until the required number of elements is available") {
        for {
          queue  <- Queue.bounded[Int](100)
          updater = queue.offer(10).forever
          getter  = queue.takeN(5)
          res    <- getter.race(updater)
        } yield assert(res)(hasSize(equalTo(5)))
      }
    ),
    test("offerAll with takeAll") {
      for {
        queue <- Queue.bounded[Int](10)
        orders = Chunk.fromIterable(Range.inclusive(1, 10))
        _     <- queue.offerAll(orders)
        _     <- waitForSize(queue, 10)
        l     <- queue.takeAll
      } yield assert(l)(equalTo(orders))
    },
    test("offerAll with takeAll and back pressure") {
      for {
        queue <- Queue.bounded[Int](2)
        orders = Range.inclusive(1, 3).toList
        f     <- queue.offerAll(orders).fork
        size  <- waitForSize(queue, 3)
        c     <- queue.takeAll
        _     <- f.interrupt
      } yield assert(size)(equalTo(3)) &&
        assert(c)(equalTo(Chunk(1, 2)))
    },
    test("offerAll with takeAll and back pressure + interruption") {
      for {
        queue  <- Queue.bounded[Int](2)
        orders1 = Chunk.fromIterable(Range.inclusive(1, 2))
        orders2 = Chunk.fromIterable(Range.inclusive(3, 4))
        _      <- queue.offerAll(orders1)
        f      <- queue.offerAll(orders2).fork
        _      <- waitForSize(queue, 4)
        _      <- f.interrupt
        l1     <- queue.takeAll
        l2     <- queue.takeAll
      } yield assert(l1)(equalTo(orders1)) &&
        assert(l2)(equalTo(Chunk.empty))
    } @@ zioTag(interruption),
    test("offerAll with takeAll and back pressure, check ordering") {
      for {
        queue <- Queue.bounded[Int](64)
        orders = Chunk.fromIterable(Range.inclusive(1, 128))
        f     <- queue.offerAll(orders).fork
        _     <- waitForSize(queue, 128)
        l     <- queue.takeAll
        _     <- f.interrupt
      } yield assert(l)(equalTo(Chunk.fromIterable(Range.inclusive(1, 64))))
    },
    test("offerAll with pending takers") {
      for {
        queue  <- Queue.bounded[Int](50)
        orders  = Range.inclusive(1, 100).toList
        takers <- ZIO.forkAll(List.fill(100)(queue.take))
        _      <- waitForSize(queue, -100)
        _      <- queue.offerAll(orders)
        l      <- takers.join
        s      <- queue.size
      } yield assert(l.toSet)(equalTo(orders.toSet)) &&
        assert(s)(equalTo(0))
    },
    test("offerAll with pending takers, check ordering") {
      for {
        queue  <- Queue.bounded[Int](256)
        orders  = Range.inclusive(1, 128).toList
        takers <- ZIO.forkAll(List.fill(64)(queue.take))
        _      <- waitForSize(queue, -64)
        _      <- queue.offerAll(orders)
        l      <- takers.join
        s      <- queue.size
        values  = orders.take(64)
      } yield assert(l.toSet)(equalTo(values.toSet)) &&
        assert(s)(equalTo(64))
    },
    test("offerAll with pending takers, check ordering of taker resolution") {
      for {
        queue  <- Queue.bounded[Int](200)
        values  = Range.inclusive(1, 100).toList
        takers <- ZIO.forkAll(List.fill(100)(queue.take))
        _      <- waitForSize(queue, -100)
        f      <- ZIO.forkAll(List.fill(100)(queue.take))
        _      <- waitForSize(queue, -200)
        _      <- queue.offerAll(values)
        l      <- takers.join
        s      <- queue.size
        _      <- f.interrupt
      } yield assert(l.toSet)(equalTo(values.toSet)) &&
        assert(s)(equalTo(-100))
    },
    test("offerAll with take and back pressure") {
      for {
        queue <- Queue.bounded[Int](2)
        orders = Range.inclusive(1, 3).toList
        _     <- queue.offerAll(orders).fork
        _     <- waitForSize(queue, 3)
        v1    <- queue.take
        v2    <- queue.take
        v3    <- queue.take
      } yield assert(v1)(equalTo(1)) &&
        assert(v2)(equalTo(2)) &&
        assert(v3)(equalTo(3))
    },
    test("multiple offerAll with back pressure") {
      for {
        queue  <- Queue.bounded[Int](2)
        orders  = Range.inclusive(1, 3).toList
        orders2 = Range.inclusive(4, 5).toList
        _      <- queue.offerAll(orders).fork
        _      <- waitForSize(queue, 3)
        _      <- queue.offerAll(orders2).fork
        _      <- waitForSize(queue, 5)
        v1     <- queue.take
        v2     <- queue.take
        v3     <- queue.take
        v4     <- queue.take
        v5     <- queue.take
      } yield assert(v1)(equalTo(1)) &&
        assert(v2)(equalTo(2)) &&
        assert(v3)(equalTo(3)) &&
        assert(v4)(equalTo(4)) &&
        assert(v5)(equalTo(5))
    },
    test("offerAll + takeAll, check ordering") {
      for {
        queue <- Queue.bounded[Int](1000)
        orders = Range.inclusive(2, 1000).toList
        _     <- queue.offer(1)
        _     <- queue.offerAll(orders)
        _     <- waitForSize(queue, 1000)
        v1    <- queue.takeAll
      } yield assert(v1)(equalTo(Chunk.fromIterable(Range.inclusive(1, 1000))))
    },
    test("combination of offer, offerAll, take, takeAll") {
      for {
        queue <- Queue.bounded[Int](32)
        orders = Chunk.fromIterable(Range.inclusive(3, 35))
        _     <- queue.offer(1)
        _     <- queue.offer(2)
        _     <- queue.offerAll(orders).fork
        _     <- waitForSize(queue, 35)
        v     <- queue.takeAll
        v1    <- queue.take
        v2    <- queue.take
        v3    <- queue.take
      } yield assert(v)(equalTo(Chunk.fromIterable(Range.inclusive(1, 32)))) &&
        assert(v1)(equalTo(33)) &&
        assert(v2)(equalTo(34)) &&
        assert(v3)(equalTo(35))
    },
    test("shutdown with take fiber") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](3)
        f      <- queue.take.fork
        _      <- waitForSize(queue, -1)
        _      <- queue.shutdown
        res    <- f.join.sandbox.either
      } yield assert(res.left.map(_.untraced))(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with offer fiber") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](2)
        _      <- queue.offer(1)
        _      <- queue.offer(1)
        f      <- queue.offer(1).fork
        _      <- waitForSize(queue, 3)
        _      <- queue.shutdown
        res    <- f.join.sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with offer") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](1)
        _      <- queue.shutdown
        res    <- queue.offer(1).sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with take") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](1)
        _      <- queue.shutdown
        res    <- queue.take.sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with takeAll") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](1)
        _      <- queue.shutdown
        res    <- queue.takeAll.sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with takeUpTo") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](1)
        _      <- queue.shutdown
        res    <- queue.takeUpTo(1).sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("shutdown with size") {
      for {
        selfId <- ZIO.fiberId
        queue  <- Queue.bounded[Int](1)
        _      <- queue.shutdown
        res    <- queue.size.sandbox.either
      } yield assert(res)(isLeft(equalTo(Cause.interrupt(selfId))))
    },
    test("back-pressured offer completes after take") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offerAll(List(1, 2))
        f     <- queue.offer(3).fork
        _     <- waitForSize(queue, 3)
        v1    <- queue.take
        v2    <- queue.take
        _     <- f.join
      } yield assert(v1)(equalTo(1)) &&
        assert(v2)(equalTo(2))
    },
    test("back-pressured offer completes after takeAll") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offerAll(List(1, 2))
        f     <- queue.offer(3).fork
        _     <- waitForSize(queue, 3)
        v1    <- queue.takeAll
        _     <- f.join
      } yield assert(v1)(equalTo(Chunk(1, 2)))
    },
    test("back-pressured offer completes after takeUpTo") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offerAll(List(1, 2))
        f     <- queue.offer(3).fork
        _     <- waitForSize(queue, 3)
        v1    <- queue.takeUpTo(2)
        _     <- f.join
      } yield assert(v1)(equalTo(Chunk(1, 2)))
    },
    test("back-pressured offerAll completes after takeAll") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offerAll(List(1, 2))
        f     <- queue.offerAll(List(3, 4, 5)).fork
        _     <- waitForSize(queue, 5)
        v1    <- queue.takeAll
        v2    <- queue.takeAll
        v3    <- queue.takeAll
        _     <- f.join
      } yield assert(v1)(equalTo(Chunk(1, 2))) &&
        assert(v2)(equalTo(Chunk(3, 4))) &&
        assert(v3)(equalTo(Chunk(5)))
    },
    test("sliding strategy with offer") {
      for {
        queue <- Queue.sliding[Int](2)
        _     <- queue.offer(1)
        v1    <- queue.offer(2)
        v2    <- queue.offer(3)
        l     <- queue.takeAll
      } yield assert(l)(equalTo(Chunk(2, 3))) &&
        assert(v1)(isTrue) &&
        assert(v2)(isTrue)
    },
    test("sliding strategy with offerAll") {
      for {
        queue <- Queue.sliding[Int](2)
        v     <- queue.offerAll(List(1, 2, 3))
        size  <- queue.size
      } yield assert(size)(equalTo(2)) &&
        assert(v)(isEmpty)
    },
    test("sliding strategy with enough capacity") {
      for {
        queue <- Queue.sliding[Int](100)
        _     <- queue.offer(1)
        _     <- queue.offer(2)
        _     <- queue.offer(3)
        l     <- queue.takeAll
      } yield assert(l)(equalTo(Chunk(1, 2, 3)))
    },
    test("sliding strategy with offerAll and takeAll") {
      for {
        queue <- Queue.sliding[Int](2)
        v1    <- queue.offerAll(Iterable(1, 2, 3, 4, 5, 6))
        l     <- queue.takeAll
      } yield assert(l)(equalTo(Chunk(5, 6))) &&
        assert(v1)(isEmpty)
    },
    test("awaitShutdown") {
      for {
        queue <- Queue.bounded[Int](3)
        p     <- Promise.make[Nothing, Boolean]
        _     <- (queue.awaitShutdown *> p.succeed(true)).fork
        _     <- queue.shutdown
        res   <- p.await
      } yield assert(res)(isTrue)
    },
    test("multiple awaitShutdown") {
      for {
        queue <- Queue.bounded[Int](3)
        p1    <- Promise.make[Nothing, Boolean]
        p2    <- Promise.make[Nothing, Boolean]
        _     <- (queue.awaitShutdown *> p1.succeed(true)).fork
        _     <- (queue.awaitShutdown *> p2.succeed(true)).fork
        _     <- queue.shutdown
        res1  <- p1.await
        res2  <- p2.await
      } yield assert(res1)(isTrue) &&
        assert(res2)(isTrue)
    },
    test("awaitShutdown when queue is already shutdown") {
      for {
        queue <- Queue.bounded[Int](3)
        _     <- queue.shutdown
        p     <- Promise.make[Nothing, Boolean]
        _     <- (queue.awaitShutdown *> p.succeed(true)).fork
        res   <- p.await
      } yield assert(res)(isTrue)
    },
    test("dropping strategy with offerAll") {
      for {
        capacity <- ZIO.succeed(4)
        queue    <- Queue.dropping[Int](capacity)
        iter      = Range.inclusive(1, 5)
        _        <- queue.offerAll(iter)
        ta       <- queue.takeAll
      } yield assert(ta)(equalTo(Chunk(1, 2, 3, 4)))
    },
    test("dropping strategy with offerAll, check offer returns false") {
      for {
        capacity <- ZIO.succeed(2)
        queue    <- Queue.dropping[Int](capacity)
        v1       <- queue.offerAll(Iterable(1, 2, 3, 4, 5, 6))
        _        <- queue.takeAll
      } yield assert(v1)(equalTo(Chunk(3, 4, 5, 6)))
    },
    test("dropping strategy with offerAll, check ordering") {
      for {
        capacity <- ZIO.succeed(128)
        queue    <- Queue.dropping[Int](capacity)
        iter      = Range.inclusive(1, 256)
        _        <- queue.offerAll(iter)
        ta       <- queue.takeAll
      } yield assert(ta)(equalTo(Chunk.fromIterable(Range.inclusive(1, 128))))
    },
    test("dropping strategy with pending taker") {
      for {
        capacity <- ZIO.succeed(2)
        queue    <- Queue.dropping[Int](capacity)
        iter      = Range.inclusive(1, 4)
        f        <- queue.take.fork
        _        <- waitForSize(queue, -1)
        oa       <- queue.offerAll(iter.toList)
        j        <- f.join
      } yield assert(j)(equalTo(1)) &&
        assert(oa)(equalTo(Chunk(4)))
    },
    test("sliding strategy with pending taker") {
      for {
        capacity <- ZIO.succeed(2)
        queue    <- Queue.sliding[Int](capacity)
        iter      = Range.inclusive(1, 4)
        _        <- queue.take.fork
        _        <- waitForSize(queue, -1)
        oa       <- queue.offerAll(iter.toList)
        t        <- queue.take
      } yield assert(t)(equalTo(3)) &&
        assert(oa)(isEmpty)
    },
    test("sliding strategy, check offerAll returns true") {
      for {
        capacity <- ZIO.succeed(5)
        queue    <- Queue.sliding[Int](capacity)
        iter      = Range.inclusive(1, 3)
        oa       <- queue.offerAll(iter.toList)
      } yield assert(oa)(isEmpty)
    },
    test("bounded strategy, check offerAll returns true") {
      for {
        capacity <- ZIO.succeed(5)
        queue    <- Queue.bounded[Int](capacity)
        iter      = Range.inclusive(1, 3)
        oa       <- queue.offerAll(iter.toList)
      } yield assert(oa)(isEmpty)
    },
    test("poll on empty queue") {
      for {
        queue <- Queue.bounded[Int](5)
        t     <- queue.poll
      } yield assert(t)(isNone)
    },
    test("poll on queue just emptied") {
      for {
        queue <- Queue.bounded[Int](5)
        iter   = Range.inclusive(1, 4)
        _     <- queue.offerAll(iter.toList)
        _     <- queue.takeAll
        t     <- queue.poll
      } yield assert(t)(isNone)
    },
    test("multiple polls") {
      for {
        queue <- Queue.bounded[Int](5)
        iter   = Range.inclusive(1, 2)
        _     <- queue.offerAll(iter.toList)
        t1    <- queue.poll
        t2    <- queue.poll
        t3    <- queue.poll
        t4    <- queue.poll
      } yield assert(t1)(isSome(equalTo(1))) &&
        assert(t2)(isSome(equalTo(2))) &&
        assert(t3)(isNone) &&
        assert(t4)(isNone)
    },
    test("queue isShutdown") {
      for {
        queue <- Queue.bounded[Int](5)
        r1    <- queue.isShutdown
        _     <- queue.offer(1)
        r2    <- queue.isShutdown
        _     <- queue.takeAll
        r3    <- queue.isShutdown
        _     <- queue.shutdown
        r4    <- queue.isShutdown
      } yield assert(r1)(isFalse) &&
        assert(r2)(isFalse) &&
        assert(r3)(isFalse) &&
        assert(r4)(isTrue)
    },
    test("shutdown race condition with offer") {
      for {
        q <- Queue.bounded[Int](2)
        f <- q.offer(1).forever.fork
        _ <- q.shutdown
        _ <- f.await
      } yield assertCompletes
    } @@ exceptJS(nonFlaky),
    test("shutdown race condition with take") {
      for {
        q <- Queue.bounded[Int](2)
        _ <- q.offer(1)
        _ <- q.offer(1)
        f <- q.take.forever.fork
        _ <- q.shutdown
        _ <- f.await
      } yield assertCompletes
    } @@ exceptJS(nonFlaky),
    suite("back-pressured bounded queue stress testing") {
      val genChunk = Gen.chunkOfBounded(20, 100)(smallInt)
      List(
        test("many to many unbounded parallelism") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue    <- Queue.bounded[Int](n)
              offerors <- ZIO.foreach(as)(a => queue.offer(a).fork)
              takers   <- ZIO.foreach(as)(_ => queue.take.fork)
              _        <- ZIO.foreach(offerors)(_.join)
              taken    <- ZIO.foreach(takers)(_.join)
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("many to many bounded parallelism") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreachPar(as)(_ => queue.take).withParallelism(10).fork
              _      <- ZIO.foreachPar(as)(a => queue.offer(a)).withParallelism(10)
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("single to many") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreachPar(as)(_ => queue.take).withParallelism(10).fork
              _      <- ZIO.foreach(as)(a => queue.offer(a))
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("many to single") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue <- Queue.bounded[Int](n)
              taker <- ZIO.foreach(as)(_ => queue.take).fork
              _     <- ZIO.foreachPar(as)(a => queue.offer(a)).withParallelism(10)
              taken <- taker.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("fewer to more") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreachPar(as)(_ => queue.take).withParallelism(10).fork
              _      <- ZIO.foreachPar(as)(a => queue.offer(a)).withParallelism(3)
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("more to fewer") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreachPar(as)(_ => queue.take).withParallelism(3).fork
              _      <- ZIO.foreachPar(as)(a => queue.offer(a)).withParallelism(10)
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("offer all to many consumers") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreachPar(as)(_ => queue.take).withParallelism(10).fork
              _      <- queue.offerAll(as)
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        },
        test("offer all to one consumers") {
          check(smallInt, genChunk) { (n, as) =>
            for {
              queue  <- Queue.bounded[Int](n)
              takers <- ZIO.foreach(as)(_ => queue.take).fork
              _      <- queue.offerAll(as)
              taken  <- takers.join
            } yield assertTrue(as.sorted == taken.sorted)
          }
        }
      )
    } @@ jvm(samples(500) @@ sequential),
    test("isEmpty") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.take.fork
        _     <- waitForSize(queue, -1)
        empty <- queue.isEmpty
      } yield assertTrue(empty)
    },
    test("isFull") {
      for {
        queue <- Queue.bounded[Int](2)
        _     <- queue.offerAll(Chunk(1, 2, 3)).fork
        _     <- waitForSize(queue, 3)
        full  <- queue.isFull
      } yield assertTrue(full)
    },
    test("bounded queue preserves ordering") {
      for {
        queue   <- Queue.bounded[Int](16)
        expected = Chunk.fromIterable(0 until 100)
        _       <- queue.offerAll(expected).fork
        actual  <- queue.take.replicateZIO(100).map(Chunk.fromIterable)
      } yield assertTrue(actual == expected)
    } @@ exceptJS(nonFlaky(1000))
  )
}

object QueueSpecUtil {
  def waitForValue[T](ref: UIO[T], value: T): UIO[T] =
    Live.live((ref <* Clock.sleep(10.millis)).repeatUntil(_ == value))

  def waitForSize[A](queue: Queue[A], size: Int): UIO[Int] =
    waitForValue(queue.size, size)

  val smallInt: Gen[Any, Int] =
    Gen.small(Gen.const(_), 1)
}
