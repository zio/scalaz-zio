package zio.stm

import zio.test.Assertion._
import zio.test._
import zio.{Chunk, Has, Random, ZIOBaseSpec}

object TPriorityQueueSpec extends ZIOBaseSpec {

  final case class Event(time: Int, description: String)

  implicit val eventOrdering: Ordering[Event] =
    Ordering.by(_.time)

  val genEvent: Gen[Has[Random] with Has[Sized], Event] =
    for {
      time        <- Gen.int(-10, 10)
      description <- Gen.alphaNumericString
    } yield Event(time, description)

  val genEvents: Gen[Has[Random] with Has[Sized], Chunk[Event]] =
    Gen.chunkOf(genEvent)

  val genPredicate: Gen[Has[Random], Event => Boolean] =
    Gen.function(Gen.boolean)

  def spec: ZSpec[Environment, Failure] = suite("TPriorityQueueSpec")(
    test("isEmpty") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue <- TPriorityQueue.empty[Event]
          _     <- queue.offerAll(as)
          empty <- queue.isEmpty
        } yield empty
        assertM(transaction.commit)(equalTo(as.isEmpty))
      }
    },
    test("nonEmpty") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue    <- TPriorityQueue.empty[Event]
          _        <- queue.offerAll(as)
          nonEmpty <- queue.nonEmpty
        } yield nonEmpty
        assertM(transaction.commit)(equalTo(as.nonEmpty))
      }
    },
    test("offerAll and takeAll") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue  <- TPriorityQueue.empty[Event]
          _      <- queue.offerAll(as)
          values <- queue.takeAll
        } yield values
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    },
    test("removeIf") {
      checkM(genEvents, genPredicate) { (as, f) =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          _     <- queue.removeIf(f)
          list  <- queue.toChunk
        } yield list
        assertM(transaction.commit)(hasSameElements(as.filterNot(f)) && isSorted)
      }
    },
    test("retainIf") {
      checkM(Gen.listOf(genEvent), genPredicate) { (as, f) =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          _     <- queue.retainIf(f)
          list  <- queue.toList
        } yield list
        assertM(transaction.commit)(hasSameElements(as.filter(f)) && isSorted)
      }
    },
    test("take") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          takes <- STM.collectAll(STM.replicate(as.length)(queue.take))
        } yield takes
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    },
    test("takeUpTo") {
      val gen = for {
        as <- genEvents
        n  <- Gen.int(0, as.length)
      } yield (as, n)
      checkM(gen) { case (as, n) =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          left  <- queue.takeUpTo(n)
          right <- queue.takeAll
        } yield left ++ right
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    },
    test("toChunk") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          list  <- queue.toChunk
        } yield list
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    },
    test("toList") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          list  <- queue.toList
        } yield list
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    },
    test("toVector") {
      checkM(genEvents) { as =>
        val transaction = for {
          queue <- TPriorityQueue.fromIterable(as)
          list  <- queue.toVector
        } yield list
        assertM(transaction.commit)(hasSameElements(as) && isSorted)
      }
    }
  )
}
