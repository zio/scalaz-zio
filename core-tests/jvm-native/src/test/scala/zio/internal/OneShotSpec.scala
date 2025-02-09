package zio.internal

import zio.ZIOBaseSpec
import zio.test.Assertion._
import zio.test._

object OneShotSpec extends ZIOBaseSpec {

  def spec = suite("OneShotSpec")(
    suite("OneShotSpec")(
      suite("Make a new OneShot")(
        test("set must accept a non-null value") {
          val oneShot = OneShot.make[Int]
          oneShot.set(1)

          assert(oneShot.get())(equalTo(1))
        },
        test("set must not accept a null value") {
          val oneShot = OneShot.make[Object]

          assert(oneShot.set(null))(throwsA[Error])
        },
        test("isSet must report if a value is set") {
          val oneShot = OneShot.make[Int]

          val resultBeforeSet = oneShot.isSet

          oneShot.set(1)

          val resultAfterSet = oneShot.isSet

          assert(resultBeforeSet)(isFalse) && assert(resultAfterSet)(isTrue)
        },
        test("get must fail if no value is set") {
          val oneShot = OneShot.make[Object]

          assert(oneShot.get(10000L))(throwsA[Error])
        },
        test("cannot set value twice") {
          val oneShot = OneShot.make[Int]
          oneShot.set(1)

          assert(oneShot.set(2))(throwsA[Error])
        }
      )
    )
  )
}
