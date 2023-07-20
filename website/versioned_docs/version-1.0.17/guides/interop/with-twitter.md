---
id: with-twitter
title: "How to Interop with Twitter?"
---

[`interop-twitter`](https://github.com/zio/interop-twitter) module provides capability to convert [Twitter `Future`](https://twitter.github.io/util/docs/com/twitter/util/Future.html) into ZIO `Task`.

### Example

```scala
import com.twitter.util.Future
import zio.{ App, Task }
import zio.console._
import zio.interop.twitter._

object Example extends App {
  def run(args: List[String]) = {
    val program =
      for {
        _        <- putStrLn("Hello! What is your name?")
        name     <- getStrLn
        greeting <- Task.fromTwitterFuture(Task(greet(name)))
        _        <- putStrLn(greeting)
      } yield ()

    program.exitCode
  }

  private def greet(name: String): Future[String] = Future.value(s"Hello, $name!")
}
```
