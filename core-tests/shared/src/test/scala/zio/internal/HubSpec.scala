package zio.internal

import zio.test.Assertion._
import zio.test._
import zio.{Hub => _}
import zio.{Chunk, UIO, ZIO, ZIOBaseSpec, Hub => _}

object HubSpec extends ZIOBaseSpec {

  def spec =
    suite("HubSpec")(
      suite("pow2")(
        basicOperationsPow2,
        publishPoll(pow2Hub),
        publishPollLimitedCapacity(pow2Hub),
        publishPollDynamic(pow2Hub),
        emptyFull(pow2Hub),
        publishAllPollUpTo(pow2Hub),
        slidingPublishPoll(pow2Hub),
        slidingPublishPollLimitedCapacity(pow2Hub),
        slidingPublishPollDynamic(pow2Hub),
        slidingPublishAllPollUpTo(pow2Hub),
        concurrentUnsubscribe(pow2Hub),
        concurrentPoll(pow2Hub)
      ),
      suite("arb")(
        publishPoll(arbHub),
        publishPollLimitedCapacity(arbHub),
        publishPollDynamic(arbHub),
        emptyFull(arbHub),
        publishAllPollUpTo(arbHub),
        slidingPublishPoll(arbHub),
        slidingPublishPollLimitedCapacity(arbHub),
        slidingPublishPollDynamic(arbHub),
        slidingPublishAllPollUpTo(arbHub),
        concurrentUnsubscribe(arbHub),
        concurrentPoll(arbHub)
      ),
      suite("single")(
        basicOperationsSingle,
        publishPollLimitedCapacity(singleHub),
        publishPollDynamic(singleHub),
        publishAllPollUpTo(singleHub),
        slidingPublishPoll(singleHub),
        slidingPublishPollLimitedCapacity(singleHub),
        slidingPublishPollDynamic(singleHub),
        slidingPublishAllPollUpTo(singleHub),
        concurrentUnsubscribe(singleHub),
        concurrentPoll(singleHub)
      ),
      suite("unbounded")(
        basicOperationsUnbounded,
        publishPoll(unboundedHub),
        publishPollDynamic(unboundedHub),
        emptyFull(unboundedHub),
        publishAllPollUpTo(unboundedHub),
        slidingPublishPoll(unboundedHub),
        slidingPublishPollDynamic(unboundedHub),
        slidingPublishAllPollUpTo(unboundedHub),
        concurrentUnsubscribe(unboundedHub),
        concurrentPoll(unboundedHub)
      )
    )

  val smallInt: Gen[Any, Int] =
    Gen.int(1, 10)

  def offerAll[A](hub: Hub[A], values: List[A]): UIO[Unit] =
    ZIO.suspendSucceed {
      values match {
        case h :: t =>
          if (hub.publish(h)) offerAll(hub, t)
          else ZIO.yieldNow *> offerAll(hub, values)
        case Nil => ZIO.unit
      }
    }

  def offerAllChunks[A](hub: Hub[A], chunks: List[Chunk[A]]): UIO[Unit] =
    ZIO.suspendSucceed {
      chunks match {
        case h :: t =>
          val remaining = hub.publishAll(h)
          if (remaining.isEmpty) offerAllChunks(hub, t)
          else ZIO.yieldNow *> offerAllChunks(hub, remaining :: t)
        case Nil => ZIO.unit
      }
    }

  def takeN[A](subscription: Hub.Subscription[A], n: Int, acc: List[A] = List.empty): UIO[List[A]] =
    ZIO.suspendSucceed {
      if (n <= 0) ZIO.succeed(acc.reverse)
      else {
        val a = subscription.poll(null.asInstanceOf[A])
        if (a.asInstanceOf[AnyRef] eq null) ZIO.yieldNow *> takeN(subscription, n, acc)
        else takeN(subscription, n - 1, a :: acc)
      }
    }

  def takeNChunks[A](
    subscription: Hub.Subscription[A],
    n: Int,
    chunkSize: Int,
    acc: List[A] = List.empty
  ): UIO[List[A]] =
    ZIO.suspendSucceed {
      if (n <= 0) ZIO.succeed(acc.reverse)
      else {
        val as        = subscription.pollUpTo(chunkSize)
        val remaining = n - as.length
        if (remaining <= 0) ZIO.succeed(((as.reverse.toList) ::: acc).reverse)
        else ZIO.yieldNow *> takeNChunks[A](subscription, remaining, chunkSize, as.reverse.toList ::: acc)
      }
    }

