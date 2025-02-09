---
id: with-javascript
title: "How to Interop with JavaScript?"
sidebar_label: "JavaScript"
---

Include ZIO in your Scala.js project by adding the following to your `build.sbt`:

```scala mdoc:passthrough
println(s"""```""")
println("""scalaJSUseMainModuleInitializer := true""")
if (zio.BuildInfo.isSnapshot) println(s"""resolvers += Resolver.sonatypeRepo("snapshots")""")
println(s"""libraryDependencies += "dev.zio" %%% "zio" % "${zio.BuildInfo.version}"""")
println(s"""```""")
```

## Example

Your main function can extend [`ZIOAppDefault`](../../reference/core/zioapp.md) as follows.

This example uses [scala-js-dom](https://github.com/scala-js/scala-js-dom) to access the DOM; to run the example you
will need to add that library as a dependency to your `build.sbt`.

It assumes you have a `<div id="app">`  somewhere in your HTML, into which the ZIO output is written.

```scala
import java.util.concurrent.TimeUnit
import org.scalajs.dom.{document,Element}
import zio._
import zio.Clock._

object Main extends ZIOAppDefault {

  override def run = {
    // The node into which we write our ZIO program output
    val node: Element = dom.document.querySelector("#app")
    for {
      _      <- Console.printLine("Starting progress bar demo.")  // Outputs on browser console log.
      _      <- IO.succeed(node.appendChild(target)) // "node" might provided in this page by mdoc.
      target <- IO.succeed(document.createElement("pre"))
      _      <- update(target).repeat(Schedule.spaced(1.seconds))
    } yield ExitCode.success
  }

  def update(target: Element) = {
      for {
        time   <- currentTime(TimeUnit.SECONDS)
        output <- ZIO.succeed(progress((time % 11).toInt, 10))
        _      <- ZIO.succeed(target.innerHTML = output)
      } yield ()
  }

  def progress(tick: Int, size: Int) = {
      val bar_length = tick
      val empty_length = size - tick
      val bar = "#" * bar_length + " " * empty_length
      s"$bar $bar_length%"
  }

}
```
