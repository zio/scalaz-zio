package zio.concurrent

import zio._
import zio.test._
import zio.test.Assertion._

object CountdownLatchSpec extends ZIOSpecDefault {
  val spec =
    suite("CountdownLatchSpec")(
      suite("Construction")(
        test("Creates a latch") {
          assertZIO(CountdownLatch.make(100).flatMap(_.count).exit)(succeeds(equalTo(100)))
        },
        test("Dies with an invalid count") {
          assertZIO(CountdownLatch.make(0).exit)(dies(anything))
        }
      ),
      suite("Operations")(
        test("Fibers wait and get released when countdown reaches 0") {
          for {
            latch  <- CountdownLatch.make(100)
            count  <- Ref.make(0)
            ps     <- ZIO.collectAll(List.fill(10)(Promise.make[Nothing, Unit]))
            _      <- ZIO.forkAll(ps.map(p => latch.await *> count.update(_ + 1) *> p.succeed(())))
            _      <- latch.countDown.repeat(Schedule.recurs(99))
            _      <- ZIO.foreachDiscard(ps)(_.await)
            result <- count.get
          } yield assert(result)(equalTo(10))
        }
      )
    )
}