  def offerAll_[A](hub: Hub[A], values: List[A]): UIO[Unit] =
    ZIO.suspendSucceed {
      values match {
        case h :: t =>
          if (hub.publish(h)) offerAll(hub, t)
          else ZIO.dieMessage("full hub")
        case Nil => ZIO.unit
      }
    }

  def takeN_[A](subscription: Hub.Subscription[A], n: Int, acc: List[A] = List.empty): UIO[List[A]] =
    ZIO.suspendSucceed {
      if (n <= 0) ZIO.succeed(acc.reverse)
      else {
        val a = subscription.poll(null.asInstanceOf[A])
        if (a.asInstanceOf[AnyRef] eq null) ZIO.dieMessage("empty hub")
        else takeN(subscription, n - 1, a :: acc)
      }
    }

  def slidingOffer[A](hub: Hub[A], value: A): UIO[Unit] =
    ZIO.suspendSucceed {
      if (hub.publish(value)) {
        ZIO.unit
      } else {
        hub.slide()
        ZIO.yieldNow *> slidingOffer(hub, value)
      }
    }

  def slidingOfferAll[A](hub: Hub[A], values: List[A]): UIO[Unit] =
    ZIO.foreachDiscard(values)(slidingOffer(hub, _) *> ZIO.yieldNow)

  def slidingOfferAllChunks[A](hub: Hub[A], chunks: List[Chunk[A]]): UIO[Unit] =
    ZIO.suspendSucceed {
      chunks match {
        case h :: t =>
          val remaining = hub.publishAll(h)
          if (remaining.isEmpty) slidingOfferAllChunks(hub, t)
          else {
            hub.slide()
            ZIO.yieldNow *> slidingOfferAllChunks(hub, remaining :: t)
          }
        case Nil => ZIO.unit
      }
    }

  def pow2Hub(n: Int): Hub[Int] =
    Hub.bounded(nextPow2(n))

  def singleHub(n: Int): Hub[Int] = {
    val _ = n
    Hub.bounded(1)
  }

  def arbHub(n: Int): Hub[Int] =
    if (n == 1 || nextPow2(n) == n) arbHub(n + 1) else Hub.bounded(n)

  def unboundedHub(n: Int): Hub[Int] = {
    val _ = n
    Hub.unbounded
  }

  def nextPow2(n: Int): Int = {
    val nextPow = (math.log(n.toDouble) / math.log(2.0)).ceil.toInt
    math.pow(2, nextPow.toDouble).toInt.max(2)
  }

  def publishPoll(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent publish and poll")(
      publishPoll1To1(makeHub, dynamic = false),
      publishPoll1ToN(makeHub, dynamic = false),
      publishPollNToN(makeHub, dynamic = false)
    )

