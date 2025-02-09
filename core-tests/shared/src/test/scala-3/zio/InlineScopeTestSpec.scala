package zio

import zio.test._
import zio.test.Assertion._
import zio.Random
import zio.test.Gen._

object AnObject {
  def bar: Int = 1
}

object AnotherObject {
  opaque type ATypeIsNeeded = Unit
  val AnAlias           = AnObject
  inline def foo(): Int = AnAlias.bar
}

object InlineScopeTestSpec extends ZIOBaseSpec {

  def spec =
    suite("Inline Scope Spec")(
      test("Inline scope is captured") {
        assertTrue(AnotherObject.foo() == 1)
      }
    )

}
