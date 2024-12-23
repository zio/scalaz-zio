---
id: build-a-restful-webservice
title: "Tutorial: How to Build a RESTful Web Service"
sidebar_label: "Building a RESTful Web Service"
---

ZIO provides good support for building RESTful web services. Using _Service Pattern_, we can build web services that are modular and easy to test and maintain. On the other hand, we have several powerful official and community libraries that can help us to work with JSON data types, and databases and also work with HTTP protocol.

In this tutorial, we will learn how to build a RESTful web service using ZIO. The corresponding source code for this tutorial is available on [GitHub](https://github.com/zio/zio-quickstarts). If you haven't read the [ZIO Quickstart: Building RESTful Web Service](../quickstarts/restful-webservice.md) yet, we recommend you read it first and download and run the source code, before reading this tutorial.

## Installation

We need to add the following dependencies to our project:

```scala
libraryDependencies ++= Seq(
  "dev.zio"  %% "zio-http" % "3.0.0-RC6"
)
```

For this tutorial, we will be using the _[ZIO HTTP](https://zio.dev/zio-http/)_ library, which is a library for building HTTP applications using ZIO.

## Introduction to The `Route` Data Type

Before we start to build a RESTful web service, we need to understand the `Route` data type. It is a data type that models a route in an HTTP application.

We can think of the `Route[Env, Err]` as a description of an HTTP route that accepts a `Request` that matches the route pattern and returns a `Response`. It can use the environment of type `Env` and may fail with an error of type `Err`.

A simple `Route` can be defined as follows:

```scala mdoc:compile-only
import zio.http._

val helloRoute: Route[Any, Nothing] =
  Method.GET / "hello" -> handler(Response.text("Hello, world!"))
```

We can say that `Route[Any, Nothing]` is a function that takes a `Request` and returns a `Response`. It doesn't require any services from the environment and won't fail.

Having multiple routes, we can collect them into a single `Routes` data type:

```scala mdoc:silent
import zio.http._

val routes: Routes[Any, Nothing] =
  Routes(
    Method.GET / "hello" -> handler(Response.text("Hello, world!")),
    Method.GET / "greet" -> handler(Response.text("Hello, ZIO!"))
  )
```

Finally, we can serve the routes using the `Server.serve` method:

```scala mdoc:compile-only
import zio._
import zio.http._

object MainApp extends ZIOAppDefault {
  def run = Server.serve(routes).provide(Server.default)
}
```

## Modeling Http Applications

Let's try to model some HTTP applications using the `Route` data type. So first, we are going to learn some basic `Route` constructors and how to combine them to build more complex HTTP applications.

### Creation of a `Handler`

The `Handler.succeed` constructor creates a `Handler` that always returns a successful response:

```scala mdoc:compile-only
import zio.http._

val app: Handler[Any, Nothing, Any, Response] =
  Handler.succeed(Response.text("Hello, world!"))
```

We have the same constructor for failures called `Http.fail`. It creates a `Handler` application that always returns a failed response:

```scala mdoc:compile-only
import zio._
import zio.http._

val app: Handler[Any, Response, Any, Nothing] =
  handler(ZIO.fail(Response.internalServerError("Something went wrong")))
```

We can also create a `Handler` form a function. The `Handler.fromFunction` constructor takes a total function of type `A => B` and then creates a `Handler` that accepts an `A` and returns a `B`:

```scala mdoc:compile-only
import zio.http._

val app: Handler[Any, Nothing, Int, Double] = Handler.fromFunction[Int](_ / 2.0)
```

Handlers can be effectual. We have a couple of constructors that can be used to create a `Handler` that are effectual:

- `Http.fromZIO`
- `Http.fromStream`
- `Http.fromFunctionZIO`
- `Http.fromFile`

There are lots of other constructors, to learn more about them, please refer to the [`Handler`](https://zio.dev/zio-http/reference/handler) page in the ZIO HTTP documentation.

### Combining Handlers

The `Handler` data type is composable like `ZIO`. We can create new complex `Handler` by combining existing simple ones by using `flatMap`, `zip`, `andThen`, `orElse`, and `++` methods:

```scala mdoc:compile-only
import zio.http._

val a           : Handler[Any, Nothing, Int, Double]    = ???
val b           : Handler[Any, Nothing, Double, String] = ???
def c(i: Double): Handler[Any, Nothing, Long, String]   = ???

val d = a >>= c // a flatMap c (combine two handlers sequentially)
val f = a >>> b // a andThen b (pipe output of the a handler to input of the b handler)
val h = a <> b  // a orElse b  (run a, if it fails, run b)
```

## Built-in `Request` and `Response` Data Types

Until now, we have learned how to create a `Handler` with some simple request and response types, e.g. `String` and `Int` in a `Handler[Any, Nothing, String, Int]`. But, in real life, when we want to deal with HTTP requests and responses, we need to have a more complex type for the request and response.

ZIO HTTP provides a type `Request` for HTTP requests and a type `Response` for HTTP responses. It has a built-in decoder for `Request` and an encoder for `Response`. So we don't need to worry about the details of how requests and responses are decoded and encoded.

The `Response` type has a default `apply` constructor in its companion object that takes a status, headers, and, HTTP data to create a `Response`:

```scala
object Response {
  def apply[R, E](
    status: Status = Status.Ok,
    headers: Headers = Headers.empty,
    data: HttpData = HttpData.Empty
  ): Response = ???
}
```

Other than the default constructor, we have several helper methods to create a `Response`. Here are some of them:

1. **`Response.ok`**: Creates a successful response with 200 status code.
2. **`Response.text("Hello World")`**: Creates a successful response with 200 status code and a body of `Hello World`.
3. **`Response.status(Status.BadRequest)`**: Creates a response with a status code of 400.
4. **`Response.html("<h1>Hello World</h1>")`**: Creates a successful response with 200 status code and an HTML body of `<h1>Hello World</h1>`.
5. **`Response.redirect("/")`**: Creates a successful response that redirects to the root path.

On the other hand, we do not need to create a `Request` instead, we need to pattern-match incoming requests to decompose them and determine the appropriate action to take.

Each incoming request can be extracted into two parts using pattern matching:

- HTTP Method (GET, POST, PUT, etc.)
- Path (e.g. /, /greeting, /download)

Let's see an example of how to pattern match on incoming requests:

```scala mdoc:compile-only
import zio.http._

val httpApp: Route[Any, Nothing] =
  Method.GET / "greet" / string("name") ->
    handler { (name: String, _: Request) =>
      Response.text(s"Hello $name!")
    }
```

Using this DSL we only access the method and path of the incoming request. If we need to access the query string, the body, and more, we need to use the following DSL:

```scala mdoc:compile-only
import zio._
import zio.http._

val httpApp: Route[Any, Response] =
  Method.GET / "greet" ->
    handler { (req: Request) =>
      if (req.url.queryParams.nonEmpty)
        ZIO.succeed(Response.text(s"Hello ${req.url.queryParams("name").mkString(" and ")}!"))
      else
        ZIO.fail(Response.badRequest("Missing query parameter 'name'"))
    }
```

Until now, we have learned how to create `Route` and `Routes` applications that handle HTTP requests. In the next section, we will learn how to create HTTP servers that can serve HTTP routes.

## Creating HTTP Server

To start an HTTP server, the ZIO HTTP requires a `Routes` of type `Routes[Env, Response]` and returns an effect that requires a `Server` from the environment and never produces a value and never fails:

```scala
object Server {
  def server[R](
    http: Routes[Env, Response]
  ): ZIO[R with Server, Nothing, Nothing] = ???
}
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Greeting App

First, we need to define a request handler that will handle `GET` requests to the `/greet` path:

```scala mdoc:silent
import zio._
import zio.http._

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
```

In the above example, we have defined three routes:

- The first case matches a request with a path of `/greet` and a query parameter `name`.
- The second case matches a request with a path of `/greet` with no query parameters.
- The third case matches a request with a path of `/greet/:name` and extracts the `name` from the path.

Next, we need to create a server for `GreetingRoutes`:

```scala mdoc:compile-only
import zio._
import zio.http._

object MainApp extends ZIOAppDefault {
  def run =
    Server.serve(GreetingRoutes()).provide(Server.default)
}
```

```scala mdoc:invisible:reset

```

Now, we have three endpoints in our server. We can test the server according to the steps mentioned in the corresponding [quickstart](../quickstarts/restful-webservice.md).

Note that if we have written other routes along with `GreetingRoutes`, such as `DownloadRoutes`, `CounterRoutes`, and `UserRoutes`, we can combine them together and start a server for that routes:

```scala mdoc:invisible
import zio.http._

object GreetingRoutes {
  def apply() = Routes.empty
}

object DownloadRoutes {
  def apply() = Routes.empty
}

object CounterRoutes {
  def apply() = Routes.empty
}

object UserRoutes {
  def apply() = Routes.empty
}
```

```scala mdoc:compile-only
import zio.http._

Server.serve(
  GreetingRoutes() ++ DownloadRoutes() ++ CounterRoutes() ++ UserRoutes()
).provide(Server.default)
```

## Conclusion

In this tutorial, we have learned the basic building blocks of writing HTTP servers. We learned how to write routes and handlers. And finally, we saw how to create an HTTP server that can handle HTTP applications.

All the source code associated with this article is available on the [ZIO Quickstart](http://github.com/zio/zio-quickstarts) project.