  def publishPollLimitedCapacity(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("with limited capacity")(
      publishPoll1To1(_ => makeHub(1), dynamic = false),
      publishPoll1ToN(_ => makeHub(1), dynamic = false),
      publishPollNToN(_ => makeHub(1), dynamic = false)
    )

  def publishPollDynamic(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("with concurrent subscribes and unsubscribes")(
      publishPoll1To1(_ => makeHub(1), dynamic = true),
      publishPoll1ToN(_ => makeHub(1), dynamic = true),
      publishPollNToN(_ => makeHub(1), dynamic = true)
    )

  def emptyFull(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent isEmpty and isFull")(
      isEmpty(makeHub),
      isFull(makeHub),
      pollNonEmpty(makeHub),
      publishNonFull(makeHub)
    )

  def publishAllPollUpTo(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent publishAll and pollUpTo")(
      publishAllPollUpTo1To1(makeHub),
      publishAllPollUpToNToN(makeHub)
    )

  def slidingPublishPoll(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent sliding publish and poll")(
      slidingPublishPoll1To1(makeHub, dynamic = false),
      slidingPublishPoll1ToN(makeHub, dynamic = false),
      slidingPublishPollNToN(makeHub, dynamic = false)
    )

  def slidingPublishPollLimitedCapacity(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("with limited capacity")(
      slidingPublishPoll1To1(_ => makeHub(1), dynamic = false),
      slidingPublishPoll1ToN(_ => makeHub(1), dynamic = false),
      slidingPublishPollNToN(_ => makeHub(1), dynamic = false)
    )

  def slidingPublishPollDynamic(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("with concurrent subscribes and unsubscribes")(
      slidingPublishPoll1To1(_ => makeHub(1), dynamic = true),
      slidingPublishPoll1ToN(_ => makeHub(1), dynamic = true),
      slidingPublishPollNToN(_ => makeHub(1), dynamic = true)
    )

  def slidingPublishAllPollUpTo(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent sliding publishAll and pollUpTo")(
      slidingPublishAllPollUpTo1To1(makeHub),
      slidingPublishAllPollUpToNToN(makeHub)
    )

  def concurrentUnsubscribe(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent unsubscribe")(
      concurrentPublishUnsubscribe(makeHub),
      concurrentPollUnsubscribe(makeHub)
    )

  def concurrentPoll(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    suite("concurrent poll")(
      poll("with two pollers")(_ => makeHub(1))
    )

  def publishPoll1To1(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with one publisher and one subscriber") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub          = makeHub(as.length)
        val subscription = hub.subscribe()
        for {
          publisher  <- offerAll(hub, as).fork
          subscriber <- takeN(subscription, as.length).fork
          _          <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          _          <- publisher.join
          values     <- subscriber.join
        } yield assert(values)(equalTo(as)) &&
          assert(hub.isEmpty())(isTrue) &&
          assert(hub.size())(equalTo(0)) &&
          assert(subscription.isEmpty())(isTrue) &&
          assert(subscription.size())(equalTo(0))
      }
    }

  def publishPoll1ToN(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with one publisher and two subscribers") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub           = makeHub(as.length)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          publisher   <- offerAll(hub, as).fork
          subscriber1 <- takeN(subscription1, as.length).fork
          subscriber2 <- takeN(subscription2, as.length).fork
          _           <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          _           <- publisher.join
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1)(equalTo(as)) &&
          assert(values2)(equalTo(as)) &&
          assert(hub.isEmpty())(isTrue) &&
          assert(hub.size())(equalTo(0)) &&
          assert(subscription1.isEmpty())(isTrue) &&
          assert(subscription1.size())(equalTo(0)) &&
          assert(subscription2.isEmpty())(isTrue) &&
          assert(subscription2.size())(equalTo(0))
      }
    }

  def publishPollNToN(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with two publishers and two subscribers") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub           = makeHub(as.length * 2)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          publisher1  <- offerAll(hub, as).fork
          publisher2  <- offerAll(hub, as.map(-_)).fork
          subscriber1 <- takeN(subscription1, as.length * 2).fork
          subscriber2 <- takeN(subscription2, as.length * 2).fork
          _           <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          _           <- publisher1.join
          _           <- publisher2.join
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1.filter(_ > 0))(equalTo(as)) &&
          assert(values1.filter(_ < 0))(equalTo(as.map(-_))) &&
          assert(values2.filter(_ > 0))(equalTo(as)) &&
          assert(values2.filter(_ < 0))(equalTo(as.map(-_))) &&
          assert(hub.isEmpty())(isTrue) &&
          assert(hub.size())(equalTo(0)) &&
          assert(subscription1.isEmpty())(isTrue) &&
          assert(subscription1.size())(equalTo(0)) &&
          assert(subscription2.isEmpty())(isTrue) &&
          assert(subscription2.size())(equalTo(0))
      }
    }

  def isEmpty(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("isEmpty always returns false on a subscription that is not empty") {
      check(Gen.listOf(smallInt)) { as =>
        val hub           = makeHub(1000)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          _           <- offerAll(hub, List.range(1, 500))
          publisher1  <- offerAll_(hub, as).fork
          publisher2  <- offerAll_(hub, as.map(-_)).fork
          subscriber1 <- takeN(subscription1, as.length).fork
          subscriber2 <- takeN(subscription2, as.length).fork
          fiber1      <- ZIO.foreach(1 to 100)(_ => ZIO.succeed(subscription1.isEmpty())).map(_.forall(a => !a)).fork
          fiber2      <- ZIO.foreach(1 to 100)(_ => ZIO.succeed(subscription2.isEmpty())).map(_.forall(a => !a)).fork
          _ <- ZIO
                 .succeed(hub.subscribe())
                 .flatMap(subscription => ZIO.yieldNow *> ZIO.succeed(subscription.unsubscribe()))
          _       <- publisher1.join
          _       <- publisher2.join
          _       <- subscriber1.join
          _       <- subscriber2.join
          values1 <- fiber1.join
          values2 <- fiber2.join
        } yield assert(values1)(isTrue) && assert(values2)(isTrue)
      }
    }

