package zio

import zio.test._

import scala.annotation.experimental
import scala.language.experimental.saferExceptions

@experimental
object ExperimentalSpec extends ZIOBaseSpec {

  import zio.Experimental._

  val limit = 10e9
  class LimitExceeded extends Exception
  def f(x: Double): Double throws LimitExceeded =
    if x < limit then x * x else throw LimitExceeded()

  def spec = suite("ExperimentalSpec")(
    test("fromThrows success case") {
      for {
        value <- ZIO.fromThrows(f(42))
      } yield assertTrue(value == 1764.0)
    },
    test("fromThrows failure case") {
      for {
        value <- ZIO.fromThrows(f(limit + 1)).orElseSucceed("too large")
      } yield assertTrue(value == "too large")
    }
  )
}
