package zio.stm

import zio.ZIOBaseSpec
import zio.test.Assertion._
import zio.test._

object TQueueSpec extends ZIOBaseSpec {

  def spec: ZSpec[Environment, Failure] = suite("TQueue")(
    suite("factories")(
      test("bounded") {
        val capacity = 5
        val tq       = TQueue.bounded[Int](capacity).map(_.capacity)
        assertM(tq.commit)(equalTo(capacity))
      },
      test("unbounded") {
        val tq = TQueue.unbounded[Int].map(_.capacity)
        assertM(tq.commit)(equalTo(Int.MaxValue))
      }
    ),
    suite("insertion and removal")(
      test("offer & take") {
        val tx = for {
          tq    <- TQueue.bounded[Int](5)
          _     <- tq.offer(1)
          _     <- tq.offer(2)
          _     <- tq.offer(3)
          one   <- tq.take
          two   <- tq.take
          three <- tq.take
        } yield List(one, two, three)
        assertM(tx.commit)(equalTo(List(1, 2, 3)))
      },
      test("takeUpTo") {
        val tx = for {
          tq   <- TQueue.bounded[Int](5)
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans  <- tq.takeUpTo(3)
          size <- tq.size
        } yield (ans, size)
        assertM(tx.commit)(equalTo((List(1, 2, 3), 2)))
      },
      test("offerAll & takeAll") {
        val tx = for {
          tq  <- TQueue.bounded[Int](5)
          _   <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans <- tq.takeAll
        } yield ans
        assertM(tx.commit)(equalTo(List(1, 2, 3, 4, 5)))
      },
      test("offerAll respects capacity") {
        val tx = for {
          tq        <- TQueue.bounded[Int](3)
          remaining <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans       <- tq.takeAll
        } yield (ans, remaining)
        assertM(tx.commit)(equalTo((List(1, 2, 3), List(4, 5))))
      },
      test("takeUpTo") {
        val tx = for {
          tq   <- TQueue.bounded[Int](5)
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans  <- tq.takeUpTo(3)
          size <- tq.size
        } yield (ans, size)
        assertM(tx.commit)(equalTo((List(1, 2, 3), 2)))
      },
      test("takeUpTo larger than container") {
        val tx = for {
          tq   <- TQueue.bounded[Int](5)
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans  <- tq.takeUpTo(7)
          size <- tq.size
        } yield (ans, size)
        assertM(tx.commit)(equalTo((List(1, 2, 3, 4, 5), 0)))
      },
      test("poll value") {
        val tx = for {
          tq  <- TQueue.bounded[Int](5)
          _   <- tq.offerAll(List(1, 2, 3))
          ans <- tq.poll
        } yield ans
        assertM(tx.commit)(isSome(equalTo(1)))
      },
      test("poll empty queue") {
        val tx = for {
          tq  <- TQueue.bounded[Int](5)
          ans <- tq.poll
        } yield ans
        assertM(tx.commit)(isNone)
      },
      test("seek element") {
        val tx = for {
          tq   <- TQueue.bounded[Int](5)
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          ans  <- tq.seek(_ == 3)
          size <- tq.size
        } yield (ans, size)
        assertM(tx.commit)(equalTo((3, 2)))
      }
    ),
    suite("lookup")(
      test("size") {
        val tx = for {
          tq   <- TQueue.unbounded[Int]
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          size <- tq.size
        } yield size
        assertM(tx.commit)(equalTo(5))
      },
      test("peek the next value") {
        val tx = for {
          tq   <- TQueue.unbounded[Int]
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          next <- tq.peek
          size <- tq.size
        } yield (next, size)
        assertM(tx.commit)(equalTo((1, 5)))
      },
      test("peekOption value") {
        val tx = for {
          tq   <- TQueue.unbounded[Int]
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          next <- tq.peekOption
          size <- tq.size
        } yield (next, size)
        assertM(tx.commit)(equalTo((Some(1), 5)))
      },
      test("peekOption empty queue") {
        val tx = for {
          tq   <- TQueue.bounded[Int](5)
          next <- tq.peekOption
        } yield next
        assertM(tx.commit)(isNone)
      },
      test("view the last value") {
        val tx = for {
          tq   <- TQueue.unbounded[Int]
          _    <- tq.offerAll(List(1, 2, 3, 4, 5))
          last <- tq.last
          size <- tq.size
        } yield (last, size)
        assertM(tx.commit)(equalTo((5, 5)))
      },
      test("check isEmpty") {
        val tx = for {
          tq1 <- TQueue.unbounded[Int]
          tq2 <- TQueue.unbounded[Int]
          _   <- tq1.offerAll(List(1, 2, 3, 4, 5))
          qb1 <- tq1.isEmpty
          qb2 <- tq2.isEmpty
        } yield (qb1, qb2)
        assertM(tx.commit)(equalTo((false, true)))
      },
      test("check isFull") {
        val tx = for {
          tq1 <- TQueue.bounded[Int](5)
          tq2 <- TQueue.bounded[Int](5)
          _   <- tq1.offerAll(List(1, 2, 3, 4, 5))
          qb1 <- tq1.isFull
          qb2 <- tq2.isFull
        } yield (qb1, qb2)
        assertM(tx.commit)(equalTo((true, false)))
      }
    )
  )
}
