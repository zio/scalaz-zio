package zio.test

import zio.test.Assertion._

import scala.concurrent.duration.Duration

object AssertionRenderSpec extends ZIOBaseSpec {

  def spec = suite("Assertion Render Spec")(
    test("renders ops")(
      assertionShouldRenderTo(equalTo(30) || equalTo(40))("(equalTo(30) || equalTo(40))") &&
        assertionShouldRenderTo(equalTo(30) && equalTo(40))("(equalTo(30) && equalTo(40))")
    ),
    test("embedded assertions")(
      assertionShouldRenderTo(isSome(equalTo(40)))("isSome(equalTo(40))") &&
        assertionShouldRenderTo(isSome(equalTo(40) && contains("a")))("isSome((equalTo(40) && contains(a)))")
    ),
    test("not assertions")(
      assertionShouldRenderTo(!equalTo(50))("not(equalTo(50))") &&
        assertionShouldRenderTo(equalTo(30) && !isGreaterThan(50))("(equalTo(30) && not(isGreaterThan(50)))")
    ),
    test("class names render")(
      assertionShouldRenderTo(isSubtype[Duration.Infinite](Assertion.anything))("isSubtype(Infinite)(anything)")
    ),
    test("list render")(
      assertionShouldRenderTo(equalTo(List.fill(9)(10)))(
        "equalTo(List(10, 10, 10, 10, 10, 10, 10, 10, 10))"
      )
    ),
    test("fields render") {
      case class Person(age: Int)
      assertionShouldRenderTo(hasField("age", (p: Person) => p.age, Assertion.equalTo(10)))(
        "hasField(_.age)(equalTo(10))"
      )
    },
    test("map keys render")(
      assertionShouldRenderTo(hasKey("bar"))("hasKey(bar)")
    ),
    test("map has keys render")(
      assertionShouldRenderTo(hasKeys[String, Int](equalTo(List("key1", "key2"))))("hasKeys(equalTo(List(key1, key2)))")
    ),
    test("has none of")(
      assertionShouldRenderTo(hasNoneOf(List(123, 234, 32)))("hasNoneOf(List(123, 234, 32))")
    ),
    test("isUnit")(
      assertionShouldRenderTo(isUnit)("isUnit")
    ),
    test("hasSameElementsAs")(
      assertionShouldRenderTo(hasSameElements(List(1, 2, 3)))("hasSameElements(List(1, 2, 3))")
    ),
    test("approximately equals")(
      assertionShouldRenderTo(approximatelyEquals(100, 10))("approximatelyEquals(100, tolerance=10)")
    ),
    test("hasAt")(
      assertionShouldRenderTo(hasAt(1)(equalTo(40)))("hasAt(1)(equalTo(40))")
    ),
    test("failsWith")(
      assertionShouldRenderTo(failsWithA[RuntimeException])("failsWithA(RuntimeException)")
    ),
    test("isWithin")(
      assertionShouldRenderTo(Assertion.isWithin(10, 100))("isWithin(min=10, max=100)")
    ),
    test("diesWithA")(
      assertionShouldRenderTo(diesWithA[RuntimeException])("diesWithA(RuntimeException)")
    ),
    test("dies") {
      val exception = new RuntimeException("boom")
      assertionShouldRenderTo(dies(equalTo(exception)))("dies(equalTo(java.lang.RuntimeException: boom))")
    },
    test("containsString")(
      assertionShouldRenderTo(containsString("a string"))("containsString(a string)")
    ),
    test("endsWithString")(
      assertionShouldRenderTo(endsWithString("a string"))("endsWithString(a string)")
    ),
    test("startsWithString")(
      assertionShouldRenderTo(startsWithString("a string"))("startsWithString(a string)")
    ),
    test("endsWith")(
      assertionShouldRenderTo(endsWith(List(1, 2, 3)))("endsWith(List(1, 2, 3))")
    ),
    test("equalsIgnoreCase")(
      assertionShouldRenderTo(equalsIgnoreCase("a String"))("equalsIgnoreCase(a String)")
    ),
    test("isSome")(
      assertionShouldRenderTo(isSome)("isSome(anything)") &&
        assertionShouldRenderTo(isSome(equalTo(1)))("isSome(equalTo(1))")
    ),
    test("isLeft")(
      assertionShouldRenderTo(isLeft)("isLeft(anything)") &&
        assertionShouldRenderTo(isLeft(equalTo(30)))("isLeft(equalTo(30))")
    ),
    test("isRight")(
      assertionShouldRenderTo(isRight)("isRight(anything)") &&
        assertionShouldRenderTo(isRight(equalTo(30)))("isRight(equalTo(30))")
    )
  )

  private def assertionShouldRenderTo[A](assertion: Assertion[A])(expected: String) =
    assert(assertion.render)(equalTo(expected))
}
