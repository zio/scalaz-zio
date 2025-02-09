package zio.test

import zio.internal.macros.StringUtils.StringOps

object PrettyPrintSpec extends ZIOBaseSpec {

  def spec = suite("PrettyPrint")(
    test("String") {
      assertTrue(PrettyPrint("A String").unstyled == "\"A String\"")
    },
    test("List") {
      assertTrue(PrettyPrint(List(1, 2, 3)).unstyled == "List(1, 2, 3)")
    },
    test("List of String") {
      assertTrue(PrettyPrint(List("1", "2", "3")).unstyled == "List(\"1\", \"2\", \"3\")")
    },
    test("Array of String") {
      assertTrue(PrettyPrint(Array("1", "2", "3")).unstyled == "Array(\"1\", \"2\", \"3\")")
    },
    test("Map") {
      val expected = """
Map(
  "name" -> "Biff",
  "age" -> 123,
  "inventory" -> Map(
    "food" -> "Cake",
    "candy" -> "Chocolate"
  )
)
""".trim
      assertTrue(
        PrettyPrint(
          Map("name" -> "Biff", "age" -> 123, "inventory" -> Map("food" -> "Cake", "candy" -> "Chocolate"))
        ).unstyled == expected
      )
    },
    test("Case Class") {
      final case class Person(name: String, age: Int)
      val expected = """
Person(
  name = "Glenda",
  age = 123
)
""".trim
      assertTrue(
        PrettyPrint(Person("Glenda", 123)).unstyled == expected
      )
    } @@ TestAspect.exceptScala212,
    test("Huge list") {
      val list = (1 to 1000).toList
      assertTrue(PrettyPrint(list).unstyled == "List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10 + 990 more)")
    }
  )

}
