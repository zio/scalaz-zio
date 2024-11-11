package zio.test.junit.maven

import zio.test.junit._
import zio.test._
import zio.test.Assertion._

object FailingSpec extends ZIOSpecDefault {
  override def spec = suite("FailingSpec")(
    test("should fail") {
      assert(11)(equalTo(12))
    },
    test("should fail - isSome") {
      assert(Some(11))(isSome(equalTo(12)))
    },
    test("should succeed") {
      assert(12)(equalTo(12))
    }
  )
}