  def isFull(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("isFull always returns false on a hub that is not full") {
      check(Gen.listOf(smallInt)) { as =>
        val hub           = makeHub(10000)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          publisher1  <- offerAll_(hub, as).fork
          publisher2  <- offerAll_(hub, as.map(-_)).fork
          subscriber1 <- takeN(subscription1, as.length).fork
          subscriber2 <- takeN(subscription2, as.length).fork
          fiber       <- ZIO.foreach(1 to 100)(_ => ZIO.succeed(hub.isFull())).map(_.forall(a => !a)).fork
          _ <- ZIO
                 .succeed(hub.subscribe())
                 .flatMap(subscription => ZIO.yieldNow *> ZIO.succeed(subscription.unsubscribe()))
          _      <- publisher1.join
          _      <- publisher2.join
          _      <- subscriber1.join
          _      <- subscriber2.join
          values <- fiber.join
        } yield assert(values)(isTrue)
      }
    }

  def publishNonFull(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("publishing to a hub that is not full always succeeds") {
      check(Gen.listOf(smallInt)) { as =>
        val hub           = makeHub(10000)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          publisher1  <- offerAll_(hub, as).fork
          publisher2  <- offerAll_(hub, as.map(-_)).fork
          subscriber1 <- takeN(subscription1, as.length).fork
          subscriber2 <- takeN(subscription2, as.length).fork
          _ <- ZIO
                 .succeed(hub.subscribe())
                 .flatMap(subscription => ZIO.yieldNow *> ZIO.succeed(subscription.unsubscribe()))
          _ <- publisher1.join
          _ <- publisher2.join
          _ <- subscriber1.join
          _ <- subscriber2.join
        } yield assertCompletes
      }
    }

  def pollNonEmpty(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("polling from a hub that is not empty always succeeds") {
      check(Gen.listOf(smallInt)) { as =>
        val hub           = makeHub(1000)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          _           <- offerAll(hub, List.range(1, 500))
          publisher1  <- offerAll(hub, as).fork
          publisher2  <- offerAll(hub, as.map(-_)).fork
          subscriber1 <- takeN_(subscription1, as.length * 2).fork
          subscriber2 <- takeN_(subscription2, as.length * 2).fork
          _ <- ZIO
                 .succeed(hub.subscribe())
                 .flatMap(subscription => ZIO.yieldNow *> ZIO.succeed(subscription.unsubscribe()))
          _ <- publisher1.join
          _ <- publisher2.join
          _ <- subscriber1.join
          _ <- subscriber2.join
        } yield assertCompletes
      }
    }

  def publishAllPollUpTo1To1(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with one publisher and one subscriber") {
      check(Gen.listOf(Gen.chunkOf(smallInt)), smallInt) { (chunks, chunkSize) =>
        val hub          = makeHub(1000)
        val subscription = hub.subscribe()
        for {
          _          <- offerAllChunks(hub, chunks).fork
          subscriber <- takeNChunks(subscription, chunks.flatten.length, chunkSize).fork
          values     <- subscriber.join
        } yield assert(values)(equalTo(chunks.flatten))
      }
    }

