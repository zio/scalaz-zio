package dev.zio.quickstart.users

import zio.schema.{DeriveSchema, Schema}

case class User(name: String, age: Int)

object User {
  implicit val schema: Schema[User] =
    DeriveSchema.gen[User]
}
