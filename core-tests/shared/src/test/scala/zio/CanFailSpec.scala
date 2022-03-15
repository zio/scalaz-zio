package zio

import zio.test.Assertion._
import zio.test._

object CanFailSpec extends ZIOBaseSpec {

  def spec = suite("CanFailSpec")(
    test("useful combinators compile") {
      val result = typeCheck {
        """
            import zio._
            val io =  ZIO.attempt(1 / 0)
            val uio = ZIO.succeed(0)
            io.orElse(uio)
            """
      }
      assertM(result)(isRight(anything))
    },
    test("useless combinators don't compile") {
      val result = typeCheck {
        """
            import zio._
            val io =  ZIO.attempt(1 / 0)
            val uio = ZIO.succeed(0)
            uio.orElse(io)
            """
      }
      assertM(result)(isLeft(anything))
    }
  )
}
