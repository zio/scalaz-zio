package zio

import zio.test.Assertion._
import zio.test._

object PromiseSpec extends ZIOBaseSpec {

  import ZIOTag._

  def spec: Spec[Any, TestFailure[Any], TestSuccess] = suite("PromiseSpec")(
    test("complete a promise using succeed") {
      for {
        p <- Promise.make[Nothing, Int]
        s <- p.succeed(32)
        v <- p.await
      } yield assert(s)(isTrue) && assert(v)(equalTo(32))
    },
    test("complete a promise using complete") {
      for {
        p  <- Promise.make[Nothing, Int]
        r  <- Ref.make(13)
        s  <- p.complete(r.updateAndGet(_ + 1))
        v1 <- p.await
        v2 <- p.await
      } yield assert(s)(isTrue) &&
        assert(v1)(equalTo(14)) &&
        assert(v2)(equalTo(14))
    },
    test("complete a promise using completeWith") {
      for {
        p  <- Promise.make[Nothing, Int]
        r  <- Ref.make(13)
        s  <- p.completeWith(r.updateAndGet(_ + 1))
        v1 <- p.await
        v2 <- p.await
      } yield assert(s)(isTrue) &&
        assert(v1)(equalTo(14)) &&
        assert(v2)(equalTo(15))
    },
    test("fail a promise using fail") {
      for {
        p <- Promise.make[String, Int]
        s <- p.fail("error with fail")
        v <- p.await.exit
      } yield assert(s)(isTrue) && assert(v)(fails(equalTo("error with fail")))
    } @@ zioTag(errors),
    test("fail a promise using complete") {
      for {
        p  <- Promise.make[String, Int]
        r  <- Ref.make(List("first error", "second error"))
        s  <- p.complete(r.modify(as => (as.head, as.tail)).flip)
        v1 <- p.await.exit
        v2 <- p.await.exit
      } yield assert(s)(isTrue) &&
        assert(v1)(fails(equalTo("first error"))) &&
        assert(v2)(fails(equalTo("first error")))
    } @@ zioTag(errors),
    test("fail a promise using completeWith") {
      for {
        p  <- Promise.make[String, Int]
        r  <- Ref.make(List("first error", "second error"))
        s  <- p.completeWith(r.modify(as => (as.head, as.tail)).flip)
        v1 <- p.await.exit
        v2 <- p.await.exit
      } yield assert(s)(isTrue) &&
        assert(v1)(fails(equalTo("first error"))) &&
        assert(v2)(fails(equalTo("second error")))
    } @@ zioTag(errors),
    test("complete a promise twice") {
      for {
        p <- Promise.make[Nothing, Int]
        _ <- p.succeed(1)
        s <- p.complete(IO.succeed(9))
        v <- p.await
      } yield assert(s)(isFalse) && assert(v)(equalTo(1))
    },
    test("interrupt a promise") {
      for {
        p <- Promise.make[Exception, Int]
        s <- p.interrupt
      } yield assert(s)(isTrue)
    } @@ zioTag(interruption),
    test("poll a promise that is not completed yet") {
      for {
        p       <- Promise.make[String, Int]
        attempt <- p.poll
      } yield assert(attempt)(isNone)
    },
    test("poll a promise that is completed") {
      for {
        p      <- Promise.make[String, Int]
        _      <- p.succeed(12)
        result <- p.poll.someOrFail("fail").flatten.exit
      } yield assert(result)(succeeds(equalTo(12)))
    },
    test("poll a promise that is failed") {
      for {
        p      <- Promise.make[String, Int]
        _      <- p.fail("failure")
        result <- p.poll.someOrFail("fail").flatten.exit
      } yield assert(result)(fails(equalTo("failure")))
    },
    test("poll a promise that is interrupted") {
      for {
        p      <- Promise.make[String, Int]
        _      <- p.interrupt
        result <- p.poll.someOrFail("fail").flatten.exit
      } yield assert(result)(isInterrupted)
    } @@ zioTag(interruption),
    test("isDone when a promise is completed") {
      for {
        p <- Promise.make[String, Int]
        _ <- p.succeed(0)
        d <- p.isDone
      } yield assert(d)(isTrue)
    },
    test("isDone when a promise is failed") {
      for {
        p <- Promise.make[String, Int]
        _ <- p.fail("failure")
        d <- p.isDone
      } yield assert(d)(isTrue)
    } @@ zioTag(errors)
  )
}
