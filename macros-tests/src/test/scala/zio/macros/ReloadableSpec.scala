package zio.macros

import zio._
import zio.test._

@scala.annotation.experimental
object ReloadableSpec extends ZIOSpecDefault {

  def spec = suite("ReloadableSpec")(
    test("reloadable") {
      for {
        first  <- Counter.get
        _      <- Counter.increment
        second <- Counter.get
        _      <- ServiceReloader.reload[Counter]
        third  <- Counter.get
        _      <- Counter.increment
        fourth <- Counter.get
      } yield assertTrue(first == 0 && second == 1 && third == 0 && fourth == 1)
    }.provide(
      Counter.live.reloadable,
      ServiceReloader.live
    )
  )
}

trait Counter {
  def get: UIO[Int]
  def increment: UIO[Unit]
}

object Counter {

  val live: ZLayer[Any, Nothing, Counter] =
    ZLayer {
      for {
        ref <- Ref.make(0)
      } yield new Counter {
        val get: UIO[Int] =
          ref.get
        val increment: UIO[Unit] =
          ref.update(_ + 1)
      }
    }

  val get: ZIO[Counter, Nothing, Int] =
    ZIO.serviceWithZIO(_.get)

  val increment: ZIO[Counter, Nothing, Unit] =
    ZIO.serviceWithZIO(_.increment)
}
