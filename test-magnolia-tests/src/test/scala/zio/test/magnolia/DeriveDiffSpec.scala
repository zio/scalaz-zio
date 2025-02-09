package zio.test.magnolia

import zio.test._

import DeriveDiff.gen
import java.time.Instant

object DeriveDiffSpec extends ZIOBaseSpec {
  final case class Pet(name: String, hasBone: Boolean, favoriteFoods: List[String], birthday: Instant)
  final case class Person(name: String, nickname: Option[String], age: Int, pet: Pet, person: Option[Person] = None)

  sealed trait Color

  object Color {
    final case class Red(brightness: Int, age: Int = 10)                    extends Color
    final case class Blue(depth: Double, cool: String = "Nice to meet you") extends Color
    final case class Green(isCool: Boolean)                                 extends Color
  }

  def spec: Spec[Any, TestFailure[Any]] = suite("DeriveDiffSpec")(
    suite("derivation")(
      test("case classes can be derived") {
        val l1 = List("Alpha", "This is a wonderful \"way\" to dance and party", "Potato")
        val l2 = List("Alpha", "This is a wonderful way to live and die", "Potato", "Brucee Lee", "Potato", "Ziverge")
        val p1 =
          Person(
            "Boboo",
            Some("Babbo\nThe\nBibber"),
            300,
            Pet("The Beautiful Crumb", hasBone = false, l1, Instant.MIN),
            Some(
              Person(
                "Boboo",
                Some("Babbo\nThe\nBibber"),
                300,
                Pet("The Beautiful Crumb", hasBone = false, l1, Instant.MIN)
              )
            )
          )
        val p2 = Person(
          "Bibi",
          Some("Bibbo\nThe\nBibber\nBobber"),
          300,
          Pet("The Beautiful Destroyer", hasBone = false, l2, Instant.now),
          Some(
            Person(
              "Bibi",
              Some("Bibbo\nThe\nBibber\nBobber"),
              300,
              Pet("The Beautiful Destroyer", hasBone = false, l2, Instant.now)
            )
          )
        )

        assertTrue(p1 == p2)
      } @@ TestAspect.failing,
      test("sealed traits can be derived") {
        val red: Color  = Color.Blue(10)
        val blue: Color = Color.Blue(30)
        assertTrue(red == blue)
      } @@ TestAspect.failing,
      test("multiline string diffs") {
        val string1 =
          """Good day
How
Are
You
            """.trim
        val string2 =
          """Good morning
Everyone
How
Are
You
Really
Doing
            """.trim
        assertTrue(string1 == string2)
      } @@ TestAspect.failing
    )
  )
}
