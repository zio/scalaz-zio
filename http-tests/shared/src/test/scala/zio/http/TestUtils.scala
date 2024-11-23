package zio.http

import zio.{ZIO, Task}

object TestUtils {
  implicit class RequestSyntax(request: Request) {
    def toZIO: Task[Request] = ZIO.succeed(request)
  }

  implicit class ResponseSyntax(response: Response) {
    def toZIO: Task[Response] = ZIO.succeed(response)
  }

  def withServer[R, E, A](app: HttpApp[R, E])(f: Int => ZIO[R, E, A]): ZIO[R, E, A] = {
    // Helper to run tests against a real server
    // Implementation depends on your HTTP server implementation
    ???
  }
} 