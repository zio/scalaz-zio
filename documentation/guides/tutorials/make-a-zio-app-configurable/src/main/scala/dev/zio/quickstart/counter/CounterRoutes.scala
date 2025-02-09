package dev.zio.quickstart.counter

import zio.http._
import zio._

/** Collection of routes that:
  *   - Accept `Request` and returns a `Response`
  *   - Do not fail
  *   - Require the `Ref[Int]` as the environment
  */
object CounterRoutes {
  def apply(): Routes[Ref[Int], Nothing] =
    Routes(
      Method.GET / "up" -> handler {
        ZIO.serviceWithZIO[Ref[Int]] { ref =>
          ref
            .updateAndGet(_ + 1)
            .map(_.toString)
            .map(Response.text)
        }
      },
      Method.GET / "down" -> handler {
        ZIO.serviceWithZIO[Ref[Int]] { ref =>
          ref
            .updateAndGet(_ - 1)
            .map(_.toString)
            .map(Response.text)
        }
      },
      Method.GET / "get" -> handler {
        ZIO.serviceWithZIO[Ref[Int]](ref =>
          ref.get.map(_.toString).map(Response.text)
        )
      }
    )
}