  def publishAllPollUpToNToN(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with two publishers and two subscribers") {
      check(Gen.listOf(Gen.chunkOf(smallInt)), smallInt) { (chunks, chunkSize) =>
        val hub           = makeHub(1000)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          _           <- offerAllChunks(hub, chunks).fork
          _           <- offerAllChunks(hub, chunks.map(_.map(-_))).fork
          subscriber1 <- takeNChunks(subscription1, chunks.flatten.length * 2, chunkSize).fork
          subscriber2 <- takeNChunks(subscription2, chunks.flatten.length * 2, chunkSize).fork
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1.filter(_ > 0))(equalTo(chunks.flatten)) &&
          assert(values1.filter(_ < 0))(equalTo(chunks.flatten.map(-_))) &&
          assert(values2.filter(_ > 0))(equalTo(chunks.flatten)) &&
          assert(values2.filter(_ < 0))(equalTo(chunks.flatten.map(-_)))
      }
    }

  def poll(label: String)(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test(label) {
      check(Gen.listOf1(smallInt)) { as =>
        val hub          = makeHub(as.length)
        val subscription = hub.subscribe()
        for {
          publisher <- offerAll(hub, as.sorted).fork
          poller1   <- takeN(subscription, as.length / 2).fork
          poller2   <- takeN(subscription, as.length - as.length / 2).fork
          _         <- publisher.join
          values1   <- poller1.join
          values2   <- poller2.join
        } yield assert(values1)(isSorted) &&
          assert(values2)(isSorted) &&
          assert((values1 ::: values2).sorted)(equalTo(as.sorted))
      }
    }

  def slidingPublishPoll1To1(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with one publisher and one subscriber") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub          = makeHub(as.length)
        val n            = math.min(hub.capacity, as.length)
        val subscription = hub.subscribe()

        for {
          _          <- slidingOfferAll(hub, as.sorted).fork
          subscriber <- takeN(subscription, n).fork
          _          <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          values     <- subscriber.join
        } yield assert(values)(hasSize(equalTo(n))) &&
          assert(values)(isSorted)
      }
    }

  def slidingPublishPoll1ToN(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with one publisher and two subscribers") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub           = makeHub(as.length)
        val n             = math.min(hub.capacity, as.length)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          _           <- slidingOfferAll(hub, as.sorted).fork
          subscriber1 <- takeN(subscription1, n).fork
          subscriber2 <- takeN(subscription2, n).fork
          _           <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1)(hasSize(equalTo(n))) &&
          assert(values1)(isSorted) &&
          assert(values2)(hasSize(equalTo(n))) &&
          assert(values2)(isSorted)
      }
    }

  def slidingPublishPollNToN(makeHub: Int => Hub[Int], dynamic: Boolean): Spec[TestEnvironment, Nothing] =
    test("with two publishers and two subscribers") {
      check(Gen.listOf1(smallInt)) { as =>
        val hub           = makeHub(as.length * 2)
        val n             = math.min(hub.capacity, as.length)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()

        for {
          publisher1  <- slidingOfferAll(hub, as.sorted).fork
          publisher2  <- slidingOfferAll(hub, as.map(-_).sorted).fork
          subscriber1 <- takeN(subscription1, n).fork
          subscriber2 <- takeN(subscription2, n).fork
          _           <- ZIO.when(dynamic)(ZIO.succeed(hub.subscribe().unsubscribe()))
          _           <- publisher1.join
          _           <- publisher2.join
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1.filter(_ > 0))(isSorted) &&
          assert(values1.filter(_ < 0))(isSorted) &&
          assert(values2.filter(_ > 0))(isSorted) &&
          assert(values2.filter(_ < 0))(isSorted)
      }
    }

  def slidingPublishAllPollUpTo1To1(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with one publisher and one subscriber") {
      check(Gen.listOf(smallInt), smallInt) { (as, chunkSize) =>
        val chunks       = as.sorted.grouped(chunkSize).map(Chunk.fromIterable).toList
        val hub          = makeHub(2)
        val n            = math.min(hub.capacity, chunks.flatten.length)
        val subscription = hub.subscribe()
        for {
          _          <- slidingOfferAllChunks(hub, chunks).fork
          subscriber <- takeNChunks(subscription, n / chunkSize, chunkSize).fork
          values     <- subscriber.join
        } yield assert(values)(isSorted)
      }
    }

  def slidingPublishAllPollUpToNToN(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with two publishers and two subscribers") {
      check(Gen.listOf(smallInt), smallInt) { (as, chunkSize) =>
        val leftChunks    = as.sorted.grouped(chunkSize).map(Chunk.fromIterable).toList
        val rightChunks   = as.map(-_).sorted.grouped(chunkSize).map(Chunk.fromIterable).toList
        val hub           = makeHub(2)
        val n             = math.min(hub.capacity, leftChunks.flatten.length)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        for {
          _           <- slidingOfferAllChunks(hub, leftChunks).fork
          _           <- slidingOfferAllChunks(hub, rightChunks).fork
          subscriber1 <- takeNChunks(subscription1, n / chunkSize, chunkSize).fork
          subscriber2 <- takeNChunks(subscription2, n / chunkSize, chunkSize).fork
          values1     <- subscriber1.join
          values2     <- subscriber2.join
        } yield assert(values1.filter(_ > 0))(isSorted) &&
          assert(values1.filter(_ < 0))(isSorted) &&
          assert(values2.filter(_ > 0))(isSorted) &&
          assert(values2.filter(_ < 0))(isSorted)
      }
    }

  def concurrentPublishUnsubscribe(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with publish") {
      val hub          = makeHub(2)
      val subscription = hub.subscribe()
      for {
        unsubscribe  <- ZIO.succeed(subscription.unsubscribe()).fork
        publish      <- ZIO.succeed(hub.publish(1)).fork
        _            <- unsubscribe.join
        _            <- publish.join
        empty        <- ZIO.succeed(hub.isEmpty())
        full         <- ZIO.succeed(hub.isFull())
        size         <- ZIO.succeed(hub.size())
        subscription <- ZIO.succeed(hub.subscribe())
        _            <- offerAll(hub, List(2, 3, 4, 5, 6)).fork
        values       <- takeN(subscription, 5)
      } yield assert(empty)(isTrue) &&
        assert(full)(isFalse) &&
        assert(size)(equalTo(0)) &&
        assert(values)(equalTo(List(2, 3, 4, 5, 6)))
    }

  def concurrentPollUnsubscribe(makeHub: Int => Hub[Int]): Spec[TestEnvironment, Nothing] =
    test("with poll") {
      val hub          = makeHub(2)
      val subscription = hub.subscribe()
      hub.publish(1)
      for {
        unsubscribe  <- ZIO.succeed(subscription.unsubscribe()).fork
        poll         <- ZIO.succeed(subscription.poll(-1)).fork
        _            <- unsubscribe.join
        _            <- poll.join
        empty        <- ZIO.succeed(hub.isEmpty())
        full         <- ZIO.succeed(hub.isFull())
        size         <- ZIO.succeed(hub.size())
        subscription <- ZIO.succeed(hub.subscribe())
        _            <- offerAll(hub, List(2, 3, 4, 5, 6)).fork
        values       <- takeN(subscription, 5)
      } yield assert(empty)(isTrue) &&
        assert(full)(isFalse) &&
        assert(size)(equalTo(0)) &&
        assert(values)(equalTo(List(2, 3, 4, 5, 6)))
    }

  lazy val basicOperationsPow2: Spec[TestEnvironment, Nothing] =
    suite("basic operations")(
      test("publish and poll a single value") {
        val hub          = Hub.bounded[Int](2)
        val subscription = hub.subscribe()
        hub.publish(0)
        val value = subscription.poll(-1)
        assert(value)(equalTo(0))
      },
      test("isFull checks whether a hub is full") {
        val hub    = Hub.bounded[Int](2)
        val before = hub.isFull()
        hub.subscribe()
        hub.publish(1)
        hub.publish(2)
        val after = hub.isFull()
        assert(before)(isFalse) &&
        assert(after)(isTrue)
      },
      test("publishing an item to a hub that is full returns false") {
        val hub          = Hub.bounded[Int](2)
        val subscription = hub.subscribe()
        val first        = hub.publish(1)
        val second       = hub.publish(2)
        val third        = hub.publish(3)
        val value        = subscription.poll(-1)
        val value2       = subscription.poll(-2)
        val value3       = subscription.poll(-3)
        assert(first)(isTrue) &&
        assert(second)(isTrue) &&
        assert(third)(isFalse) &&
        assert(value)(equalTo(1)) &&
        assert(value2)(equalTo(2)) &&
        assert(value3)(equalTo(-3))
      },
      test("publish and poll a chunk of values") {
        val hub           = Hub.bounded[Int](32)
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        hub.publishAll(Chunk(1, 2))
        val a = subscription1.pollUpTo(2)
        val b = subscription2.pollUpTo(2)
        val c = hub.publishAll(Chunk(3, 4))
        assert(a)(equalTo(Chunk(1, 2))) &&
        assert(b)(equalTo(Chunk(1, 2))) &&
        assert(c)(equalTo(Chunk.empty))
      }
    )

  lazy val basicOperationsUnbounded: Spec[TestEnvironment, Nothing] =
    suite("basic operations")(
      test("publish and poll a single value") {
        val hub           = Hub.unbounded[Int]
        val subscription  = hub.subscribe()
        val subscription2 = hub.subscribe()
        hub.publish(0)
        hub.publish(1)
        val value = subscription.poll(-1)
        subscription2.poll(-1)
        val size = hub.size()
        subscription2.poll(-2)
        subscription.poll(-2)
        val size2 = hub.size()
        assert(size)(equalTo(1)) &&
        assert(size2)(equalTo(0)) &&
        assert(value)(equalTo(0))
      },
      test("isFull checks whether a hub is full") {
        val hub    = Hub.unbounded[Int]
        val before = hub.isFull()
        hub.subscribe()
        hub.publish(1)
        hub.publish(2)
        val after = hub.isFull()
        assert(before)(isFalse) &&
        assert(after)(isFalse)
      },
      test("subscribers can progress independently") {
        val hub           = Hub.unbounded[Int]
        val subscription1 = hub.subscribe()
        val first         = hub.publish(1)
        val size1         = hub.size()
        val subscription2 = hub.subscribe()
        val second        = hub.publish(2)
        val size2         = hub.size()
        val value         = subscription1.poll(-1)
        val size3         = hub.size()
        val value2        = subscription1.poll(-2)
        val size4         = hub.size()
        val value3        = subscription2.poll(-3)
        val size5         = hub.size()
        assert(first)(isTrue) &&
        assert(second)(isTrue) &&
        assert(value)(equalTo(1)) &&
        assert(value2)(equalTo(2)) &&
        assert(value3)(equalTo(2)) &&
        assert(size1)(equalTo(1)) &&
        assert(size2)(equalTo(2)) &&
        assert(size3)(equalTo(1)) &&
        assert(size4)(equalTo(1)) &&
        assert(size5)(equalTo(0))
      },
      test("size interacts correctly with unsubscribe") {
        val hub           = Hub.unbounded[Int]
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        hub.publish(1)
        hub.publish(2)
        hub.publish(3)
        subscription1.poll(-1)
        subscription1.poll(-2)
        subscription1.poll(-3)
        subscription2.unsubscribe()
        assert(hub.isEmpty())(isTrue) &&
        assert(hub.size())(equalTo(0))
      },
      test("size interacts correctly with subscribe and unsubscribe") {
        val hub           = Hub.unbounded[Int]
        val subscription1 = hub.subscribe()
        hub.publish(1)
        hub.subscribe()
        hub.publish(2)
        hub.publish(3)
        subscription1.unsubscribe()
        assert(hub.isEmpty())(isFalse) &&
        assert(hub.size())(equalTo(2))
      },
      test("publish and poll a chunk of values") {
        val hub          = Hub.unbounded[Int]
        val subscription = hub.subscribe()
        hub.publishAll(Chunk(1, 2, 3))
        hub.publishAll(Chunk(4, 5, 6))
        val a = subscription.pollUpTo(2)
        val b = subscription.pollUpTo(2)
        val c = subscription.pollUpTo(2)
        assert(a)(equalTo(Chunk(1, 2))) &&
        assert(b)(equalTo(Chunk(3, 4))) &&
        assert(c)(equalTo(Chunk(5, 6)))
      },
      test("slide") {
        val hub           = Hub.unbounded[Int]
        val subscription1 = hub.subscribe()
        val subscription2 = hub.subscribe()
        hub.publish(1)
        hub.publish(2)
        hub.publish(3)
        hub.publish(4)
        subscription1.poll(10)
        subscription2.poll(11)
        subscription2.poll(12)
        hub.slide()
        val a = subscription1.poll(-1)
        val b = subscription2.poll(-2)
        assert(a)(equalTo(3)) &&
        assert(b)(equalTo(3))
      }
    )

  lazy val basicOperationsSingle: Spec[TestEnvironment, Nothing] =
    suite("basic operations")(
      test("publish and poll a single value") {
        val hub          = Hub.bounded[Int](1)
        val subscription = hub.subscribe()
        hub.publish(0)
        val value = subscription.poll(-1)
        assert(value)(equalTo(0))
      },
      test("isFull checks whether a hub is full") {
        val hub    = Hub.bounded[Int](1)
        val before = hub.isFull()
        hub.subscribe()
        hub.publish(1)
        val after = hub.isFull()
        assert(before)(isFalse) &&
        assert(after)(isTrue)
      },
      test("publishing an item to a hub that is full returns false") {
        val hub          = Hub.bounded[Int](1)
        val subscription = hub.subscribe()
        val first        = hub.publish(1)
        val second       = hub.publish(2)
        val value        = subscription.poll(-1)
        val value2       = subscription.poll(-2)
        assert(first)(isTrue) &&
        assert(second)(isFalse) &&
        assert(value)(equalTo(1)) &&
        assert(value2)(equalTo(-2))
      }
    )
}
