package zio.test

import zio.*

object OpaqueTypeTagsSpec extends ZIOBaseSpec {

  object UserName {
    opaque type T = String
    def apply(s: String): T = s"UserName: $s"
  }

  type UserName = UserName.T

  object UserId {
    opaque type T = String
    def apply(s: String): T = s"UserId: $s"
  }

  type UserId = UserId.T

  def spec =
    suite("OpaqueTypeTagsSpec")(
      test("extracts opaque types from environment") {
        val f = for {
          n <- ZIO.service[UserName.T]
          i <- ZIO.service[UserId.T]
        } yield assertTrue(n.toString == "UserName: foo", i.toString == "UserId: bar")

        f.provide(ZLayer.succeed(UserName("foo")), ZLayer.succeed(UserId("bar")))
      }
    )
}
