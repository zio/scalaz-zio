package zio

import zio.test._

object HasNoScopeSpec extends ZIOSpecDefault {
  def isScopeError(e: Either[String, Unit], typeString: String): Boolean = e match {
    case Left(err) =>
      err.startsWith(
        s"""Can not prove that $typeString does not contain Scope.
           |If $typeString contains a zio.Scope, please handle it explicitly. If it contains a generic type, add a context bound""".stripMargin
      )
    case _ => false
  }

  def noScope[R: HasNoScope]: String = "noScope"

  def genericWithImplicit[R: HasNoScope]: String =
    noScope[R]

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suiteAll("HasNoScope") {
      test("no scope") {
        noScope[Any]
        assertTrue(true)
      }
      test("with implicit") {
        genericWithImplicit[Any]
        assertTrue(true)
      }
      test("with scope") {
        typeCheck(
          // language=Scala
          """noScope[Scope]"""
        ).map(e => assertTrue(e == Left("The type Scope contains a zio.Scope. This is not allowed.")))
      }
      test("generic") {
        typeCheck(
          // language=Scala
          """
           def genericNoImplicit[R]: String = noScope[R]
        """
        ).map(e =>
          assertTrue(
            e == Left("Can not prove that R does not contain a zio.Scope. Please add a context bound R: HasNoScope.")
          )
        )
      }
      test("generic with R") {
        typeCheck(
          // language=Scala
          """
               def genericNoImplicitWithR[R]: ZIO[Int & R, Nothing, Unit] =
                noScope[Int & R]
            """
        ).map(e =>
          assertTrue(
            e == Left("Can not prove that R does not contain a zio.Scope. Please add a context bound R: HasNoScope.")
          )
        )
      }
    }
}
