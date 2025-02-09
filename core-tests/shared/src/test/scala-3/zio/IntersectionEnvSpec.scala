package zio

import zio.test._
import zio.test.Assertion._

object IntersectionEnvSpec extends ZIOBaseSpec {
  type Curry[A] = [B] =>> [C] =>> A => Set[B] => C
  val hasNoTag = isLeft(startsWithString("could not find implicit value for izumi.reflect.Tag"))

  def spec = suite("IntersectionEnvSpec")(
    suite("intersection type")(
      test("top-level") {
        assertZIO(typeCheck("ZIO.service[Int & String]"))(hasNoTag)
      },
      test("in a list") {
        assertZIO(typeCheck("ZIO.service[List[Int & String]]"))(hasNoTag)
      },
      test("deeply nested in a covariant position") {
        assertZIO(typeCheck("ZIO.service[Either[Char, Option[List[Int & String]]]]"))(hasNoTag)
      },
      test("in twice contravariant position") {
        assertZIO(typeCheck("ZIO.service[(Int & String => Boolean) => Boolean]"))(hasNoTag)
      },
      test("in a set") {
        assertZIO(typeCheck("ZIO.service[Set[Int & String]]"))(isRight)
      },
      test("in a contravariant position") {
        assertZIO(typeCheck("ZIO.service[Int & String => Boolean]"))(isRight)
      },
      test("in a covariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Byte][Char][Int & String]]"))(hasNoTag)
      },
      test("in a contravariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Int & String][Byte][Char]]"))(isRight)
      },
      test("in an invariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Byte][Int & String][Char]]"))(isRight)
      }
    ),
    suite("union type")(
      test("top-level") {
        assertZIO(typeCheck("ZIO.service[Int | String]"))(isRight)
      },
      test("in a list") {
        assertZIO(typeCheck("ZIO.service[List[Int | String]]"))(isRight)
      },
      test("deeply nested in a covariant position") {
        assertZIO(typeCheck("ZIO.service[Either[Char, Option[List[Int | String]]]]"))(isRight)
      },
      test("in twice contravariant position") {
        assertZIO(typeCheck("ZIO.service[(Int | String => Boolean) => Boolean]"))(isRight)
      },
      test("in a set") {
        assertZIO(typeCheck("ZIO.service[Set[Int | String]]"))(isRight)
      },
      test("in a contravariant position") {
        assertZIO(typeCheck("ZIO.service[Int | String => Boolean]"))(hasNoTag)
      },
      test("in a covariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Byte][Char][Int | String]]"))(isRight)
      },
      test("in a contravariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Int | String][Byte][Char]]"))(hasNoTag)
      },
      test("in an invariant position of a type alias") {
        assertZIO(typeCheck("ZIO.service[Curry[Byte][Int | String][Char]]"))(isRight)
      }
    )
  )
}
