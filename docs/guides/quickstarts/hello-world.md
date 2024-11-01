---
id: hello-world
title: "ZIO Quickstart: Hello World"
sidebar_label: "Hello World"
---

This is the simplest quickstart for a ZIO application. You can download and run it very quickly. This will give you an idea of how to write similar apps.

## Running The Example

First, open the console and clone the [ZIO Quickstarts](https://github.com/zio/zio-quickstarts) project using `git` (or you can simply download the project) and then change the directory:

```bash
$ git clone https://github.com/zio/zio-quickstarts.git
$ cd zio-quickstarts/zio-quickstart-hello-world
```

Once you are inside the project directory, run the application:

```bash
sbt run
```

Alternatively, to enable hot-reloading and prevent port binding issues, you can use:

```bash
sbt reStart
```

:::note
If you encounter a "port already in use" error, you can use `sbt-revolver` to manage server restarts more effectively. The `reStart` command will start your server and `reStop` will properly stop it, releasing the port.

To enable this feature, we have included `sbt-revolver` in the project. For more details on this, refer to the [ZIO HTTP documentation on hot-reloading](https://zio.dev/zio-http/installation#hot-reload-changes-watch-mode).
:::

## Testing The Quickstart

When we prompt the `sbt run` the sbt search for the executable class and will find the `zio.dev.quickstart.MainApp` which contains the following code:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = Console.printLine("Hello, World!")
}
```

The `MainApp` is the main object of our application which extends the `ZIOAppDefault` trait. This trait provides the `main` function which can be invoked from IDEs or the command-line, e.g. the `sbt run`.

This example is the simplest, example we can run. Let's change it so that we ask the name of the user and print a hello along with the user's name:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run =
    for {
      _    <- Console.print("Please enter your name: ")
      name <- Console.readLine
      _    <- Console.printLine(s"Hello, $name!")
    } yield ()
}
```

In this example, we used `for-comprehension` to compose `print`, `readLine` and `printLine` workflows. We can think of it as the following `flatMap` operations:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run =
    Console.print("Please enter your name: ")
      .flatMap { _ =>
        Console.readLine
          .flatMap { name =>
            Console.printLine(s"Hello, $name!")
          }
      }
}
```
