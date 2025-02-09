package dev.zio.quickstart.users

import zio._
import zio.http._
import zio.schema.codec.JsonCodec.schemaBasedBinaryCodec

/** Collection of routes that:
  *   - Accept a `Request` and returns a `Response`
  *   - May fail with type of `Response`
  *   - Require a `UserRepo` from the environment
  */
object UserRoutes {
  def apply(): Routes[UserRepo, Response] =
    Routes(
      // POST /users -d '{"name": "John", "age": 35}'
      Method.POST / "users" -> handler { (req: Request) =>
        for {
          u <- req.body.to[User].orElseFail(Response.badRequest)
          r <-
            UserRepo
              .register(u)
              .mapBoth(
                _ =>
                  Response
                    .internalServerError(s"Failed to register the user: $u"),
                id => Response.text(id)
              )
        } yield r
      },

      // GET /users/:id
      Method.GET / "users" / string("id") -> handler {
        (id: String, _: Request) =>
          UserRepo
            .lookup(id)
            .mapBoth(
              _ => Response.internalServerError(s"Cannot retrieve user $id"),
              {
                case Some(user) =>
                  Response(body = Body.from(user))
                case None =>
                  Response.notFound(s"User $id not found!")
              }
            )
      },
      // GET /users
      Method.GET / "users" -> handler {
        UserRepo.users.mapBoth(
          _ => Response.internalServerError("Cannot retrieve users!"),
          users => Response(body = Body.from(users))
        )
      }
    )
}
