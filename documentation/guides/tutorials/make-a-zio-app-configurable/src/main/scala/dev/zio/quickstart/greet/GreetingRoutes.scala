package dev.zio.quickstart.greet

import zio._
import zio.http._

/** Collection of routes that:
  *   - Accept a `Request` and return a `Response`
  *   - Do not fail
  *   - Do not use the environment
  */
object GreetingRoutes {
  def apply(): Routes[Any, Response] =
    Routes(
      // GET /greet?name=:name
      Method.GET / "greet" -> handler { (req: Request) =>
        if (req.url.queryParams.nonEmpty)
          ZIO.succeed(
            Response.text(
              s"Hello ${req.url.queryParams("name").map(_.mkString(" and "))}!"
            )
          )
        else
          ZIO.fail(Response.badRequest("The name query parameter is missing!"))
      },

      // GET /greet
      Method.GET / "greet" -> handler(Response.text(s"Hello World!")),

      // GET /greet/:name
      Method.GET / "greet" / string("name") -> handler {
        (name: String, _: Request) =>
          Response.text(s"Hello $name!")
      }
    )
}
